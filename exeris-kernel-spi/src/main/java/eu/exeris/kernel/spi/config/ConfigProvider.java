/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * The single source of configuration for one kernel instance: exactly one implementation is
 * chosen at L0 bootstrap, before any other subsystem exists, and answers every settings read
 * and every key lookup for the life of the process.
 *
 * <h2>The Wall</h2>
 * <p>This interface is the single SPI entry point for configuration. It is loaded via
 * {@code ServiceLoader} by the {@code KernelBootstrap} in {@code exeris-kernel-core}.
 * The highest-{@link #priority()} implementation found on the classpath wins.
 * This SPI does not hard-code any implementation; implementations are discovered via {@code ServiceLoader}.
 *
 * <h2>Design Philosophy</h2>
 * <p>Configuration is not a {@code Map<String, Object>} or a mutable POJO.
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
 * {@snippet lang="java" :
 * // Today (JDK 26, no preview required):
 * Supplier<KernelSettings> kernelSettings();
 *
 * // JDK 27+ with LazyConstant:
 * LazyConstant<KernelSettings> kernelSettings();
 * }
 *
 * <h2>ScopedValue Propagation (JEP 506)</h2>
 * <p>The resolved {@code ConfigProvider} is bound to
 * {@link eu.exeris.kernel.spi.context.KernelProviders#CURRENT_CONFIG} by the
 * {@code KernelBootstrap} during L0 bootstrap and flows automatically to every
 * virtual thread spawned within the kernel scope — no constructor injection needed.
 *
 * <h2>ServiceLoader Registration</h2>
 * {@snippet :
 * # META-INF/services/eu.exeris.kernel.spi.config.ConfigProvider
 * eu.exeris.kernel.community.config.CommunityConfigProvider    # priority=0
 * eu.exeris.kernel.enterprise.config.EnterpriseConfigProvider  # priority=100
 * }
 *
 * <p><b>Allocation:</b> allocates (one {@link Optional} per raw lookup, plus a boxed
 * {@code Integer} / {@code Long} / {@code Boolean} for the numeric and boolean accessors);
 * {@link #kernelSettings()} resolves once and every later call hands back the same instance.
 * <p><b>Thread confinement:</b> virtual-thread-safe — a single instance is bound to
 * {@link eu.exeris.kernel.spi.context.KernelProviders#CURRENT_CONFIG} for the whole kernel
 * scope and is read concurrently by every virtual thread inside it.
 * <p><b>Ownership:</b> the {@code KernelBootstrap} owns the instance — it discovers it, binds
 * it, and tears the binding down when the kernel scope ends; {@link KernelSettings} and its
 * sections are immutable carriers that a caller may hold indefinitely, with no release step.
 *
 * @implSpec Implementations are discovered through {@code ServiceLoader} and must therefore
 *           declare a public no-argument constructor. {@link #priority()} must return a
 *           non-negative value; when two implementations report the same priority, which of
 *           them wins is unspecified — it follows classpath order.
 * @apiNote The raw accessors ({@link #getString(String)}, {@link #getInt(String)},
 *          {@link #getLong(String)}, {@link #getBoolean(String)} and
 *          {@link #get(String, Class)}) report an unbound key as {@link Optional#empty()};
 *          a caller that requires the key raises
 *          {@link ConfigProviderException#missingProperty(String, String)}
 *          ({@code EX-CFG-1001}) on the empty result itself, which is what turns a missing
 *          required key into a deterministic boot failure instead of a
 *          {@code NullPointerException} deep inside a subsystem initializer. Whether a value
 *          that is present but malformed is reported as empty or raised as
 *          {@link ConfigProviderException#typeMismatch(String, String, String)}
 *          ({@code EX-CFG-1002}) is left to the implementation.
 * @since 0.5
 */
public interface ConfigProvider {

    // =========================================================================
    // Typed settings — Value Record API
    // =========================================================================

    /**
     * Returns the fully resolved kernel settings, computed lazily and exactly once.
     *
     * @return a supplier that resolves every configuration source on its first invocation and
     *         hands back that same instance on every later one; neither the supplier nor the
     *         {@link KernelSettings} it supplies is ever {@code null}
     * @implSpec Implementations must guarantee:
     *           <ul>
     *             <li>Thread-safe single initialization via {@code AtomicReference} CAS or
     *                 equivalent (mirrors the {@code LazyConstant} semantic from JEP 526).</li>
     *             <li>The returned object is effectively immutable after first access.</li>
     *             <li>Repeated calls always return the same instance — eligible for JIT
     *                 constant-folding after warm-up.</li>
     *           </ul>
     * @apiNote The first invocation is the only one that can be slow, and the
     *          {@code KernelBootstrap} makes it during L0 boot so that the resolution cost is
     *          captured by the {@code ConfigSettingsResolved} JFR event rather than by the
     *          first request path that happens to touch configuration.
     */
    Supplier<KernelSettings> kernelSettings();

    // =========================================================================
    // Raw key-value access
    // =========================================================================

    /**
     * Returns the raw value bound to {@code key} by the highest-precedence source that binds it.
     *
     * @param key dot-path key in SPI form (for example {@code "network.port"}); the
     *            implementation maps it onto the syntax of its own sources
     * @return the bound value, or {@link Optional#empty()} when no source binds the key
     */
    Optional<String> getString(String key);

    /**
     * Coerces the value bound to {@code key} to an {@code int}.
     *
     * @param key dot-path key in SPI form (for example {@code "network.port"})
     * @return the coerced value, or {@link Optional#empty()} when no source binds the key
     */
    Optional<Integer> getInt(String key);

    /**
     * Coerces the value bound to {@code key} to a {@code long}.
     *
     * @param key dot-path key in SPI form (for example {@code "globalMemoryMb"})
     * @return the coerced value, or {@link Optional#empty()} when no source binds the key
     */
    Optional<Long> getLong(String key);

    /**
     * Coerces the value bound to {@code key} to a {@code boolean}.
     *
     * @param key dot-path key in SPI form (for example {@code "telemetry.jfrEnabled"})
     * @return the coerced value, or {@link Optional#empty()} when no source binds the key
     */
    Optional<Boolean> getBoolean(String key);

    /**
     * Converts the value bound to {@code key} into the requested target type.
     *
     * @param <T>  the target type
     * @param key  dot-path key in SPI form (for example {@code "persistence.jdbcUrl"})
     * @param type the type the bound value is to be converted to
     * @return the converted value, or {@link Optional#empty()} when no source binds the key or
     *         the implementation offers no conversion to {@code type}
     * @apiNote L0 carries no reflective document parser, so the set of supported target types is
     *          small and provider-specific; prefer the four raw accessors above, and read
     *          compound values from {@link KernelSettings} rather than from this method.
     * @implNote The Community provider converts to {@code String}, {@code Integer},
     *           {@code Long} and {@code Boolean}, and reports every other target type as empty.
     */
    <T> Optional<T> get(String key, Class<T> type);

    // =========================================================================
    // Convenience defaults
    // =========================================================================

    /**
     * Returns the raw value bound to {@code key}, substituting {@code defaultValue} when the key
     * is unbound.
     *
     * @param key          dot-path key in SPI form (for example {@code "telemetry.nodeId"})
     * @param defaultValue value to substitute when the key is unbound; may be {@code null}
     * @return the bound value, or {@code defaultValue} when no source binds the key
     * @implSpec The default implementation delegates to {@link #getString(String)} and applies
     *           {@link Optional#orElse(Object)}; an override changes nothing about which value
     *           wins, only how it is obtained.
     */
    default String getStringOrDefault(String key, String defaultValue) {
        return getString(key).orElse(defaultValue);
    }

    /**
     * Returns the value bound to {@code key} as an {@code int}, substituting {@code defaultValue}
     * when the key is unbound.
     *
     * @param key          dot-path key in SPI form (for example {@code "network.port"})
     * @param defaultValue value to substitute when the key is unbound
     * @return the coerced value, or {@code defaultValue} when no source binds the key
     * @implSpec The default implementation delegates to {@link #getInt(String)} and applies
     *           {@link Optional#orElse(Object)}.
     */
    default int getIntOrDefault(String key, int defaultValue) {
        return getInt(key).orElse(defaultValue);
    }

    /**
     * Returns the value bound to {@code key} as a {@code long}, substituting
     * {@code defaultValue} when the key is unbound.
     *
     * @param key          dot-path key in SPI form (for example {@code "globalMemoryMb"})
     * @param defaultValue value to substitute when the key is unbound
     * @return the coerced value, or {@code defaultValue} when no source binds the key
     * @implSpec The default implementation delegates to {@link #getLong(String)} and applies
     *           {@link Optional#orElse(Object)}.
     */
    default long getLongOrDefault(String key, long defaultValue) {
        return getLong(key).orElse(defaultValue);
    }

    /**
     * Returns the value bound to {@code key} as a {@code boolean}, substituting
     * {@code defaultValue} when the key is unbound.
     *
     * @param key          dot-path key in SPI form (for example {@code "telemetry.jfrEnabled"})
     * @param defaultValue value to substitute when the key is unbound
     * @return the coerced value, or {@code defaultValue} when no source binds the key
     * @implSpec The default implementation delegates to {@link #getBoolean(String)} and applies
     *           {@link Optional#orElse(Object)}.
     */
    default boolean getBooleanOrDefault(String key, boolean defaultValue) {
        return getBoolean(key).orElse(defaultValue);
    }
    // =========================================================================
    // Hot-reload (@Dynamic support)
    // =========================================================================

    /**
     * Registers a callback invoked when the value at {@code key} changes.
     *
     * @param file     observed filename (relative to config directory); {@code null} = any
     * @param key      dot-path key (e.g., {@code "network.port"})
     * @param callback receives an {@code Object} whose runtime type is {@code String}
     *                 containing the new raw value read from the config file; invoked on a
     *                 virtual thread; must not be {@code null}
     * @throws NullPointerException if {@code callback} is {@code null} — rejected at
     *                              registration time even by a tier that runs no watcher, so
     *                              that the mistake surfaces where it is made
     * @implSpec Registration must not throw for any other combination of arguments, whether or
     *           not the implementation supports hot-reload. An implementation that does support
     *           it delivers the update on a virtual thread — never on a carrier or platform
     *           thread, and never through a {@code ScheduledExecutorService} — and never
     *           delivers a value for a key sealed by {@link #guardImmutable(String, String)}.
     *           An implementation that does not support it registers nothing and never invokes
     *           {@code callback}.
     * @implNote Community providers run no watcher, so registration is a no-op. Enterprise
     *           providers deliver updates from a virtual-thread watcher backed by the
     *           {@code NIO WatchService}.
     * @see Dynamic
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
     * @param file observed filename (relative to config directory); {@code null} = any
     * @param key  dot-path key to seal (e.g., {@code "security.jwks.uri"})
     * @implSpec The default implementation does nothing, which is the correct behaviour for a
     *           provider that runs no hot-reload watcher: with no reload path, the key is
     *           already sealed. An implementation that does run one must record the guard and
     *           keep the boot-time value authoritative for the lifetime of the process,
     *           refusing every later on-disk change to the guarded pair and auditing it under
     *           {@link KernelErrorCodes#EX_CFG_1004} with the file and key name only — never
     *           the value.
     * @implNote The kernel's registry-backed provider overrides this to record the guard so the
     *           Core {@code WatchService} driver can enforce the refusal.
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
        return 0;
    }

    /**
     * Human-readable provider name for logging and JFR telemetry.
     *
     * @return a non-null identifier for this implementation; it is what the
     *         {@code ConfigSettingsResolved} JFR event records as the elected provider and what
     *         {@link ConfigProviderException#missingProperty(String, String)} carries in
     *         {@code rawArgs}, so it is the only clue an operator has as to which provider
     *         answered a lookup
     * @implSpec The default implementation returns the simple class name. An override should
     *           stay stable across releases — a name that changes breaks the telemetry a
     *           reader correlates on.
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
     * One resolved snapshot of every configuration section, handed out by
     * {@link ConfigProvider#kernelSettings()} and immutable for the life of the kernel.
     *
     * @param profile        active kernel profile ({@link KernelProfile#DEV} /
     *                       {@link KernelProfile#TEST} / {@link KernelProfile#PROD})
     * @param globalMemoryMb total off-heap budget in MB (set by {@code ExerisSmartLauncher})
     * @param network        network / transport settings
     * @param persistence    persistence / database settings
     * @param telemetry      telemetry and observability settings
     * @implNote Four references and one {@code long} per instance, ≈ 40 bytes on the heap; as a
     *           {@code value record} (JEP 401) the same state inlines into its holder and costs
     *           no heap allocation at all. Because {@code profile} is a {@link KernelProfile}
     *           enum rather than a string, a profile test is a JIT-constant-folded identity
     *           check against a JVM singleton: enum references are effectively integer-width
     *           constants that the C2 compiler scalarizes cleanly.
     */
    @ValueCandidate
    record KernelSettings(
            KernelProfile profile,
            long globalMemoryMb,
            NetworkSettings network,
            PersistenceSettings persistence,
            TelemetrySettings telemetry
    ) {
        /**
         * Creates the compiled fallback settings a provider starts from before any source is
         * consulted: the {@link KernelProfile#PROD} profile — the safest of the three — a
         * 512 MB off-heap budget, and the defaults of every section.
         *
         * @return a fully populated settings snapshot that binds no external source
         */
        public static KernelSettings defaults() {
            return new KernelSettings(
                    KernelProfile.PROD,
                    512L,
                    NetworkSettings.defaults(),
                    PersistenceSettings.defaults(),
                    TelemetrySettings.defaults()
            );
        }

        /**
         * Reports whether this snapshot carries the development profile, under which optional
         * subsystems may degrade, in-memory backends are permitted and full exception detail
         * is disclosed.
         *
         * @return {@code true} when running in the {@link KernelProfile#DEV} profile
         */
        public boolean isDev() {
            return profile.isDev();
        }

        /**
         * Reports whether this snapshot carries the production profile, under which no
         * subsystem may degrade, in-memory backends are refused and exception detail is
         * withheld from callers.
         *
         * @return {@code true} when running in the {@link KernelProfile#PROD} profile
         */
        public boolean isProd() {
            return profile.isProd();
        }

        /**
         * Reports whether this snapshot carries the test profile, under which degradation and
         * in-memory backends are permitted but exception detail is withheld from callers.
         *
         * @return {@code true} when running in the {@link KernelProfile#TEST} profile
         */
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
        /**
         * Creates the compiled fallback section: port 8443, 64 KiB per-connection buffers, a
         * native transport preferred, reactor count auto-detected from the CPU topology and
         * QUIC enabled.
         *
         * @return the compiled network defaults, which a provider substitutes field by field
         *         for whatever its own sources bind
         */
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
        /**
         * Creates the compiled fallback section: a local PostgreSQL instance, an empty
         * password and no schema migration on startup.
         *
         * @return the compiled persistence defaults — a development preset, not a production
         *         configuration; the empty password is the absence of a credential, never a
         *         credential worth carrying
         */
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
        /**
         * Creates the compiled fallback section: JFR recording and the Prometheus endpoint on,
         * the dormant tracing knob off, and placeholder {@code "local"} / {@code "default"}
         * identifiers.
         *
         * @return the compiled telemetry defaults; a deployment that runs more than one kernel
         *         instance sets {@code telemetry.nodeId} itself, since the placeholder cannot
         *         tell two instances apart
         */
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
     * <p>Extends {@link ExerisKernelException}, so it carries the structured {@code errorCode}
     * and the {@code rawArgs} binary telemetry payload. It reports one of three codes:
     * <ul>
     *   <li>{@link KernelErrorCodes#EX_CFG_1001} — required property missing</li>
     *   <li>{@link KernelErrorCodes#EX_CFG_1002} — type mismatch</li>
     *   <li>{@link KernelErrorCodes#EX_CFG_1003} — hot-reload file read error</li>
     * </ul>
     *
     * @apiNote Build one through a typed factory
     *          ({@link #missingProperty(String, String)},
     *          {@link #typeMismatch(String, String, String)},
     *          {@link #hotReloadFailure(String, String, Throwable)}) rather than through the
     *          general constructor: the factory is what pairs the right
     *          {@link KernelErrorCodes} code with the {@code rawArgs} layout a Glass-Box
     *          decoder reads positionally.
     */
    class ConfigProviderException extends ExerisKernelException {

        /** Maximum number of characters retained from a non-sensitive config value in telemetry. */
        private static final int MAX_VALUE_PREVIEW_LENGTH = 32;

        /**
         * General-purpose constructor for config failures not covered by the typed factory methods.
         *
         * @param errorCode a non-null {@code EX-CFG-*} code from {@link KernelErrorCodes}
         * @param message   human-readable message; may include runtime details for console/log
         *                  output during L0 boot failure — for stable machine-readable telemetry,
         *                  rely on {@code errorCode} and {@code rawArgs} instead
         * @param cause     upstream throwable; may be {@code null}
         * @param rawArgs   raw domain arguments for binary telemetry
         * @apiNote Prefer a typed factory ({@link #missingProperty(String, String)},
         *          {@link #typeMismatch(String, String, String)},
         *          {@link #hotReloadFailure(String, String, Throwable)}). This constructor fixes
         *          neither the code nor the {@code rawArgs} layout, so what it produces is not
         *          something a Glass-Box decoder can read positionally.
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




