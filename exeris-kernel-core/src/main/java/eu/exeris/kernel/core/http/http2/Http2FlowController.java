/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http2;

/**
 * RFC 7540 §5.2 — HTTP/2 Flow Control (connection + stream level).
 *
 * <h2>Contract</h2>
 * <p>Tracks the flow-control window for a single direction (either connection-level
 * or per-stream). The initial window size is 65535 bytes (RFC 7540 §6.9.2).
 *
 * <h2>Scope</h2>
 * <p><strong>RFC 7540 (HTTP/2 over TCP) only.</strong> This class implements the
 * credit-based window model of RFC 7540 §5.2 exclusively. It MUST NOT be reused
 * as a flow-control primitive for HTTP/3 / QUIC traffic. HTTP/3 stream flow control
 * is governed by RFC 9000 §4 and RFC 9114 §4.1. Enterprise H3 implementations
 * MUST use QUIC-native flow control; reaching for this class from an H3 code path
 * is a contract violation.
 *
 * <p><b>Allocation:</b> zero-alloc on the success path — every operation is primitive arithmetic
 * on the controller's own window-size field. The two rejection paths allocate: each builds a
 * message by concatenation before throwing.
 * <p><b>Thread confinement:</b> owner thread — not thread-safe; each HTTP/2 connection
 * owns its own controller instances, one per direction, and must not share them across
 * threads without external synchronization.
 * <p><b>Ownership:</b> the controller holds no buffers or native resources; there is
 * nothing to release.
 *
 * @since 0.5
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7540#section-5.2">RFC 7540 §5.2</a>
 */
public final class Http2FlowController {

    /** RFC 7540 §6.9.2 — initial window size. */
    public static final int DEFAULT_WINDOW_SIZE = 65_535;

    /** RFC 7540 §6.9 — maximum flow-control window size. */
    public static final int MAX_WINDOW_SIZE = Integer.MAX_VALUE;

    private long windowSize;

    /**
     * Creates a flow controller with the default initial window size.
     */
    public Http2FlowController() {
        this(DEFAULT_WINDOW_SIZE);
    }

    /**
     * Creates a flow controller with the given initial window size.
     *
     * @param initialWindowSize initial window size in bytes
     * @throws IllegalArgumentException if {@code initialWindowSize} is negative
     */
    public Http2FlowController(int initialWindowSize) {
        if (initialWindowSize < 0) {
            throw new IllegalArgumentException(
                    "initialWindowSize must be >= 0, got: " + initialWindowSize);
        }
        this.windowSize = initialWindowSize;
    }

    /**
     * Returns the number of bytes this direction is currently permitted to send (or receive)
     * before the peer must grant more credit via WINDOW_UPDATE.
     *
     * @return available window bytes; can be negative immediately after a SETTINGS change that
     *         shrinks the initial window below what was already in flight
     */
    public long windowSize() {
        return windowSize;
    }

    /**
     * Consumes bytes from the flow-control window (after sending DATA).
     *
     * @param bytes number of bytes consumed
     * @return {@code true} if the window was sufficient, {@code false} if flow control
     *         would be violated (window remains unchanged in this case)
     */
    public boolean consume(int bytes) {
        if (bytes < 0) {
            return false;
        }
        if (bytes == 0) {
            return true;
        }
        if (bytes > windowSize) {
            return false;
        }
        windowSize -= bytes;
        return true;
    }

    /**
     * Increases the window size (WINDOW_UPDATE received).
     *
     * @param increment window increment (must be positive)
     * @return {@code true} if the update was valid, {@code false} if it would
     *         exceed {@link #MAX_WINDOW_SIZE}
     */
    public boolean increment(int increment) {
        if (increment <= 0) {
            return false;
        }
        long newSize = windowSize + increment;
        if (newSize > MAX_WINDOW_SIZE) {
            return false;
        }
        windowSize = newSize;
        return true;
    }

    /**
     * Updates the window size due to a SETTINGS_INITIAL_WINDOW_SIZE change.
     *
     * @param oldInitial previous initial window size
     * @param newInitial new initial window size
     * @throws IllegalStateException if applying the delta would push the window size past
     *         {@link #MAX_WINDOW_SIZE}
     */
    public void updateInitialWindowSize(int oldInitial, int newInitial) {
        final long delta = (long) newInitial - (long) oldInitial;
        final long newSize = windowSize + delta;
        if (newSize > MAX_WINDOW_SIZE) {
            throw new IllegalStateException(
                    "Flow-control window exceeds maximum " + MAX_WINDOW_SIZE
                            + " after SETTINGS_INITIAL_WINDOW_SIZE change");
        }
        windowSize = newSize;
    }
}
