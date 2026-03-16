/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.config;

import eu.exeris.kernel.core.config.jfr.DynamicReloadEvent;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Core: Lock-free, VarHandle-backed registry of {@code @Dynamic} config callbacks.
 *
 * <h2>Architecture</h2>
 * <p>This class is the single coordination point between the
 * {@link DynamicConfigFileWatcher} (writer) and any code that consumes hot-reloaded
 * config values. It intentionally does <em>not</em> use {@code volatile} fields or
 * {@code synchronized} blocks — all state transitions go through
 * {@link VarHandle#setRelease}/{@link VarHandle#getAcquire} acquire-release pairs,
 * which are cheaper than a full {@code volatile} fence on x86_64 TSO hardware.
 *
 * <h2>Registration protocol</h2>
 * <ol>
 *   <li>Subsystems call {@link #register(String, String, Consumer)} once during
 *       {@code initialize()} to declare interest in a (file, key) pair.</li>
 *   <li>{@link DynamicConfigFileWatcher} calls {@link #fireReload(String, String, String)}
 *       on every matching file-change event.</li>
 *   <li>The callback receives the new raw string value and is responsible for
 *       atomically updating its own field via its own {@code VarHandle.setRelease()}.</li>
 * </ol>
 *
 * <h2>Why raw string callbacks?</h2>
 * <p>Type conversion belongs at the call site, not inside the registry.
 * The registry is type-agnostic — it does not know whether a value is a {@code long},
 * an {@code int}, or a complex record. This keeps the hot-path clean and prevents
 * leaky abstractions between the Core config machinery and the SPI types.
 *
 * <h2>Thread safety</h2>
 * <p>{@link #register(String, String, Consumer)} is called only during single-threaded
 * bootstrap (L0 FOUNDATION phase). After bootstrap, the list is effectively read-only
 * for {@link #fireReload(String, String, String)}. {@code AtomicBoolean sealed} prevents
 * late registrations from Virtual Threads.
 *
 * <h2>Valhalla readiness</h2>
 * <p>{@link Registration} is a {@code record} — no identity operations,
 * scalarization-eligible via C2 JIT Escape Analysis.
 *
 * @since 0.5.0
 * @see DynamicConfigFileWatcher
 */
// UnusedPrivateField: 'sealed' is managed exclusively by VarHandle.setRelease/getAcquire —
// PMD cannot see VarHandle writes as "usage" of the field.
@SuppressWarnings("PMD.UnusedPrivateField")
public final class KernelConfigRegistry {

    private static final System.Logger LOG =
            System.getLogger(KernelConfigRegistry.class.getName());

    // =========================================================================
    // State — static before instance, per DeclarationOrder
    // =========================================================================

    private static final VarHandle SEALED_HANDLE;

    static {
        try {
            SEALED_HANDLE = MethodHandles.lookup()
                    .findVarHandle(KernelConfigRegistry.class, "sealed", boolean.class);
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final List<Registration> registrations = new ArrayList<>();

    /**
     * Sealed flag — {@code true} after bootstrap completes.
     * Prevents late registrations from Virtual Threads on the hot path.
     * Written via SEALED_HANDLE setRelease/getAcquire.
     */
    private boolean sealed;

    // =========================================================================
    // Nested data carrier
    // =========================================================================

    /**
     * Valhalla-ready data carrier for a single (file, key, callback) registration.
     *
     * @param file     config file name (relative to config dir), or {@code null} = any file
     * @param key      dot-path key (e.g., {@code "network.idleTimeoutMillis"})
     * @param callback invoked with the new raw string value; must be non-null
     */
    public record Registration(String file, String key, Consumer<String> callback) {

        public Registration {
            Objects.requireNonNull(key,      "key must not be null");
            Objects.requireNonNull(callback, "callback must not be null");
        }

        /**
         * Returns {@code true} if this registration matches the given (file, key) pair.
         *
         * @param changedFile the file that changed
         * @param changedKey  the key inside that file
         * @return {@code true} if this registration should fire
         */
        public boolean matches(String changedFile, String changedKey) {
            boolean fileMatch = file == null || file.equals(changedFile);
            return fileMatch && key.equals(changedKey);
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Registers a callback for the given (file, key) pair.
     *
     * <p>Must only be called during single-threaded bootstrap (before {@link #seal()}).
     * Late registrations after sealing are silently ignored (no-op) with a WARN log.
     *
     * @param file     config file name relative to config dir; {@code null} = any file
     * @param key      dot-path key, e.g., {@code "network.idleTimeoutMillis"}
     * @param callback invoked with the new raw string value on file change
     */
    public void register(String file, String key, Consumer<String> callback) {
        if ((boolean) SEALED_HANDLE.getAcquire(this)) {
            LOG.log(System.Logger.Level.WARNING,
                    "KernelConfigRegistry: register() called after seal() — ignored. key=''{0}''",
                    key);
            return;
        }
        registrations.add(new Registration(file, key, callback));
        LOG.log(System.Logger.Level.DEBUG,
                "KernelConfigRegistry: registered key=''{0}'' file=''{1}''", key, file);
    }

    /**
     * Seals the registry — no further registrations are accepted.
     *
     * <p>Called by the bootstrap orchestrator after all FOUNDATION subsystems
     * have completed {@code initialize()}. The underlying list becomes effectively
     * read-only; no lock is needed for {@link #fireReload(String, String, String)}.
     */
    public void seal() {
        SEALED_HANDLE.setRelease(this, true);
        LOG.log(System.Logger.Level.DEBUG,
                "KernelConfigRegistry: sealed — {0} registration(s)", registrations.size());
    }

    /**
     * Fires all callbacks whose (file, key) pair matches the given arguments.
     *
     * <p>Called by {@link DynamicConfigFileWatcher} on a Virtual Thread — never
     * on a carrier platform thread. Callbacks must be fast (single VarHandle write)
     * and must not block.
     *
     * <p>Each matching callback is surrounded by JFR telemetry:
     * success → {@code DynamicFieldReloadedEvent}, failure → {@code DynamicReloadFailedEvent}
     * ({@code EX-CFG-1003}).
     *
     * @param file     the config file that changed
     * @param key      the dot-path key that changed
     * @param newValue the new raw string value (already parsed by the watcher)
     */
    public void fireReload(String file, String key, String newValue) {
        for (Registration reg : registrations) {
            if (reg.matches(file, key)) {
                long startNanos = System.nanoTime();
                try {
                    reg.callback().accept(newValue);
                    DynamicReloadEvent.emitReloaded(file, key, startNanos);
                } catch (RuntimeException ex) { // NOPMD — callback failures must never crash the watcher VT
                    LOG.log(System.Logger.Level.WARNING,
                            "KernelConfigRegistry: reload callback failed [{0}] — {1} [{2}]",
                            key, ex.getMessage(), KernelErrorCodes.EX_CFG_1003);
                    DynamicReloadEvent.emitFailed(file, key,
                            "callback threw: " + ex.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Returns an unmodifiable view of all registered callbacks.
     * Intended for diagnostics and testing only — not for hot-path use.
     *
     * @return immutable list of registrations
     */
    public List<Registration> registrations() {
        return Collections.unmodifiableList(registrations);
    }

    /**
     * Returns {@code true} if the registry has been sealed (bootstrap complete).
     *
     * @return sealed flag
     */
    public boolean isSealed() {
        return (boolean) SEALED_HANDLE.getAcquire(this);
    }
}





