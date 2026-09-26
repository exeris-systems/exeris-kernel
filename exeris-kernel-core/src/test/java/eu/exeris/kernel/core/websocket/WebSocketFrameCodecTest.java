/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.websocket;

import eu.exeris.kernel.spi.config.KernelProfile;
import eu.exeris.kernel.spi.exceptions.ExceptionDisclosure;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.FaultOrigin;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The RFC 6455 frame codec: parser, writer and the message assembler over them.
 *
 * <p>Written against the wire rather than against the implementation — every frame a test parses is
 * either assembled byte by byte here or produced by the writer, so a bug that moved both sides the
 * same way would still be caught by the hand-built cases.
 */
@DisplayName("WebSocket frame codec")
class WebSocketFrameCodecTest {

    private static MemorySegment segmentOf(byte... bytes) {
        return MemorySegment.ofArray(bytes);
    }

    private static String feed(WebSocketMessageAssembler assembler, byte[] frame) {
        MemorySegment seg = MemorySegment.ofArray(frame);
        WebSocketFrameHeader header = WebSocketFrameParser.parse(seg, 0, seg.byteSize());
        return assembler.accept(seg, header);
    }

    /** Builds a client-to-server frame: FIN set, masked, with the mask applied to the payload. */
    private static byte[] maskedFrame(int opcode, byte[] payload, int maskKey) {
        byte[] header = new byte[6];
        header[0] = (byte) (0x80 | opcode);
        header[1] = (byte) (0x80 | payload.length);
        for (int i = 0; i < 4; i++) {
            header[2 + i] = (byte) (maskKey >>> (24 - (i << 3)));
        }
        byte[] frame = new byte[header.length + payload.length];
        System.arraycopy(header, 0, frame, 0, header.length);
        for (int i = 0; i < payload.length; i++) {
            frame[header.length + i] =
                    (byte) (payload[i] ^ (byte) (maskKey >>> (24 - ((i & 3) << 3))));
        }
        return frame;
    }

    @Nested
    @DisplayName("parser")
    class Parser {

        @Test
        @DisplayName("a short unmasked frame parses, and the payload stays in the segment")
        void shortFrame() {
            byte[] payload = "hi".getBytes(StandardCharsets.UTF_8);
            MemorySegment seg = segmentOf((byte) 0x81, (byte) payload.length, payload[0], payload[1]);
            WebSocketFrameHeader header = WebSocketFrameParser.parse(seg, 0, seg.byteSize());
            assertThat(header).isNotNull();
            assertThat(header.opcode()).isEqualTo(WebSocketOpcode.TEXT);
            assertThat(header.fin()).isTrue();
            assertThat(header.masked()).isFalse();
            assertThat(header.payloadLength()).isEqualTo(2);
            assertThat(header.payloadOffset())
                    .as("the payload is described, not copied")
                    .isEqualTo(2);
            assertThat(header.frameEnd()).isEqualTo(4);
        }

        @Test
        @DisplayName("a masked frame unmasks during the copy")
        void maskedFrameUnmasks() {
            // The mask cycles by position, so a payload longer than the four-byte key is what
            // actually exercises it — a shorter one would pass with a broken index.
            byte[] payload = "abcdefghij".getBytes(StandardCharsets.UTF_8);
            byte[] frame = maskedFrame(0x1, payload, 0x37FA213D);
            MemorySegment seg = MemorySegment.ofArray(frame);
            WebSocketFrameHeader header = WebSocketFrameParser.parse(seg, 0, seg.byteSize());
            assertThat(header.masked()).isTrue();
            byte[] out = new byte[payload.length];
            WebSocketFrameParser.copyPayload(seg, header, out, 0);
            assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("abcdefghij");
        }

        @Test
        @DisplayName("all three length encodings round-trip through the writer")
        void lengthFormsRoundTrip() {
            for (int length : new int[]{0, 125, 126, 1000, 65_535, 65_536}) {
                String message = "x".repeat(length);
                byte[] buffer = new byte[WebSocketFrameWriter.frameSize(length) + 8];
                MemorySegment seg = MemorySegment.ofArray(buffer);
                long end = WebSocketFrameWriter.writeText(seg, 0, message);
                WebSocketFrameHeader header = WebSocketFrameParser.parse(seg, 0, end);
                assertThat(header).as("length %d", length).isNotNull();
                assertThat(header.payloadLength()).as("length %d", length).isEqualTo(length);
                assertThat(header.masked())
                        .as("a server frame must never be masked (RFC 6455 §5.1)")
                        .isFalse();
                assertThat(header.frameEnd()).as("length %d", length).isEqualTo(end);
            }
        }

