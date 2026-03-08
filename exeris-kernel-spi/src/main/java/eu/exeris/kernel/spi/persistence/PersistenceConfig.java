/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.persistence;

import java.util.Map;
import java.util.Objects;

/**
 * SPI: Immutable configuration record for {@link PersistenceProvider#createEngine}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record} — all fields are primitives or value-safe types.
 * Ready for {@code value record} migration (JEP 401) once toolchain supports it.
 * No identity operations ({@code ==}, {@code synchronized}, {@code System.identityHashCode()})
 * are permitted on instances of this type.
 *
 * <h2>Tier Separation (The Wall)</h2>
 * <p>This record exposes <em>logical</em> configuration knobs only. Enterprise
 * implementations map these to io_uring buffer registration, RLS pool routing,
 * and GlobalArbiter partition claims internally — none of that leaks here.
 *
 * <h2>Opaque Properties (Native Options)</h2>
 * <p>The {@code properties} map provides a type-safe escape hatch for tier-specific
 * configuration. Enterprise implementations read keys like
 * {@code "exeris.iouring.sqe_size"}, {@code "exeris.iouring.provided_buffers"},
 * or {@code "exeris.native.keepalive_idle_sec"} from this map.
 * Community implementations may pass these through to HikariCP data source properties.
 * The SPI layer treats this map as opaque — it never inspects its contents.
 *
 * @param connectionUrl       JDBC or URI-style connection URL (parsed by implementations for host/port/db).
 *                            Examples: {@code "jdbc:postgresql://localhost:5432/exeris"},
 *                            {@code "postgresql://localhost:5432/exeris"}
 * @param username            Database username.
 * @param password            Database password.
 *                            <b>SECRET — treat as a credential. Never log or expose this value.
 *                            {@link #toString()} deliberately redacts it.</b>
 * @param maxPoolSize         Maximum number of connections in the shared pool.
 * @param minIdleConnections  Minimum idle connections maintained.
 * @param connectionTimeoutMs Maximum wait time for a connection from pool (ms).
 * @param idleTimeoutMs       Maximum idle time before connection is evicted (ms).
 * @param maxLifetimeMs       Maximum connection lifetime before forced recycle (ms).
 * @param rlsEnabled          Whether Row-Level Security is active.
 * @param perTenantPooling    Whether to create per-tenant connection pools.
 * @param useTls              Whether TLS is required for the connection.
 * @param maxTenantPools      Maximum number of per-tenant pools (if perTenantPooling=true).
 * @param properties          Opaque key-value properties for tier-specific native options.
 *                            Never {@code null} — use {@link Map#of()} for empty.
 * @see PersistenceProvider
 * @since 0.5.0
 */
