/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * Most keys remain opaque to SPI, but standardized keys {@code pool.warmup.enabled}
 * and {@code pool.warmup.connections} are interpreted here for shared pool warm-up.
 *
 * @param connectionUrl          JDBC or URI-style connection URL (parsed by implementations for host/port/db).
 *                               Examples: {@code "jdbc:postgresql://localhost:5432/exeris"},
 *                               {@code "postgresql://localhost:5432/exeris"}
 * @param username               Database username.
 * @param password               Database password.
 *                               <b>SECRET — treat as a credential. Never log or expose this value.
 *                               {@link #toString()} deliberately redacts it.</b>
 * @param maxPoolSize            Maximum number of connections in the shared pool.
 * @param minIdleConnections     Minimum idle connections maintained.
 * @param connectionTimeoutMs    Maximum wait time for a connection from pool (ms).
 * @param idleTimeoutMs          Maximum idle time before connection is evicted (ms).
 * @param maxLifetimeMs          Maximum connection lifetime before forced recycle (ms).
 * @param rlsEnabled             Whether Row-Level Security is active.
 * @param perTenantPooling       Whether to create per-tenant connection pools.
 * @param useTls                 Whether TLS is required for the connection.
 * @param maxTenantPools         Maximum number of per-tenant pools (if perTenantPooling=true).
 * @param properties             Opaque key-value properties for tier-specific native options.
 *                               Never {@code null} — use {@link Map#of()} for empty.
 * @param dedicatedDataSources   Per-key datasource configurations for the
 *                               {@link eu.exeris.kernel.spi.security.StorageContext.IsolationStrategy#DEDICATED}
 *                               strategy. Keys must match the {@code x-exeris-isolation-datasource}
 *                               JWT claim value (i.e. the value returned by
 *                               {@link eu.exeris.kernel.spi.security.StorageContext#dataSourceKey()}).
 *                               Never {@code null} — use {@link Map#of()} for empty.
 *                               Nested entries MUST NOT themselves have non-empty
 *                               {@code dedicatedDataSources} maps (nesting depth = 1).
 * @since 0.5
 * @see PersistenceProvider
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
        Map<String, String> properties,
        Map<String, PersistenceConfig> dedicatedDataSources
) {

    public static final String POOL_WARMUP_ENABLED_KEY = "pool.warmup.enabled";
    public static final String POOL_WARMUP_CONNECTIONS_KEY = "pool.warmup.connections";
    public static final boolean DEFAULT_POOL_WARMUP_ENABLED = true;
    public static final int DEFAULT_POOL_WARMUP_CONNECTIONS = 2;
    public static final int MIN_POOL_WARMUP_CONNECTIONS = 1;
    public static final int MAX_POOL_WARMUP_CONNECTIONS = 8;

    private static final int MIN_POOL = 1;
    private static final int DEFAULT_MAX_POOL = 256;
    private static final int DEFAULT_MIN_IDLE = 16;
    private static final long DEFAULT_CONN_TIMEOUT_MS = 30_000L;
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 600_000L;
    private static final long DEFAULT_MAX_LIFETIME_MS = 1_800_000L;
    private static final int DEFAULT_MAX_TENANT_POOLS = 100;

    /**
     * Validates every component at construction time and takes defensive copies of both maps, so
     * that an invalid pool sizing or warm-up property is rejected at configuration time rather
     * than on the first connection.
     *
     * @throws NullPointerException     if {@code connectionUrl}, {@code username},
     *                                  {@code password}, {@code properties} or
     *                                  {@code dedicatedDataSources} is {@code null}
     * @throws IllegalArgumentException if {@code maxPoolSize} is below 1,
     *                                  {@code minIdleConnections} is negative or exceeds
     *                                  {@code maxPoolSize}, {@code connectionTimeoutMs} is not
     *                                  positive, {@code maxTenantPools} is negative, a dedicated
     *                                  datasource nests further dedicated datasources, or a
     *                                  {@code pool.warmup.*} property is unparsable or out of range
     */
    public PersistenceConfig {
        Objects.requireNonNull(connectionUrl, "connectionUrl must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(password, "password must not be null");
        Objects.requireNonNull(properties, "properties must not be null — use Map.of()");
        Objects.requireNonNull(dedicatedDataSources, "dedicatedDataSources must not be null — use Map.of()");
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
        for (Map.Entry<String, PersistenceConfig> entry : dedicatedDataSources.entrySet()) {
            if (!entry.getValue().dedicatedDataSources().isEmpty()) {
                throw new IllegalArgumentException(
                        "Dedicated datasource configs must not contain nested dedicatedDataSources: "
                                + entry.getKey());
            }
        }
        validateWarmupProperties(properties);
        // Defensive copies — ensures immutability (Valhalla readiness)
        properties = Map.copyOf(properties);
        dedicatedDataSources = Map.copyOf(dedicatedDataSources);
    }

    /**
     * Builds a configuration that declares no dedicated datasources, delegating to the canonical
     * constructor with an empty {@code dedicatedDataSources} map.
     *
     * @param connectionUrl       JDBC or URI-style connection URL, e.g.
     *                            {@code "jdbc:postgresql://localhost:5432/exeris"}
     * @param username            database username
     * @param password            database password — a credential; it is never logged, and
     *                            {@link #toString()} redacts it
     * @param maxPoolSize         maximum number of connections in the shared pool; at least 1
     * @param minIdleConnections  minimum idle connections maintained; between 0 and
     *                            {@code maxPoolSize}
     * @param connectionTimeoutMs maximum wait for a connection from the pool, in milliseconds;
     *                            positive. Exceeding it raises
     *                            {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5002}
     * @param idleTimeoutMs       maximum idle time before a connection is evicted, in milliseconds
     * @param maxLifetimeMs       maximum connection lifetime before forced recycle, in milliseconds
     * @param rlsEnabled          whether Row-Level Security is active
     * @param perTenantPooling    whether to create per-tenant connection pools
     * @param useTls              whether TLS is required for the connection
     * @param maxTenantPools      maximum number of per-tenant pools; not negative, and only
     *                            consulted when {@code perTenantPooling} is set
     * @param properties          opaque key-value properties for tier-specific native options;
     *                            never {@code null} — use {@link Map#of()} for none
     * @throws NullPointerException     on a {@code null} reference component
     * @throws IllegalArgumentException on a component outside the range stated above
     * @apiNote A caller that needs
     *          {@link eu.exeris.kernel.spi.security.StorageContext.IsolationStrategy#DEDICATED}
     *          routing calls the canonical 14-component constructor instead; this overload cannot
     *          express it.
     */
    @SuppressWarnings("PMD.ExcessiveParameterList") // backward-compat bridge
    public PersistenceConfig(
            String connectionUrl, String username, String password,
            int maxPoolSize, int minIdleConnections,
            long connectionTimeoutMs, long idleTimeoutMs, long maxLifetimeMs,
            boolean rlsEnabled, boolean perTenantPooling, boolean useTls,
            int maxTenantPools,
            Map<String, String> properties) {
        this(connectionUrl, username, password,
                maxPoolSize, minIdleConnections,
                connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs,
                rlsEnabled, perTenantPooling, useTls,
                maxTenantPools, properties, Map.of());
    }

    /**
     * Reads the {@code pool.warmup.enabled} property, which decides whether the shared pool opens
     * connections at engine startup instead of on first use.
     *
     * @return {@code true} when the property is absent — the default — or set to {@code "true"}
     *         in any case; {@code false} only for an explicit {@code "false"}
     */
    public boolean poolWarmupEnabled() {
        String val = properties.get(POOL_WARMUP_ENABLED_KEY);
        if (val == null) {
            return DEFAULT_POOL_WARMUP_ENABLED;
        }
        return "true".equalsIgnoreCase(val);
    }

    /**
     * Reads the {@code pool.warmup.connections} property, which says how many connections the
     * shared pool opens at startup when warm-up is enabled.
     *
     * @return the configured count, in {@code [1, 8]}, or {@code 2} when the property is absent;
     *         a value outside that range is rejected at construction rather than clamped here
     */
    public int poolWarmupConnections() {
        String val = properties.get(POOL_WARMUP_CONNECTIONS_KEY);
        if (val == null) {
            return DEFAULT_POOL_WARMUP_CONNECTIONS;
        }
        return parseWarmupConnections(val);
    }

    private static void validateWarmupProperties(Map<String, String> properties) {
        validateWarmupEnabled(properties.get(POOL_WARMUP_ENABLED_KEY));
        validateWarmupConnections(properties.get(POOL_WARMUP_CONNECTIONS_KEY));
    }

    private static void validateWarmupEnabled(String warmupEnabled) {
        if (warmupEnabled != null
                && !"true".equalsIgnoreCase(warmupEnabled)
                && !"false".equalsIgnoreCase(warmupEnabled)) {
            throw new IllegalArgumentException(
                    POOL_WARMUP_ENABLED_KEY + " must be true or false, got: " + warmupEnabled);
        }
    }

    private static void validateWarmupConnections(String warmupConnections) {
        if (warmupConnections == null) {
            return;
        }
        int parsed = parseWarmupConnections(warmupConnections);
        if (parsed < MIN_POOL_WARMUP_CONNECTIONS || parsed > MAX_POOL_WARMUP_CONNECTIONS) {
            throw new IllegalArgumentException(
                    POOL_WARMUP_CONNECTIONS_KEY + " must be an integer in ["
                            + MIN_POOL_WARMUP_CONNECTIONS + ',' + MAX_POOL_WARMUP_CONNECTIONS
                            + "], got: " + warmupConnections);
        }
    }

    private static int parseWarmupConnections(String warmupConnections) {
        try {
            return Integer.parseInt(warmupConnections);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    POOL_WARMUP_CONNECTIONS_KEY + " must be an integer in ["
                            + MIN_POOL_WARMUP_CONNECTIONS + ',' + MAX_POOL_WARMUP_CONNECTIONS
                            + "], got: " + warmupConnections,
                    ex);
        }
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
                ", dedicatedDataSources={" + formatDedicatedDataSources(dedicatedDataSources) + "}" +
                ']';
    }

    private static String formatDedicatedDataSources(Map<String, PersistenceConfig> dedicatedSources) {
        if (dedicatedSources.isEmpty()) {
            return "empty";
        }
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, PersistenceConfig> e : dedicatedSources.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return builder.toString();
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
     * Fixed convenience preset for development and unit tests.
     *
     * <p>This helper always returns the same pool and timeout values
     * ({@code maxPoolSize=256}, {@code minIdleConnections=16}, standard timeouts)
     * so tests and local harnesses get a predictable baseline. It is not the
     * Community runtime bootstrap default. Community bootstrap resolves runtime
     * sizing adaptively when persistence pool settings are left unset.
     *
     * @param url      connection URL
     * @param username database user
     * @param password database password
     * @return fixed dev/test preset
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
     * @since 0.5
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
