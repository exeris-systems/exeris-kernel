/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/**
 * JFR event emitted exactly once per connection when it becomes established — for a
 * plaintext stream the moment the reactor arms its key, for a TLS stream the moment the
 * reactor-driven handshake reaches {@code ACTIVE}.
 *
 * <h2>JFR-First Principle</h2>
 * <p>Makes the connection-establishment path observable in production recordings: the
 * {@code establishLatencyNanos} is the time from key registration to established (≈0 for
 * plaintext, the handshake duration for TLS), and {@code handlerDurationNanos} is the time
 * the connection-handler notification + PAQS schedule spent on the reactor thread. Because
 * that callback runs synchronously on the selector loop, a large {@code handlerDurationNanos}
 * is the signal that a {@code ConnectionHandler} is violating its non-blocking contract and
 * stalling key dispatch.
 *
 * <p>Single-phase {@code commit()} on the reactor platform thread — no
 * {@code begin()→blocking-op→commit()} straddle, safe regardless of carrier scheduling.
 * The {@link FlightRecorder#isInitialized()} guard keeps it zero-overhead when disabled, and
 * it fires once per connection (not per request), off the request hot path.
 *
 * @since 0.9
 */
@Name("eu.exeris.kernel.core.transport.ConnectionEstablished")
@Label("Connection Established")
@Category({"Exeris Kernel", "Transport", "Lifecycle"})
@Description("Emitted once when a connection is established (plaintext: key armed; TLS: handshake ACTIVE).")
@StackTrace(false)
public final class ConnectionEstablishedEvent extends Event {

        /**
         * SPI stream identifier ({@link eu.exeris.kernel.spi.transport.TransportStream#streamId()})
         * of the connection that was established.
         */
    @Label("Stream ID")
    public long streamId;

    /** {@code true} if this connection negotiated TLS; {@code false} for a plaintext connection. */
    @Label("TLS Enabled")
    public boolean tlsEnabled;

    /**
     * Nanoseconds from key registration to the connection becoming established: near zero for
     * a plaintext connection (established the moment the reactor arms the key), the TLS
     * handshake duration for a TLS connection.
     */
    @Label("Establish Latency")
    @Timespan(Timespan.NANOSECONDS)
    public long establishLatencyNanos;

    /**
     * Nanoseconds the established callback (connection-handler notification plus PAQS schedule)
     * spent running synchronously on the reactor thread. A large value is the signal that the
     * {@code ConnectionHandler} is blocking in violation of its non-blocking contract.
     */
    @Label("Handler Duration")
    @Timespan(Timespan.NANOSECONDS)
    public long handlerDurationNanos;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public ConnectionEstablishedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Emits a connection-established event.
     *
     * @param streamId              the SPI stream identifier
     * @param tlsEnabled            {@code true} if the connection negotiated TLS
     * @param establishLatencyNanos nanoseconds from key registration to established
     * @param handlerDurationNanos  nanoseconds spent in the established callback on the reactor thread
     */
    public static void emit(long streamId, boolean tlsEnabled,
                            long establishLatencyNanos, long handlerDurationNanos) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        ConnectionEstablishedEvent evt = new ConnectionEstablishedEvent();
        if (evt.isEnabled()) {
            evt.streamId = streamId;
            evt.tlsEnabled = tlsEnabled;
            evt.establishLatencyNanos = establishLatencyNanos;
            evt.handlerDurationNanos = handlerDurationNanos;
            evt.commit();
        }
    }
}
