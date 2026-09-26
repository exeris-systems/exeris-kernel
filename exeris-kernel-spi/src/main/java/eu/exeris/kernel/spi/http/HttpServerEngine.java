/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

/**
 * SPI: HTTP server engine lifecycle — receives inbound connections, parses requests,
 * and dispatches to the registered {@link HttpHandler}.
 *
 * <h2>The Wall</h2>
 * <p>This interface does not expose transport internals or provider-specific mechanics.
 * Business logic operates exclusively via {@link HttpExchange}.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   HttpProvider.createServerEngine(config)  → engine (CREATED)
 *   engine.setHandler(handler)               → handler registered
 *   engine.start()                           → engine (RUNNING, port bound)
 *   ... accept exchanges, dispatch handler ...
 *   engine.stop()                            → engine (STOPPING, draining in-flight)
 *   engine.close()                           → engine (CLOSED, resources released)
 * </pre>
 *
 * <p><b>Ownership:</b> the engine owns everything it binds — the listening port, its connections
 * and their off-heap buffers — and releases them on {@link #close()}, which stops a running engine
 * first and is idempotent
 *
 * @implSpec An implementation emits a {@code jdk.jfr.Event} annotated
 *           {@code @StackTrace(false)} on {@link #start()} and {@link #stop()}: lifecycle
 *           telemetry is part of the contract, and a stack trace on an event that fires twice per
 *           process is cost with no reader.
 * @since 0.5
 */
public interface HttpServerEngine extends AutoCloseable {

    /**
     * Registers the {@link HttpHandler} that will receive every inbound
     * {@link HttpExchange}.
     *
     * @param handler application request handler; must not be {@code null}
     * @throws IllegalStateException if the engine has already been started — a running engine
     *                               cannot swap handlers, because a request already in flight
     *                               would then be answered by neither of them
     * @implSpec Dispatch each inbound request to this handler on a virtual thread of its own, so
     *           that a handler which blocks holds up nothing but its own request.
     * @apiNote Call it before {@link #start()}; an engine with no handler has nothing to serve
     *          accepted connections with.
     */
    void setHandler(HttpHandler handler);

    /**
     * Starts the engine — binds the port, initialises acceptor loops.
     *
     * <p>After this call the engine is ready to accept inbound connections.
     *
     * @throws IllegalStateException if the engine has already been started or closed
     * @apiNote Potentially blocking (socket bind, TLS context setup); do not call it from a virtual
     *          thread that is expected never to park.
     */
    void start();

    /**
     * Stops the engine — drains in-flight exchanges and releases the bound port.
     *
     * @throws IllegalStateException if the engine has not been started
     * @implSpec Refuse new inbound connections immediately, let the exchanges already in flight
     *           finish, and only then release the port; block the caller until the drain completes
     *           or the implementation's own drain timeout expires.
     */
    void stop();

    /**
     * Returns {@code true} if the engine is currently running (started but not stopped).
     *
     * @return {@code true} while the engine is accepting inbound connections
     */
    boolean isRunning();

    /**
     * Returns the stable identifier of the underlying engine implementation.
     *
     * <p>Used in bootstrap JFR events and diagnostics.
     * Examples: {@code "community-http"}, {@code "enterprise-quic"}.
     *
     * @return engine name; never {@code null}
     */
    String engineName();

    /**
     * Closes the engine, releasing all native resources (sockets, off-heap slab partitions,
     * io_uring rings). Idempotent — multiple calls are safe.
     *
     * <p>If the engine is still running when this is called, it is stopped first.
     */
    @Override
    void close();
}

