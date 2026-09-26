/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.websocket;

/**
 * Core: RFC 6455 §5.2 opcodes.
 *
 * <p>Control opcodes (≥ 0x8) may be interleaved between the fragments of a data message, which is
 * why {@link #isControl()} exists as a property rather than a comparison scattered through the
 * assembler: RFC 6455 §5.4 requires a control frame to be handled without disturbing the
 * fragmentation in progress.
 */
public enum WebSocketOpcode {

    /** 0x0 — a continuation of the data message already in progress. */
    CONTINUATION(0x0),
    /** 0x1 — a UTF-8 text message. The only data opcode this contract accepts. */
    TEXT(0x1),
    /** 0x2 — a binary message; declined by the SPI, closed as a protocol error (ADR-084 §3). */
    BINARY(0x2),
    /** 0x8 — close. */
    CLOSE(0x8),
    /** 0x9 — ping. */
    PING(0x9),
    /** 0xA — pong. */
    PONG(0xA);

    private static final WebSocketOpcode[] BY_CODE = new WebSocketOpcode[16];

    static {
        for (WebSocketOpcode value : values()) {
            BY_CODE[value.code] = value;
        }
    }

    private final int code;

    WebSocketOpcode(int code) {
        this.code = code;
    }

    /**
     * Identifies this opcode's four-bit value on the wire (RFC 6455 §5.2).
     *
     * @return the wire value
     */
    public int code() {
        return code;
    }

    /**
     * Distinguishes a control opcode, which RFC 6455 §5.4 permits to interleave between the
     * fragments of a data message, from a data opcode, which may not.
     *
     * @return whether this is a control opcode, which may appear between data fragments
     */
    public boolean isControl() {
        return code >= 0x8;
    }

    /**
     * Resolves a wire opcode.
     *
     * <p>A table lookup rather than a switch because the value comes off the wire and is bounded to
     * four bits by the caller's mask; an unassigned code is a protocol error the caller reports, not
     * an exception thrown from a lookup.
     *
     * @param code the four-bit opcode
     * @return the opcode, or {@code null} when the code is reserved or unassigned
     */
    public static WebSocketOpcode fromCode(int code) {
        return code >= 0 && code < BY_CODE.length ? BY_CODE[code] : null;
    }
}
