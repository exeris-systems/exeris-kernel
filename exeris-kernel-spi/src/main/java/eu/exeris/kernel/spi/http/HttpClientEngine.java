/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p><b>Allocation:</b> allocates (one {@link HttpResponse} per {@link #send(HttpRequest)}, and an
 * off-heap {@link eu.exeris.kernel.spi.memory.LoanedBuffer} for the response body when the response
 * carries one)
 * <p><b>Ownership:</b> the caller of {@code send} owns the returned {@link HttpResponse#body()} and
 * releases it with {@code close()}; the engine owns its connections and releases them on
 * {@link #close()}
 *
 * @apiNote {@link #send(HttpRequest)} blocks the calling virtual thread until the full response is
 *          received, so a caller sizes concurrency in virtual threads rather than in engines. Each
 *          {@code send} may use a pooled connection internally; pooling is not part of this
 *          contract and no call site may depend on a request occupying a connection of its own.
 * @since 0.5
 */
public interface HttpClientEngine extends AutoCloseable {

    /**
     * Starts the client engine — initialises connection pool, TLS context, etc.
     *
     * @throws IllegalStateException if the engine has already been started or closed
     * @apiNote This is a potentially blocking call; it must not be issued from a virtual thread
     *          that is expected never to park.
     */
    void start();

    /**
     * Sends an HTTP request and returns the response.
     *
     * <p>This call blocks the calling virtual thread until the full response is
     * received (status + headers + body assembled into a {@link eu.exeris.kernel.spi.memory.LoanedBuffer}).
     *
     * @param request outbound request; must not be {@code null}
     * @return the server's response; never {@code null}
     * @throws IllegalStateException if the engine has not been started or has been closed
     * @apiNote <strong>Body lifecycle:</strong> when {@link HttpResponse#body()} is non-null the
     *          caller owns that buffer and must {@code close()} it once the payload has been read;
     *          the off-heap segment returns to the pool only then, so a missed close is a leak that
     *          no engine-side teardown recovers.
     */
    HttpResponse send(HttpRequest request);

    /**
     * Returns the peer this engine sends a request to when the request names none, or {@code null}
     * when it has no default and an unaddressed request must be refused.
     *
     * <p>Exists so ADR-074's ordering rule — resolve the authority, THEN enrich, THEN send — can be
     * honoured by a caller that sits above the engine. Without it the substitution of the default
     * happens inside {@link #send(HttpRequest)}, which is strictly after
     * {@link HttpClientRequestEnricher#enrich(HttpRequest)} has already run, so an enricher binding
     * an outbound credential's audience to the peer (ADR-040) would only ever observe {@code null}.
     *
     * @return the configured default peer as {@code host:port}, or {@code null} if there is none
     * @implSpec The default implementation returns {@code null} — an engine that does not override
     *           it declares no default peer, so every unaddressed request is refused rather than
     *           dialled somewhere the application did not name.
     * @since 0.12
     */
    default String defaultAuthority() {
        return null;
    }

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


