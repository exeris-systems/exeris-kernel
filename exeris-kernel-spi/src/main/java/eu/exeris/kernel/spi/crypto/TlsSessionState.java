/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.crypto;

/**
 * SPI: Read-only view of the current TLS session lifecycle state.
 *
 * <h2>Design Contract</h2>
 * <p>This interface is intentionally read-only. Transitions are driven exclusively
 * by the implementation-side state machine ({@code TlsStateMachine} in core).
 * Consumers (transport, protocol handlers) may only query — never mutate — state.
 *
 * <h2>The Wall</h2>
 * <p>This SPI type must remain blind to {@code TlsStateMachine}, VarHandle internals,
 * and any native OpenSSL error codes. It exposes only {@link TlsPhase} semantics.
 *
 * @since 0.5.0
 * @see TlsPhase
 * @see TlsEngine
 */
// ARCH-DECISION: Single abstract method is intentional — not a functional interface.
// TlsSessionState is a stateful query contract; lambda substitution would silently
// bypass the VarHandle acquire-read guarantee required for memory-visibility correctness.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface TlsSessionState {

    /**
     * Returns the current phase of this TLS session.
     *
     * <p>Implementations must guarantee that this is an O(1) read — no locking,
     * no allocation. Backed by a {@code VarHandle} acquire-read in core.
     *
     * @return current {@link TlsPhase}, never {@code null}
     */
    TlsPhase phase();

    /** Shorthand for {@code phase() == TlsPhase.ACTIVE}. */
    default boolean isActive() {
        return phase() == TlsPhase.ACTIVE;
    }

    /** Shorthand for {@code phase().isHandshaking()}. */
    default boolean isHandshaking() {
        return phase().isHandshaking();
    }

    /** Shorthand for {@code phase().isShuttingDown()}. */
    default boolean isShuttingDown() {
        return phase().isShuttingDown();
    }

    /** Shorthand for {@code phase().isTerminal()}. */
    default boolean isTerminal() {
        return phase().isTerminal();
    }

    /** Shorthand for {@code phase() == TlsPhase.ERROR}. */
    default boolean isError() {
        return phase() == TlsPhase.ERROR;
    }
}


