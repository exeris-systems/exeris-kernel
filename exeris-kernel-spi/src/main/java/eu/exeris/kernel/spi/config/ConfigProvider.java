/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.config;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Kernel-Grade Config SPI — L0 Foundation.
 *
 * <h2>The Wall</h2>
 * <p>This interface is the single SPI entry point for configuration. It is loaded via
 * {@code ServiceLoader} by the {@code KernelBootstrap} in {@code exeris-kernel-core}.
 * The highest-{@link #priority()} implementation found on the classpath wins.
 * This SPI does not hard-code any implementation; implementations are discovered via {@code ServiceLoader}.
 *
 * <h2>Design Philosophy</h2>
 * <p>Configuration is no longer a {@code Map<String, Object>} or a mutable POJO.
 * Each section is an immutable, identity-free data carrier: today a {@code record},
 * tomorrow a {@code value record} (JEP 401 / Valhalla). The JVM can flatten value
 * records into arrays and stack frames, eliminating pointer-chasing on hot-paths.
 *
 * <h2>Lazy Initialization (JEP 526 Readiness)</h2>
 * <p>{@link #kernelSettings()} returns a {@link Supplier}{@code <KernelSettings>} today.
 * The intent is identical to the proposed {@code LazyConstant<KernelSettings>}:
 * the value is computed <em>exactly once</em>, on first access, and then treated
 * by the JVM as effectively final — eligible for constant-folding and inlining.
 *
 * <p>Migration path when JEP 526 stabilizes:
 * <pre>{@code
 * // Today (JDK 26, no preview required):
 * Supplier<KernelSettings> kernelSettings();
 *
 * // JDK 27+ with LazyConstant:
 * LazyConstant<KernelSettings> kernelSettings();
 * }</pre>
 *
 * <h2>ScopedValue Propagation (JEP 506)</h2>
 * <p>The resolved {@code ConfigProvider} is bound to
 * {@link eu.exeris.kernel.spi.context.KernelProviders#CURRENT_CONFIG} by the
 * {@code KernelBootstrap} during L0 bootstrap and flows automatically to every
 * virtual thread spawned within the kernel scope — no constructor injection needed.
 *
 * <h2>ServiceLoader Registration</h2>
 * <pre>
 * # META-INF/services/eu.exeris.kernel.spi.config.ConfigProvider
 * eu.exeris.kernel.community.config.SimpleFileConfigProvider   # priority=100
 * eu.exeris.kernel.enterprise.config.EnterpriseConfigProvider  # priority=200
 * </pre>
 *
 * @since 0.5.0
 */
public interface ConfigProvider {

    // =========================================================================
    // Typed settings — Value Record API
    // =========================================================================

    /**
     * Returns the fully resolved kernel settings, computed lazily and exactly once.
     *
     * <p>Implementations MUST guarantee:
     * <ul>
     *   <li>Thread-safe single initialization via {@code AtomicReference} CAS or
     *       equivalent (mirrors the {@code LazyConstant} semantic from JEP 526).</li>
     *   <li>The returned object is effectively immutable after first access.</li>
     *   <li>Repeated calls always return the same instance — eligible for JIT
     *       constant-folding after warm-up.</li>
     * </ul>
     *
     * @return lazy supplier of kernel settings; never {@code null}
     */
    Supplier<KernelSettings> kernelSettings();

    // =========================================================================
    // Raw key-value access
    // =========================================================================

    /** Returns the string value for {@code key}, or empty if absent. */
    Optional<String> getString(String key);

    /** Returns the integer value for {@code key}, or empty if absent. */
    Optional<Integer> getInt(String key);

    /** Returns the long value for {@code key}, or empty if absent. */
    Optional<Long> getLong(String key);

    /** Returns the boolean value for {@code key}, or empty if absent. */
    Optional<Boolean> getBoolean(String key);

    /** Returns the value for {@code key} deserialized to {@code type}, or empty if absent. */
    <T> Optional<T> get(String key, Class<T> type);

    // =========================================================================
    // Convenience defaults
    // =========================================================================

    default String getStringOrDefault(String key, String defaultValue) {
        return getString(key).orElse(defaultValue);
    }

    default int getIntOrDefault(String key, int defaultValue) {
        return getInt(key).orElse(defaultValue);
    }

    default long getLongOrDefault(String key, long defaultValue) {
        return getLong(key).orElse(defaultValue);
    }

    default boolean getBooleanOrDefault(String key, boolean defaultValue) {
        return getBoolean(key).orElse(defaultValue);
    }
    // =========================================================================
    // Hot-reload (@Dynamic support)
    // =========================================================================

    /**
     * Registers a callback invoked when the value at {@code key} changes.
     *
     * <p>Community implementations treat this as a no-op (no hot-reload support).
     * Enterprise implementations deliver updates via a Virtual Thread watcher backed
     * by {@code NIO WatchService} — never a platform thread or
     * {@code ScheduledExecutorService}.
     *
     * @param file     observed filename (relative to config directory); {@code null} = any
     * @param key      dot-path key (e.g., {@code "network.port"})
     * @param callback receives the new deserialized value; invoked on a virtual thread
     */
    void watch(String file, String key, Consumer<Object> callback);

    // =========================================================================
    // Provider metadata
    // =========================================================================

    /**
     * Selection priority — the highest value wins at bootstrap time.
     *
     * <p>Convention: Community=100, Enterprise=200.
     *
     * @return priority ≥ 0
     */
    default int priority() {
        return 100;
    }

    /**
     * Human-readable provider name for logging and JFR telemetry.
     *
     * @return non-null name string
     */
    default String providerName() {
        return getClass().getSimpleName();
    }

    // =========================================================================
    // Value Records — Kernel Settings Hierarchy
    //
    // All records are @ValueCandidate — ready for 'value record' (JEP 401).
    // No identity operations (==, synchronized, identityHashCode) are used,
    // enabling clean scalarization via C2 JIT Escape Analysis today.
    // =========================================================================

    /**
     * Top-level, immutable kernel settings.
     *
     * <h3>Memory layout</h3>
     * <p>Today: 3 references + 1 long ≈ 40 bytes on heap per instance.
     * With {@code value record} (JEP 401): inlined into parent, zero heap allocation.
     *
     * @param profile        active kernel profile ({@code "dev"} / {@code "test"} / {@code "prod"})
     * @param globalMemoryMb total off-heap budget in MB (set by {@code ExerisSmartLauncher})
     * @param network        network / transport settings
     * @param persistence    persistence / database settings
     * @param telemetry      telemetry and observability settings
     */
    @ValueCandidate
    record KernelSettings(
            String profile,
            long globalMemoryMb,
            NetworkSettings network,
            PersistenceSettings persistence,
            TelemetrySettings telemetry
    ) {
        /** Creates minimal settings using defaults for all optional sections. */
        public static KernelSettings defaults() {
            return new KernelSettings(
                    "prod",
                    512L,
                    NetworkSettings.defaults(),
                    PersistenceSettings.defaults(),
                    TelemetrySettings.defaults()
            );
        }

        public boolean isDev() {
            return "dev".equalsIgnoreCase(profile);
        }

        public boolean isProd() {
            return "prod".equalsIgnoreCase(profile);
        }

        public boolean isTest() {
            return "test".equalsIgnoreCase(profile);
        }
    }

    /**
     * Network / transport layer settings.
     *
     * @param port                     HTTP/3 + QUIC listener port
     * @param bufferSize               per-connection off-heap buffer size in bytes
     * @param nativeTransportPreferred hint to prefer kernel-native async I/O where available
     *                                 (implementations may ignore if unsupported on the host OS;
     *                                 Enterprise maps this to {@code io_uring}/epoll/KQueue internally)
     * @param reactorCount             number of reactor threads (0 = auto-detect from CPU topology)
     * @param quicEnabled              whether QUIC transport is active
     */
    @ValueCandidate
    record NetworkSettings(
            int port,
            int bufferSize,
            boolean nativeTransportPreferred,
            int reactorCount,
            boolean quicEnabled
    ) {
        public static NetworkSettings defaults() {
            return new NetworkSettings(8443, 65_536, true, 0, true);
        }
    }

    /**
     * Persistence / database settings.
     *
     * <p><b>Security note:</b> {@code password} should originate from Vault
     * (Enterprise) or an environment variable, never from a checked-in file.
     * {@link #toString()} deliberately redacts all credential-bearing fields.
     *
     * @param jdbcUrl        JDBC connection URL
     * @param username       database user — <b>SECRET</b>, never logged
     * @param password       database password — <b>SECRET</b>, never logged
     * @param maxPoolSize    connection pool ceiling
     * @param runMigrations  whether to run schema migrations on startup
     */
    @ValueCandidate
    record PersistenceSettings(
            String jdbcUrl,
            String username,
            String password,
            int maxPoolSize,
            boolean runMigrations
    ) {
        public static PersistenceSettings defaults() {
            return new PersistenceSettings(
                    "jdbc:postgresql://localhost:5432/exeris",
                    "exeris", "", 20, false
            );
        }

        /**
         * Returns a safe string representation with all credential-bearing fields redacted.
         *
         * <p>The following fields are always redacted to prevent accidental exposure
         * in logs, JFR events, or exception messages:
         * <ul>
         *   <li>{@code password} — always {@code [REDACTED]}</li>
         *   <li>{@code username} — always {@code [REDACTED]}</li>
         *   <li>{@code jdbcUrl}  — userinfo ({@code user:pass@}) stripped and query string
         *       ({@code ?...}) removed; only {@code scheme://host:port/path} is shown,
         *       preventing credential leakage via URL parameters (e.g., {@code ?password=xyz})</li>
         * </ul>
         *
         * @return safe string representation
         */
        @Override
        public String toString() {
            return "PersistenceSettings["
                    + "jdbcUrl=" + sanitizeUrl(jdbcUrl)
                    + ", username=[REDACTED]"
                    + ", password=[REDACTED]"
                    + ", maxPoolSize=" + maxPoolSize
                    + ", runMigrations=" + runMigrations
                    + ']';
        }

        /**
         * Strips userinfo ({@code user:password@}) and query/fragment ({@code ?...}, {@code #...})
         * from a JDBC URL to prevent credential leakage into logs and telemetry.
         *
         * <p>Examples:
         * <ul>
         *   <li>{@code jdbc:postgresql://user:secret@localhost:5432/db}
         *       → {@code jdbc:postgresql://localhost:5432/db}</li>
         *   <li>{@code jdbc:postgresql://localhost:5432/db?password=xyz&sslcert=...}
         *       → {@code jdbc:postgresql://localhost:5432/db}</li>
         * </ul>
         */
        private static String sanitizeUrl(String url) {
            if (url == null) {
                return "[null]";
            }
            // 1. Strip userinfo: scheme://user:pass@host → scheme://host
            String stripped = url.replaceFirst("(\\w[\\w+\\-.]*://)[^@]+@", "$1");
            // 2. Strip query string and fragment: anything from '?' or '#' onwards
            int queryIdx = stripped.indexOf('?');
            int fragIdx  = stripped.indexOf('#');
            int cut;
            if (queryIdx >= 0 && (fragIdx < 0 || queryIdx <= fragIdx)) {
                cut = queryIdx;
            } else if (fragIdx >= 0) {
                cut = fragIdx;
            } else {
                cut = -1;
            }
            return cut >= 0 ? stripped.substring(0, cut) : stripped;
        }
    }

    /**
     * Telemetry and observability settings.
     *
     * @param jfrEnabled     whether JFR recording is active
     * @param metricsEnabled whether Prometheus metrics endpoint is active
     * @param tracingEnabled whether distributed tracing (OTEL) is active
     * @param nodeId         unique identifier for this kernel instance
     * @param region         deployment region for distributed tracing
     */
    @ValueCandidate
    record TelemetrySettings(
            boolean jfrEnabled,
            boolean metricsEnabled,
            boolean tracingEnabled,
            String nodeId,
            String region
    ) {
        public static TelemetrySettings defaults() {
            return new TelemetrySettings(true, true, false, "local", "default");
        }
    }

    // =========================================================================
    // @ValueCandidate marker annotation
    // =========================================================================

    /**
     * Documents that this record is a candidate for promotion to
     * {@code value record} (JEP 401 / Project Valhalla).
     *
     * <p>Requirements already satisfied:
     * <ul>
     *   <li>All fields are final, assigned only in the canonical constructor.</li>
     *   <li>No mutable state, no {@code synchronized}, no identity-dependent ops.</li>
     *   <li>No inheritance (record semantics prevent this).</li>
     * </ul>
     *
     * <p>This annotation is SOURCE-retained — zero runtime overhead.
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @interface ValueCandidate {}

    // =========================================================================
    // Exception
    // =========================================================================

    /**
     * Thrown when the {@code ConfigProvider} cannot satisfy a required key or
     * encounters a fatal loading error during L0 bootstrap.
     *
     * <h2>Telemetry Contract</h2>
     * <p>Extends {@link ExerisKernelException} — inherits the structured {@code errorCode}
     * and {@code rawArgs} binary telemetry infrastructure. Use the typed factory methods
     * to ensure correct {@link KernelErrorCodes} codes and a stable {@code rawArgs} layout.
     *
     * <h2>Error Codes</h2>
     * <ul>
     *   <li>{@link KernelErrorCodes#EX_CFG_1001} — required property missing</li>
     *   <li>{@link KernelErrorCodes#EX_CFG_1002} — type mismatch</li>
     *   <li>{@link KernelErrorCodes#EX_CFG_1003} — hot-reload file read error</li>
     * </ul>
     */
    class ConfigProviderException extends ExerisKernelException {

        /**
         * General-purpose constructor for config failures not covered by the typed factory methods.
         *
         * <p>For new code, prefer the factory methods ({@link #missingProperty},
         * {@link #typeMismatch}, {@link #hotReloadFailure}) to guarantee a stable
         * {@code rawArgs} layout for Black-Box telemetry.
         *
         * @param errorCode a non-null {@code EX-CFG-*} code from {@link KernelErrorCodes}
         * @param message   human-readable message; may include runtime details for console/log
         *                  output during L0 boot failure — for stable machine-readable telemetry,
         *                  rely on {@code errorCode} and {@code rawArgs} instead
         * @param cause     upstream throwable; may be {@code null}
         * @param rawArgs   raw domain arguments for binary telemetry
         */
        public ConfigProviderException(String errorCode, String message, Throwable cause, Object... rawArgs) {
            super(errorCode, message, cause, rawArgs);
        }

        /**
         * Required property missing — {@link KernelErrorCodes#EX_CFG_1001}.
         *
         * <p><b>rawArgs:</b> [0] missingKey, [1] providerName
         *
         * @param missingKey   dot-path key that was not found
         * @param providerName name of the active {@code ConfigProvider}
         * @return exception with error code {@code EX-CFG-1001}
         */
        public static ConfigProviderException missingProperty(String missingKey, String providerName) {
            return new ConfigProviderException(
                    KernelErrorCodes.EX_CFG_1001,
                    "Required configuration property missing: " + missingKey,
                    null,
                    missingKey, providerName);
        }

        /**
         * Type mismatch — {@link KernelErrorCodes#EX_CFG_1002}.
         *
         * <p><b>rawArgs:</b> [0] key, [1] expectedType, [2] actualValue (truncated)
         *
         * @param key          dot-path key
         * @param expectedType simple class name of the requested target type
         * @param actualValue  raw string value present in the config source
         * @return exception with error code {@code EX-CFG-1002}
         */
        public static ConfigProviderException typeMismatch(String key, String expectedType, String actualValue) {
            return new ConfigProviderException(
                    KernelErrorCodes.EX_CFG_1002,
                    "Configuration type mismatch for key: " + key,
                    null,
                    key, expectedType, actualValue);
        }

        /**
         * Hot-reload file read error — {@link KernelErrorCodes#EX_CFG_1003}.
         *
         * <p><b>rawArgs:</b> [0] filename, [1] reason
         *
         * @param filename relative path to the config file that could not be read
         * @param reason   static failure description
         * @param cause    upstream I/O exception; may be {@code null}
         * @return exception with error code {@code EX-CFG-1003}
         */
        public static ConfigProviderException hotReloadFailure(String filename, String reason, Throwable cause) {
            return new ConfigProviderException(
                    KernelErrorCodes.EX_CFG_1003,
                    "Hot-reload file read error: " + filename,
                    cause,
                    filename, reason);
        }
    }
}




