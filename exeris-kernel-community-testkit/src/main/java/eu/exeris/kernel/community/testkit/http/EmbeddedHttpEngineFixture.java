/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit.http;

import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpServerEngine;

/**
 * Deterministic fixture for starting and stopping a kernel-owned HTTP engine in tests.
 *
 * <p>A conforming implementation does not return from {@link #start(HttpHandler)} until the engine
 * it started is either accepting connections or has failed to come up, so a caller reading
 * {@link #boundPort()}, {@link #engine()} or {@link #isRunning()} immediately afterwards is never
 * racing a still-booting engine.
 *
 * <p>The fixture manages a single engine's lifecycle; it does not simulate multiple concurrent
 * engines or a specific transport implementation — which {@link HttpServerEngine} actually answers
 * requests is a property of whatever the fixture bootstraps underneath, not of this interface.
 */
public interface EmbeddedHttpEngineFixture extends AutoCloseable {

    /**
     * Starts a kernel-owned HTTP engine dispatching to {@code handler}, and does not return until
     * it is accepting connections or has failed to start.
     *
     * @param handler receives every inbound exchange the started engine accepts
     * @throws IllegalStateException if the fixture is already started, or if the engine fails or
     *                               times out coming up
     */
    void start(HttpHandler handler);

    /**
     * Returns the engine this fixture started.
     *
     * @return the real {@link HttpServerEngine}; never a double
     * @throws IllegalStateException if the fixture has not been started
     */
    HttpServerEngine engine();

    /**
     * Returns the port the started engine is bound to.
     *
     * @return the bound port
     * @throws IllegalStateException if the fixture has not been started
     */
    int boundPort();

    /**
     * Returns whether the started engine is currently accepting connections.
     *
     * @return {@code true} if the engine is running
     */
    boolean isRunning();

    /**
     * Runs {@code body} on the thread that holds the boot open, inside the kernel scope the engine
     * was started in.
     *
     * <p>For code that reads kernel {@code ScopedValue} slots as the boot bound them. A handler the
     * engine dispatches to runs on a thread the driver starts, which carries only the bindings the
     * driver establishes for the call, so a test observes the boot's own bindings here and not from
     * a handler. Exceptions propagate to the caller, so an assertion failing inside {@code body} fails the test
     * rather than disappearing onto another thread.
     *
     * @param body the work to run inside the kernel scope
     * @throws IllegalStateException if the fixture has not been started, or if {@code body} does not
     *                               complete within the fixture's start timeout
     * @since 0.12
     */
    void runInKernelScope(Runnable body);

    /**
     * Stops the engine this fixture started, releasing the bound port. A no-op if the fixture was
     * never started.
     */
    @Override
    void close();
}
