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
import eu.exeris.kernel.core.persistence.PersistenceEngineBootstrapEvent;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 * <h2>Memory</h2>
 * <p>Uses temporary JDBC connections backed by HikariCP's default heap-based pool.
 * No {@code GlobalMemoryArbiter} claims — Community tier only.
 *
 * @since 0.5.0
 */
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods", "PMD.CouplingBetweenObjects",
        "PMD.GodClass"})
final class CommunityPersistenceEngine implements PersistenceEngine, PhysicalConnectionSource {

    private static final String ENGINE_CLOSED_MESSAGE = "CommunityPersistenceEngine is closed";
    private static final long MIN_CONNECTION_TIMEOUT_MS = 250L;
    private static final Runnable NOOP_ON_CLOSE = () -> { };
    private static final String REQUIRED_SHARED_INTERCEPTOR = "RlsConnectionInterceptor";
    private static final String RUN_MIGRATIONS_KEY = "run.migrations";
    private static final List<String> MIGRATION_RESOURCES = List.of(
            "db/migration/V0.5.0__create_outbox.sql",
            "db/migration/V0.7.0__create_saga_state.sql"
    );

    private final PersistenceConfig config;
    private final HikariDataSource sharedPool;
    private final CommunityHikariSupport sharedHikari;
    private final Object lifecycleLock = new Object();
    private final CommunityTenantPoolRegistry poolRegistry;
    private final List<ConnectionInterceptor> interceptors;
    private final AtomicReference<List<ConnectionInterceptor>> interceptorSnapshot;
    private final AtomicBoolean firstConnectionOpened = new AtomicBoolean(false);
    private final CommunityPersistenceAdmissionController admissionController =
            new CommunityPersistenceAdmissionController();
    private volatile boolean closed;

