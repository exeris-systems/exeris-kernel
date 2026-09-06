/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import com.zaxxer.hikari.HikariDataSource;
import eu.exeris.kernel.core.persistence.PersistenceTenantPoolCreatedEvent;
import eu.exeris.kernel.core.persistence.PersistenceTenantPoolReclaimedEvent;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Owns the Community engine's per-tenant and dedicated HikariCP pools: lazy creation up to
 * {@link PersistenceConfig#maxTenantPools()}, periodic reclamation of tenant pools idle past
 * {@code idleTimeoutMs}, and the {@link PoolShutdownPlan} that hands every pool this registry
 * created to the engine for closing.
 *
 * <p>The shared pool is not owned here — it is supplied by the engine and returned as-is for
 * the {@code SHARED} strategy and whenever per-tenant pooling is disabled; this registry only
 * manages the tenant and dedicated pools it creates itself.
 */
@SuppressWarnings("PMD.CyclomaticComplexity")
final class CommunityTenantPoolRegistry {

    private static final long MIN_RECLAIM_CADENCE_MS = 250L;
    private static final long MAX_RECLAIM_CADENCE_MS = 5_000L;

    private final String providerId;
    private final PersistenceConfig config;
    private final Object lifecycleLock;
    private final HikariDataSource sharedPool;
    private final ConcurrentMap<String, TenantPoolState> tenantPools = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, HikariDataSource> dedicatedPools = new ConcurrentHashMap<>();
    private final long tenantIdleTtlNanos;
    private final long tenantReclaimCadenceNanos;
    private final AtomicLong nextTenantReclaimAtNanos;

    /* default */ CommunityTenantPoolRegistry(String providerId,
                                PersistenceConfig config,
                                Object lifecycleLock,
                                HikariDataSource sharedPool) {
        this.providerId = providerId;
        this.config = config;
        this.lifecycleLock = lifecycleLock;
        this.sharedPool = sharedPool;
        this.tenantIdleTtlNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, config.idleTimeoutMs()));
        this.tenantReclaimCadenceNanos = computeReclaimCadenceNanos(tenantIdleTtlNanos);
        this.nextTenantReclaimAtNanos = new AtomicLong(System.nanoTime() + tenantReclaimCadenceNanos);
    }

    /* default */ int tenantPoolCount() {
        return tenantPools.size();
    }

    /* default */ int computeActiveConnections() {
        int total = CommunityHikariSupport.activeConnections(sharedPool);
        for (TenantPoolState state : tenantPools.values()) {
            total += CommunityHikariSupport.activeConnections(state.pool());
        }
        return total;
    }

    /* default */ HikariDataSource resolvePoolForTenant(String tenantKey,
                                          Runnable ensureOpenUnderLock,
                                          BooleanSupplier closedSupplier,
                                          IntSupplier activeConnectionsSupplier) {
        if (tenantKey.startsWith(CommunityPersistenceConstants.DEDICATED_KEY_PREFIX)) {
            return getOrBuildDedicatedPool(
                    tenantKey.substring(
                            CommunityPersistenceConstants.DEDICATED_KEY_PREFIX.length()));
        }
        if (!config.perTenantPooling() || CommunityPersistenceConstants.SHARED_TENANT.equals(tenantKey)) {
            return sharedPool;
        }
        maybeReclaimIdleTenantPools(false, closedSupplier);
        synchronized (lifecycleLock) {
            ensureOpenUnderLock.run();
            TenantPoolState existing = tenantPools.get(tenantKey);
            if (existing != null) {
                existing.touch(System.nanoTime());
                return existing.pool();
            }
            if (tenantPools.size() >= config.maxTenantPools()) {
                throw PersistenceProviderException.connectionExhausted(
                        providerId,
                        config.connectionTimeoutMs(),
                        activeConnectionsSupplier.getAsInt());
            }
        }
        return buildAndInstallTenantPool(tenantKey, ensureOpenUnderLock, activeConnectionsSupplier);
    }

    /* default */ PoolShutdownPlan prepareShutdownPlan() {
        List<HikariDataSource> tenantPoolsToClose = tenantPools.values().stream().map(TenantPoolState::pool).toList();
        tenantPools.clear();
        List<HikariDataSource> dedicatedPoolsToClose = List.copyOf(dedicatedPools.values());
        dedicatedPools.clear();
        return new PoolShutdownPlan(tenantPoolsToClose, dedicatedPoolsToClose);
    }

    /* default */ void close(PoolShutdownPlan shutdownPlan) {
        closePools(shutdownPlan.tenantPools());
        closePools(shutdownPlan.dedicatedPools());
    }

    @SuppressWarnings("PMD.UseTryWithResources")
    private HikariDataSource buildAndInstallTenantPool(String tenantKey,
                                                       Runnable ensureOpenUnderLock,
                                                       IntSupplier activeConnectionsSupplier) {
        HikariDataSource candidate = CommunityHikariSupport.buildPool(config, tenantKey);
        long now = System.nanoTime();
        boolean installed = false;
        try {
            synchronized (lifecycleLock) {
                ensureOpenUnderLock.run();
                TenantPoolState existing = tenantPools.get(tenantKey);
                if (existing != null) {
                    existing.touch(now);
                    return existing.pool();
                }
                if (tenantPools.size() >= config.maxTenantPools()) {
                    throw PersistenceProviderException.connectionExhausted(
                            providerId,
                            config.connectionTimeoutMs(),
                            activeConnectionsSupplier.getAsInt());
                }
                tenantPools.put(tenantKey, new TenantPoolState(candidate, now));
                installed = true;
                nextTenantReclaimAtNanos.set(now + tenantReclaimCadenceNanos);
                PersistenceTenantPoolCreatedEvent.emit(
                        providerId,
                        tenantKey,
                        config.maxPoolSize(),
                        CommunityHikariSupport.resolveMinIdle(config, tenantKey),
                        tenantPools.size());
                return candidate;
            }
        } finally {
            if (!installed) {
                candidate.close();
            }
        }
    }

    private void maybeReclaimIdleTenantPools(boolean force, BooleanSupplier closedSupplier) {
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

        List<Map.Entry<String, TenantPoolState>> toReclaimList = new ArrayList<>();
        List<HikariDataSource> poolsToClose = new ArrayList<>();

        synchronized (lifecycleLock) {
            if (closedSupplier.getAsBoolean()) {
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

        for (Map.Entry<String, TenantPoolState> entry : toReclaimList) {
            TenantPoolState state = entry.getValue();
            long idleDurationNanos = now - state.lastAccessNanos();
            long idleDurationMs = TimeUnit.NANOSECONDS.toMillis(idleDurationNanos);
            PersistenceTenantPoolReclaimedEvent.emit(
                    providerId,
                    entry.getKey(),
                    "idle_timeout",
                    idleDurationMs,
                    tenantPools.size());
        }

        poolsToClose.forEach(HikariDataSource::close);
    }

    private HikariDataSource getOrBuildDedicatedPool(String dataSourceKey) {
        HikariDataSource existing = dedicatedPools.get(dataSourceKey);
        if (existing != null) {
            return existing;
        }
        PersistenceConfig dedicatedConfig = config.dedicatedDataSources().get(dataSourceKey);
        HikariDataSource candidate = CommunityHikariSupport.buildPool(dedicatedConfig, null);
        HikariDataSource prior = dedicatedPools.putIfAbsent(dataSourceKey, candidate);
        if (prior != null) {
            candidate.close();
            return prior;
        }
        return candidate;
    }

    @SuppressWarnings("PMD.CloseResource")
    private static void closePools(List<HikariDataSource> pools) {
        for (HikariDataSource pool : pools) {
            if (pool == null || pool.isClosed()) {
                continue;
            }
            pool.close();
        }
    }

    private static long computeReclaimCadenceNanos(long tenantIdleTtlNanos) {
        long ttlMs = TimeUnit.NANOSECONDS.toMillis(tenantIdleTtlNanos);
        long cadenceMs = Math.clamp(ttlMs / 4L, MIN_RECLAIM_CADENCE_MS, MAX_RECLAIM_CADENCE_MS);
        return TimeUnit.MILLISECONDS.toNanos(cadenceMs);
    }

    /** The pools to close, captured under {@link #lifecycleLock} and closed outside it. */
    /* default */ record PoolShutdownPlan(List<HikariDataSource> tenantPools, List<HikariDataSource> dedicatedPools) {
    }

    private static final class TenantPoolState {
        private final HikariDataSource pool;
        private final AtomicLong lastAccessNanos;

        private TenantPoolState(HikariDataSource pool, long now) {
            this.pool = pool;
            this.lastAccessNanos = new AtomicLong(now);
        }

        private HikariDataSource pool() {
            return pool;
        }

        private void touch(long now) {
            lastAccessNanos.set(now);
        }

        private long lastAccessNanos() {
            return lastAccessNanos.get();
        }

        private boolean idlePast(long now, long ttlNanos) {
            return now - lastAccessNanos() >= ttlNanos;
        }

        private boolean hasNoActiveConnections() {
            return CommunityHikariSupport.hasNoActiveConnections(pool);
        }
    }
}