        @Test
        @DisplayName("incomplete input returns null at every truncation point, and never throws")
        void incompleteInputIsNotAFault() {
            // A reader holding half a header has read too little, which is the normal state of a
            // stream. Treating it as a fault would close connections for being slow.
            byte[] frame = maskedFrame(0x1, "abcdefgh".getBytes(StandardCharsets.UTF_8), 0x01020304);
            MemorySegment seg = MemorySegment.ofArray(frame);
            for (int limit = 0; limit < frame.length; limit++) {
                assertThat(WebSocketFrameParser.parse(seg, 0, limit))
                        .as("limit %d of %d", limit, frame.length)
                        .isNull();
            }
            assertThat(WebSocketFrameParser.parse(seg, 0, frame.length)).isNotNull();
        }

        @Test
        @DisplayName("a reserved bit with no extension negotiated is a protocol error")
        void reservedBitRefused() {
            MemorySegment seg = segmentOf((byte) 0xC1, (byte) 0x00);
            assertThatThrownBy(() -> WebSocketFrameParser.parse(seg, 0, seg.byteSize()))
                    .isInstanceOf(WebSocketProtocolException.class)
                    .satisfies(t -> assertThat(((WebSocketProtocolException) t).closeCode())
                            .isEqualTo(WebSocketCloseCode.PROTOCOL_ERROR));
        }

        @Test
        @DisplayName("a reserved opcode is a protocol error")
        void reservedOpcodeRefused() {
            MemorySegment seg = segmentOf((byte) 0x83, (byte) 0x00);
            assertThatThrownBy(() -> WebSocketFrameParser.parse(seg, 0, seg.byteSize()))
                    .isInstanceOf(WebSocketProtocolException.class);
        }

        @Test
        @DisplayName("a fragmented control frame is refused (RFC 6455 §5.5)")
        void fragmentedControlFrameRefused() {
            MemorySegment seg = segmentOf((byte) 0x09, (byte) 0x00);
            assertThatThrownBy(() -> WebSocketFrameParser.parse(seg, 0, seg.byteSize()))
                    .isInstanceOf(WebSocketProtocolException.class)
                    .hasMessageContaining("fragmented control frame");
        }

        @Test
        @DisplayName("a control frame over 125 bytes is refused before its length is even read")
        void oversizeControlFrameRefused() {
            MemorySegment seg = segmentOf((byte) 0x89, (byte) 126, (byte) 0x01, (byte) 0x00);
            assertThatThrownBy(() -> WebSocketFrameParser.parse(seg, 0, seg.byteSize()))
                    .isInstanceOf(WebSocketProtocolException.class)
                    .hasMessageContaining("125");
        }

        @Test
        @DisplayName("a 64-bit length with its high bit set is refused")
        void negativeLengthRefused() {
            // RFC 6455 §5.2 forbids it, and a negative length would make every bounds check below
            // meaningless rather than merely wrong.
            byte[] frame = new byte[10];
            frame[0] = (byte) 0x81;
            frame[1] = (byte) 127;
            frame[2] = (byte) 0x80;
            MemorySegment seg = MemorySegment.ofArray(frame);
            assertThatThrownBy(() -> WebSocketFrameParser.parse(seg, 0, seg.byteSize()))
                    .isInstanceOf(WebSocketProtocolException.class)
                    .hasMessageContaining("most significant bit");
        }
    }

    @Nested
    @DisplayName("writer")
    class Writer {

        @Test
        @DisplayName("a close frame carries the code and reason, and parses back")
        void closeFrameRoundTrips() {
            byte[] buffer = new byte[128];
            MemorySegment seg = MemorySegment.ofArray(buffer);
            long end = WebSocketFrameWriter.writeClose(seg, 0, WebSocketCloseCode.GOING_AWAY, "bye");
            WebSocketFrameHeader header = WebSocketFrameParser.parse(seg, 0, end);
            assertThat(header.opcode()).isEqualTo(WebSocketOpcode.CLOSE);
            byte[] payload = new byte[(int) header.payloadLength()];
            WebSocketFrameParser.copyPayload(seg, header, payload, 0);
            int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            assertThat(code).isEqualTo(WebSocketCloseCode.GOING_AWAY.code());
            assertThat(new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8))
                    .isEqualTo("bye");
        }

