/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.codec.EntityDecoder;
import eu.exeris.kernel.spi.persistence.codec.EntityEncoder;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * SPI: Top-level entry point for persistence subsystem discovery.
 *
 * <h2>ServiceLoader Contract</h2>
 * <p>Discovered via {@link java.util.ServiceLoader}. Each tier (Community/Enterprise)
 * provides exactly one binding. The {@code exeris-kernel-core} bootstrapper loads the
 * highest-priority provider and calls {@link #createEngine(PersistenceConfig)} once
 * during startup.
 *
 * <h2>The Wall (Open-Core)</h2>
 * <ul>
 *   <li><b>Community binding</b> (free): JDBC + HikariCP, temporary Arenas, standard TCP.</li>
 *   <li><b>Enterprise binding</b> (secret sauce): PG Native wire protocol + io_uring
 *       transport + zero-copy {@link QueryResult} over {@code LoanedBuffer}.
 *       This binding lives in {@code exeris-kernel-enterprise} and must <em>never</em>
 *       be referenced from this SPI.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  ServiceLoader.load(PersistenceProvider.class)
 *      → select max priority()
 *      → createEngine(config)
 *      → bind to ScopedValue
 *      → kernel runs
 *      → engine.close()
 * </pre>
 *
 * @see PersistenceEngine
 * @see PersistenceConfig
 * @since 0.5.0
 */
public interface PersistenceProvider {

    /**
     * Unique identifier for this persistence backend (e.g., {@code "postgres-community"},
     * {@code "postgres-enterprise"}).
     *
     * <p>Used in configuration routing and diagnostic JFR events.
     *
     * @return stable backend identifier
     */
    String providerId();

    /**
     * Display name used in bootstrap JFR events and diagnostics
     * (e.g., {@code "ExerisCommunity/JDBC+HikariCP"}).
     *
     * @return human-readable provider name
     */
    String providerName();

    /**
     * Selection priority when multiple providers are on the classpath.
     *
     * <p>Higher value wins. Community implementations MUST return {@code 0};
     * Enterprise implementations MUST return {@code 100}.
     *
     * @return priority; default {@code 0}
     */
    default int priority() {
        return 0;
    }

    /**
     * Creates and initialises a {@link PersistenceEngine} from the given configuration.
     *
     * <p>This is a potentially blocking call — it may open connections, register
     * io_uring buffers, or claim memory partitions. It MUST NOT be called on a
     * virtual thread that is expected to be non-blocking.
     *
     * <h3>SPI Dependency Injection</h3>
     * <p>Implementations obtain their {@link eu.exeris.kernel.spi.memory.MemoryAllocator}
     * and {@link eu.exeris.kernel.spi.crypto.KernelCryptoProvider} from
     * {@link eu.exeris.kernel.spi.context.KernelProviders} scoped slots — they do NOT
     * receive them as constructor parameters. This ensures clean SPI isolation.
     *
     * @param config persistence configuration (connection URL, pool sizing, etc.)
     * @return fully initialised engine ready for connection creation
     * @throws PersistenceProviderException if the engine cannot be created
     */
    PersistenceEngine createEngine(PersistenceConfig config);

    /**
     * Returns the {@link DatabaseDialect} supported by this provider.
     *
     * <p>Used by {@code KernelBootstrap} to emit dialect-capability warnings
     * (e.g., "COPY not supported") and to gate optional features at runtime.
     *
     * <p>Default implementation performs autodetection from
     * {@link PersistenceConfig#connectionUrl()} — override for explicit declarations.
     *
     * @param config the same config that will be passed to {@link #createEngine(PersistenceConfig)}
     * @return the dialect descriptor; never {@code null}
     * @since 0.5.0
     */
    default DatabaseDialect dialect(PersistenceConfig config) {
        return DatabaseDialect.fromUrl(config.connectionUrl());
    }

    /**
     * Optional raw entity encoder binding for payload-oriented persistence paths.
     *
     * <p>Default is empty to preserve compatibility for providers that do not
     * expose a raw {@link MemorySegment} codec.
     *
     * @return raw encoder binding, if supported by this provider
     * @since 0.5.0
     */
    default Optional<EntityEncoder<MemorySegment>> rawEntityEncoder() {
        return Optional.empty();
    }

    /**
     * Optional raw entity decoder binding for payload-oriented persistence paths.
     *
     * <p>Default is empty to preserve compatibility for providers that do not
     * expose a raw {@link MemorySegment} codec.
     *
     * @return raw decoder binding, if supported by this provider
     * @since 0.5.0
     */
    default Optional<EntityDecoder<MemorySegment>> rawEntityDecoder() {
        return Optional.empty();
    }
}
