/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.core.websocket.WebSocketFrameHeader;
import eu.exeris.kernel.core.websocket.WebSocketMessageAssembler;
import eu.exeris.kernel.core.websocket.WebSocketProtocolException;
import eu.exeris.kernel.spi.exceptions.http.WebSocketClosedException;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;
import eu.exeris.kernel.spi.websocket.WebSocketExchange;
import eu.exeris.kernel.spi.websocket.WebSocketSession;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One connection's duplex conversation, driven on the virtual thread the transport handed it.
 *
 * <p><b>Both directions are inline, and that is the design rather than a simplification.</b>
 * {@code receive()} reads and parses on the calling thread; {@code send()} writes on it. There is no
 * queue in either direction, so backpressure is the socket's — ADR-043 obligation 4 extended to
 * duplex: a full egress window parks this virtual thread, and a heap queue would turn a slow reader
 * into a leak with a timer on it.
 *
 * <p><b>{@code send()} serialises across threads</b> because RFC 6455 forbids interleaving the
 * frames of two messages on one connection. The consequence is worth stating rather than leaving to
 * be found under load: a slow peer blocks <em>every</em> sender on that connection, not just the one
 * that filled the window.
 *
 * <p>The two directions end differently on purpose. {@code receive()} returns {@code null} at close,
 * because that is the ordinary end of a loop and a handler should fall out of it; {@code send()}
 * throws, because a handler that had something to say and could not has to see it.
 */
final class CommunityWebSocketExchange implements WebSocketExchange, AutoCloseable {

    private final TransportStream stream;
    private final WebSocketSession session;
    private final WebSocketMessageAssembler assembler;
    private final CommunityWebSocketFrameStream frames;
    private final long openedAtNanos = System.nanoTime();

    private final CommunityWebSocketEgress egress;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean released = new AtomicBoolean(false);

    private long messagesSent;
    private int observedCloseCode;

    /* default */ CommunityWebSocketExchange(TransportStream stream, WebSocketSession session,
                                             MemoryAllocator allocator, long maxMessageBytes) {
        this.stream = Objects.requireNonNull(stream, "stream must not be null");
        this.session = Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
        this.assembler = new WebSocketMessageAssembler(maxMessageBytes);
        this.frames = new CommunityWebSocketFrameStream(this.stream, allocator, maxMessageBytes);
        this.egress = new CommunityWebSocketEgress(this.stream, allocator);
    }

    @Override
    public WebSocketSession session() {
        return session;
    }

    @Override
    public String receive() {
        while (true) {
            if (closed.get()) {
                return null;
            }
            try {
                String message = drainBufferedFrames();
                if (message != null) {
                    return message;
                }
            } catch (WebSocketProtocolException violation) {
                // The peer broke the protocol: close with the code the violation maps to and end the
                // handler's loop. The handler is not told which rule broke — it has no way to act on
                // it, and the frame that broke it is the input most likely to be hostile.
                closeOnViolation(violation.closeCode());
                return null;
            }
            if (frames.overCeiling()) {
                // The buffer already holds more than any acceptable frame, and the parser still
                // wants more: a declared length past the ceiling. Refuse rather than grow to meet it.
                closeOnViolation(WebSocketCloseCode.MESSAGE_TOO_BIG);
                return null;
            }
            if (!frames.fill()) {
                markClosed();
                return null;
            }
        }
    }

    /**
     * @return a completed message, or {@code null} when the buffer holds no further whole frame
     */
    private String drainBufferedFrames() {
        while (true) {
            WebSocketFrameHeader header = frames.peek();
            if (header == null) {
                return null;
            }
            MemorySegment segment = frames.segment();
            String message = null;
            if (header.opcode().isControl()) {
                CommunityWebSocketControlFrames.Reaction reaction =
                        CommunityWebSocketControlFrames.handle(segment, header, egress);
                if (reaction.closing()) {
                    // Ordered deliberately: the connection stops being writable BEFORE the echo goes
                    // out. CI caught the reverse -- the client observed the echoed close, called
                    // send() and got no throw, because `closed` had not flipped yet. A machine under
                    // load opens that window; a fast one hides it. Announcing a close and only then
                    // refusing to write is backwards, and the echo is now sent from here so the
                    // ordering is visible in one place.
                    observedCloseCode = reaction.closeCode();
                    markClosed();
                    egress.sendCloseOnce(reaction.echoCode(), "");
                }
            } else {
                message = assembler.accept(segment, header);
            }
            frames.consume(header);
            if (closed.get()) {
                return null;
            }
            if (message != null) {
                return message;
            }
        }
    }





    @Override
    public void send(String message) {
        Objects.requireNonNull(message, "message must not be null");
        if (closed.get()) {
            throw WebSocketClosedException.notWritable(ageMillis(), messagesSent, observedCloseCode);
        }
        egress.writeText(message);
        messagesSent++;
    }

    @Override
    public void close() {
        close(WebSocketCloseCode.NORMAL_CLOSURE, "");
    }

    /**
     * Releases the connection's off-heap buffers, at most once.
     *
     * <p>A handler that calls {@code close()} itself and then falls out of the engine's
     * try-with-resources reaches here twice. That is <em>not</em> a double-free: the SPI requires
     * {@code LoanedBuffer.close()} to be idempotent and {@code AbstractLoanedBuffer} honours it with
     * an explicit {@code prev <= 0} early return, which {@code AbstractLoanedBufferTest} and
     * {@code CommunityLoanedBufferTest} have both been pinning all along. An earlier revision of
     * this comment claimed the opposite, repeating a rule in CONTRIBUTING.md that contradicted the
     * contract, the code and those two tests at once; that rule is corrected in the same change.
     *
     * <p>The CAS stays because this exchange holds buffers from whatever {@code MemoryAllocator} was
     * bound, and a driver's own implementation is the one thing here that cannot be read from this
     * repository. Guarding costs one boolean; the failure it would otherwise permit is a
     * use-after-free.
     */
    private void releaseBuffers() {
        if (released.compareAndSet(false, true)) {
            frames.close();
            egress.close();
        }
    }

    @Override
    public void close(WebSocketCloseCode code, String reason) {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        markClosed();
        egress.sendCloseOnce(code, reason);
        stream.close();
        releaseBuffers();
    }

    private void closeOnViolation(WebSocketCloseCode code) {
        markClosed();
        egress.sendCloseOnce(code, "");
        stream.close();
        releaseBuffers();
    }




    private void markClosed() {
        closed.set(true);
        assembler.reset();
    }



    private long ageMillis() {
        return (System.nanoTime() - openedAtNanos) / 1_000_000L;
    }


}