        @Test
        @DisplayName("a long reason is truncated on a character boundary, not a byte one")
        void closeReasonTruncatesOnCharacterBoundary() {
            // Cutting mid-sequence would make the close frame itself invalid UTF-8 — a protocol
            // violation committed while reporting one.
            String reason = "ą".repeat(100);
            byte[] buffer = new byte[256];
            MemorySegment seg = MemorySegment.ofArray(buffer);
            long end = WebSocketFrameWriter.writeClose(seg, 0, WebSocketCloseCode.INTERNAL_ERROR,
                    reason);
            WebSocketFrameHeader header = WebSocketFrameParser.parse(seg, 0, end);
            byte[] payload = new byte[(int) header.payloadLength()];
            WebSocketFrameParser.copyPayload(seg, header, payload, 0);
            String decoded = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
            assertThat(decoded)
                    .as("every character survived whole")
                    .matches("ą*");
            assertThat(payload.length).isLessThanOrEqualTo(125);
        }

        @Test
        @DisplayName("a code RFC 6455 forbids on the wire cannot be sent")
        void unsendableCloseCodeRefused() {
            MemorySegment seg = MemorySegment.ofArray(new byte[64]);
            assertThatThrownBy(() -> WebSocketFrameWriter.writeClose(seg, 0,
                    WebSocketCloseCode.ABNORMAL_CLOSURE, ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("assembler")
    class Assembler {

        /** A client data frame, optionally non-final, always masked as RFC 6455 §5.3 requires. */
        private byte[] dataFrame(int opcode, boolean fin, String payload) {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            byte[] frame = maskedFrame(opcode, bytes, 0x0A0B0C0D);
            if (!fin) {
                frame[0] &= 0x7F;
            }
            return frame;
        }

        @Test
        @DisplayName("a single final text frame is one message")
        void singleFrame() {
            WebSocketMessageAssembler assembler = new WebSocketMessageAssembler(1024);
            assertThat(feed(assembler, dataFrame(0x1, true, "hello"))).isEqualTo("hello");
            assertThat(assembler.fragmentInProgress()).isFalse();
        }

        @Test
        @DisplayName("continuation frames are reassembled into one message")
        void fragmentsReassemble() {
            WebSocketMessageAssembler assembler = new WebSocketMessageAssembler(1024);
            assertThat(feed(assembler, dataFrame(0x1, false, "one "))).isNull();
            assertThat(assembler.fragmentInProgress()).isTrue();
            assertThat(feed(assembler, dataFrame(0x0, false, "two "))).isNull();
            assertThat(feed(assembler, dataFrame(0x0, true, "three")))
                    .isEqualTo("one two three");
            assertThat(assembler.fragmentInProgress()).isFalse();
        }

        @Test
        @DisplayName("the size limit is enforced as fragments arrive, not only at the end")
        void limitEnforcedOnArrival() {
            // Checking only the completed message would mean buffering an unbounded one first, so a
            // peer could exhaust memory with a message it never finishes.
            WebSocketMessageAssembler assembler = new WebSocketMessageAssembler(8);
            assertThat(feed(assembler, dataFrame(0x1, false, "12345"))).isNull();
            assertThatThrownBy(() -> feed(assembler, dataFrame(0x0, false, "67890")))
                    .isInstanceOf(WebSocketProtocolException.class)
                    .satisfies(t -> assertThat(((WebSocketProtocolException) t).closeCode())
                            .isEqualTo(WebSocketCloseCode.MESSAGE_TOO_BIG));
        }

        @Test
        @DisplayName("a binary frame is refused rather than handed to a text-only handler")
        void binaryRefused() {
            WebSocketMessageAssembler assembler = new WebSocketMessageAssembler(1024);
            assertThatThrownBy(() -> feed(assembler, dataFrame(0x2, true, "bytes")))
                    .isInstanceOf(WebSocketProtocolException.class)
                    .satisfies(t -> assertThat(((WebSocketProtocolException) t).closeCode())
                            .isEqualTo(WebSocketCloseCode.UNSUPPORTED_DATA));
        }

        @Test
        @DisplayName("a continuation with nothing in progress, and a new text mid-fragment, both fail")
        void fragmentationStateIsEnforced() {
            WebSocketMessageAssembler orphan = new WebSocketMessageAssembler(1024);
            assertThatThrownBy(() -> feed(orphan, dataFrame(0x0, true, "stray")))
                    .isInstanceOf(WebSocketProtocolException.class)
                    .hasMessageContaining("no message in progress");

            WebSocketMessageAssembler interleaved = new WebSocketMessageAssembler(1024);
            feed(interleaved, dataFrame(0x1, false, "start"));
            assertThatThrownBy(() -> feed(interleaved, dataFrame(0x1, true, "interrupt")))
                    .isInstanceOf(WebSocketProtocolException.class)
                    .hasMessageContaining("still fragmented");
        }

        @Test
        @DisplayName("multi-byte UTF-8 survives, including across a fragment boundary")
        void multiByteUtf8Survives() {
            // A character split across two frames is the case a per-frame decode would corrupt.
            WebSocketMessageAssembler assembler = new WebSocketMessageAssembler(1024);
            byte[] encoded = "ąćę".getBytes(StandardCharsets.UTF_8);
            byte[] head = java.util.Arrays.copyOfRange(encoded, 0, 3);
            byte[] tail = java.util.Arrays.copyOfRange(encoded, 3, encoded.length);

            byte[] first = maskedFrame(0x1, head, 0x11223344);
            first[0] &= 0x7F;
            assertThat(feed(assembler, first)).isNull();
            assertThat(feed(assembler, maskedFrame(0x0, tail, 0x55667788))).isEqualTo("ąćę");
        }

        @Test
        @DisplayName("invalid UTF-8 closes with INVALID_PAYLOAD_DATA rather than substituting U+FFFD")
        void invalidUtf8Refused() {
            // Both directions of the classic failure: a bare continuation byte, and an overlong
            // encoding of '/' — the second is the security-relevant one, since accepting it is how
            // UTF-8 validation gets bypassed.
            for (byte[] bad : new byte[][]{{(byte) 0x80}, {(byte) 0xC0, (byte) 0xAF}}) {
                WebSocketMessageAssembler assembler = new WebSocketMessageAssembler(1024);
                byte[] frame = maskedFrame(0x1, bad, 0x0A0B0C0D);
                assertThatThrownBy(() -> feed(assembler, frame))
                        .isInstanceOf(WebSocketProtocolException.class)
                        .satisfies(t -> assertThat(((WebSocketProtocolException) t).closeCode())
                                .isEqualTo(WebSocketCloseCode.INVALID_PAYLOAD_DATA));
            }
        }
    }

    @Nested
    @DisplayName("violation reporting")
    class ViolationReporting {

        @Test
        @DisplayName("a violation is a kernel exception carrying EX-HTTP-4015 and the close code")
        void carriesTheRegisteredCode() {
            // A plain RuntimeException would satisfy every closeCode() assertion above and still
            // miss the registry, so this asserts the base type rather than only the payload.
            WebSocketMessageAssembler assembler = new WebSocketMessageAssembler(1024);
            byte[] binary = maskedFrame(0x2, "x".getBytes(StandardCharsets.UTF_8), 0x11223344);

            assertThatThrownBy(() -> feed(assembler, binary))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(t -> {
                        ExerisKernelException kernel = (ExerisKernelException) t;
                        assertThat(kernel.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4015);
                        assertThat(kernel.rawArgs())
                                .containsExactly(WebSocketCloseCode.UNSUPPORTED_DATA.code());
                        assertThat(kernel.faultOrigin()).isEqualTo(FaultOrigin.CALLER);
                    });
        }

        @Test
        @DisplayName("PROD disclosure envelopes the violation instead of surfacing its detail")
        void prodDisclosureIsOpaque() {
            // The consequence of the base type, stated as a test: ExceptionDisclosure only accepts
            // an ExerisKernelException, so a plain RuntimeException would have reached an operator
            // with its message verbatim.
            WebSocketProtocolException violation = new WebSocketProtocolException(
                    WebSocketCloseCode.PROTOCOL_ERROR, "continuation frame with no message in progress");

            assertThat(ExceptionDisclosure.discloseMessage(violation, KernelProfile.PROD))
                    .startsWith(KernelErrorCodes.EX_HTTP_4015)
                    .doesNotContain("continuation");
            assertThat(ExceptionDisclosure.discloseRawArgs(violation, KernelProfile.PROD)).isEmpty();
            assertThat(ExceptionDisclosure.discloseMessage(violation, KernelProfile.DEV))
                    .isEqualTo("continuation frame with no message in progress");
        }

        @Test
        @DisplayName("a ceiling larger than a byte[] is refused at construction, not wrapped at use")
        void oversizeCeilingRefused() {
            // Accepting it would narrow to a negative int on the first frame past 2 GiB, skip the
            // grow entirely and fail inside the copy — a config error reported as an index fault.
            assertThatThrownBy(() -> new WebSocketMessageAssembler((long) Integer.MAX_VALUE + 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("byte[]");
            assertThat(new WebSocketMessageAssembler(Integer.MAX_VALUE)).isNotNull();
        }
    }
}
