/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * eu.exeris.kernel.community.config.SimpleFileConfigProvider   # priority=0
 * eu.exeris.kernel.enterprise.config.EnterpriseConfigProvider  # priority=100
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
     * @param callback receives an {@code Object} whose runtime type is {@code String}
     *                 containing the new raw value read from the config file; invoked on a
     *                 virtual thread; must not be {@code null}
     */
    void watch(String file, String key, Consumer<Object> callback);

    /**
     * Registers a key marked {@link Immutable} as a sealed trust anchor — the inverse of
     * {@link #watch(String, String, Consumer)}.
     *
     * <p>Once guarded, any on-disk change to {@code (file, key)} observed by the watcher is
     * <b>refused</b> (the field is never updated) and surfaced as a secret-safe structured
     * event under {@link KernelErrorCodes#EX_CFG_1004}.
     *
     * <p>The default implementation is a no-op: Community-tier providers do not run a
     * hot-reload watcher, so an {@code @Immutable} key is already effectively sealed.
     * The kernel's registry-backed provider overrides this to record the guard so the
     * Core {@code WatchService} driver can enforce the refusal.
     *
     * @param file observed filename (relative to config directory); {@code null} = any
     * @param key  dot-path key to seal (e.g., {@code "security.jwks.uri"})
     * @see Immutable
     */
    default void guardImmutable(String file, String key) {
        // Default: no-op — no hot-reload watcher, key is already sealed.
    }

    // =========================================================================
    // Provider metadata
    // =========================================================================

    /**
     * Selection priority — the highest value wins at bootstrap time.
     *
     * <p>Convention: Community=0, Enterprise=100.
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
     * <h3>Strong Typing</h3>
     * <p>{@code profile} is now a {@link KernelProfile} enum — JVM singletons, zero heap
     * allocation on comparison, and JIT-constant-folded identity checks replace the former
     * {@code String.equalsIgnoreCase} hot-path calls. This is idiomatic Project Valhalla
     * preparation: enum references are effectively integer-width constants that the C2
     * compiler scalarizes cleanly.
     *
     * @param profile        active kernel profile ({@link KernelProfile#DEV} /
     *                       {@link KernelProfile#TEST} / {@link KernelProfile#PROD})
     * @param globalMemoryMb total off-heap budget in MB (set by {@code ExerisSmartLauncher})
     * @param network        network / transport settings
     * @param persistence    persistence / database settings
     * @param telemetry      telemetry and observability settings
     */
    @ValueCandidate
    record KernelSettings(
            KernelProfile profile,
            long globalMemoryMb,
            NetworkSettings network,
            PersistenceSettings persistence,
            TelemetrySettings telemetry
    ) {
        /** Creates minimal settings using defaults for all optional sections. */
        public static KernelSettings defaults() {
            return new KernelSettings(
                    KernelProfile.PROD,
                    512L,
                    NetworkSettings.defaults(),
                    PersistenceSettings.defaults(),
                    TelemetrySettings.defaults()
            );
        }

        /** @return {@code true} when running in the {@link KernelProfile#DEV} profile. */
        public boolean isDev() {
            return profile.isDev();
        }

        /** @return {@code true} when running in the {@link KernelProfile#PROD} profile. */
        public boolean isProd() {
            return profile.isProd();
        }

        /** @return {@code true} when running in the {@link KernelProfile#TEST} profile. */
        public boolean isTest() {
            return profile.isTest();
        }
    }

    /**
     * Network / transport layer settings.
     *
     * @param port                     HTTP/3 + QUIC listener port
     * @param bufferSize               per-connection off-heap buffer size in bytes
     * @param nativeTransportPreferred hint to prefer a native asynchronous I/O transport where available
     *                                 (implementations may ignore this flag if no suitable native backend
     *                                 is available on the host; mapping to concrete mechanisms is
     *                                 implementation-specific and out of scope for this SPI)
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
            // 1. Strip userinfo: //user:pass@host → //host
            //    Pattern //[^@]*@ handles multi-part JDBC schemes (e.g. jdbc:postgresql://)
            //    where the scheme component contains a colon — the simpler approach avoids
            //    matching scheme-specific characters entirely and is consistent with
            //    PersistenceConfig.sanitizeUrl() and PersistenceProviderException.sanitizeUrl().
            String stripped = url.replaceAll("//[^@]*@", "//");
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
     * <p><b>Reserved knobs.</b> {@code tracingEnabled} and {@code region} are <em>dormant</em>:
     * they are parsed and carried in config, but the kernel has no distributed-tracing /
     * OTLP emission path yet — there is no {@code TraceContext} carrier, no OTLP sink, and no
     * span emission in SPI/Core/Community. They are forward placeholders for the kernel tracing
     * milestone tracked in {@code docs/ROADMAP.md} §"Telemetry: OTLP Metrics Export and
     * Distributed Tracing" (targeted ~Sprint 0.12 / v0.12 of the consolidated 1.0 GA roadmap).
     * The only telemetry export shipping today is the Prometheus pull sink (v0.7).
     *
     * @param jfrEnabled     whether JFR recording is active
     * @param metricsEnabled whether the Prometheus metrics endpoint is active
     * @param tracingEnabled reserved — distributed tracing (OTLP) is not implemented yet; parsed
     *                       and carried but not acted upon (see the reserved-knobs note above)
     * @param nodeId         unique identifier for this kernel instance
     * @param region         reserved — deployment region intended for distributed tracing labels
     *                       (dormant until the tracing milestone; see the reserved-knobs note above)
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

        /** Maximum number of characters retained from a non-sensitive config value in telemetry. */
        private static final int MAX_VALUE_PREVIEW_LENGTH = 32;

        /**
         * General-purpose constructor for config failures not covered by the typed factory methods.
         *
         * <p>For new code, prefer the factory methods ({@link #missingProperty},
         * {@link #typeMismatch}, {@link #hotReloadFailure}) to guarantee a stable
         * {@code rawArgs} layout for Glass-Box telemetry.
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
         * <p><b>rawArgs:</b> [0] key, [1] expectedType, [2] sanitizedValue
         *
         * <p>The {@code actualValue} parameter is automatically sanitized before it is
         * stored in {@code rawArgs} to prevent CWE-532 (Information Exposure Through Log
         * Files). Sanitization rules, applied in order:
         * <ol>
         *   <li><b>Redaction</b> — if {@code key} (case-insensitive) contains any of
         *       {@code "pass"}, {@code "secret"}, {@code "token"}, {@code "credential"},
         *       {@code "apikey"}, or {@code "private"}, the value is replaced with
         *       {@code "[REDACTED]"} unconditionally.</li>
         *   <li><b>Truncation</b> — all other values are truncated to at most
         *       {@value #MAX_VALUE_PREVIEW_LENGTH} characters and suffixed with
         *       {@code "…"} when truncated, preventing large blobs from inflating the
         *       telemetry stream.</li>
         * </ol>
         *
         * @param key          dot-path key
         * @param expectedType simple class name of the requested target type
         * @param actualValue  raw string value present in the config source — will be
         *                     sanitized before reaching telemetry; callers need not
         *                     pre-sanitize this parameter
         * @return exception with error code {@code EX-CFG-1002}
         */
        public static ConfigProviderException typeMismatch(String key, String expectedType, String actualValue) {
            return new ConfigProviderException(
                    KernelErrorCodes.EX_CFG_1002,
                    "Configuration type mismatch for key: " + key,
                    null,
                    key, expectedType, sanitizeConfigValue(key, actualValue));
        }

        /**
         * Sanitizes a raw configuration value for safe inclusion in Glass-Box telemetry.
         *
         * <p>Sensitive keys are fully redacted; all other values are truncated to
         * {@value #MAX_VALUE_PREVIEW_LENGTH} characters.
         *
         * @param key   dot-path config key used to detect sensitivity
         * @param value raw value to sanitize; {@code null} is returned as-is
         * @return a telemetry-safe snapshot of the value
         */
        private static String sanitizeConfigValue(String key, String value) {
            if (value == null) {
                return null;
            }
            if (key == null) {
                // No key context — truncate only; cannot detect sensitivity without a key.
                return value.length() > MAX_VALUE_PREVIEW_LENGTH
                        ? value.substring(0, MAX_VALUE_PREVIEW_LENGTH) + "\u2026"
                        : value;
            }
            String keyLower = key.toLowerCase(java.util.Locale.ROOT);
            boolean sensitive = keyLower.contains("pass")
                    || keyLower.contains("secret")
                    || keyLower.contains("token")
                    || keyLower.contains("credential")
                    || keyLower.contains("apikey")
                    || keyLower.contains("private");
            if (sensitive) {
                return "[REDACTED]";
            }
            if (value.length() > MAX_VALUE_PREVIEW_LENGTH) {
                return value.substring(0, MAX_VALUE_PREVIEW_LENGTH) + "\u2026";
            }
            return value;
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




