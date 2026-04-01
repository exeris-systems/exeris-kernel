/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import eu.exeris.kernel.community.persistence.jdbc.JdbcPersistenceConnection;
import eu.exeris.kernel.core.persistence.AdmissionDecisionEvent;
import eu.exeris.kernel.core.persistence.PersistenceAdmissionStageEvent;
import eu.exeris.kernel.core.persistence.PersistenceEngineBootstrapEvent;
import eu.exeris.kernel.core.persistence.PersistenceTenantPoolCreatedEvent;
import eu.exeris.kernel.core.persistence.PersistenceTenantPoolReclaimedEvent;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceEngineCapabilities;
import eu.exeris.kernel.spi.persistence.PersistenceHealthStatus;
import eu.exeris.kernel.spi.security.StorageContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Community: JDBC-based {@link PersistenceEngine} backed by HikariCP connection pool.
 *
 * <h2>Architecture</h2>
 * <pre>
 *  CommunityPersistenceEngine
 *      ├── HikariDataSource (shared pool)
 *      ├── Map&lt;tenantKey, HikariDataSource&gt; (per-tenant pools, lazy)
 *      ├── List&lt;ConnectionInterceptor&gt; (RLS / schema injectors)
 *      └── JdbcPersistenceConnection → JdbcPersistenceStatement
 *                                    → JdbcQueryResult → JdbcRowCursor
 * </pre>
 *
 * <h2>Capabilities</h2>
 * <p>Returns {@link PersistenceEngineCapabilities#DEFAULT} — no native protocol,
 * no io_uring, no zero-copy rows. {@code KernelBootstrap} uses this to emit the
 * {@code EX-PERS-0001} WARN event when Enterprise is unavailable.
 *
 * <h2>Memory</h2>
 * <p>Uses temporary JDBC connections backed by HikariCP's default heap-based pool.
 * No {@code GlobalMemoryArbiter} claims — Community tier only.
 *
 * @since 0.5.0
 */
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods", "PMD.CouplingBetweenObjects",
 "PMD.GodClass", "PMD.ExcessiveImports"})
final class CommunityPersistenceEngine implements PersistenceEngine {

    private static final String PROVIDER_ID   = "postgres-community";
    private static final String SHARED_TENANT = "shared";
    private static final String ENGINE_CLOSED_MESSAGE = "CommunityPersistenceEngine is closed";
    private static final long MIN_RECLAIM_CADENCE_MS = 250L;
    private static final long MAX_RECLAIM_CADENCE_MS = 5_000L;
    private static final long MIN_CONNECTION_TIMEOUT_MS = 250L;
    private static final Runnable NOOP_ON_CLOSE = () -> { };
    private static final String ADMISSION_ACCEPT = "ACCEPT";
    private static final String ADMISSION_REJECT_HARD_SATURATION = "REJECT_HARD_SATURATION";
    private static final String ADMISSION_REJECT_GUARD_BAND_FAIRNESS = "REJECT_GUARD_BAND_FAIRNESS";
    private static final String ADMISSION_REJECT_ENGINE_CLOSED = "REJECT_ENGINE_CLOSED";
    private static final String ADMISSION_REJECT_NO_CAPACITY = "REJECT_NO_CAPACITY";
    private static final double HARD_SATURATION_THRESHOLD = 0.90d;
    private static final double GUARD_BAND_THRESHOLD = 0.85d;
    private static final double FAIRNESS_STRESS_THRESHOLD = 0.90d;
    private static final long FAIRNESS_QUEUE_DEPTH_THRESHOLD = 1L;
    private static final long QUEUE_WAIT_TELEMETRY_THRESHOLD_MS = 0L;
    private static final String RUN_MIGRATIONS_KEY = "run.migrations";
    private static final List<String> MIGRATION_RESOURCES = List.of(
            "db/migration/V0.5.0__create_outbox.sql"
    );

    /**
     * Driver-local capabilities descriptor — postgres-community specific.
     * Built once at class-load via {@link PersistenceEngineCapabilities#withProvider(String)}
     * to stamp the real {@code PROVIDER_ID} in JFR/diagnostic output.
     * Pre-built constant; O(1) return from {@link #capabilities()}.
     */
    private static final PersistenceEngineCapabilities CAPABILITIES =
            PersistenceEngineCapabilities.DEFAULT.withProvider(PROVIDER_ID);

