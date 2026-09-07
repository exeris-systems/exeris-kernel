/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p><b>Allocation:</b> allocates — {@link #createEngine} may open connections, register
 * io_uring buffers, or claim memory partitions in one bootstrap-time reservation; nothing on
 * this interface runs on a hot path.
 * <p><b>Thread confinement:</b> owner thread — the bootstrap thread calls
 * {@link #createEngine} once, and that call may block on syscalls.
 * <p><b>Ownership:</b> the caller owns the returned {@link PersistenceEngine} and closes it via
 * {@link PersistenceEngine#close()} at kernel shutdown.
 *
 * @since 0.5
 * @see PersistenceEngine
 * @see PersistenceConfig
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
     * Selection priority when several providers are on the classpath — the highest value wins.
     *
     * @return priority; default {@code 0}
     * @implSpec A Community binding MUST return {@code 0} and an Enterprise binding {@code 100},
     *           so that an Enterprise jar on the classpath displaces the Community one without
     *           either binding knowing about the other.
     */
    default int priority() {
        return 0;
    }

    /**
     * Creates and initialises a {@link PersistenceEngine} from the given configuration.
     *
     * <p>This is a potentially blocking call — it may open connections, register
     * io_uring buffers, or claim memory partitions.
     *
     * @param config persistence configuration (connection URL, pool sizing, etc.)
     * @return fully initialised engine ready for connection creation; the caller owns it and
     *         closes it via {@link PersistenceEngine#close()} at kernel shutdown
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5001} if the
     *         engine cannot be created — the connection URL carried in {@code rawArgs} has its
     *         {@code user:password@} userinfo stripped before capture
     * @implSpec An implementation obtains its {@link eu.exeris.kernel.spi.memory.MemoryAllocator}
     *           and {@link eu.exeris.kernel.spi.crypto.KernelCryptoProvider} from
     *           {@link eu.exeris.kernel.spi.context.KernelProviders} scoped slots, and MUST NOT
     *           take them as constructor parameters — that is what keeps the SPI boundary clean.
     * @apiNote Call this from the bootstrap path, not from a virtual thread that is expected to
     *          stay non-blocking.
     */
    PersistenceEngine createEngine(PersistenceConfig config);

    /**
     * Optional raw entity encoder binding for payload-oriented persistence paths.
     *
     * @return the provider's raw {@link MemorySegment} encoder, or {@link Optional#empty()} —
     *         the default — when it exposes no such codec
     * @since 0.5
     */
    default Optional<EntityEncoder<MemorySegment>> rawEntityEncoder() {
        return Optional.empty();
    }

    /**
     * Optional raw entity decoder binding for payload-oriented persistence paths.
     *
     * @return the provider's raw {@link MemorySegment} decoder, or {@link Optional#empty()} —
     *         the default — when it exposes no such codec
     * @since 0.5
     */
    default Optional<EntityDecoder<MemorySegment>> rawEntityDecoder() {
        return Optional.empty();
    }
}
