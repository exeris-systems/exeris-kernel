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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
@SuppressWarnings("PMD.CyclomaticComplexity")
final class CommunityPersistenceEngine implements PersistenceEngine {

    private static final String PROVIDER_ID   = "postgres-community";
    private static final String SHARED_TENANT = "shared";

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
    private final ConcurrentMap<String, HikariDataSource> tenantPools;
    private final List<ConnectionInterceptor> interceptors;
    private volatile boolean closed;
    private final AtomicBoolean firstConnectionOpened = new AtomicBoolean(false);

    /* default */ CommunityPersistenceEngine(PersistenceConfig config) {
        this.config       = config;
        this.tenantPools  = new ConcurrentHashMap<>();
        this.interceptors = new ArrayList<>(2);
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
        CommunityHikariSupport hikari;
        synchronized (lifecycleLock) {
            ensureOpenUnderLock();
            firstConnectionOpened.set(true);
            hikari = CommunityHikariSupport.with(sharedPool);
        }
        try {
            return hikari.acquireConnection(PROVIDER_ID, SHARED_TENANT);
        } catch (HikariPool.PoolInitializationException | SQLException cause) {
            if (closed) {
                throw new IllegalStateException("CommunityPersistenceEngine is closed", cause);
            }
            throw hikari.translateAcquireFailure(cause, PROVIDER_ID, config.connectionTimeoutMs());
        }
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public PersistenceConnection openConnection(StorageContext storageContext) {
        String tenantKey;
        CommunityHikariSupport hikari;
        synchronized (lifecycleLock) {
            ensureOpenUnderLock();
            firstConnectionOpened.set(true);
            tenantKey = selectTenantKey(storageContext);
            hikari = CommunityHikariSupport.with(resolvePoolUnderLock(tenantKey));
        }
        try {
            JdbcPersistenceConnection conn = hikari.acquireConnection(PROVIDER_ID, tenantKey);
            for (ConnectionInterceptor interceptor : interceptors) {
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
            poolsToClose = new ArrayList<>(tenantPools.values());
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
        synchronized (interceptors) {
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

    private HikariDataSource resolvePoolUnderLock(String tenantKey) {
        if (!config.perTenantPooling() || SHARED_TENANT.equals(tenantKey)) {
            return sharedPool;
        }
        HikariDataSource existing = tenantPools.get(tenantKey);
        if (existing != null) {
            return existing;
        }
        if (tenantPools.size() >= config.maxTenantPools()) {
            throw PersistenceProviderException.connectionExhausted(
                    PROVIDER_ID, config.connectionTimeoutMs(), tenantPools.size());
        }
        HikariDataSource pool = CommunityHikariSupport.buildPool(config, tenantKey);
        tenantPools.put(tenantKey, pool);
        return pool;
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
}

