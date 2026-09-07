/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry;

/**
 * Abstract transport error codes for the network edge.
 *
 * <h2>Design</h2>
 * <p>The network edge (HTTP/3, QUIC, TCP) must never expose internal
 * {@code EX-NET-*} or {@code EX-MEM-*} codes to clients — those are forensic-only.
 * {@link ErrorMapperRegistry} translates
 * {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException} subclasses into one of
 * these abstract codes, which the transport layer may map to a protocol-level status
 * (H3 error frame, QUIC close code, etc.).
 *
 * <h2>Wire-Code Stability</h2>
 * <p>Ordinal values are intentionally not used for serialization. Use {@link #wireCode()}
 * to obtain a stable integer for protocol frames. These codes are frozen — new entries
 * MUST be appended; existing entries MUST NOT be renumbered.
 *
 * @since 0.5
 */
public enum TransportErrorCode {

    /**
     * Catch-all: an internal kernel error not covered by a specific category.
     */
    INTERNAL_ERROR(0x00),

    /**
     * Off-heap memory exhausted — the kernel cannot service the request.
     */
    RESOURCE_EXHAUSTED(0x01),

    /**
     * Transport bind or handshake failure — service unavailable on this endpoint.
     */
    BIND_FAILURE(0x02),

    /**
     * Send failed — the frame could not be delivered to the peer.
     */
    SEND_FAILURE(0x03),

    /**
     * Receive timeout — no data arrived within the deadline.
     */
    RECEIVE_TIMEOUT(0x04),

    /**
     * Bootstrap failure — the subsystem failed to initialize.
     */
    BOOTSTRAP_FAILURE(0x05),

    /**
     * Security / principal context missing or token validation failed.
     */
    SECURITY_VIOLATION(0x06),

    /**
     * Persistence layer failure — query, connection, or auth error.
     */
    PERSISTENCE_FAILURE(0x07),

    /**
     * Virtual-thread carrier pinned beyond the kill threshold.
     * Signals scheduler overload — triggers backpressure upstream.
     */
    CARRIER_PINNED(0x08);

    private final int wireCode;

    TransportErrorCode(int wireCode) {
        this.wireCode = wireCode;
    }

    /**
     * Stable integer code for protocol-level serialization (H3 error frame, QUIC close).
     * Values are frozen and MUST NOT be reordered across releases.
     *
     * @return non-negative integer wire representation
     */
    public int wireCode() {
        return wireCode;
    }
}