    private final PersistenceConfig config;
    private final HikariDataSource  sharedPool;
    private final CommunityHikariSupport sharedHikari;
    private final Object lifecycleLock = new Object();
    // Per-tenant pools — lazily created on first openConnection(StorageContext)
    private final ConcurrentMap<String, TenantPoolState> tenantPools;
    private final List<ConnectionInterceptor> interceptors;
    private volatile List<ConnectionInterceptor> interceptorSnapshot;
    private final long tenantIdleTtlNanos;
    private final long tenantReclaimCadenceNanos;
    private final AtomicLong nextTenantReclaimAtNanos;
    private volatile boolean closed;
    private final AtomicBoolean firstConnectionOpened = new AtomicBoolean(false);
    private final FairnessTracker fairnessTracker = new FairnessTracker();

    /* default */ CommunityPersistenceEngine(PersistenceConfig config) {
        validateRuntimeConfig(config);
        this.config       = config;
        this.tenantPools  = new ConcurrentHashMap<>();
        this.interceptors = new ArrayList<>(2);
        this.interceptorSnapshot = List.of();
        this.tenantIdleTtlNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, config.idleTimeoutMs()));
        this.tenantReclaimCadenceNanos = computeReclaimCadenceNanos(this.tenantIdleTtlNanos);
        this.nextTenantReclaimAtNanos = new AtomicLong(System.nanoTime() + tenantReclaimCadenceNanos);
        this.closed       = false;
        this.sharedPool   = CommunityHikariSupport.buildPool(config, null);
        this.sharedHikari = CommunityHikariSupport.with(sharedPool);

        // JFR-First: emit bootstrap event so SRE tooling can verify clean startup
        PersistenceEngineBootstrapEvent.emit(
                PROVIDER_ID,
                "ExerisCommunity/JDBC+HikariCP",
                config.maxPoolSize(),
                config.rlsEnabled(),
                config.perTenantPooling(),
                config.useTls(),
                "BlockingTCP"
        );

        maybeRunMigrations();
        prewarmSharedPool();
    }
    /**
     * Pre-warm the shared connection pool by acquiring N connections simultaneously,
     * then returning them all. Holding all N at once forces HikariCP to create them
     * concurrently, so the idle pool is fully populated before the first request arrives.
     * Fail-fast on error to surface DB unavailability at startup.
     */
    @SuppressWarnings({"PMD.UseTryWithResources", "PMD.CloseResource"})
    private void prewarmSharedPool() {
        int warmupConnections = config.poolWarmupConnections();
        if (!config.poolWarmupEnabled() || warmupConnections <= 0) {
            return;
        }
        // Never request more connections than the pool can hold simultaneously.
        warmupConnections = Math.min(warmupConnections, config.maxPoolSize());
        List<Connection> held = new ArrayList<>(warmupConnections);
        try {
            for (int i = 0; i < warmupConnections; i++) {
                Connection conn = sharedPool.getConnection();
                held.add(conn);  // add before isValid so finally closes it even if isValid throws
                conn.isValid(5);
            }
        } catch (SQLException sqlEx) {
            throw PersistenceProviderException.bootstrapFailure(PROVIDER_ID, config.connectionUrl(), sqlEx);
        } finally {
            for (Connection conn : held) {
                try {
                    conn.close();
                } catch (SQLException _) {
                    // best-effort return to pool
                }
            }
        }
    }

    // =========================================================================
    // PersistenceEngine
    // =========================================================================

    @Override
    public PersistenceEngineCapabilities capabilities() {
        return CAPABILITIES; // pre-built constant, O(1), real providerId in JFR
    }

    @Override
    public PersistenceConnection openConnection() {
        if (closed) {
            throw new IllegalStateException(ENGINE_CLOSED_MESSAGE);
        }
        PersistenceSessionBox requestBox = PersistenceSessionBox.currentOrNull();
        if (requestBox != null && requestBox.belongsTo(this)) {
            RequestPersistenceSession requestSession = requestBox.getOrAcquire(this::openPhysicalConnection);
            if (requestSession != null) {
                return requestBox.requestScopedConnection(requestSession);
            }
        }
        return openPhysicalConnection();
    }

    /* default */ PersistenceConnection openPhysicalConnection() {
        if (closed) {
            throw new IllegalStateException(ENGINE_CLOSED_MESSAGE);
        }
        firstConnectionOpened.set(true);
        try {
            return sharedHikari.acquireConnection(PROVIDER_ID, SHARED_TENANT, NOOP_ON_CLOSE);
        } catch (HikariPool.PoolInitializationException | SQLException cause) {
            if (closed) {
                throw new IllegalStateException(ENGINE_CLOSED_MESSAGE, cause);
            }
            throw sharedHikari.translateAcquireFailure(cause, PROVIDER_ID, config.connectionTimeoutMs());
        }
    }

    @Override
    public PersistenceConnection openConnection(StorageContext storageContext) {
        if (closed) {
            throw new IllegalStateException(ENGINE_CLOSED_MESSAGE);
        }
        firstConnectionOpened.set(true);
        String tenantKey = selectTenantKey(storageContext);
        List<ConnectionInterceptor> snapshot = this.interceptorSnapshot;

        PersistenceSessionBox requestBox = PersistenceSessionBox.currentOrNull();
        if (requestBox != null && requestBox.belongsTo(this)) {
            RequestPersistenceSession requestSession = requestBox.getOrAcquireIfScopeMatches(
                    tenantKey,
                    () -> openPhysicalConnection(storageContext, tenantKey, snapshot));
            if (requestSession != null) {
                return requestBox.requestScopedConnection(requestSession);
            }
        }

        return openPhysicalConnection(storageContext, tenantKey, snapshot);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private JdbcPersistenceConnection openPhysicalConnection(StorageContext storageContext,
                                                             String tenantKey,
                                                             List<ConnectionInterceptor> snapshot) {
        CommunityHikariSupport tenantHikari = null;
        try {
            tenantHikari = CommunityHikariSupport.with(resolvePoolForTenant(tenantKey));
            JdbcPersistenceConnection conn =
                    tenantHikari.acquireConnection(PROVIDER_ID, tenantKey, NOOP_ON_CLOSE);
            for (ConnectionInterceptor interceptor : snapshot) {
                try {
                    interceptor.onConnectionAcquired(conn, storageContext);
                } catch (PersistenceProviderException ppe) {
                    conn.close();
                    throw ppe;
                } catch (RuntimeException ex) {
                    conn.close();
                    throw PersistenceProviderException.interceptorInitFailed(
                            interceptor.getClass().getSimpleName(),
                            storageContext.isolationKey().orElse("[none]"),
                            ex);
                }
            }
            return conn;
        } catch (HikariPool.PoolInitializationException | SQLException cause) {
            if (closed) {
                throw new IllegalStateException(ENGINE_CLOSED_MESSAGE, cause);
            }
            CommunityHikariSupport hikari =
                    tenantHikari != null ? tenantHikari : CommunityHikariSupport.with(sharedPool);
            throw hikari.translateAcquireFailure(cause, PROVIDER_ID, config.connectionTimeoutMs());
        }
    }

    @Override
    public PersistenceHealthStatus healthCheckDetailed() {
        synchronized (lifecycleLock) {
            ensureOpenUnderLock();
        }
        long start = System.nanoTime();
        try (Connection conn = sharedPool.getConnection()) {
            boolean valid = conn.isValid(2);
            long latency = System.nanoTime() - start;
            return valid ? PersistenceHealthStatus.ok(latency)
                         : PersistenceHealthStatus.failed("JDBC isValid() returned false");
        } catch (SQLException sqlEx) {
            return PersistenceHealthStatus.failed(sqlEx);
        }
    }

    @Override
    public EngineStats stats() {
        return sharedHikari.toEngineStats(config.maxPoolSize(), tenantPools.size());
    }

    @Override
    public boolean canServiceRequest() {
        if (closed) {
            emitAdmissionTelemetry(false, 0, 1.0d, 0.0d, 0L, 0L, ADMISSION_REJECT_ENGINE_CLOSED);
            return false; // Engine is shutting down — cannot service new requests
        }

        CommunityHikariSupport.AdmissionSnapshot snapshot =
                sharedHikari.admissionSnapshot(config.maxPoolSize());
        return canServiceRequest(snapshot);
    }

    /* default */ boolean canServiceRequest(CommunityHikariSupport.AdmissionSnapshot snapshot) {
        AdmissionDecision decision = evaluateAdmission(snapshot);
        int queued = snapshot.pendingAcquires();

        // Record decision for fairness tracking
        fairnessTracker.recordDecision(decision.accepted(), queued);

        // Event metrics include current decision sample.
        FairnessTracker.FairnessSnapshot fairnessSnapshot = fairnessTracker.computeSnapshot();

        emitAdmissionTelemetry(
                decision.accepted(),
                queued,
                decision.saturation(),
                fairnessSnapshot.fairnessRatio(),
                fairnessSnapshot.queueDepthP95(),
                fairnessSnapshot.queueWaitP95Ms(),
                decision.decisionReason());

        return decision.accepted();
    }

    /* default */ String decisionReason(CommunityHikariSupport.AdmissionSnapshot snapshot) {
        return evaluateAdmission(snapshot).decisionReason();
    }

    private AdmissionDecision evaluateAdmission(CommunityHikariSupport.AdmissionSnapshot snapshot) {
        if (closed) {
            return new AdmissionDecision(false, ADMISSION_REJECT_ENGINE_CLOSED, 1.0d);
        }
        int active = snapshot.activeConnections();
        int queued = snapshot.pendingAcquires();
        int idle = snapshot.idleConnections();
        int max = snapshot.maxConnections();

        if (max <= 0) {
            return new AdmissionDecision(false, ADMISSION_REJECT_NO_CAPACITY, 1.0d);
        }

        double saturation = (double) active / (double) max;
        if (saturation >= HARD_SATURATION_THRESHOLD) {
            return new AdmissionDecision(false, ADMISSION_REJECT_HARD_SATURATION, saturation);
        }

        if (saturation >= GUARD_BAND_THRESHOLD
                && queued > 0
                && fairnessTracker.indicatesAdmissionStress(FAIRNESS_STRESS_THRESHOLD,
                        FAIRNESS_QUEUE_DEPTH_THRESHOLD)) {
            return new AdmissionDecision(false, ADMISSION_REJECT_GUARD_BAND_FAIRNESS, saturation);
        }

        if (idle <= 0 && queued > 0) {
            return new AdmissionDecision(false, ADMISSION_REJECT_NO_CAPACITY, saturation);
        }

        return new AdmissionDecision(true, ADMISSION_ACCEPT, saturation);
    }

    private static void emitAdmissionTelemetry(boolean accepted,
                                               int queueDepth,
                                               double saturation,
                                               double fairnessRatio,
                                               long queueDepthP95,
                                               long queueWaitP95Ms,
                                               String decisionReason) {
        if (queueDepth > 0 && PersistenceAdmissionStageEvent.isEnabled()) {
            PersistenceAdmissionStageEvent.emit(new PersistenceAdmissionStageEvent.Payload(
                    PROVIDER_ID,
                    "queue_enter",
                    queueDepth,
                    0L,
                    accepted,
                    decisionReason));
        }
        if (queueWaitP95Ms > QUEUE_WAIT_TELEMETRY_THRESHOLD_MS
                && PersistenceAdmissionStageEvent.isEnabled()) {
            PersistenceAdmissionStageEvent.emit(new PersistenceAdmissionStageEvent.Payload(
                    PROVIDER_ID,
                    "queue_wait",
                    queueDepth,
                    queueWaitP95Ms,
                    accepted,
                    decisionReason));
        }
        if (PersistenceAdmissionStageEvent.isEnabled()) {
            PersistenceAdmissionStageEvent.emit(new PersistenceAdmissionStageEvent.Payload(
                    PROVIDER_ID,
                    "persistence_admission",
                    queueDepth,
                    queueWaitP95Ms,
                    accepted,
                    decisionReason));
        }

        if (AdmissionDecisionEvent.isEnabled()) {
            AdmissionDecisionEvent.emit(new AdmissionDecisionEvent.Payload(
                    PROVIDER_ID,
                    accepted,
                    queueDepth,
                    saturation,
                    fairnessRatio,
                    queueDepthP95,
                    queueWaitP95Ms,
                    decisionReason));
        }
    }

    @Override
    @SuppressWarnings("try")
    public void close() {
        List<HikariDataSource> poolsToClose;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            poolsToClose = tenantPools.values().stream().map(TenantPoolState::pool).toList();
            tenantPools.clear();
        }
        poolsToClose.forEach(HikariDataSource::close);
        sharedPool.close();
    }

    // =========================================================================
    // Package-private — interceptor registration (called by bootstrap / TCK)
    // =========================================================================

    @Override
    public void registerInterceptor(ConnectionInterceptor interceptor) {
        Objects.requireNonNull(interceptor, "interceptor must not be null");
        synchronized (lifecycleLock) {
            ensureOpenUnderLock();
            if (firstConnectionOpened.get()) {
                throw new IllegalStateException(
                        "Interceptors must be registered before the first connection is opened");
            }
            interceptors.add(interceptor);
            interceptorSnapshot = List.copyOf(interceptors);
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private HikariDataSource resolvePoolForTenant(String tenantKey) {
        if (!config.perTenantPooling() || SHARED_TENANT.equals(tenantKey)) {
            return sharedPool;
        }
        maybeReclaimIdleTenantPools(false);
        synchronized (lifecycleLock) {
            ensureOpenUnderLock();
            TenantPoolState existing = tenantPools.get(tenantKey);
            if (existing != null) {
                existing.touch(System.nanoTime());
                return existing.pool();
            }
            if (tenantPools.size() >= config.maxTenantPools()) {
                throw PersistenceProviderException.connectionExhausted(
                        PROVIDER_ID, config.connectionTimeoutMs(), tenantPools.size());
            }
        }
        return buildAndInstallTenantPool(tenantKey);
    }

    @SuppressWarnings("PMD.UseTryWithResources")
    private HikariDataSource buildAndInstallTenantPool(String tenantKey) {
        // candidate may be returned to tenantPools, so try-with-resources cannot express this ownership transfer.
        HikariDataSource candidate = CommunityHikariSupport.buildPool(config, tenantKey);
        long now = System.nanoTime();
        boolean installed = false;
        try {
            synchronized (lifecycleLock) {
                ensureOpenUnderLock();
                TenantPoolState existing = tenantPools.get(tenantKey);
                if (existing != null) {
                    existing.touch(now);
                    return existing.pool();
                }
                if (tenantPools.size() >= config.maxTenantPools()) {
                    throw PersistenceProviderException.connectionExhausted(
                            PROVIDER_ID, config.connectionTimeoutMs(), tenantPools.size());
                }
                tenantPools.put(tenantKey, new TenantPoolState(candidate, now));
                installed = true;
                nextTenantReclaimAtNanos.set(now + tenantReclaimCadenceNanos);
                
                // Phase 1A: Emit JFR event for tenant pool creation
                int minIdle;
                String minPerTenantStr = config.properties().getOrDefault("persistence.minPoolSizePerTenant", "2");
                try {
                    minIdle = Math.max(1, Integer.parseInt(minPerTenantStr));
                } catch (NumberFormatException _) {
                    minIdle = 2;
                }
                PersistenceTenantPoolCreatedEvent.emit(
                        PROVIDER_ID,
                        tenantKey,
                        config.maxPoolSize(),
                        minIdle,
                        tenantPools.size()
                );
                
                return candidate;
            }
        } finally {
            if (!installed) {
                candidate.close();
            }
        }
    }

    private void maybeReclaimIdleTenantPools(boolean force) {
        if (!config.perTenantPooling() || tenantPools.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        long nextCheck = nextTenantReclaimAtNanos.get();
        if (!force && now < nextCheck) {
            return;
        }
        if (!nextTenantReclaimAtNanos.compareAndSet(nextCheck, now + tenantReclaimCadenceNanos) && !force) {
            return;
        }
        
        // Phase 1A: Aggressive reclamation with JFR telemetry
        List<Map.Entry<String, TenantPoolState>> toReclaimList = new ArrayList<>();
        List<HikariDataSource> poolsToClose = new ArrayList<>();
        
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            for (Map.Entry<String, TenantPoolState> entry : tenantPools.entrySet()) {
                TenantPoolState state = entry.getValue();
                boolean reclaimable = state.idlePast(now, tenantIdleTtlNanos) && state.hasNoActiveConnections();
                if (reclaimable && tenantPools.remove(entry.getKey(), state)) {
                    toReclaimList.add(entry);
                    poolsToClose.add(state.pool());
                }
            }
        }
        
        // Emit JFR events for reclaimed pools
        for (Map.Entry<String, TenantPoolState> entry : toReclaimList) {
            TenantPoolState state = entry.getValue();
            long idleDurationNanos = now - state.getLastAccessNanos();
            long idleDurationMs = TimeUnit.NANOSECONDS.toMillis(idleDurationNanos);
            PersistenceTenantPoolReclaimedEvent.emit(
                    PROVIDER_ID,
                    entry.getKey(),
                    "idle_timeout",
                    idleDurationMs,
                    tenantPools.size()
            );
        }
        
        poolsToClose.forEach(HikariDataSource::close);
    }

    private String selectTenantKey(StorageContext storageContext) {
        return switch (storageContext.strategy()) {
            case DEDICATED        -> throw new IllegalStateException(
                    "DEDICATED strategy is unsupported in Community provider: dedicated datasource routing "
                            + "is unsupported in Community provider");
            case SEPARATED_SCHEMA -> storageContext.schemaName()
                    .orElseGet(() -> storageContext.isolationKey().orElse(SHARED_TENANT));
            case SHARED           -> storageContext.isolationKey().orElse(SHARED_TENANT);
        };
    }

    private void ensureOpenUnderLock() {
        if (closed) {
            throw new IllegalStateException(ENGINE_CLOSED_MESSAGE);
        }
    }

    private static long computeReclaimCadenceNanos(long tenantIdleTtlNanos) {
        long ttlMs = TimeUnit.NANOSECONDS.toMillis(tenantIdleTtlNanos);
        long cadenceMs = Math.clamp(ttlMs / 4L, MIN_RECLAIM_CADENCE_MS, MAX_RECLAIM_CADENCE_MS);
        return TimeUnit.MILLISECONDS.toNanos(cadenceMs);
    }

    private static void validateRuntimeConfig(PersistenceConfig config) {
        if (config.perTenantPooling() && config.maxTenantPools() < 1) {
            throw new IllegalArgumentException(
                    "Invalid persistence config: perTenantPooling=true requires maxTenantPools>=1, got maxTenantPools="
                            + config.maxTenantPools() + ", maxPoolSize=" + config.maxPoolSize());
        }
        if (config.connectionTimeoutMs() < MIN_CONNECTION_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                "Invalid persistence config: connectionTimeoutMs must be >= 250ms for Hikari, "
                    + "got connectionTimeoutMs="
                            + config.connectionTimeoutMs() + ", maxPoolSize=" + config.maxPoolSize());
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void maybeRunMigrations() {
        if (!Boolean.parseBoolean(config.properties().getOrDefault(RUN_MIGRATIONS_KEY, "false"))) {
            return;
        }
        List<String> resources = new ArrayList<>(MIGRATION_RESOURCES);
        resources.sort(Comparator.naturalOrder());
        try (Connection connection = sharedPool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String resource : resources) {
                    executeMigrationScript(connection, resource);
                }
                connection.commit();
            } catch (RuntimeException | SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException sqlEx) {
            throw PersistenceProviderException.bootstrapFailure(PROVIDER_ID, config.connectionUrl(), sqlEx);
        }
    }

    private void executeMigrationScript(Connection connection, String resourcePath) throws SQLException {
        String migrationSql = readMigrationResource(resourcePath);
        for (String statementSql : splitSqlStatements(migrationSql)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }
    }

    @SuppressWarnings("PMD.LawOfDemeter")
    private static String readMigrationResource(String resourcePath) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing SQL migration resource: " + resourcePath);
            }
            byte[] bytes = inputStream.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ioEx) {
            throw new UncheckedIOException("Failed to read SQL migration resource: " + resourcePath, ioEx);
        }
    }

    private static List<String> splitSqlStatements(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        StringBuilder current = new StringBuilder(sql.length());
        List<String> statements = new ArrayList<>();
        boolean inSingleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char currentChar = sql.charAt(i);
            if (currentChar == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inSingleQuote = !inSingleQuote;
            }
            if (currentChar == ';' && !inSingleQuote) {
                addStatement(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(currentChar);
        }
        addStatement(statements, current);
        return Collections.unmodifiableList(statements);
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String candidate = stripLineComments(current.toString()).trim();
        if (!candidate.isEmpty()) {
            statements.add(candidate);
        }
    }

    private static String stripLineComments(String sql) {
        String[] lines = sql.split("\\R");
        StringBuilder builder = new StringBuilder(sql.length());
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--")) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private static boolean hasNoActiveConnections(HikariDataSource pool) {
        return CommunityHikariSupport.hasNoActiveConnections(pool);
    }

    private static final class TenantPoolState {
        private final HikariDataSource pool;
        private volatile long lastAccessNanos;

        private TenantPoolState(HikariDataSource pool, long now) {
            this.pool = pool;
            this.lastAccessNanos = now;
        }

        private HikariDataSource pool() {
            return pool;
        }

        private void touch(long now) {
            lastAccessNanos = now;
        }

        private boolean idlePast(long now, long ttlNanos) {
            return now - lastAccessNanos >= ttlNanos;
        }

        private boolean hasNoActiveConnections() {
            return CommunityPersistenceEngine.hasNoActiveConnections(pool);
        }
        
        // Phase 1A: Expose lastAccessNanos for JFR telemetry
        private long getLastAccessNanos() {
            return lastAccessNanos;
        }
    }

    private record AdmissionDecision(boolean accepted, String decisionReason, double saturation) {
    }
}

