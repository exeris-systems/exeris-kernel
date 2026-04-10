/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.config;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.config.KernelProfile;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Community tier {@link ConfigProvider} — environment/property-based, zero dependencies.
 *
 * <h2>Resolution Order</h2>
 * <ol>
 *   <li>System properties ({@code exeris.*}) — highest priority in this provider.</li>
 *   <li>Environment variables ({@code EXERIS_*}) — second priority.</li>
 *   <li>Compiled defaults from {@link KernelSettings#defaults()} — safe fallback.</li>
 * </ol>
 *
 * <h2>Lazy Initialization (JEP 526 Readiness)</h2>
 * <p>{@link #kernelSettings()} returns an {@link AtomicReference}-backed {@link Supplier}
 * that initializes exactly once — the same semantic as the proposed
 * {@code LazyConstant<KernelSettings>}. The JIT can constant-fold the result after
 * warm-up because the same object reference is always returned.
 *
 * <h2>Zero External Dependencies</h2>
 * <p>L0 mandate: no SLF4J, no Jackson, no external libraries. Uses only JDK APIs.
 * Logging is via {@link System.Logger} (JEP 264).
 *
 * <h2>Hot-Reload</h2>
 * <p>Community tier: {@link #watch(String, String, Consumer)} is a no-op.
 * Enterprise tier overrides this with NIO {@code WatchService} hot-reload.
 *
 * @since 0.5.0
 */
public final class CommunityConfigProvider implements ConfigProvider {

    private static final System.Logger LOG =
            System.getLogger(CommunityConfigProvider.class.getName());

    /**
     * Lazy, thread-safe {@link KernelSettings}.
     *
     * <p>Mirrors the semantics of the proposed {@code LazyConstant<KernelSettings>}
     * (JEP 526): computed once on first access, effectively final after that,
     * eligible for JIT constant-folding after warm-up.
     */
    private final AtomicReference<KernelSettings> cachedSettings = new AtomicReference<>();

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public CommunityConfigProvider() {
        // Zero-magic DI — no injection framework, no factories.
    }

    // =========================================================================
    // ConfigProvider API
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Returns an {@link AtomicReference}-backed supplier that computes
     * {@link KernelSettings} exactly once via CAS.
     */
    @Override
    public Supplier<KernelSettings> kernelSettings() {
        return () -> {
            KernelSettings existing = cachedSettings.get();
            if (existing != null) {
                return existing;
            }
            KernelSettings resolved = resolveSettings();
            if (cachedSettings.compareAndSet(null, resolved)) {
                LOG.log(System.Logger.Level.INFO,
                        "CommunityConfigProvider: KernelSettings resolved — profile={0}",
                        resolved.profile());
                return resolved;
            }
            // Another thread won the CAS — return their value
            return cachedSettings.get();
        };
    }

    @Override
    public Optional<String> getString(String key) {
        return resolveRaw(key);
    }

    @Override
    public Optional<Integer> getInt(String key) {
        return resolveRaw(key).flatMap(v -> {
            try {
                return Optional.of(Integer.parseInt(v.strip()));
            } catch (NumberFormatException ex) {
                LOG.log(System.Logger.Level.WARNING,
                        "CommunityConfigProvider: key ''{0}'' value ''{1}'' is not an integer",
                        key, v);
                return Optional.empty();
            }
        });
    }

    @Override
    public Optional<Long> getLong(String key) {
        return resolveRaw(key).flatMap(v -> {
            try {
                return Optional.of(Long.parseLong(v.strip()));
            } catch (NumberFormatException ex) {
                LOG.log(System.Logger.Level.WARNING,
                        "CommunityConfigProvider: key ''{0}'' value ''{1}'' is not a long",
                        key, v);
                return Optional.empty();
            }
        });
    }

    @Override
    public Optional<Boolean> getBoolean(String key) {
        return resolveRaw(key).map(v -> Boolean.parseBoolean(v.strip()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        if (type == String.class) {
            return (Optional<T>) getString(key);
        }
        if (type == Integer.class) {
            return (Optional<T>) getInt(key);
        }
        if (type == Long.class) {
            return (Optional<T>) getLong(key);
        }
        if (type == Boolean.class) {
            return (Optional<T>) getBoolean(key);
        }
        return Optional.empty();
    }

    /**
     * No-op — Community tier does not support hot-reload.
     * Enterprise overrides with NIO WatchService.
     */
    @Override
    public void watch(String file, String key, Consumer<Object> callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        // Community: hot-reload not supported.
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public String providerName() {
        return "CommunityConfigProvider";
    }

    // =========================================================================
    // Internal
    // =========================================================================

    /**
     * Resolves a raw string value from system properties, then environment variables.
     * Key format: {@code "network.port"} → sysprop {@code exeris.network.port}
     *                                     → env var {@code EXERIS_NETWORK_PORT}.
     */
    private static Optional<String> resolveRaw(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        // 1. System property: exeris.<key>
        String sysProp = System.getProperty("exeris." + key);
        if (sysProp != null && !sysProp.isBlank()) {
            return Optional.of(sysProp);
        }
        // 2. Environment variable: EXERIS_<KEY.UPPER.UNDERSCORE>
        String envKey = "EXERIS_" + key.toUpperCase(Locale.ROOT)
                .replace('.', '_').replace('-', '_');
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) {
            return Optional.of(envVal);
        }
        return Optional.empty();
    }

    /**
     * Builds {@link KernelSettings} from environment/system properties with
     * safe defaults for all unspecified values.
     */
    private static KernelSettings resolveSettings() {
        KernelSettings defaults = KernelSettings.defaults();

        // Profile — see KernelProfile.loadFromEnvironment() for resolution order
        KernelProfile profile = KernelProfile.loadFromEnvironment();

        // Network
        int port       = resolveRaw("network.port")
                .map(CommunityConfigProvider::safeInt)
                .orElse(defaults.network().port());
        int bufferSize = resolveRaw("network.bufferSize")
                .map(CommunityConfigProvider::safeInt)
                .orElse(defaults.network().bufferSize());
        boolean nativeTransportPreferred = resolveRaw("network.nativeTransportPreferred")
                .map(Boolean::parseBoolean)
                .orElse(defaults.network().nativeTransportPreferred());
        int reactors   = resolveRaw("network.reactorCount")
                .map(CommunityConfigProvider::safeInt)
                .orElse(defaults.network().reactorCount());
        boolean quic   = resolveRaw("network.quicEnabled")
                .map(Boolean::parseBoolean)
                .orElse(defaults.network().quicEnabled());

        // Memory
        long memoryMb = resolveRaw("globalMemoryMb")
                .map(CommunityConfigProvider::safeLong)
                .orElse(defaults.globalMemoryMb());

        // Telemetry
        boolean jfr     = resolveRaw("telemetry.jfrEnabled")
                .map(Boolean::parseBoolean)
                .orElse(defaults.telemetry().jfrEnabled());
        boolean metrics = resolveRaw("telemetry.metricsEnabled")
                .map(Boolean::parseBoolean)
                .orElse(defaults.telemetry().metricsEnabled());
        boolean tracing = resolveRaw("telemetry.tracingEnabled")
                .map(Boolean::parseBoolean)
                .orElse(defaults.telemetry().tracingEnabled());
        String nodeId   = resolveRaw("telemetry.nodeId")
                .orElse(System.getProperty("exeris.node.id",
                        defaults.telemetry().nodeId()));
        String region   = resolveRaw("telemetry.region")
                .orElse(defaults.telemetry().region());

        // Persistence — use defaults (no DB URL required at compile time)
        String jdbcUrl  = resolveRaw("persistence.jdbcUrl")
                .orElse(defaults.persistence().jdbcUrl());
        String user     = resolveRaw("persistence.username")
                .orElse(defaults.persistence().username());
        String pass     = resolveRaw("persistence.password")
                .orElse(defaults.persistence().password());
        int maxPool     = resolveRaw("persistence.maxPoolSize")
                .map(CommunityConfigProvider::safeInt)
                .orElse(defaults.persistence().maxPoolSize());
        boolean migrate = resolveRaw("persistence.runMigrations")
                .map(Boolean::parseBoolean)
                .orElse(defaults.persistence().runMigrations());

        return new KernelSettings(
                profile,
                memoryMb,
                new NetworkSettings(port, bufferSize, nativeTransportPreferred, reactors, quic),
                new PersistenceSettings(jdbcUrl, user, pass, maxPool, migrate),
                new TelemetrySettings(jfr, metrics, tracing, nodeId, region)
        );
    }

    private static int safeInt(String value) {
        return Integer.parseInt(value.strip());
    }

    private static long safeLong(String value) {
        return Long.parseLong(value.strip());
    }
}
