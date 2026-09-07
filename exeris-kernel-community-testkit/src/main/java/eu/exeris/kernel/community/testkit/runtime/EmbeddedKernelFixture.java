/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit.runtime;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

/**
 * Real kernel engines for events and flow, booted in-process, for consumers outside this repository.
 *
 * <h2>Why this exists</h2>
 * <p>v0.11 gave the testkit a real persistence engine and nothing else, so a host runtime binding the
 * events or flow SPI still had to write doubles — and a double encodes how its author <em>read</em> the
 * contract, not how the runtime <em>behaves</em>. Ordering, lifecycle and threading are exactly the
 * properties a double cannot get wrong loudly. Saga state is the sharpest case: step ordering,
 * compensation, and the optimistic-lock conflict on a stale write are behaviour no stub reproduces,
 * and they are backed by a real table.
 *
 * <h2>One kernel, several subsystems — not one kernel each</h2>
 * <p>This is a single fixture over a chosen subsystem set rather than one fixture per subsystem, and
 * the reason is mechanical: each fixture holds an entire {@code KernelBootstrap} open on its own
 * thread, and {@code FixtureBootLock} serialises boots because the configuration travels through
 * JVM-global system properties. Three fixtures for one saga test would be three kernels, booted in
 * sequence, sharing nothing. A saga that emits an event needs both engines out of the <em>same</em>
 * runtime, which is also how it works in production.
 *
 * <p>Dependency closure is the orchestrator's job, so a caller names only what it wants:
 * {@code flow} pulls {@code persistence}, and {@code events} pulls {@code persistence} and
 * {@code memory}. {@link #persistenceEngine()} is therefore always available — the engine those
 * subsystems store into is the one you can assert against.
 *
 * <h2>Lifecycle</h2>
 * <pre>{@code
 * try (EmbeddedKernelFixture fixture = EmbeddedKernelFixtures.eventsAndFlowOnH2()) {
 *     fixture.start();
 *     FlowEngine flow = fixture.flowEngine();
 *     // … drive a saga, then assert on fixture.persistenceEngine()
 * }
 * }</pre>
 *
 * <h2>Which thread</h2>
 * <p>An engine returned here is safe to use from the test thread. Code that resolves kernel
 * {@code ScopedValue} slots — the usual shape of a host runtime's own wiring — must go through
 * {@link #runInKernelScope(Runnable)}, because a {@code ScopedValue} binding cannot outlive the frame
 * that opened it and therefore cannot be handed to another thread.
 *
 * @since 0.12
 */
public interface EmbeddedKernelFixture extends AutoCloseable {

    /**
     * Boots the kernel with the selected subsystems and blocks until their engines are bound.
     *
     * @throws IllegalStateException if already started, or if boot fails or times out
     */
    void start();

    /**
     * Returns the booted event engine.
     *
     * @return the real {@link EventEngine}; never a double
     * @throws IllegalStateException if the fixture has not been started, or if {@code events} was not
     *                               among the selected subsystems
     */
    EventEngine eventEngine();

    /**
     * Returns the booted flow engine.
     *
     * @return the real {@link FlowEngine}; never a double
     * @throws IllegalStateException if the fixture has not been started, or if {@code flow} was not
     *                               among the selected subsystems
     */
    FlowEngine flowEngine();

    /**
     * Returns the persistence engine the selected subsystems store into.
     *
     * <p>Always available: both {@code events} and {@code flow} declare {@code dependsOn("persistence")},
     * so it is present whichever of them was selected. This is what makes an assertion on saga state or
     * on the outbox possible — the test reads the same rows the engine wrote.
     *
     * @return the real {@link PersistenceEngine}
     * @throws IllegalStateException if the fixture has not been started
     */
    PersistenceEngine persistenceEngine();

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
     * <p>Exceptions propagate to the caller, so an assertion failing inside {@code body} fails the test
     * rather than disappearing onto another thread.
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
