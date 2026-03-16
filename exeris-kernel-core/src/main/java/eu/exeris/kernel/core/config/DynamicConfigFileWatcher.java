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

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core: NIO.2 {@link WatchService}-based hot-reload driver for {@code @Dynamic} config fields.
 *
 * <h2>Architecture</h2>
 * <p>This class owns a single background Virtual Thread that blocks on
 * {@link WatchService#take()} — a blocking JVM call that parks the VT on its carrier
 * without pinning it. On file change, it delegates to
 * {@link KernelConfigRegistry#fireReload(String, String, String)} which invokes
 * all registered callbacks in order, each atomically updating its own VarHandle field.
 *
 * <h2>L0 Mandate: No ExecutorService</h2>
 * <p>The background task is started via {@link Thread#ofVirtual().start(Runnable)} —
 * a single, named Virtual Thread. Using {@code ExecutorService} or
 * {@code ScheduledThreadPoolExecutor} is banned by the Performance Contract.
 * A {@link java.util.concurrent.StructuredTaskScope} is not used here because the
 * watcher loop is a long-lived daemon, not a bounded concurrent subtask.
 *
 * <h2>Config file parsing</h2>
 * <p>This class does NOT parse the config file itself. It only reads the raw content and
 * delegates extraction to a provided {@link RawValueExtractor}. The built-in extractor is
 * intentionally NIO-only and avoids legacy java.io reader APIs.
 *
 * <h2>Zero-Downtime guarantee</h2>
 * <p>If the file cannot be read on a change event ({@code EX-CFG-1003}), the previous
 * stable value is preserved in all VarHandle fields — the watcher logs a WARNING and
 * emits a JFR {@code DynamicReloadFailedEvent}. No callback is invoked on failure.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   start()  — registers the watch dir, starts the VT watcher loop
 *   close()  — interrupts the VT, closes the WatchService (called in shutdown())
 * </pre>
 *
 * @since 0.5.0
 * @see KernelConfigRegistry
 */
// CognitiveComplexity: this class is a thin NIO.2 watcher/event pump. The branching
// comes from OS watch events and lifecycle rollback, not business logic; extracting
// helper layers here would add ceremony without reducing kernel risk.
// CloseResource: activeWatchService is closed in close() — lifecycle is explicit.
@SuppressWarnings({
    "PMD.CognitiveComplexity",
    "PMD.CloseResource"
})
public final class DynamicConfigFileWatcher implements AutoCloseable {

    private static final System.Logger LOG =
            System.getLogger(DynamicConfigFileWatcher.class.getName());

    // =========================================================================
    // Fields — ALL at top-of-class per DeclarationOrder
    // =========================================================================

    private final Path                 watchDir;
    private final KernelConfigRegistry registry;
    private final RawValueExtractor    extractor;
    private final AtomicBoolean        running = new AtomicBoolean(false);

    private volatile WatchService activeWatchService;
    private volatile Thread       watcherVt;

    // =========================================================================
    // Functional interface — RawValueExtractor
    // =========================================================================

    /**
     * Functional interface for extracting a raw value from the changed config file.
     *
     * <p>Implementations MUST be pure: no side effects, no I/O beyond reading
     * the provided file path, no caching. Called on the watcher VT for each
     * registered key that matches the changed file.
     *
     * @since 0.5.0
     */
    @FunctionalInterface
    public interface RawValueExtractor {

        /**
         * Extracts the raw string value for {@code key} from the file at {@code filePath}.
         *
         * @param filePath absolute path to the changed config file
         * @param key      dot-path key to extract (e.g., {@code "network.idleTimeoutMillis"})
         * @return raw string value, or {@code null} if the key is absent in the file
         * @throws IOException if the file cannot be read
         */
        String extract(Path filePath, String key) throws IOException;
    }

    // =========================================================================
    // Constructor (Zero-Magic DI — pure constructor)
    // =========================================================================

    /**
     * Constructs the watcher.
     *
     * @param watchDir  directory to watch (typically {@code /etc/exeris/config})
     * @param registry  the config registry that receives reload callbacks
     * @param extractor strategy for extracting a key value from the changed file
     */
    public DynamicConfigFileWatcher(Path watchDir,
                                    KernelConfigRegistry registry,
                                    RawValueExtractor extractor) {
        this.watchDir  = Objects.requireNonNull(watchDir,  "watchDir");
        this.registry  = Objects.requireNonNull(registry,  "registry");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    // =========================================================================
    // Factory
    // =========================================================================

    /**
     * Convenience factory: resolves the config directory from the process configuration
     * and builds a watcher using a {@code java.util.Properties}-based extractor.
     *
     * <p>The config directory is resolved in order:
     * <ol>
     *   <li>{@code exeris.config.dir} system property</li>
     *   <li>{@code EXERIS_CONFIG_DIR} environment variable</li>
     *   <li>Default: {@code /etc/exeris/config}</li>
     * </ol>
     *
     * @param registry config registry to dispatch callbacks to
     * @return a ready-to-start watcher, or {@code null} if the config dir does not exist
     */
    public static DynamicConfigFileWatcher forRegistry(KernelConfigRegistry registry) {
        Path dir = resolveConfigDir();
        if (!Files.isDirectory(dir)) {
            LOG.log(System.Logger.Level.INFO,
                    "DynamicConfigFileWatcher: config dir ''{0}'' does not exist — hot-reload disabled",
                    dir);
            return null;
        }
        return new DynamicConfigFileWatcher(dir, registry, PropertiesExtractor.INSTANCE);
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
    * Starts the background Virtual Thread that watches the config directory.
     *
     * <p>This method is idempotent — calling it twice is a no-op.
     *
     * @throws IOException if the {@link WatchService} cannot be created or the directory
     *                     cannot be registered
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        WatchService watchService = null;
        boolean published = false;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            watchDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
            final WatchService startedWatchService = watchService;

            Thread watcherThread = Thread.ofVirtual()
                    .name("exeris-config-watcher")
                .start(() -> watchLoop(startedWatchService));

            activeWatchService = watchService;
            watcherVt = watcherThread;
            published = true;

            LOG.log(System.Logger.Level.INFO,
                    "DynamicConfigFileWatcher: watching ''{0}''", watchDir);
        } finally {
            if (!published) {
                running.set(false);
                closeUnpublishedWatchService(watchService);
            }
        }
    }

    /**
     * Stops the watcher — interrupts the VT and closes the {@link WatchService}.
     * Called during kernel shutdown. Never throws.
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread currentVt = watcherVt;
        if (currentVt != null) {
            currentVt.interrupt();
        }
        WatchService currentWs = activeWatchService;
        if (currentWs != null) {
            try {
                currentWs.close();
            } catch (IOException ex) {
                LOG.log(System.Logger.Level.WARNING,
                        "DynamicConfigFileWatcher: error closing WatchService — {0}",
                        ex.getMessage());
            }
        }
        LOG.log(System.Logger.Level.INFO, "DynamicConfigFileWatcher: stopped");
    }

    /**
     * Returns {@code true} if the watcher is currently running.
     *
     * @return running state
     */
    public boolean isRunning() {
        return running.get();
    }

    // =========================================================================
    // Watch loop — executed on the dedicated VT
    // =========================================================================

    /**
     * Blocks on {@link WatchService#take()}, dispatching each MODIFY/CREATE event
     * to {@link KernelConfigRegistry#fireReload(String, String, String)}.
     *
     * <p>{@link WatchService#take()} parks the Virtual Thread without pinning the
     * carrier — it is one of the JDK's well-known VT-safe blocking operations.
     */
    private void watchLoop(WatchService watchService) {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException ex) {
                if (!running.get()) {
                    break;
                }
                LOG.log(System.Logger.Level.WARNING,
                        "DynamicConfigFileWatcher: WatchService closed unexpectedly [{0}]",
                        KernelErrorCodes.EX_CFG_1003);
                break;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Path changedPath = watchDir.resolve(((WatchEvent<Path>) event).context());
                String fileName  = changedPath.getFileName().toString();
                dispatchFileChange(changedPath, fileName);
            }

            if (!key.reset()) {
                LOG.log(System.Logger.Level.WARNING,
                        "DynamicConfigFileWatcher: WatchKey invalidated — directory removed?");
                break;
            }
        }
    }

    /**
     * For each unique key registered for {@code fileName}, reads the new value and
     * calls {@link KernelConfigRegistry#fireReload(String, String, String)}.
     *
     * <p>On read failure, logs {@code EX-CFG-1003} and emits a JFR failure event.
     * The previous stable value is preserved — no callback is invoked on failure.
     */
    private void dispatchFileChange(Path filePath, String fileName) {
        registry.registrations().stream()
                .filter(reg -> reg.file() == null || reg.file().equals(fileName))
                .map(KernelConfigRegistry.Registration::key)
                .distinct()
                .forEach(key -> {
                    try {
                        String newValue = extractor.extract(filePath, key);
                        if (newValue != null) {
                            registry.fireReload(fileName, key, newValue);
                        } else {
                            LOG.log(System.Logger.Level.DEBUG,
                                    "DynamicConfigFileWatcher: key ''{0}'' absent in ''{1}'' — skipping",
                                    key, fileName);
                        }
                    } catch (IOException ex) {
                        LOG.log(System.Logger.Level.WARNING,
                                "DynamicConfigFileWatcher: failed to read ''{0}'' — {1} [{2}]",
                                fileName, ex.getMessage(), KernelErrorCodes.EX_CFG_1003);
                        DynamicReloadEvent.emitFailed(fileName, key,
                                "read failed: " + ex.getClass().getSimpleName());
                    }
                });
    }

    // =========================================================================
    // Config dir resolution
    // =========================================================================

    private static Path resolveConfigDir() {
        String sysProp = System.getProperty("exeris.config.dir");
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp);
        }
        String envVar = System.getenv("EXERIS_CONFIG_DIR");
        if (envVar != null && !envVar.isBlank()) {
            return Path.of(envVar);
        }
        return Path.of("/etc/exeris/config");
    }

    private static void closeUnpublishedWatchService(WatchService watchService) {
        if (watchService == null) {
            return;
        }
        try {
            watchService.close();
        } catch (IOException ignored) {
            // Unpublished startup resources are best-effort cleanup only.
        }
    }

    // =========================================================================
    // Built-in extractor: minimal .properties parser on top of NIO
    // =========================================================================

    /**
     * Properties-file extractor — parses key/value pairs from UTF-8 text using NIO.
     *
     * <p>This is the Community-grade default. Enterprise implementations may replace
     * this with a JSON or YAML extractor via the constructor.
     */
    // CommentDefaultAccessModifier: package-private enum with explicit modifier below.
    /* default */ enum PropertiesExtractor implements RawValueExtractor {
        INSTANCE;

        private static final String[] EMPTY_KEY_VALUE = new String[0];

        @Override
        public String extract(Path filePath, String key) throws IOException {
            try (java.util.stream.Stream<String> lines = Files.lines(filePath)) {
                return lines.map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .filter(line -> !line.startsWith("#") && !line.startsWith("!"))
                        .map(PropertiesExtractor::splitKeyValue)
                        .filter(parts -> parts.length == 2)
                        .filter(parts -> key.equals(parts[0]))
                        .map(parts -> parts[1])
                        .findFirst()
                        .orElse(null);
            }
        }

        private static String[] splitKeyValue(String line) {
            int separator = line.indexOf('=');
            if (separator < 0) {
                separator = line.indexOf(':');
            }
            if (separator < 0) {
                return EMPTY_KEY_VALUE;
            }
            String parsedKey = line.substring(0, separator).trim();
            String parsedValue = line.substring(separator + 1).trim();
            return new String[]{parsedKey, parsedValue};
        }
    }
}