public record PersistenceConfig(
        String connectionUrl,
        String username,
        String password,
        int maxPoolSize,
        int minIdleConnections,
        long connectionTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs,
        boolean rlsEnabled,
        boolean perTenantPooling,
        boolean useTls,
        int maxTenantPools,
        Map<String, String> properties
) {

    private static final int MIN_POOL = 1;
    private static final int DEFAULT_MAX_POOL = 20;
    private static final int DEFAULT_MIN_IDLE = 2;
    private static final long DEFAULT_CONN_TIMEOUT_MS = 30_000L;
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 600_000L;
    private static final long DEFAULT_MAX_LIFETIME_MS = 1_800_000L;
    private static final int DEFAULT_MAX_TENANT_POOLS = 100;

    /**
     * Strict validation at construction time — fail fast, not silently.
     */
    public PersistenceConfig {
        Objects.requireNonNull(connectionUrl, "connectionUrl must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(password, "password must not be null");
        Objects.requireNonNull(properties, "properties must not be null — use Map.of()");
        if (maxPoolSize < MIN_POOL) {
            throw new IllegalArgumentException("maxPoolSize must be >= 1, got: " + maxPoolSize);
        }
        if (minIdleConnections < 0) {
            throw new IllegalArgumentException("minIdleConnections must be >= 0, got: " + minIdleConnections);
        }
        if (minIdleConnections > maxPoolSize) {
            throw new IllegalArgumentException(
                    "minIdleConnections (" + minIdleConnections + ") > maxPoolSize (" + maxPoolSize + ")");
        }
        if (connectionTimeoutMs <= 0) {
            throw new IllegalArgumentException("connectionTimeoutMs must be > 0");
        }
        if (maxTenantPools < 0) {
            throw new IllegalArgumentException("maxTenantPools must be >= 0");
        }
        // Defensive copy — ensures immutability (Valhalla readiness)
        properties = Map.copyOf(properties);
    }

    /**
     * Returns a safe string representation with all credential-bearing fields redacted.
     *
     * <p>The following fields are redacted to prevent accidental exposure in logs,
     * JFR events, or exception messages:
     * <ul>
     *   <li>{@code password} — always {@code [REDACTED]}</li>
     *   <li>{@code username} — always {@code [REDACTED]}</li>
     *   <li>{@code connectionUrl} — userinfo stripped; only {@code scheme://host:port/db} shown</li>
     *   <li>{@code properties} values — always {@code [REDACTED]} (may contain tier secrets)</li>
     * </ul>
     *
     * @return safe string representation
     */
    @Override
    public String toString() {
        return "PersistenceConfig[" +
                "connectionUrl=" + sanitizeUrl(connectionUrl) +
                ", username=[REDACTED]" +
                ", password=[REDACTED]" +
                ", maxPoolSize=" + maxPoolSize +
                ", minIdleConnections=" + minIdleConnections +
                ", connectionTimeoutMs=" + connectionTimeoutMs +
                ", idleTimeoutMs=" + idleTimeoutMs +
                ", maxLifetimeMs=" + maxLifetimeMs +
                ", rlsEnabled=" + rlsEnabled +
                ", perTenantPooling=" + perTenantPooling +
                ", useTls=" + useTls +
                ", maxTenantPools=" + maxTenantPools +
                ", properties={" + properties.size() + " entries, values=[REDACTED]}" +
                ']';
    }

    /**
     * Strips any embedded userinfo (user:password@) from a JDBC/native connection URL.
     *
     * <p>Example: {@code postgresql://user:secret@localhost:5432/db}
     * → {@code postgresql://localhost:5432/db}
     */
    private static String sanitizeUrl(String url) {
        if (url == null) {
            return "[null]";
        }
        // Strip user:password@ portion from URLs of the form scheme://user:pass@host/...
        return url.replaceAll("//[^@]*@", "//");
    }

    /**
     * Default configuration for development / unit tests.
     *
     * @param url      connection URL
     * @param username database user
     * @param password database password
     * @return dev-ready configuration
     */
    public static PersistenceConfig defaults(String url, String username, String password) {
        return new PersistenceConfig(
                url, username, password,
                DEFAULT_MAX_POOL, DEFAULT_MIN_IDLE,
                DEFAULT_CONN_TIMEOUT_MS, DEFAULT_IDLE_TIMEOUT_MS, DEFAULT_MAX_LIFETIME_MS,
                true, false, false,
                DEFAULT_MAX_TENANT_POOLS,
                Map.of()
        );
    }

    /**
     * Production configuration with RLS and TLS enabled.
     *
     * @param url         connection URL
     * @param username    database user
     * @param password    database password
     * @param maxPool     max pool size
     * @param minIdle     min idle connections
     * @param tenantPools max per-tenant pools
     * @return production-ready configuration
     */
    public static PersistenceConfig production(String url, String username, String password,
                                               int maxPool, int minIdle, int tenantPools) {
        return new PersistenceConfig(
                url, username, password,
                maxPool, minIdle,
                DEFAULT_CONN_TIMEOUT_MS, DEFAULT_IDLE_TIMEOUT_MS, DEFAULT_MAX_LIFETIME_MS,
                true, true, true,
                tenantPools,
                Map.of()
        );
    }

    /**
     * Production configuration with RLS, TLS, and native properties.
     *
     * <p>Use this overload to pass tier-specific native options (e.g.,
     * {@code "exeris.iouring.sqe_size"} for Enterprise io_uring tuning).
     *
     * @param url         connection URL
     * @param username    database user
     * @param password    database password
     * @param maxPool     max pool size
     * @param minIdle     min idle connections
     * @param tenantPools max per-tenant pools
     * @param properties  opaque native options
     * @return production-ready configuration with native options
     * @since 0.5.0
     */
    public static PersistenceConfig production(String url, String username, String password,
                                               int maxPool, int minIdle, int tenantPools,
                                               Map<String, String> properties) {
        return new PersistenceConfig(
                url, username, password,
                maxPool, minIdle,
                DEFAULT_CONN_TIMEOUT_MS, DEFAULT_IDLE_TIMEOUT_MS, DEFAULT_MAX_LIFETIME_MS,
                true, true, true,
                tenantPools,
                properties
        );
    }
}
