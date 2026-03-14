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
 * SPI: HTTP client engine lifecycle — initiates outbound HTTP connections and
 * sends requests, receiving responses as {@link HttpResponse} carriers.
 *
 * <h2>The Wall</h2>
 * <p>This interface does not expose transport internals or provider-specific mechanics.
 * All wire details remain behind the SPI boundary.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   HttpProvider.createClientEngine(config)  → engine (CREATED)
 *   engine.start()                           → engine (RUNNING)
 *   ... engine.send(request) ...             → HttpResponse
 *   engine.close()                           → engine (CLOSED, resources released)
 * </pre>
 *
 * <h2>Threading Model</h2>
 * <p>{@link #send(HttpRequest)} blocks the calling virtual thread until the full
 * response headers are received (body is buffered by the engine).
 * Each {@code send} call may use a pooled connection internally — connection pooling
 * semantics are the implementation's private concern.
 *
 * @since 0.5.0
 */
public interface HttpClientEngine extends AutoCloseable {

    /**
     * Starts the client engine — initialises connection pool, TLS context, etc.
     *
     * <p>This is a potentially blocking call. MUST NOT be called on a virtual thread
     * expected to be non-blocking.
     *
     * @throws IllegalStateException if the engine has already been started or closed
     */
    void start();

    /**
     * Sends an HTTP request and returns the response.
     *
     * <p>This call blocks the calling virtual thread until the full response is
     * received (status + headers + body assembled into a {@link eu.exeris.kernel.spi.memory.LoanedBuffer}).
     *
     * <p><strong>Body lifecycle:</strong> if {@link HttpResponse#body()} is non-null,
     * the caller MUST close the {@link eu.exeris.kernel.spi.memory.LoanedBuffer} when
     * it is no longer needed to return the off-heap segment to the pool.
     *
     * @param request outbound request; must not be {@code null}
     * @return the server's response; never {@code null}
     * @throws IllegalStateException if the engine has not been started or has been closed
     */
    HttpResponse send(HttpRequest request);

    /**
     * Returns {@code true} if the engine is currently running (started but not closed).
     *
     * @return {@code true} while the engine can accept {@link #send(HttpRequest)} calls
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
     * Closes the engine, draining in-flight requests and releasing all resources.
     * Idempotent — multiple calls are safe.
     */
    @Override
    void close();
}


