/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceEngine;

/**
 * A real kernel {@link PersistenceEngine}, booted in-process, for consumers outside this repository.
 *
 * <h2>Why this exists</h2>
 * <p>Until 0.11 the testkit shipped HTTP fixtures and nothing else, so a host runtime binding the
 * persistence SPI had no way to test against the engine it actually runs on. What it could do was write
 * a double — and a double encodes how its author <em>read</em> the contract, not how the runtime
 * <em>behaves</em>. Ordering, lifecycle, and threading are precisely the properties a double cannot get
 * wrong loudly, which is why defects in them keep being found by applications rather than by tests.
 *
 * <p>This fixture is the real engine: real provider discovery through {@code ServiceLoader}, real
 * bootstrap, real migrations, real pool. Nothing here is a stand-in.
 *
 * <h2>Lifecycle</h2>
 * <pre>{@code
 * try (EmbeddedPersistenceEngineFixture fixture = EmbeddedPersistenceEngineFixtures.inMemoryH2()) {
 *     fixture.start();
 *     try (PersistenceConnection connection = fixture.engine().openConnection()) {
 *         connection.beginTransaction();
 *         // …
 *         connection.rollback();
 *     }
 * }
 * }</pre>
 *
 * <h2>Which thread</h2>
 * <p>{@link #engine()} is safe to use directly from the test thread: the Community engine reads no
 * {@code ScopedValue}, so it does not require the kernel scope to be bound around the caller. Code
 * that <em>does</em> read kernel slots — anything resolving {@code KernelProviders.CURRENT_CONFIG} or
 * a provider slot, which is the usual shape of a host runtime's transaction manager — must run through
 * {@link #runInKernelScope(Runnable)} instead, or it will fail to resolve them.
 *
 * @since 0.11
 */
public interface EmbeddedPersistenceEngineFixture extends AutoCloseable {

    /**
     * Boots the kernel with the persistence subsystem and blocks until the engine is bound.
     *
     * @throws IllegalStateException if already started, or if boot fails or times out
     */
    void start();

    /**
     * Returns the booted engine.
     *
     * @return the real {@link PersistenceEngine}; never a double
     * @throws IllegalStateException if the fixture has not been started
     */
    PersistenceEngine engine();

    /**
     * Returns the JDBC URL this fixture booted against — unique per instance, so parallel tests do not
     * share a database.
     *
     * @return the JDBC URL
     * @throws IllegalStateException if the fixture has not been started
     */
    String jdbcUrl();

    /**
     * Runs {@code body} on the kernel thread, inside the bound kernel scope.
     *
     * <p>For consumer code that resolves kernel {@code ScopedValue} slots. Exceptions propagate to the
     * caller, so an assertion failing inside {@code body} fails the test rather than disappearing onto
     * another thread.
     *
     * @param body the work to run inside the kernel scope
     * @throws IllegalStateException if the fixture has not been started
     */
    void runInKernelScope(Runnable body);

    /**
     * Returns whether the fixture is currently booted.
     *
     * @return {@code true} between a successful {@link #start()} and {@link #close()}
     */
    boolean isRunning();

    /** Shuts the kernel down and releases the database. Idempotent. */
    @Override
    void close();
}
