/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import eu.exeris.kernel.community.persistence.jdbc.JdbcPersistenceConnection;
import eu.exeris.kernel.core.persistence.ConnectionAcquireEvent;
import eu.exeris.kernel.core.persistence.PersistenceErrorTranslator;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
final class CommunityHikariSupport {

    private final HikariDataSource pool;

    private CommunityHikariSupport(HikariDataSource pool) {
        this.pool = pool;
    }

    /* default */ static CommunityHikariSupport with(HikariDataSource pool) {
        return new CommunityHikariSupport(pool);
    }

    /* default */ static boolean hasNoActiveConnections(HikariDataSource pool) {
        return with(pool).hasNoActiveConnections();
    }

    /* default */ static int activeConnections(HikariDataSource pool) {
        return with(pool).activeConnections();
    }

    /**
     * Resolves the minimumIdle setting for a Hikari pool.
     *
     * <p>Shared pool ({@code tenantKey == null}) uses {@link PersistenceConfig#minIdleConnections()}.
     * Per-tenant pools read {@code persistence.minPoolSizePerTenant} from config properties
     * (default {@code 0}); invalid or negative values are clamped to 0.
     */
    /* default */ static int resolveMinIdle(PersistenceConfig config, String tenantKey) {
        if (tenantKey == null) {
            return config.minIdleConnections();
        }
        String minPerTenantStr = config.properties()
                .getOrDefault("persistence.minPoolSizePerTenant", "0");
        try {
            return Math.max(0, Integer.parseInt(minPerTenantStr));
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    /* default */ static HikariDataSource buildPool(PersistenceConfig config, String tenantKey) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.connectionUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(config.maxPoolSize());
        hikariConfig.setMinimumIdle(resolveMinIdle(config, tenantKey));
        
        hikariConfig.setConnectionTimeout(config.connectionTimeoutMs());
        hikariConfig.setIdleTimeout(config.idleTimeoutMs());
        hikariConfig.setMaxLifetime(config.maxLifetimeMs());
        hikariConfig.setKeepaliveTime(30_000L);
        hikariConfig.setValidationTimeout(5_000L);
        hikariConfig.setAutoCommit(false);
        hikariConfig.setPoolName(tenantKey == null
                ? "exeris-community-shared"
                : "exeris-community-tenant-" + tenantKey);
        hikariConfig.setRegisterMbeans(false);
        applyDataSourceProperties(hikariConfig, config);
        return new HikariDataSource(hikariConfig);
    }

    /* default */ static void applyDataSourceProperties(HikariConfig hikariConfig, PersistenceConfig config) {
        Map<String, String> properties = new LinkedHashMap<>(config.properties());
        if (config.useTls() && !CommunityHikariUtils.containsKeyIgnoreCase(properties, "ssl")
                && !CommunityHikariUtils.containsKeyIgnoreCase(properties, "sslmode")) {
            properties.put("ssl", "true");
            properties.put("sslmode", "require");
        }
        if (isPostgreSql(config.connectionUrl())
                && !CommunityHikariUtils.containsKeyIgnoreCase(properties, "defaultRowFetchSize")) {
            properties.put("defaultRowFetchSize", "50");
        }
        properties.forEach(hikariConfig::addDataSourceProperty);
    }

    private static boolean isPostgreSql(String connectionUrl) {
        return connectionUrl != null
                && connectionUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:");
    }

    /* default */ JdbcPersistenceConnection acquireConnection(
            String providerId,
            String tenantKey,
            Runnable onClose
    ) throws SQLException {
        Objects.requireNonNull(onClose, "onClose must not be null");
        long startNs = System.nanoTime();
        boolean success = false;
        // The checkout below can park (and unmount) a virtual thread. The JFR event is
        // committed single-phase after it returns — never held across the unmount — to
        // avoid a carrier-bound EventWriter flushing a stale buffer. See ConnectionAcquireEvent.
        try (ConnectionLease lease = ConnectionLease.open(pool)) {
            JdbcPersistenceConnection connection = lease.transferToJdbc(onClose);
            success = true;
            return connection;
        } finally {
            ConnectionAcquireEvent.commitAcquire(providerId, tenantKey, success, startNs);
        }
    }

    /* default */ void discardConnection(JdbcPersistenceConnection connection) {
        Objects.requireNonNull(connection, "connection must not be null");
        try (connection) {
            pool.evictConnection(connection.rawJdbcConnection());
        } catch (IllegalArgumentException | IllegalStateException _) {
            // best-effort: connection already detached or pool is shutting down
        }
    }

    private static final class ConnectionLease implements AutoCloseable {
        private final Connection connection;
        private boolean transferred;

        private ConnectionLease(Connection connection) {
            this.connection = connection;
            this.transferred = false;
        }

        private static ConnectionLease open(HikariDataSource pool) throws SQLException {
            return new ConnectionLease(pool.getConnection());
        }

        private JdbcPersistenceConnection transferToJdbc(Runnable onClose) {
            transferred = true;
            return new JdbcPersistenceConnection(connection, onClose);
        }

        @Override
        public void close() throws SQLException {
            if (!transferred) {
                connection.close();
            }
        }
    }

    /* default */ EngineStats toEngineStats(int maxPoolSize, int tenantPoolCount) {
        HikariPoolMXBean poolMxBean = mxBean();
        if (poolMxBean == null) {
            return EngineStats.empty();
        }
        return new EngineStats(
                poolMxBean.getActiveConnections(),
                poolMxBean.getIdleConnections(),
                maxPoolSize,
                poolMxBean.getThreadsAwaitingConnection(),
                0L,
                0L,
                0L,
                tenantPoolCount
        );
    }

    /* default */ AdmissionSnapshot admissionSnapshot(int maxPoolSize) {
        HikariPoolMXBean poolMxBean = mxBean();
        if (poolMxBean == null) {
            return new AdmissionSnapshot(0, 0, 0, maxPoolSize);
        }
        return new AdmissionSnapshot(
                poolMxBean.getActiveConnections(),
                poolMxBean.getIdleConnections(),
                poolMxBean.getThreadsAwaitingConnection(),
                maxPoolSize
        );
    }

    /* default */ PersistenceProviderException translateAcquireFailure
                (Throwable failure, String providerId, long timeoutMs) {
        SQLException sqlException = CommunityHikariUtils.findSqlException(failure);
        if (sqlException != null && CommunityHikariUtils.hasSqlState(sqlException)) {
            return PersistenceErrorTranslator.translate(sqlException.getSQLState(), sqlException.getMessage(), 
                sqlException);
        }
        return PersistenceProviderException.connectionExhausted(providerId, timeoutMs, activeConnections());
    }

    private int activeConnections() {
        HikariPoolMXBean poolMxBean = mxBean();
        return poolMxBean != null ? poolMxBean.getActiveConnections() : -1;
    }

    private boolean hasNoActiveConnections() {
        HikariPoolMXBean poolMxBean = mxBean();
        return poolMxBean == null || poolMxBean.getActiveConnections() == 0;
    }

    private HikariPoolMXBean mxBean() {
        return pool.getHikariPoolMXBean();
    }

    /* default */ record AdmissionSnapshot(
            int activeConnections,
            int idleConnections,
            int pendingAcquires,
            int maxConnections) {
    }
}