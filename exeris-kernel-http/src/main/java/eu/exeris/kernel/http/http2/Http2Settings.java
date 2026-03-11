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
 * RFC 7540 §6.5 — HTTP/2 Settings parameters.
 *
 * <p>Valhalla-ready: deeply immutable, record-based value type.
 * All fields are primitives — eligible for JIT scalarization.
 *
 * <h2>Unlimited Sentinel</h2>
 * <p>{@code maxConcurrentStreams} and {@code maxHeaderListSize} use {@code -1} to
 * represent "no limit". This is an internal convention: per RFC 7540 §6.5.2, a peer
 * that imposes no limit simply omits the corresponding parameter from the SETTINGS
 * frame rather than sending a sentinel value.
 *
 * @param headerTableSize      SETTINGS_HEADER_TABLE_SIZE (default: 4096)
 * @param enablePush           SETTINGS_ENABLE_PUSH (default: true; 1 = enabled)
 * @param maxConcurrentStreams SETTINGS_MAX_CONCURRENT_STREAMS (default: {@code -1} — no limit)
 * @param initialWindowSize    SETTINGS_INITIAL_WINDOW_SIZE (default: 65535)
 * @param maxFrameSize         SETTINGS_MAX_FRAME_SIZE (default: 16384)
 * @param maxHeaderListSize    SETTINGS_MAX_HEADER_LIST_SIZE (default: {@code -1} — no limit)
 * @since 0.5.0
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7540#section-6.5">RFC 7540 §6.5</a>
 */
public record Http2Settings(
        int headerTableSize,
        boolean enablePush,
        int maxConcurrentStreams,
        int initialWindowSize,
        int maxFrameSize,
        long maxHeaderListSize
) {

    /** RFC 7540 §6.5.2 — default settings values. */
    public static final Http2Settings DEFAULTS = new Http2Settings(
            4096, true, -1, 65_535, 16_384, -1L);

    /** SETTINGS_HEADER_TABLE_SIZE identifier (0x01). */
    public static final int ID_HEADER_TABLE_SIZE = 0x01;
    /** SETTINGS_ENABLE_PUSH identifier (0x02). */
    public static final int ID_ENABLE_PUSH = 0x02;
    /** SETTINGS_MAX_CONCURRENT_STREAMS identifier (0x03). */
    public static final int ID_MAX_CONCURRENT_STREAMS = 0x03;
    /** SETTINGS_INITIAL_WINDOW_SIZE identifier (0x04). */
    public static final int ID_INITIAL_WINDOW_SIZE = 0x04;
    /** SETTINGS_MAX_FRAME_SIZE identifier (0x05). */
    public static final int ID_MAX_FRAME_SIZE = 0x05;
    /** SETTINGS_MAX_HEADER_LIST_SIZE identifier (0x06). */
    public static final int ID_MAX_HEADER_LIST_SIZE = 0x06;

    /**
     * Returns a new settings instance with the given parameter applied.
     *
     * @param identifier settings parameter identifier (0x01–0x06)
     * @param value      parameter value
     * @return updated settings (original is unchanged)
     */
    public Http2Settings withSetting(int identifier, int value) {
        return switch (identifier) {
            case ID_HEADER_TABLE_SIZE      -> new Http2Settings(
                    value, enablePush, maxConcurrentStreams,
                    initialWindowSize, maxFrameSize, maxHeaderListSize);
            case ID_ENABLE_PUSH            -> new Http2Settings(
                    headerTableSize, value != 0, maxConcurrentStreams,
                    initialWindowSize, maxFrameSize, maxHeaderListSize);
            case ID_MAX_CONCURRENT_STREAMS -> new Http2Settings(
                    headerTableSize, enablePush, value,
                    initialWindowSize, maxFrameSize, maxHeaderListSize);
            case ID_INITIAL_WINDOW_SIZE    -> new Http2Settings(
                    headerTableSize, enablePush, maxConcurrentStreams,
                    value, maxFrameSize, maxHeaderListSize);
            case ID_MAX_FRAME_SIZE         -> new Http2Settings(
                    headerTableSize, enablePush, maxConcurrentStreams,
                    initialWindowSize, value, maxHeaderListSize);
            case ID_MAX_HEADER_LIST_SIZE   -> new Http2Settings(
                    headerTableSize, enablePush, maxConcurrentStreams,
                    initialWindowSize, maxFrameSize, value);
            default -> this;
        };
    }
}
