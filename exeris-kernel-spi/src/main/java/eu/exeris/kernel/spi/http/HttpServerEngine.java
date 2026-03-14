/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * <h2>JFR-First</h2>
 * <p>Implementations MUST emit a {@code jdk.jfr.Event} annotated with
 * {@code @StackTrace(false)} on {@link #start()} and {@link #stop()} for
 * zero-overhead lifecycle telemetry.
 *
 * @since 0.5.0
 */
public interface HttpServerEngine extends AutoCloseable {

    /**
     * Registers the {@link HttpHandler} that will receive every inbound
     * {@link HttpExchange}.
     *
     * <p>MUST be called <strong>before</strong> {@link #start()}. The engine spawns
     * a virtual thread per inbound request and invokes the handler on that thread.
     *
     * @param handler application request handler; must not be {@code null}
     * @throws IllegalStateException if the engine has already been started
     */
    void setHandler(HttpHandler handler);

    /**
     * Starts the engine — binds the port, initialises acceptor loops.
     *
     * <p>After this call the engine is ready to accept inbound connections.
     * This is a potentially blocking call (socket bind, TLS context setup).
     * MUST NOT be called on a virtual thread expected to be non-blocking.
     *
     * @throws IllegalStateException if the engine has already been started or closed
     */
    void start();

    /**
     * Stops the engine — drains in-flight exchanges and releases the bound port.
     *
     * <p>In-flight exchanges are allowed to complete before the port is released.
     * New inbound connections are rejected immediately after this call.
     * This method blocks until all active exchanges have completed or the
     * implementation's drain timeout expires.
     *
     * @throws IllegalStateException if the engine has not been started
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

