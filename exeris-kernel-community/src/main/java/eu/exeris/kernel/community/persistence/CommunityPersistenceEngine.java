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
import eu.exeris.kernel.spi.persistence.PersistenceEngineCapabilities;
import eu.exeris.kernel.spi.persistence.PersistenceHealthStatus;
import eu.exeris.kernel.spi.security.StorageContext;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
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
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods"})
final class CommunityPersistenceEngine implements PersistenceEngine {

    private static final String PROVIDER_ID   = "postgres-community";
    private static final String SHARED_TENANT = "shared";
    private static final long MIN_RECLAIM_CADENCE_MS = 250L;
    private static final long MAX_RECLAIM_CADENCE_MS = 5_000L;
    private static final long MIN_CONNECTION_TIMEOUT_MS = 250L;

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
    private final Object lifecycleLock = new Object();
    // Per-tenant pools — lazily created on first openConnection(StorageContext)
    private final ConcurrentMap<String, TenantPoolState> tenantPools;
    private final List<ConnectionInterceptor> interceptors;
    private final long tenantIdleTtlNanos;
    private final long tenantReclaimCadenceNanos;
    private final AtomicLong nextTenantReclaimAtNanos;
    private volatile boolean closed;
    private final AtomicBoolean firstConnectionOpened = new AtomicBoolean(false);

    /* default */ CommunityPersistenceEngine(PersistenceConfig config) {
        validateRuntimeConfig(config);
        this.config       = config;
        this.tenantPools  = new ConcurrentHashMap<>();
        this.interceptors = new ArrayList<>(2);
        this.tenantIdleTtlNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, config.idleTimeoutMs()));
        this.tenantReclaimCadenceNanos = computeReclaimCadenceNanos(this.tenantIdleTtlNanos);
        this.nextTenantReclaimAtNanos = new AtomicLong(System.nanoTime() + tenantReclaimCadenceNanos);
        this.closed       = false;
        this.sharedPool   = CommunityHikariSupport.buildPool(config, null);

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
        Lease permitLease = new Lease();
        CommunityHikariSupport hikari;
        synchronized (lifecycleLock) {
            ensureOpenUnderLock();
            firstConnectionOpened.set(true);
            hikari = CommunityHikariSupport.with(sharedPool);
        }
        boolean handedOff = false;
        try {
            JdbcPersistenceConnection conn =
                    hikari.acquireConnection(PROVIDER_ID, SHARED_TENANT, permitLease::release);
            handedOff = true;
            return conn;
        } catch (HikariPool.PoolInitializationException | SQLException cause) {
            if (closed) {
                throw new IllegalStateException("CommunityPersistenceEngine is closed", cause);
            }
            throw hikari.translateAcquireFailure(cause, PROVIDER_ID, config.connectionTimeoutMs());
        } finally {
            if (!handedOff) {
                permitLease.release();
            }
        }
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public PersistenceConnection openConnection(StorageContext storageContext) {
        Lease permitLease = new Lease();
        String tenantKey;
        List<ConnectionInterceptor> interceptorSnapshot;
        CommunityHikariSupport tenantHikari = null;
        synchronized (lifecycleLock) {
            ensureOpenUnderLock();
            firstConnectionOpened.set(true);
            tenantKey = selectTenantKey(storageContext);
            interceptorSnapshot = List.copyOf(interceptors);
        }
        boolean handedOff = false;
        try {
            tenantHikari = CommunityHikariSupport.with(resolvePoolForTenant(tenantKey));
            JdbcPersistenceConnection conn =
                    tenantHikari.acquireConnection(PROVIDER_ID, tenantKey, permitLease::release);
            handedOff = true;
            for (ConnectionInterceptor interceptor : interceptorSnapshot) {
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
                throw new IllegalStateException("CommunityPersistenceEngine is closed", cause);
            }
            CommunityHikariSupport hikari =
                    tenantHikari != null ? tenantHikari : CommunityHikariSupport.with(sharedPool);
            throw hikari.translateAcquireFailure(cause, PROVIDER_ID, config.connectionTimeoutMs());
        } finally {
            if (!handedOff) {
                permitLease.release();
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
        return CommunityHikariSupport.with(sharedPool).toEngineStats(config.maxPoolSize(), tenantPools.size());
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
        List<HikariDataSource> poolsToClose = new ArrayList<>();
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            for (Map.Entry<String, TenantPoolState> entry : tenantPools.entrySet()) {
                TenantPoolState state = entry.getValue();
                boolean reclaimable = state.idlePast(now, tenantIdleTtlNanos) && state.hasNoActiveConnections();
                if (reclaimable && tenantPools.remove(entry.getKey(), state)) {
                    poolsToClose.add(state.pool());
                }
            }
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
            throw new IllegalStateException("CommunityPersistenceEngine is closed");
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
    }

    private final class Lease {
        private static final VarHandle RELEASED;

        static {
            try {
                RELEASED = MethodHandles.lookup().findVarHandle(Lease.class, "released", int.class);
            } catch (ReflectiveOperationException ex) {
                throw new ExceptionInInitializerError(ex);
            }
        }

        @SuppressWarnings("unused")
        private volatile int released;

        private void release() {
            RELEASED.compareAndSet(this, 0, 1);
        }
    }
}

