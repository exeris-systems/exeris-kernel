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
import java.util.Map;

final class CommunityHikariSupport {

    private final HikariDataSource pool;

    private CommunityHikariSupport(HikariDataSource pool) {
        this.pool = pool;
    }

    /* default */ static CommunityHikariSupport with(HikariDataSource pool) {
        return new CommunityHikariSupport(pool);
    }

    /* default */ static HikariDataSource buildPool(PersistenceConfig config, String tenantKey) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.connectionUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(config.maxPoolSize());
        hikariConfig.setMinimumIdle(config.minIdleConnections());
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
        properties.forEach(hikariConfig::addDataSourceProperty);
    }

    /* default */ JdbcPersistenceConnection acquireConnection(String providerId, String tenantKey) throws SQLException {
        long startNs = System.nanoTime();
        ConnectionAcquireEvent event = ConnectionAcquireEvent.beginAcquire();
        Connection raw = pool.getConnection();
        ConnectionAcquireEvent.endAcquire(event, providerId, tenantKey, true, startNs);
        return new JdbcPersistenceConnection(raw);
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

    private HikariPoolMXBean mxBean() {
        return pool.getHikariPoolMXBean();
    }
}