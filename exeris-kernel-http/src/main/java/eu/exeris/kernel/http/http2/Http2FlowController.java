/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.http2;

/**
 * RFC 7540 §5.2 — HTTP/2 Flow Control (connection + stream level).
 *
 * <h2>Contract</h2>
 * <p>Tracks the flow-control window for a single direction (either connection-level
 * or per-stream). The initial window size is 65535 bytes (RFC 7540 §6.9.2).
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe. Each HTTP/2 connection manages its own flow controller instances.
 *
 * @since 0.5.0
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
     */
    public Http2FlowController(int initialWindowSize) {
        this.windowSize = initialWindowSize;
    }

    /**
     * Returns the current window size.
     *
     * @return available window bytes (may be negative after settings change)
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
     */
    public void updateInitialWindowSize(int oldInitial, int newInitial) {
        windowSize += (long) newInitial - oldInitial;
    }
}