    /* default */ CommunityPersistenceEngine(PersistenceConfig config) {
        validateRuntimeConfig(config);
        this.config = config;
        this.sharedPool = CommunityHikariSupport.buildPool(config, null);
        this.sharedHikari = CommunityHikariSupport.with(sharedPool);
        this.poolRegistry = new CommunityTenantPoolRegistry(
            CommunityPersistenceConstants.PROVIDER_ID,
            config,
            lifecycleLock,
            sharedPool);
        this.interceptors = new ArrayList<>(2);
        this.interceptorSnapshot = new AtomicReference<>(List.of());
        this.closed = false;

        // JFR-First: emit bootstrap event so SRE tooling can verify clean startup
        PersistenceEngineBootstrapEvent.emit(
                CommunityPersistenceConstants.PROVIDER_ID,
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
    private void prewarmSharedPool() {
        int warmupConnections = config.poolWarmupConnections();
        if (!config.poolWarmupEnabled() || warmupConnections <= 0) {
            return;
        }
        int targetConnections = Math.min(warmupConnections, config.maxPoolSize());
        List<Connection> heldConnections = new ArrayList<>(targetConnections);
        try {
            acquireWarmupConnections(targetConnections, heldConnections);
        } catch (SQLException sqlEx) {
            throw PersistenceProviderException.bootstrapFailure(
                    CommunityPersistenceConstants.PROVIDER_ID,
                    config.connectionUrl(),
                    sqlEx);
        } finally {
            releaseConnectionsQuietly(heldConnections);
        }
    }

    private void acquireWarmupConnections(int warmupConnections,
                                          List<Connection> heldConnections) throws SQLException {
        for (int i = 0; i < warmupConnections; i++) {
            Connection connection = sharedPool.getConnection(); // NOPMD CloseResource -- held for warm-up
            heldConnections.add(connection);
            validateWarmupConnection(connection);
        }
    }

    private void validateWarmupConnection(Connection connection) throws SQLException {
        if (!connection.isValid(5)) {
            throw PersistenceProviderException.bootstrapFailure(
                    CommunityPersistenceConstants.PROVIDER_ID,
                    config.connectionUrl(),
                    new IllegalStateException("Connection validation returned false during pool warm-up"));
        }
    }

    // =========================================================================
    // PersistenceEngine
    // =========================================================================

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

    @Override
    public PersistenceConnection openPhysical() {
        return openPhysicalConnection();
    }

    /* default */ PersistenceConnection openPhysicalConnection() {
        if (closed) {
            throw new IllegalStateException(ENGINE_CLOSED_MESSAGE);
        }
        firstConnectionOpened.set(true);
        try {
            return sharedHikari.acquireConnection(
                    CommunityPersistenceConstants.PROVIDER_ID,
                    CommunityPersistenceConstants.SHARED_TENANT,
                    NOOP_ON_CLOSE);
        } catch (HikariPool.PoolInitializationException | SQLException cause) {
            if (closed) {
                throw new IllegalStateException(ENGINE_CLOSED_MESSAGE, cause);
            }
            throw sharedHikari.translateAcquireFailure(
                    cause,
                    CommunityPersistenceConstants.PROVIDER_ID,
                    config.connectionTimeoutMs());
        }
    }

    @Override
    public PersistenceConnection openConnection(StorageContext storageContext) {
        if (closed) {
            throw new IllegalStateException(ENGINE_CLOSED_MESSAGE);
        }
        firstConnectionOpened.set(true);
        String tenantKey = selectTenantKey(storageContext);
        List<ConnectionInterceptor> snapshot = interceptorSnapshot.get();
        ensureSharedInterceptorPresent(storageContext, snapshot);

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

    private JdbcPersistenceConnection openPhysicalConnection(StorageContext storageContext,
                                                             String tenantKey,
                                                             List<ConnectionInterceptor> snapshot) {
        CommunityHikariSupport tenantHikari = null;
        try {
            tenantHikari = CommunityHikariSupport.with(resolvePoolForTenant(tenantKey));
            JdbcPersistenceConnection conn =
                    tenantHikari.acquireConnection(CommunityPersistenceConstants.PROVIDER_ID, tenantKey, NOOP_ON_CLOSE);
            applyConnectionInterceptors(tenantHikari, conn, storageContext, snapshot);
            return conn;
        } catch (HikariPool.PoolInitializationException | SQLException cause) {
            if (closed) {
                throw new IllegalStateException(ENGINE_CLOSED_MESSAGE, cause);
            }
            CommunityHikariSupport hikari =
                    tenantHikari != null ? tenantHikari : CommunityHikariSupport.with(sharedPool);
            throw hikari.translateAcquireFailure(
                    cause,
                    CommunityPersistenceConstants.PROVIDER_ID,
                    config.connectionTimeoutMs());
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void applyConnectionInterceptors(CommunityHikariSupport tenantHikari,
                                             JdbcPersistenceConnection conn,
                                             StorageContext storageContext,
                                             List<ConnectionInterceptor> snapshot) {
        for (ConnectionInterceptor interceptor : snapshot) {
            try {
                interceptor.onConnectionAcquired(conn, storageContext);
            } catch (PersistenceProviderException ppe) {
                discardAfterInterceptorFailure(tenantHikari, conn);
                throw ppe;
            } catch (RuntimeException ex) {
                discardAfterInterceptorFailure(tenantHikari, conn);
                throw PersistenceProviderException.interceptorInitFailed(
                        interceptor.getClass().getSimpleName(),
                        storageContext.isolationKey().orElse("[none]"),
                        ex);
            }
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
        return sharedHikari.toEngineStats(config.maxPoolSize(), poolRegistry.tenantPoolCount());
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>v1 limitation:</b> saturation is assessed against the shared pool only.
     * Dedicated-datasource pool saturation is not checked here; a request backed
     * exclusively by a DEDICATED pool may be admitted even if that pool is fully
     * saturated.
     */
    @Override
    public boolean canServiceRequest() {
        if (closed) {
            return canServiceRequest(new CommunityHikariSupport.AdmissionSnapshot(0, 0, 0, 0));
        }

        CommunityHikariSupport.AdmissionSnapshot snapshot =
                sharedHikari.admissionSnapshot(config.maxPoolSize());
        return canServiceRequest(snapshot);
    }

    /* default */ boolean canServiceRequest(CommunityHikariSupport.AdmissionSnapshot snapshot) {
        return admissionController.canServiceRequest(snapshot, closed, CommunityAdmissionConfig.CURRENT);
    }

    /* default */ String decisionReason(CommunityHikariSupport.AdmissionSnapshot snapshot) {
        return admissionController.decisionReason(snapshot, closed, CommunityAdmissionConfig.CURRENT);
    }

    @Override
    public void close() {
        CommunityTenantPoolRegistry.PoolShutdownPlan shutdownPlan = preparePoolShutdown();
        if (shutdownPlan == null) {
            return;
        }
        poolRegistry.close(shutdownPlan);
        closeSharedPoolSafely();
    }

    @SuppressWarnings("PMD.CloseResource")
    private void releaseConnectionsQuietly(List<Connection> connections) {
        for (Connection connection : connections) {
            if (connection == null) {
                continue;
            }
            try {
                connection.close();
            } catch (SQLException _) {
                // best-effort return to pool
            }
        }
    }

    private CommunityTenantPoolRegistry.PoolShutdownPlan preparePoolShutdown() {
        synchronized (lifecycleLock) {
            if (closed) {
                return null;
            }
            closed = true;
            return poolRegistry.prepareShutdownPlan();
        }
    }

    private void closeSharedPoolSafely() {
        if (!sharedPool.isClosed()) {
            sharedPool.close();
        }
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
            interceptorSnapshot.set(List.copyOf(interceptors));
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private HikariDataSource resolvePoolForTenant(String tenantKey) {
        return poolRegistry.resolvePoolForTenant(
                tenantKey,
                this::ensureOpenUnderLock,
                () -> closed,
                this::computeActiveConnections);
    }

    private String selectTenantKey(StorageContext storageContext) {
        return switch (storageContext.strategy()) {
            case DEDICATED -> {
                String dataSourceKey = storageContext.dataSourceKey()
                        .orElseThrow(() -> PersistenceProviderException.dedicatedDatasourceNotFound(
                                CommunityPersistenceConstants.PROVIDER_ID, "[none]"));
                if (!config.dedicatedDataSources().containsKey(dataSourceKey)) {
                    throw PersistenceProviderException.dedicatedDatasourceNotFound(
                            CommunityPersistenceConstants.PROVIDER_ID, dataSourceKey);
                }
                yield CommunityPersistenceConstants.DEDICATED_KEY_PREFIX + dataSourceKey;
            }
            case SEPARATED_SCHEMA -> storageContext.schemaName()
                    .orElseGet(() -> storageContext.isolationKey()
                        .orElse(CommunityPersistenceConstants.SHARED_TENANT));
                case SHARED           -> storageContext.isolationKey()
                    .orElse(CommunityPersistenceConstants.SHARED_TENANT);
        };
    }

    private void ensureOpenUnderLock() {
        if (closed) {
            throw new IllegalStateException(ENGINE_CLOSED_MESSAGE);
        }
    }

    private void ensureSharedInterceptorPresent(StorageContext storageContext,
                                                List<ConnectionInterceptor> snapshot) {
        if (!config.rlsEnabled()) {
            return;
        }
        if (storageContext.strategy() != StorageContext.IsolationStrategy.SHARED) {
            return;
        }
        String isolationKey = storageContext.isolationKey().orElse(null);
        if (isolationKey == null || isolationKey.isBlank()) {
            return;
        }
        if (snapshot.isEmpty()) {
            throw PersistenceProviderException.interceptorInitFailed(
                    REQUIRED_SHARED_INTERCEPTOR,
                    isolationKey,
                    new IllegalStateException(
                            "RLS enabled but no ConnectionInterceptor is registered for SHARED strategy"));
        }
    }

    private static void discardAfterInterceptorFailure(CommunityHikariSupport tenantHikari,
                                                       JdbcPersistenceConnection conn) {
        tenantHikari.discardConnection(conn);
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

    private void maybeRunMigrations() {
        if (!Boolean.parseBoolean(config.properties().getOrDefault(RUN_MIGRATIONS_KEY, "false"))) {
            return;
        }
        List<String> resources = new ArrayList<>(MIGRATION_RESOURCES);
        resources.sort(Comparator.naturalOrder());
        try (Connection connection = sharedPool.getConnection()) {
            runMigrationsInTransaction(connection, resources);
        } catch (SQLException | IllegalStateException | UncheckedIOException ex) {
            throw PersistenceProviderException.bootstrapFailure(
                    CommunityPersistenceConstants.PROVIDER_ID,
                    config.connectionUrl(),
                    ex);
        }
    }

    private void runMigrationsInTransaction(Connection connection,
                                            List<String> resources) throws SQLException {
        connection.setAutoCommit(false);
        boolean committed = false;
        try {
            for (String resource : resources) {
                executeMigrationScript(connection, resource);
            }
            connection.commit();
            committed = true;
        } finally {
            restoreAutoCommit(connection, committed);
        }
    }

    private void restoreAutoCommit(Connection connection, boolean committed) throws SQLException {
        try {
            if (!committed) {
                connection.rollback();
            }
        } finally {
            connection.setAutoCommit(true);
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

    private int computeActiveConnections() {
        return poolRegistry.computeActiveConnections();
    }
}

