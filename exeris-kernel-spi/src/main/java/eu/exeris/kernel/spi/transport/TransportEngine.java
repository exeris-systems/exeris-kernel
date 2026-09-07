/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

/**
 * SPI: The kernel's transport engine — protocol-blind lifecycle manager for
 * connections and streams.
 *
 * <h2>Protocol Blindness (The Wall)</h2>
 * <p>This interface does <strong>not</strong> expose TCP, UDP, QUIC, or native I/O mechanisms.
 * Business logic operates on {@link TransportConnection} and {@link TransportStream}
 * abstractions. Whether data flows over a standard TCP connection (Community) or
 * a QUIC/multiplexed pipeline (Enterprise) is entirely opaque.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  TransportProvider.createEngine(config)  → engine (CREATED)
 *  engine.setStreamHandler(handler)        → handler registered
 *  engine.start()                          → engine (RUNNING)
 *  ... accept streams / connect ...
 *  engine.stop()                           → engine (STOPPING, draining)
 *  engine.close()                          → engine (CLOSED, resources released)
 * </pre>
 *
 * <h2>Server Mode</h2>
 * <p>When {@link TransportConfig#mode()} includes server functionality, the engine
 * binds to the configured port and delivers incoming streams to the registered
 * {@link StreamHandler} — each stream on its own virtual thread.
 *
 * <h2>Client Mode</h2>
 * <p>When client functionality is enabled, {@link #connect(String, int)} creates
 * outbound connections that the caller can open streams on.
 *
 * <h2>Resource Management</h2>
 * <p>The engine owns all native resources (sockets, native I/O rings, slab pool
 * partitions) and releases them in {@link #close()}. Implementations MUST
 * make {@code close()} idempotent.
 *
 * @since 0.5
 * @see TransportProvider
 * @see TransportConnection
 * @see TransportStream
 */
public interface TransportEngine extends AutoCloseable {

    /**
     * Registers the handler that will receive every incoming {@link TransportStream}.
     *
     * <p>MUST be called <strong>before</strong> {@link #start()}. The engine spawns
     * a virtual thread per incoming stream and invokes the handler on that thread.
     *
     * @param handler callback for incoming streams; must not be {@code null}
     * @throws IllegalStateException if the engine has already been started
     */
    void setStreamHandler(StreamHandler handler);

    /**
     * Registers a handler for completed connection handshakes (server mode).
     *
     * <p>Called when a new inbound connection is fully established (TLS handshake
     * complete, ready for streams). Useful for protocol-level initialization
     * (e.g., HTTP/3 SETTINGS exchange, QPACK stream setup).
     *
     * <p>Optional — if not set, the engine delivers streams directly to the
     * {@link StreamHandler} without a connection-level intercept.
     *
     * @param handler callback for newly established connections
     */
    void setConnectionHandler(ConnectionHandler handler);

    /**
     * Starts the transport engine — binds sockets, starts carrier loops.
     *
     * <p>After this call, the engine is ready to accept (server) or initiate
     * (client) connections. The {@link StreamHandler} MUST be registered before
     * this call if the engine is in server or dual mode.
     *
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException on bind/start failure
     * @throws IllegalStateException if no stream handler is registered (server/dual mode)
     */
    void start();

    /**
     * Initiates a graceful stop — no new connections accepted, in-flight
     * streams are drained for a bounded period before being forcefully closed.
     *
     * <p>The exact drain/stop timeout is implementation-defined and configured
     * outside this SPI (for example via provider-specific configuration passed to
     * {@link TransportProvider#createEngine(TransportConfig)}). This SPI does not
     * currently expose a dedicated stop/drain timeout parameter.
     *
     * <p>After this call returns, the engine is in STOPPED state and
     * {@link #close()} can be called to release resources.
     */
    void stop();

    /**
     * Creates an outbound connection to the given remote address.
     *
     * <p>This call may block the virtual thread during the handshake phase.
     * The returned connection is ready for {@link TransportConnection#openStream()}.
     *
     * @param host remote host name or IP address
     * @param port remote port number
     * @return an open connection to the remote peer
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException on connection failure
     * @throws IllegalStateException if the engine is not running or does not support client mode
     */
    TransportConnection connect(String host, int port);

    /**
     * Returns the {@link TransportMode} this engine was configured with.
     *
     * @return active transport mode
     */
    TransportMode mode();

    /**
     * Returns a point-in-time diagnostics snapshot.
     *
     * <p>This call is non-blocking and low-allocation. {@link TransportStats} is a
     * Valhalla-ready record — allocations will be eliminated once JEP 401 lands and
     * the record is migrated to {@code value record}. Until then, implementations
     * SHOULD return a cached or pool-sourced instance where possible.
     *
     * @return current transport statistics
     */
    TransportStats stats();

    /**
     * Returns the display name of this engine for diagnostics and JFR events.
     *
     * @return engine name (e.g., {@code "CommunityTcpEngine"}, {@code "EnterpriseNativeEngine"})
     */
    String engineName();

    /**
     * Releases all native resources (sockets, io_uring rings, slab pools, TLS contexts).
     *
     * <p>Idempotent — multiple calls are safe. Calling {@code close()} on a stopped
     * engine is valid; calling it on a running engine implicitly calls {@link #stop()} first.
     */
    @Override
    void close();
}
