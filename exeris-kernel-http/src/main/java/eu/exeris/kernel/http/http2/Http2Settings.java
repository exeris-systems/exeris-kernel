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
     * <p>This is the primary overload covering the full unsigned 32-bit value range
     * defined by RFC 7540 §6.5.2. Range validation is applied per identifier.
     *
     * @param identifier settings parameter identifier (0x01–0x06)
     * @param value      parameter value as an unsigned 32-bit quantity (0 to 2^32-1)
     * @return updated settings (original is unchanged)
     * @throws IllegalArgumentException if the value is out of range for the given identifier
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    public Http2Settings withSetting(int identifier, long value) {
        return switch (identifier) {
            case ID_HEADER_TABLE_SIZE      -> new Http2Settings(
                    validateIntParam("SETTINGS_HEADER_TABLE_SIZE", value, 0L, 0xFFFF_FFFFL),
                    enablePush, maxConcurrentStreams,
                    initialWindowSize, maxFrameSize, maxHeaderListSize);
            case ID_ENABLE_PUSH            -> {
                if (value != 0L && value != 1L) {
                    throw new IllegalArgumentException(
                            "SETTINGS_ENABLE_PUSH must be 0 or 1, was: " + value);
                }
                yield new Http2Settings(headerTableSize, value == 1L, maxConcurrentStreams,
                        initialWindowSize, maxFrameSize, maxHeaderListSize);
            }
            case ID_MAX_CONCURRENT_STREAMS -> new Http2Settings(
                    headerTableSize, enablePush,
                    validateIntParam("SETTINGS_MAX_CONCURRENT_STREAMS", value, 0L, 0xFFFF_FFFFL),
                    initialWindowSize, maxFrameSize, maxHeaderListSize);
            case ID_INITIAL_WINDOW_SIZE    -> new Http2Settings(
                    headerTableSize, enablePush, maxConcurrentStreams,
                    validateIntParam("SETTINGS_INITIAL_WINDOW_SIZE", value, 0L, 0x7FFF_FFFFL),
                    maxFrameSize, maxHeaderListSize);
            case ID_MAX_FRAME_SIZE         -> new Http2Settings(
                    headerTableSize, enablePush, maxConcurrentStreams, initialWindowSize,
                    validateIntParam("SETTINGS_MAX_FRAME_SIZE", value, 16_384L, 16_777_215L),
                    maxHeaderListSize);
            case ID_MAX_HEADER_LIST_SIZE   -> new Http2Settings(
                    headerTableSize, enablePush, maxConcurrentStreams,
                    initialWindowSize, maxFrameSize,
                    validateLongParam("SETTINGS_MAX_HEADER_LIST_SIZE", value, 0L, 0xFFFF_FFFFL));
            default -> this;
        };
    }

    /**
     * Convenience overload for callers that hold the wire value as a signed 32-bit integer.
     *
     * <p>The {@code value} is widened treating it as unsigned via
     * {@link Integer#toUnsignedLong(int)}, then delegated to {@link #withSetting(int, long)}.
     *
     * @param identifier settings parameter identifier (0x01–0x06)
     * @param value      parameter value as a signed 32-bit integer (treated as unsigned on wire)
     * @return updated settings (original is unchanged)
     * @throws IllegalArgumentException if the unsigned-widened value is out of range
     */
    public Http2Settings withSetting(int identifier, int value) {
        return withSetting(identifier, Integer.toUnsignedLong(value));
    }

    private static int validateIntParam(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    name + " out of range: " + value + " (expected " + min + ".." + max + ')');
        }
        return (int) value;
    }

    private static long validateLongParam(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    name + " out of range: " + value + " (expected " + min + ".." + max + ')');
        }
        return value;
    }
}
