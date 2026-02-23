/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.crypto;

/**
 * SPI: Explicit lifecycle phases for a TLS connection.
 *
 * <h2>State Diagram</h2>
 * <pre>
 *   UNINITIALIZED
 *        ↓
 *   HANDSHAKE_IN_PROGRESS ─────────────────────┐
 *        ↓                                     ↓
 *   HANDSHAKE_COMPLETE                       ERROR (terminal)
 *        ↓                                     ↑
 *   ACTIVE ──────────────────────────────────┘↑
 *        ↓                                     ↑
 *   SHUTDOWN_INITIATED ──────────────────────┘
 *        ↓
 *   SHUTDOWN_COMPLETE
 *        ↓
 *   CLOSED (terminal)
 * </pre>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Enum constants are singletons — safe as {@code value record} fields.
 * Ordinal-based dispatch in {@link TlsStateMachine} is O(1).
 *
 * @since 0.5.0
 * @see TlsSessionState
 */
public enum TlsPhase {

    /**
     * Initial state: SSL* created but no OpenSSL operations performed yet.
     * Legal transitions: → {@link #HANDSHAKE_IN_PROGRESS}, → {@link #ERROR}
     */
    UNINITIALIZED("Connection not yet initialized"),

    /**
     * TLS handshake in progress.
     * Call {@code accept()} or {@code connect()} repeatedly until complete.
     * Legal transitions: → {@link #HANDSHAKE_COMPLETE}, → {@link #ERROR}
     */
    HANDSHAKE_IN_PROGRESS("TLS handshake in progress"),

    /**
     * Handshake completed: certificate verified, cipher negotiated, keys derived.
     * Legal transitions: → {@link #ACTIVE}, → {@link #ERROR}
     */
    HANDSHAKE_COMPLETE("TLS handshake completed successfully"),

    /**
     * Connection active and encrypted — application data may flow.
     * Legal transitions: → {@link #SHUTDOWN_INITIATED}, → {@link #ERROR}
     */
    ACTIVE("Connection active and encrypted"),

    /**
     * Graceful shutdown initiated: close-notify sent, waiting for peer's close-notify.
     * Legal transitions: → {@link #SHUTDOWN_COMPLETE}, → {@link #ERROR}
     */
    SHUTDOWN_INITIATED("Graceful shutdown initiated"),

    /**
     * Both sides have exchanged close-notify alerts.
     * Legal transitions: → {@link #CLOSED}
     */
    SHUTDOWN_COMPLETE("Graceful shutdown completed"),

    /**
     * Terminal state: SSL* deallocated, no further operations possible.
     */
    CLOSED("Connection closed and resources freed"),

    /**
     * Terminal error state: connection unusable after a fatal error.
     * Reachable from any non-terminal phase.
     */
    ERROR("Error state — connection unusable");

    private final String description;

    TlsPhase(String description) {
        this.description = description;
    }

    /** Human-readable description of this phase, suitable for JFR events and logs. */
    public String description() {
        return description;
    }

    /** Returns {@code true} if no further transitions are possible from this phase. */
    public boolean isTerminal() {
        return this == CLOSED || this == ERROR;
    }

    /** Returns {@code true} if TLS handshake is currently in progress. */
    public boolean isHandshaking() {
        return this == HANDSHAKE_IN_PROGRESS;
    }

    /** Returns {@code true} if application data may be transferred. */
    public boolean canTransferData() {
        return this == ACTIVE;
    }

    /** Returns {@code true} if a graceful shutdown is in progress or complete. */
    public boolean isShuttingDown() {
        return this == SHUTDOWN_INITIATED || this == SHUTDOWN_COMPLETE;
    }
}

