/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.crypto;

/**
 * SPI: Read-only view of the current TLS session lifecycle state.
 *
 * <h2>The Wall</h2>
 * <p>This SPI type must remain blind to {@code TlsStateMachine}, VarHandle internals,
 * and any provider-specific error codes. It exposes only {@link TlsPhase} semantics.
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — {@link #phase()} is an O(1) read and the
 * default predicates do nothing but compare its result.
 * <p><b>Thread confinement:</b> any thread — the phase is published by the thread driving the
 * engine and read back through an acquire-read, so a transport or protocol handler observes
 * transitions from another thread without extra synchronisation.
 *
 * @implSpec Transitions are driven exclusively by the implementation-side state machine; this
 *           interface is a query surface, and an implementation must not offer mutation through
 *           it. {@link #phase()} must be an O(1) read that neither locks nor allocates, and must
 *           make a transition visible to a reader on another thread.
 * @implNote The Core state machine backs the phase with a {@code VarHandle} acquire-read.
 * @since 0.5
 * @see TlsPhase
 * @see TlsEngine
 */
// ARCH-DECISION: Single abstract method is intentional — not a functional interface.
// TlsSessionState is a stateful query contract; lambda substitution would silently
// bypass the VarHandle acquire-read guarantee required for memory-visibility correctness.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface TlsSessionState {

    /**
     * Samples the lifecycle phase the session is in at this instant.
     *
     * @return the current {@link TlsPhase}, never {@code null}; the value is a snapshot and the
     *         session may have moved on by the time the caller acts on it
     */
    TlsPhase phase();

    /**
     * Reports that the handshake is behind the session and encrypted application data may flow.
     *
     * @return {@code true} when {@link #phase()} is {@link TlsPhase#ACTIVE}
     */
    default boolean isActive() {
        return phase() == TlsPhase.ACTIVE;
    }

    /**
     * Reports that the session is still negotiating.
     *
     * @return {@code true} when {@link #phase()} is {@link TlsPhase#HANDSHAKE_IN_PROGRESS}
     */
    default boolean isHandshaking() {
        return phase().isHandshaking();
    }

    /**
     * Reports that a graceful close-notify exchange has begun.
     *
     * @return {@code true} when {@link #phase()} is {@link TlsPhase#SHUTDOWN_INITIATED} or
     *         {@link TlsPhase#SHUTDOWN_COMPLETE}
     */
    default boolean isShuttingDown() {
        return phase().isShuttingDown();
    }

    /**
     * Reports that the session has reached a phase it can never leave.
     *
     * @return {@code true} when {@link #phase()} is {@link TlsPhase#CLOSED} or
     *         {@link TlsPhase#ERROR}
     */
    default boolean isTerminal() {
        return phase().isTerminal();
    }

    /**
     * Reports that the session failed and is unusable, as opposed to closed in good order.
     *
     * @return {@code true} when {@link #phase()} is {@link TlsPhase#ERROR}
     */
    default boolean isError() {
        return phase() == TlsPhase.ERROR;
    }
}


