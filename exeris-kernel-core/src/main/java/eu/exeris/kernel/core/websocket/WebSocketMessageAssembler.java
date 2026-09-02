/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.websocket;

import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Core: turns a stream of frames into whole text messages.
 *
 * <p>Stateful and confined to one connection: it holds the fragmentation in progress, which is
 * per-connection by definition. Not thread-safe, and does not need to be — one virtual thread reads
 * a connection.
 *
 * <h2>What it enforces, and why each is here rather than in the engine</h2>
 * <ul>
 *   <li><b>Fragment reassembly.</b> A peer splitting a large message across continuation frames is
 *       speaking the protocol correctly; the handler must see one message (ADR-084 §3).</li>
 *   <li><b>The size limit, checked as fragments arrive.</b> Checking only on the final fragment
 *       would mean buffering an unbounded message first, so a peer could exhaust memory with a
 *       message it never finishes.</li>
 *   <li><b>Binary is refused.</b> The SPI is text-only, so a binary opcode closes the connection
 *       rather than reaching a handler that has nowhere to put it.</li>
 *   <li><b>Strict UTF-8.</b> RFC 6455 §8.1 requires closing on invalid UTF-8 in a text message.
 *       {@code new String(bytes, UTF_8)} would substitute U+FFFD and hand the handler something the
 *       peer did not send, which is the silent-corruption shape rather than a decode.</li>
 * </ul>
 */
public final class WebSocketMessageAssembler {

    private final long maxMessageBytes;
    private final CharsetDecoder decoder;

    private byte[] buffer;
    private int bufferLength;
    private boolean fragmentInProgress;

    /**
     * @param maxMessageBytes the configured ceiling on a reassembled message; must be positive
     */
    public WebSocketMessageAssembler(long maxMessageBytes) {
        if (maxMessageBytes <= 0) {
            throw new IllegalArgumentException(
                    "maxMessageBytes must be positive: " + maxMessageBytes);
        }
        this.maxMessageBytes = maxMessageBytes;
        // REPORT on both malformed and unmappable input: the point is to refuse, and the default
        // action would replace silently.
        this.decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        this.buffer = new byte[0];
    }

    /**
     * Accepts one data frame.
     *
     * @param seg    the segment holding it
     * @param header the parsed header; must not be a control frame
     * @return the completed message when this frame finished one, otherwise {@code null}
     * @throws WebSocketProtocolException on a violation the connection must close for
     */
    public String accept(MemorySegment seg, WebSocketFrameHeader header) {
        validate(header.opcode());

        long total = (long) bufferLength + header.payloadLength();
        if (total > maxMessageBytes) {
            // Checked here, on arrival, rather than at the end: refusing only a completed oversize
            // message would still require holding it first.
            throw new WebSocketProtocolException(WebSocketCloseCode.MESSAGE_TOO_BIG,
                    "message exceeds the configured maximum");
        }

        ensureCapacity((int) total);
        WebSocketFrameParser.copyPayload(seg, header, buffer, bufferLength);
        bufferLength = (int) total;

        if (!header.fin()) {
            fragmentInProgress = true;
            return null;
        }
        fragmentInProgress = false;
        String message = decodeStrict(buffer, bufferLength);
        bufferLength = 0;
        return message;
    }

    /**
     * RFC 6455 fragmentation rules plus this contract's text-only restriction.
     *
     * <p>PMD scores this 10 because it counts every arm of an enum switch as a path, including the
     * three control opcodes the default covers. The method is four statements; splitting it would
     * produce two methods that exist to satisfy a counter rather than to be read. The earlier
     * if-chain scored 11, so the switch is the improvement, not the cause.
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    private void validate(WebSocketOpcode opcode) {
        switch (opcode) {
            case TEXT -> {
                if (fragmentInProgress) {
                    throw new WebSocketProtocolException(WebSocketCloseCode.PROTOCOL_ERROR,
                            "new data frame while a message is still fragmented");
                }
            }
            case CONTINUATION -> {
                if (!fragmentInProgress) {
                    throw new WebSocketProtocolException(WebSocketCloseCode.PROTOCOL_ERROR,
                            "continuation frame with no message in progress");
                }
            }
            case BINARY -> throw new WebSocketProtocolException(WebSocketCloseCode.UNSUPPORTED_DATA,
                    "binary frames are not accepted on a text-only contract");
            default -> throw new IllegalArgumentException(
                    "control frames are handled by the engine, not the assembler: " + opcode);
        }
    }

    /**
     * @return whether a fragmented message is currently in progress
     */
    public boolean fragmentInProgress() {
        return fragmentInProgress;
    }

    /** Discards any partial message, for a connection that is being torn down. */
    public void reset() {
        bufferLength = 0;
        fragmentInProgress = false;
    }

    private void ensureCapacity(int required) {
        if (buffer.length >= required) {
            return;
        }
        // Grow by doubling, bounded by the configured limit — which the caller has already checked
        // the message against, so this never allocates for a message that will be refused.
        int target = Math.max(required, Math.max(64, buffer.length * 2));
        byte[] grown = new byte[(int) Math.min(target, maxMessageBytes)];
        System.arraycopy(buffer, 0, grown, 0, bufferLength);
        buffer = grown;
    }

    // ByteBuffer is on the scoped-ban list for zero-copy runtime paths, and this use is justified
    // rather than overlooked: it wraps a heap array this class already owns and already copied into,
    // so no off-heap buffer is involved and nothing is copied a second time. CharsetDecoder is the
    // only API that reports invalid UTF-8 instead of substituting for it, and reporting is the whole
    // point — new String(bytes, UTF_8) would hand the handler U+FFFD where the peer sent a violation.
    private String decodeStrict(byte[] bytes, int length) {
        try {
            CharBuffer decoded = decoder.reset().decode(ByteBuffer.wrap(bytes, 0, length));
            return decoded.toString();
        } catch (CharacterCodingException invalid) {
            // The cause is kept: CharacterCodingException carries the offending length and nothing
            // from the payload, so preserving it aids diagnosis without leaking what the peer sent.
            throw new WebSocketProtocolException(WebSocketCloseCode.INVALID_PAYLOAD_DATA,
                    "text frame payload is not valid UTF-8", invalid);
        }
    }
}
