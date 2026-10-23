/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.transport;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * TCK: Abstract base for {@link TransportStream} I/O semantics verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code read()} and {@code write()} operate on {@link MemorySegment} slices</li>
 *   <li>{@code queueWrite(LoanedBuffer, length)} transfers buffer ownership <b>on normal return</b> —
 *       the caller MUST NOT close the buffer after a successful call, and MUST close it after a
 *       thrown one</li>
 *   <li>{@code close()} is idempotent</li>
 *   <li>{@code connection().close()} closes the stream even after the stream has read the peer's
 *       end of stream: {@code write()} and {@code queueWrite()} are then refused with
 *       {@link TransportStreamClosedException}</li>
 *   <li>a read or write after this endpoint's own {@code close()} or {@code reset()} throws
 *       {@link TransportStreamClosedException} with {@code EX-NET-4009}; the peer-closed code
 *       {@code EX-NET-4008} is pinned for a send after the peer's {@code reset()} only, because
 *       whether a peer's graceful end of stream closes the local side is transport semantics</li>
 *   <li>a send that fails because the peer reset the stream throws
 *       {@link TransportStreamClosedException} with {@code EX-NET-4008} as its first failure, never
 *       a {@link TransportException}</li>
 *   <li>a thread blocked in {@code read()} is woken when its own stream ends: by this endpoint's
 *       {@code close()}, even while queued writes are still draining, and by the peer's
 *       {@code reset()}; it then returns end of stream or throws the closed-stream signal, and a
 *       write after the peer's reset is refused</li>
 *   <li>{@code queueWrite()} returns without waiting for a peer that does not read, and releases each
 *       loan once its bytes leave the stream (or the stream ends), and not before</li>
 *   <li>a thread blocked in {@code write()} is woken by the stream's end</li>
 *   <li>{@code streamId()} returns a non-negative identifier</li>
 *   <li>{@code connection()} returns non-null parent</li>
 * </ul>
 *
 * <h2>Zero-Copy Rule</h2>
 * <p>{@code queueWrite(LoanedBuffer)} MUST either consume the buffer (decrement refCount
 * or take ownership) or throw {@code TransportException} — preventing off-heap leaks.
 *
 * @since 0.5
 */
public abstract class AbstractTransportStreamTck {

    /**
     * Creates a pair of connected streams for loopback I/O testing.
     * The writer stream sends data; the reader stream receives it.
     *
     * @return a connected stream pair
     */
    protected abstract StreamPair createStreamPair();

    /**
     * Provides a {@link MemoryAllocator} for buffer creation in tests.
     *
     * @return an allocator the suite uses for every buffer it creates
     */
    protected abstract MemoryAllocator createAllocator();

    /**
     * Declares whether this binding implements TRUE abortive {@link TransportStream#reset(long)}
     * semantics (reset as an <em>abort</em>) rather than the SPI-default best-effort graceful
     * {@link TransportStream#close()}.
     *
     * <p>When {@code false} (the default), the abandon-vs-drain peer discriminator
     * ({@code resetAbandonsQueuedWriteAtPeer}) is SKIPPED via a JUnit assumption: a binding that
     * degrades reset to graceful close is <em>visibly exempt</em> (reported skipped) instead of
     * silently passing abort semantics it does not provide — no binding may silently claim abort
     * fidelity it does not implement.
     *
     * <p>When {@code true}, the binding asserts that data genuinely queued but unflushed at
     * {@code reset()} time is NOT delivered to the peer. Override to {@code true} only for a
     * transport with a distinct abort primitive (SO_LINGER-0 abortive TCP close, QUIC RESET_STREAM),
     * and pair it with {@link #holdOutboundEgress(TransportStream)}.
     *
     * @return {@code true} if this binding provides true abortive reset; {@code false} by default
     */
    protected boolean expectsTrueReset() {
        return false;
    }

    /**
     * Arranges for a subsequent {@link TransportStream#queueWrite} on {@code writer} to leave the
     * payload GENUINELY PENDING (unflushed) rather than flushing it synchronously on the calling
     * thread, then deterministically completes an already-requested {@link TransportStream#reset}
     * as an abandon when the returned handle is closed.
     *
     * <p>Bindings whose {@code queueWrite} opportunistically flushes on the caller thread MUST
     * override this so the abandon-vs-drain discriminator is deterministic on a constrained CI box;
     * a volume-based socket-buffer fill is flaky and platform-dependent. The terminal-wake cases
     * hold egress the same way, so the writes they queue are still draining when the stream ends;
     * for them closing the handle also lets an already-requested {@code close()} finish by draining.
     * The default returns a no-op handle, so a binding that does not override it relies on
     * {@link #fillOutboundUntilPending(TransportStream)} to reach a pending state.
     *
     * @param writer the stream that will receive a queued write to be abandoned by {@code reset}
     * @return a handle whose {@code close()} releases the hold; never {@code null}
     */
    protected AutoCloseable holdOutboundEgress(TransportStream writer) {
        return () -> { };
    }

    /**
     * Declares whether {@link TransportStream#write} blocks at a bounded outbound queue.
     *
     * <p>When {@code false} (the default) the {@link WriteBackpressure} cases are SKIPPED via a JUnit
     * assumption, so a binding without a bounded outbound is visibly exempt rather than silently
     * passing. When {@code true}, the binding configures a bound small enough that 64 MiB of writes
     * exceed the bound plus its kernel buffers, and pairs it with
     * {@link #holdOutboundEgress(TransportStream)} when its peer would otherwise absorb the writes.
     *
     * @return {@code true} if {@code write()} blocks at a bounded outbound; {@code false} by default
     */
    protected boolean supportsWriteBackpressure() {
        return false;
    }

    /**
     * Leaves {@code stream} with outbound data it has accepted but not yet handed to the peer, by
     * queueing fixed-size loans until {@link TransportStream#hasPendingData()} reports pending.
     *
     * <p>The cases that need "writes still draining" call this inside a
     * {@link #holdOutboundEgress(TransportStream)} hold, so the first loan is already pending. For a
     * binding without a hold the default ends at the first stall the transport reports, and fails
     * the case if the transport swallows a bounded volume without ever reporting pending data; a
     * binding whose {@code queueWrite} never reports pending data overrides this with its own way
     * to reach that state.
     *
     * @param stream the stream to load; its peer must not be reading
     */
    protected void fillOutboundUntilPending(TransportStream stream) {
        int chunk = 64 * 1024;
        long volumeCap = 1L << 30;
        long queued = 0;
        while (!stream.hasPendingData()) {
            if (queued >= volumeCap) {
                fail("outbound never reported pending data after " + queued + " bytes with the peer not reading");
            }
            LoanedBuffer loan = allocator.allocateNetwork(chunk);
            loan.setSize(chunk);
            try {
                stream.queueWrite(loan, chunk);
            } catch (RuntimeException refused) {
                // a throwing queueWrite leaves the loan with the caller
                loan.close();
                throw refused;
            }
            queued += chunk;
        }
    }

    /**
     * Body of {@link ResetContract#resetAbandonsQueuedWriteAtPeer}, extracted (without the
     * {@link #expectsTrueReset()} assumption) so the TCK's own meta-test can drive it against a
     * fake binding and prove the discriminator is non-vacuous — a binding that drains queued data
     * on {@code reset()} MUST make this throw {@link AssertionError}. Package-private, test-only.
     */
    final void runResetAbandonsAtPeerDiscriminator() throws Exception {
        TransportStream writer = streams.writer();
        TransportStream reader = streams.reader();

        try (var _ = holdOutboundEgress(writer)) {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0x7E);
            writer.queueWrite(buf, 1);   // ownership transferred; egress held → stays pending

            assertThat(writer.hasPendingData())
                    .as("precondition: with egress held the queued write must be genuinely "
                            + "pending — if false, holdOutboundEgress() did not defeat the "
                            + "synchronous flush")
                    .isTrue();

            writer.reset(0L);            // abort: abandon the pending byte, engage stream reset
        }   // hold.close() completes the abort on the slot owner before any reactor can flush

        // The peer MUST NOT observe the abandoned 0x7E — only EOF (-1) or a reset error.
        try (LoanedBuffer recvBuf = allocator.allocate(AllocationHint.MICRO)) {
            MemorySegment recvSeg = recvBuf.segment();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                int bytesRead;
                try {
                    bytesRead = reader.read(recvSeg, 1);
                } catch (RuntimeException resetObserved) {
                    return;   // RST surfaced as TransportException/IllegalStateException — abandoned. PASS.
                }
                if (bytesRead == -1) {
                    return;   // EOF before any payload byte — abandoned. PASS.
                }
                if (bytesRead >= 1) {
                    byte got = recvSeg.get(ValueLayout.JAVA_BYTE, 0);
                    fail("reset() must abandon the queued write, but the peer received payload "
                            + "byte 0x" + Integer.toHexString(got & 0xFF) + " — this binding "
                            + "drains on reset() and must not declare expectsTrueReset()=true");
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            fail("peer neither received the abandoned byte nor observed EOF/RST within 3s "
                    + "(reset() did not engage, or the timing window is too tight)");
        }
    }

    /**
     * Body of {@link QueueWriteDoesNotWait}, extracted so the TCK's own meta-test can drive it against
     * a fake binding whose {@code queueWrite} waits for the peer: such a binding MUST make this throw
     * {@link AssertionError}. Package-private, test-only.
     */
    final void runQueueWriteDoesNotWaitDiscriminator() throws Exception {
        runQueuedBurst(false);
    }

    /**
     * Queues {@link #BURST_LOANS} pattern loans on the writer while its peer does not take them.
     *
     * @param retentionCase when {@code true}, asserts that no loan is released while its bytes are
     *                      still queued and stops there; when {@code false}, asserts that the queueing
     *                      thread returns, then lets the peer drain and asserts every byte arrives in
     *                      order and every loan is released
     */
    private void runQueuedBurst(boolean retentionCase) throws Exception {
        TransportStream writer = streams.writer();
        CountDownLatch released = new CountDownLatch(BURST_LOANS);
        AtomicReference<Throwable> queueFailure = new AtomicReference<>();
        try (var _ = holdOutboundEgress(writer)) {
            Thread queuer = Thread.ofVirtual().start(() -> {
                try {
                    for (int chunk = 0; chunk < BURST_LOANS; chunk++) {
                        queuePatternLoan(writer, chunk, released);
                    }
                } catch (RuntimeException | Error failure) {
                    queueFailure.set(failure);
                }
            });
            queuer.join(TimeUnit.SECONDS.toMillis(QUEUE_SECONDS));
            assertThat(queuer.isAlive())
                    .as("queueWrite returns without waiting for a peer that does not read")
                    .isFalse();
            assertThat(queueFailure.get()).as("queueWrite does not fail while the peer does not read").isNull();
            assertThat(writer.hasPendingData())
                    .as("precondition: the peer has not taken the queued bytes, so they are still pending — "
                            + "if false, neither the bound nor holdOutboundEgress() held them back and the "
                            + "case proves nothing")
                    .isTrue();
            if (retentionCase) {
                assertThat(released.getCount())
                        .as("a loan is not released while its bytes are still queued")
                        .isPositive();
                return;
            }
        }
        assertThat(drainAndVerify(streams.reader(), BURST_LOANS, DRAIN_SECONDS))
                .as("the peer receives every queued byte, in order")
                .isNull();
        assertThat(released.await(RELEASE_SECONDS, TimeUnit.SECONDS))
                .as("every loan is released once the peer has taken its bytes")
                .isTrue();
    }

    private void queuePatternLoan(TransportStream stream, int chunk, CountDownLatch released) {
        LoanedBuffer loan = allocator.allocateNetwork(BURST_CHUNK);
        fillPattern(loan.segment(), chunk);
        loan.setSize(BURST_CHUNK);
        loan.addCloseAction(released::countDown);
        try {
            stream.queueWrite(loan, BURST_CHUNK);
        } catch (RuntimeException refused) {
            // a throwing queueWrite leaves the loan with the caller
            loan.close();
            throw refused;
        }
    }

    private static byte patternByte(int chunk, int offset) {
        return (byte) (chunk * 131 + offset);
    }

    private static void fillPattern(MemorySegment segment, int chunk) {
        for (int offset = 0; offset < BURST_CHUNK; offset++) {
            segment.set(ValueLayout.JAVA_BYTE, offset, patternByte(chunk, offset));
        }
    }

    /**
     * Reads {@code chunks} pattern chunks from {@code peer} on a virtual thread and checks every byte.
     *
     * @return {@code null} when all arrived in order in time, otherwise what went wrong
     */
    private String drainAndVerify(TransportStream peer, int chunks, long seconds) throws InterruptedException {
        AtomicReference<String> problem = new AtomicReference<>();
        Thread drainer = Thread.ofVirtual().start(() -> {
            try (LoanedBuffer sink = allocator.allocateNetwork(BURST_CHUNK)) {
                long expected = (long) chunks * BURST_CHUNK;
                long total = 0;
                while (total < expected && problem.get() == null) {
                    int read = peer.read(sink.segment(), (int) Math.min(BURST_CHUNK, expected - total));
                    if (read < 0) {
                        problem.set("the stream ended after " + total + " of " + expected + " bytes");
                    } else {
                        problem.compareAndSet(null, firstMismatch(sink.segment(), read, total));
                        total += read;
                    }
                }
            } catch (RuntimeException failure) {
                problem.set("the read failed: " + failure);
            }
        });
        drainer.join(TimeUnit.SECONDS.toMillis(seconds));
        return drainer.isAlive() ? "the drain did not finish within " + seconds + "s" : problem.get();
    }

    private static String firstMismatch(MemorySegment segment, int length, long globalOffset) {
        for (int i = 0; i < length; i++) {
            long global = globalOffset + i;
            byte expected = patternByte((int) (global / BURST_CHUNK), (int) (global % BURST_CHUNK));
            if (segment.get(ValueLayout.JAVA_BYTE, i) != expected) {
                return "byte " + global + " is out of order or corrupt";
            }
        }
        return null;
    }

    /**
     * Stream pair for loopback testing.
     *
     * @param writer the stream that sends data
     * @param reader the stream that receives it
     */
    public record StreamPair(TransportStream writer, TransportStream reader) {
    }

    private static final int BURST_CHUNK = 64 * 1024;
    private static final int BURST_LOANS = 256;
    private static final long QUEUE_SECONDS = 5L;
    private static final long RELEASE_SECONDS = 10L;
    private static final long DRAIN_SECONDS = 30L;

    private StreamPair streams;
    private MemoryAllocator allocator;

    /**
     * Creates the contract; subclasses supply the connected pair and allocator via
     * {@link #createStreamPair()} and {@link #createAllocator()}.
     *
     * <p>The {@code streams} and {@code allocator} fields start unset — {@link #setUp()}
     * populates both before each test.
     */
    public AbstractTransportStreamTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @BeforeEach
    final void setUp() {
        allocator = createAllocator();
        streams = createStreamPair();
    }

    @AfterEach
    final void tearDown() {
        streams.writer().close();
        streams.reader().close();
        allocator.close();
    }

    // =========================================================================
    // Identity
    // =========================================================================

    @Nested
    @DisplayName("Stream identity")
    class Identity {

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise stream identity.
         */
        Identity() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("streamId() returns non-negative value")
        void streamIdNonNegative() {
            assertThat(streams.writer().streamId()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("connection() returns non-null parent")
        void connectionNonNull() {
            assertThat(streams.writer().connection()).isNotNull();
        }

        @Test
        @DisplayName("isBidirectional() returns a boolean (no NPE)")
        void isBidirectional() {
            assertThatCode(() -> streams.writer().isBidirectional()).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // write() + read() round-trip
    // =========================================================================

    @Nested
    @DisplayName("write() + read() round-trip")
    class WriteReadRoundTrip {

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise the write/read zero-copy round trip.
         */
        WriteReadRoundTrip() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("write() followed by read() returns identical data (zero-copy)")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void roundTrip() {
            try (LoanedBuffer sendBuf = allocator.allocate(AllocationHint.SMALL)) {
                MemorySegment seg = sendBuf.segment();
                seg.set(ValueLayout.JAVA_LONG, 0, 0xDEADCAFE_BABE1234L);
                int length = Long.BYTES;

                streams.writer().write(seg, length);

                try (LoanedBuffer recvBuf = allocator.allocate(AllocationHint.SMALL)) {
                    MemorySegment recvSeg = recvBuf.segment();
                    int bytesRead = streams.reader().read(recvSeg, length);
                    assertThat(bytesRead).isEqualTo(length);
                    assertThat(recvSeg.get(ValueLayout.JAVA_LONG, 0))
                            .isEqualTo(0xDEADCAFE_BABE1234L);
                }
            }
        }
    }

    // =========================================================================
    // read() bounds — non-positive maxBytes contract
    // =========================================================================

    @Nested
    @DisplayName("read() bounds")
    class ReadBounds {

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise {@code read()} argument bounds.
         */
        ReadBounds() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("read(target, 0) and read(target, -1) return 0 without consuming pending data")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void nonPositiveMaxBytesIsNoOp() {
            // Arrange: put a payload in flight so the reader genuinely has data pending.
            try (LoanedBuffer sendBuf = allocator.allocate(AllocationHint.SMALL)) {
                MemorySegment seg = sendBuf.segment();
                seg.set(ValueLayout.JAVA_LONG, 0, 0xFEEDFACE_CAFEBEEFL);
                int length = Long.BYTES;
                streams.writer().write(seg, length);

                TransportStream reader = streams.reader();

                try (LoanedBuffer recvBuf = allocator.allocate(AllocationHint.SMALL)) {
                    MemorySegment recvSeg = recvBuf.segment();

                    // Act + Assert: non-positive maxBytes is a no-op (no I/O, no throw, returns 0).
                    assertThat(reader.read(recvSeg, 0))
                            .as("read(target, 0) MUST return 0 immediately (no-op contract)")
                            .isZero();
                    assertThat(reader.read(recvSeg, -1))
                            .as("read(target, -1) MUST return 0 immediately (no-op contract)")
                            .isZero();

                    // Assert: the stream remains readable and the pending payload was not consumed.
                    int bytesRead = reader.read(recvSeg, length);
                    assertThat(bytesRead)
                            .as("a no-op read MUST NOT consume pending data — the payload is still readable")
                            .isEqualTo(length);
                    assertThat(recvSeg.get(ValueLayout.JAVA_LONG, 0))
                            .isEqualTo(0xFEEDFACE_CAFEBEEFL);
                }
            }
        }

        @Test
        @DisplayName("read(target, 0) on a closed stream returns 0 (no-op takes precedence over closed-state)")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void nonPositiveMaxBytesOnClosedStreamReturnsZero() {
            TransportStream reader = streams.reader();
            reader.close();
            try (LoanedBuffer recvBuf = allocator.allocate(AllocationHint.MICRO)) {
                assertThat(reader.read(recvBuf.segment(), 0))
                        .as("read(target, 0) on a closed stream MUST return 0, not throw — "
                                + "the no-op contract takes precedence over the closed-state rejection")
                        .isZero();
            }
        }
    }

    // =========================================================================
    // queueWrite() ownership transfer
    // =========================================================================

    @Nested
    @DisplayName("queueWrite() ownership transfer")
    class QueueWriteOwnership {

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise buffer ownership transfer on
         * {@code queueWrite()}.
         */
        QueueWriteOwnership() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("queueWrite() takes ownership — buffer refCount decremented or transferred")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void ownershipTransfer() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0xAB);

            streams.writer().queueWrite(buf, 1);

            try (LoanedBuffer recvBuf = allocator.allocate(AllocationHint.MICRO)) {
                MemorySegment recvSeg = recvBuf.segment();
                int bytesRead = streams.reader().read(recvSeg, 1);
                assertThat(bytesRead)
                        .as("queueWrite() MUST result in at least 1 byte delivered to the peer")
                        .isGreaterThanOrEqualTo(1);
                byte receivedByte = recvSeg.get(ValueLayout.JAVA_BYTE, 0);
                assertThat(receivedByte)
                        .as("Delivered byte MUST equal the sentinel 0xAB — transport must not " +
                                "corrupt or drop the owned buffer's content")
                        .isEqualTo((byte) 0xAB);
            }

            assertThat(streams.writer().streamId())
                    .as("Stream MUST remain open (non-negative streamId) after queueWrite() — "
                            + "ownership transfer must not corrupt the stream state")
                    .isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("a rejected length leaves the buffer with the caller")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void rejectedLengthLeavesBufferWithCaller() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                int before = buf.refCount();

                assertThatThrownBy(() -> streams.writer().queueWrite(buf, -1))
                        .isInstanceOf(IllegalArgumentException.class);

                // The other half of the transfer, and nothing executed it until now. This suite
                // asserted the successful return above and stated "caller MUST NOT close the buffer
                // after the call" without qualifying it — so a driver reading the contract before
                // writing its error paths would close on a throw, and the caller doing what the SPI
                // tells it then double-frees.
                assertThat(buf.refCount())
                        .as("ownership transfers on normal return only; after a throw the buffer is "
                            + "still the caller's, so its refcount must be untouched")
                        .isEqualTo(before);
                assertThat(buf.isAlive())
                        .as("and still usable — the caller has to be able to close it")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a closed stream leaves the buffer with the caller")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void closedStreamLeavesBufferWithCaller() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                buf.setSize(Long.BYTES);
                int before = buf.refCount();

                TransportStream writer = streams.writer();
                writer.close();

                // The SPI names the closed-stream signal as a caller-owns case explicitly, so both
                // throwing branches of one method have to agree. One closing and the other not is
                // the same defect wearing a smaller hat.
                assertThatThrownBy(() -> writer.queueWrite(buf, Long.BYTES))
                        .isInstanceOfSatisfying(TransportStreamClosedException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4009));

                assertThat(buf.refCount()).isEqualTo(before);
                assertThat(buf.isAlive()).isTrue();
            }
        }
    }

    // =========================================================================
    // close() contract
    // =========================================================================

    @Nested
    @DisplayName("close() contract")
    class CloseContract {

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise the {@code close()} contract.
         */
        CloseContract() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("close() is idempotent")
        void closeIsIdempotent() {
            streams.writer().close();
            assertThatCode(() -> streams.writer().close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("write() after close() throws the closed-stream signal with EX-NET-4009")
        void writeAfterCloseThrows() {
            var writer = streams.writer();
            writer.close();
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                MemorySegment seg = buf.segment();
                assertThatThrownBy(() -> writer.write(seg, 1))
                        .isInstanceOfSatisfying(TransportStreamClosedException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4009));
            }
        }

        @Test
        @DisplayName("connection().close() after the peer's close closes the stream")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void connectionCloseAfterRemoteCloseClosesStream() {
            TransportStream reader = streams.reader();
            TransportConnection connection = reader.connection();

            // The peer's close reaches the reader as end of stream. A transport may record that
            // on the connection too, but such a record releases nothing the stream holds: the
            // connection's own close() still has to close its child streams.
            streams.writer().close();
            int endOfStream = 0;
            boolean readerOpenAfterPeerClose = true;
            try (LoanedBuffer sink = allocator.allocate(AllocationHint.MICRO)) {
                endOfStream = reader.read(sink.segment(), 1);
            } catch (TransportStreamClosedException _) {
                readerOpenAfterPeerClose = false;
            }
            Assumptions.assumeTrue(readerOpenAfterPeerClose,
                    "the binding's writer.close() also closes the reader stream locally, so no open "
                            + "stream is left for connection().close() to release");
            assertThat(endOfStream)
                    .as("precondition: the reader observes the peer's close as end of stream")
                    .isEqualTo(-1);

            connection.close();

            assertThat(connection.isOpen())
                    .as("a closed connection is not open")
                    .isFalse();
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                MemorySegment seg = buf.segment();
                assertThatExceptionOfType(TransportStreamClosedException.class)
                        .as("connection().close() closes its streams, so a write() on one is refused")
                        .isThrownBy(() -> reader.write(seg, 1))
                        .satisfies(e -> assertThat(e.errorCode())
                                .isIn(KernelErrorCodes.EX_NET_4008, KernelErrorCodes.EX_NET_4009));
                assertThatExceptionOfType(TransportStreamClosedException.class)
                        .as("and so is a queueWrite(), which leaves the buffer with the caller")
                        .isThrownBy(() -> reader.queueWrite(buf, 1))
                        .satisfies(e -> assertThat(e.errorCode())
                                .isIn(KernelErrorCodes.EX_NET_4008, KernelErrorCodes.EX_NET_4009));
                assertThat(buf.isAlive())
                        .as("the caller still owns the buffer after the throw")
                        .isTrue();
            }
        }
    }

    // =========================================================================
    // reset() abortive-termination contract (TransportStream#reset)
    // =========================================================================

    @Nested
    @DisplayName("reset() abortive termination")
    class ResetContract {

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise the {@code reset()} contract.
         */
        ResetContract() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("reset() clears the pending-data flag (teardown liveness)")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void resetAbandonsPendingData() {
            TransportStream writer = streams.writer();
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0x7E);
            writer.queueWrite(buf, 1);

            writer.reset(0L);

            // The owning carrier reactor may complete the discard asynchronously, so poll.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (writer.hasPendingData() && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            assertThat(writer.hasPendingData())
                    .as("reset() MUST abandon queued writes (no drain wait) — hasPendingData() "
                            + "must clear, otherwise close()/teardown hangs")
                    .isFalse();
        }

        @Test
        @DisplayName("reset() abandons a genuinely-pending queued write — peer does not receive it")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void resetAbandonsQueuedWriteAtPeer() throws Exception {
            Assumptions.assumeTrue(expectsTrueReset(),
                    "binding does not claim true abortive reset (reset() degrades to graceful close); "
                            + "abandon-vs-drain peer discriminator skipped — see issue #180");
            runResetAbandonsAtPeerDiscriminator();
        }

        @Test
        @DisplayName("reset() is idempotent and composes with close()")
        void resetIsIdempotentAndComposesWithClose() {
            TransportStream writer = streams.writer();
            writer.reset(0L);
            assertThatCode(() -> writer.reset(7L)).doesNotThrowAnyException();
            assertThatCode(writer::close).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("read() after reset() is rejected")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void readAfterResetThrows() {
            TransportStream writer = streams.writer();
            // No queued write here: with an idle outbound consumer, reset() terminates the stream
            // synchronously (closed == true), so a subsequent read() observes the closed state.
            writer.reset(0L);
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                MemorySegment seg = buf.segment();
                assertThatThrownBy(() -> writer.read(seg, 1))
                        .as("read() on a stream that has been reset MUST be rejected")
                        .isInstanceOfSatisfying(TransportStreamClosedException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4009));
            }
        }

        @Test
        @DisplayName("write() after reset() is rejected")
        void writeAfterResetThrows() {
            TransportStream writer = streams.writer();
            writer.reset(0L);
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                MemorySegment seg = buf.segment();
                assertThatThrownBy(() -> writer.write(seg, 1))
                        .isInstanceOfSatisfying(TransportStreamClosedException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4009));
            }
        }

        @Test
        @DisplayName("queueWrite() after reset() is rejected, and the buffer stays with the caller")
        void queueWriteAfterResetThrows() {
            TransportStream writer = streams.writer();
            writer.reset(0L);
            // try-with-resources: after the throw the buffer is still the caller's, so this test
            // must close it itself — leaving it open here would leak under LeakDetectionMode.PARANOID.
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                assertThatThrownBy(() -> writer.queueWrite(buf, 1))
                        .isInstanceOfSatisfying(TransportStreamClosedException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4009));
                assertThat(buf.isAlive())
                        .as("the caller still owns it after the throw, so it must still be closeable")
                        .isTrue();
            }
        }
    }

    // =========================================================================
    // Remote close — read() unblocking
    // =========================================================================

    @Nested
    @DisplayName("Remote close unblocks reader")
    class RemoteCloseUnblocksReader {

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise a blocked reader unblocking on remote close.
         */
        RemoteCloseUnblocksReader() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("read() returns -1 within 500ms of remote close signal")
        @Timeout(value = 2, unit = TimeUnit.SECONDS)
        void remoteClosedUnblocksReader() throws InterruptedException {
            // Arrange: start a VT that blocks in read()
            try (LoanedBuffer sink = allocator.allocate(AllocationHint.SMALL)) {
                AtomicLong readReturnedAt = new AtomicLong(-1L);
                AtomicInteger readResult = new AtomicInteger(Integer.MIN_VALUE);

                Thread reader = Thread.ofVirtual().start(() -> {
                    readResult.set(streams.reader().read(sink.segment(), (int) sink.segment().byteSize()));
                    readReturnedAt.set(System.nanoTime());
                });

                // Wait for the VT to reach a waiting state (parked or blocked in read)
                long spinDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (reader.getState() == Thread.State.RUNNABLE
                        && System.nanoTime() < spinDeadline) {
                    Thread.onSpinWait();
                }

                // Act: close the writer side to signal remote close to the reader
                long signalAt = System.nanoTime();
                streams.writer().close();

                reader.join(500);

                // Assert: read() returned -1 (remote-close EOF)
                assertThat(readResult.get())
                        .as("read() should return -1 on remote close")
                        .isEqualTo(-1);

                // Assert: unblocked within 500ms
                long returnedAt = readReturnedAt.get();
                assertThat(returnedAt)
                        .as("read() should have returned after remote close (join backstop: 500ms)")
                        .isGreaterThanOrEqualTo(0L);

                long durationMs = TimeUnit.NANOSECONDS.toMillis(returnedAt - signalAt);
                assertThat(durationMs)
                        .as("read() should unblock within 500ms of remote close — got %dms", durationMs)
                        .isLessThanOrEqualTo(500L);
            }
        }
    }

    // =========================================================================
    // Terminal transitions wake blocked readers
    // =========================================================================

    @Nested
    @DisplayName("Terminal transitions wake a blocked reader")
    class TerminalWake {

        private static final long WAKE_SECONDS = 2L;
        private static final long DRAIN_SECONDS = 10L;
        private static final int MAX_WRITE_ATTEMPTS = 100_000;

        private BlockedRead startBlockedRead(TransportStream stream) {
            BlockedRead blocked = new BlockedRead(stream);
            blocked.thread.start();
            return blocked;
        }

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise terminal wakeups of a blocked reader.
         */
        TerminalWake() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("close() while queued writes are still draining wakes a blocked reader with EX-NET-4009")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void closeWithQueuedWritesWakesBlockedReader() throws Exception {
            TransportStream stream = streams.writer();
            BlockedRead blocked = startBlockedRead(stream);
            blocked.awaitParked();
            try (var _ = holdOutboundEgress(stream)) {
                fillOutboundUntilPending(stream);
                assertThat(stream.hasPendingData())
                        .as("precondition: the queued writes are still draining when close() is called")
                        .isTrue();

                stream.close();

                assertThat(blocked.outcome(WAKE_SECONDS))
                        .as("close() ends the stream for its own reader even while queued writes still "
                                + "drain: the reader must be woken and told so")
                        .isInstanceOfSatisfying(TransportStreamClosedException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4009));
            }
            assertThat(drainedToEnd(streams.reader()))
                    .as("the close still delivers what was queued, then ends the stream for the peer")
                    .isTrue();
        }

        @Test
        @DisplayName("close() with nothing queued wakes a blocked reader with EX-NET-4009")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void closeWithNothingQueuedWakesBlockedReader() throws Exception {
            TransportStream stream = streams.writer();
            BlockedRead blocked = startBlockedRead(stream);
            blocked.awaitParked();

            stream.close();

            assertThat(blocked.outcome(WAKE_SECONDS))
                    .isInstanceOfSatisfying(TransportStreamClosedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4009));
        }

        @Test
        @DisplayName("the peer's reset() releases a blocked reader, and later writes are refused")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void peerResetReleasesBlockedReaderAndRefusesLaterWrites() throws Exception {
            TransportStream stream = streams.writer();
            BlockedRead blocked = startBlockedRead(stream);
            blocked.awaitParked();

            streams.reader().reset(0L);

            Object outcome = blocked.outcome(WAKE_SECONDS);
            assertThat(outcome)
                    .as("a reader on a stream whose peer reset it returns end of stream or throws "
                            + "the closed-stream signal; it never stays blocked")
                    .satisfiesAnyOf(
                            o -> assertThat(o).isEqualTo(-1),
                            o -> assertThat(o).isInstanceOf(TransportStreamClosedException.class));
            assertThat(writeRefusedWithinDeadline(stream))
                    .as("the stream is over, so a write is refused with the closed-stream signal")
                    .isTrue();
        }

        @Test
        @DisplayName("the peer's close() without reset releases a blocked reader with end of stream")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void peerCloseReleasesBlockedReaderWithEndOfStream() throws Exception {
            TransportStream stream = streams.writer();
            BlockedRead blocked = startBlockedRead(stream);
            blocked.awaitParked();

            streams.reader().close();

            assertThat(blocked.outcome(WAKE_SECONDS))
                    .as("a graceful peer close is end of stream, not an error")
                    .isEqualTo(-1);
        }

        /** Whether the peer reads to the end of stream, or hits the stream's closed signal, in time. */
        private boolean drainedToEnd(TransportStream peer) throws InterruptedException {
            AtomicReference<Boolean> done = new AtomicReference<>(Boolean.FALSE);
            Thread drainer = Thread.ofVirtual().start(() -> {
                try (LoanedBuffer sink = allocator.allocateNetwork(64 * 1024)) {
                    int read;
                    do {
                        read = peer.read(sink.segment(), 64 * 1024);
                    } while (read >= 0);
                    done.set(Boolean.TRUE);
                } catch (TransportStreamClosedException _) {
                    done.set(Boolean.TRUE);
                } catch (RuntimeException _) {
                    done.set(Boolean.FALSE);
                }
            });
            drainer.join(TimeUnit.SECONDS.toMillis(DRAIN_SECONDS));
            return !drainer.isAlive() && done.get();
        }

        /**
         * Writes until the closed-stream signal arrives; false if it does not within the deadline
         * or the attempt bound. A send failure is the one other outcome a write may report first
         * (it ends the stream, and the next write reports the closed signal); any other exception
         * is a defect and propagates.
         */
        private boolean writeRefusedWithinDeadline(TransportStream stream) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAKE_SECONDS);
            try (LoanedBuffer probe = allocator.allocate(AllocationHint.MICRO)) {
                for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS && System.nanoTime() < deadline; attempt++) {
                    try {
                        stream.write(probe.segment(), 1);
                    } catch (TransportStreamClosedException _) {
                        return true;
                    } catch (TransportException _) {
                        Thread.onSpinWait();
                    }
                }
            }
            return false;
        }
    }

    // =========================================================================
    // A send to a peer that ended the stream
    // =========================================================================

    @Nested
    @DisplayName("A send after the peer's reset")
    class PeerEndRefusesWrites {

        private static final int MAX_SEND_ATTEMPTS = 100_000;

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise a send to a peer that reset the stream.
         */
        PeerEndRefusesWrites() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("write() after the peer's reset fails first with the closed-stream signal EX-NET-4008")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void writesAfterThePeersResetAreRefusedWithThePeerCode() {
            TransportStream writer = streams.writer();
            streams.reader().reset(0L);

            Throwable first = null;
            try (LoanedBuffer probe = allocator.allocate(AllocationHint.MICRO)) {
                for (int attempt = 0; attempt < MAX_SEND_ATTEMPTS && first == null; attempt++) {
                    try {
                        writer.write(probe.segment(), 1);
                    } catch (RuntimeException failure) {
                        first = failure;
                    }
                }
            }

            assertThat(first)
                    .as("a send to a peer that reset the stream is refused within the attempt bound")
                    .isNotNull();
            assertThat(first)
                    .as("the first failure says the peer ended the stream, not that the send failed")
                    .isInstanceOfSatisfying(TransportStreamClosedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008));
        }

        @Test
        @DisplayName("queueWrite() after the peer's reset fails first with the closed-stream signal EX-NET-4008")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void queuedWritesAfterThePeersResetAreRefusedWithThePeerCode() {
            TransportStream writer = streams.writer();
            streams.reader().reset(0L);

            Throwable first = null;
            for (int attempt = 0; attempt < MAX_SEND_ATTEMPTS && first == null; attempt++) {
                LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
                try {
                    writer.queueWrite(buf, 1);
                } catch (RuntimeException failure) {
                    first = failure;
                    // The caller closes on a throw; the close is idempotent, so it holds whether or not
                    // the transport released the loan while it unwound.
                    assertThatCode(buf::close).doesNotThrowAnyException();
                }
            }

            assertThat(first)
                    .as("a queued send to a peer that reset the stream is refused within the attempt bound")
                    .isNotNull();
            assertThat(first)
                    .as("the first failure says the peer ended the stream, not that the send failed")
                    .isInstanceOfSatisfying(TransportStreamClosedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008));
        }
    }

    // =========================================================================
    // queueWrite never waits for the peer
    // =========================================================================

    @Nested
    @DisplayName("queueWrite() does not wait for the peer")
    class QueueWriteDoesNotWait {

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise the non-waiting {@code queueWrite} obligation.
         */
        QueueWriteDoesNotWait() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("queueWrite returns without waiting for a peer that does not read, and every loan is "
                + "released once the peer drains")
        @Timeout(value = 90, unit = TimeUnit.SECONDS)
        void queueWriteReturnsWithoutWaitingAndLoansAreReleased() throws Exception {
            runQueueWriteDoesNotWaitDiscriminator();
        }

        @Test
        @DisplayName("a loan is not released while its bytes are still queued")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void loanIsNotReleasedBeforeItsBytesLeaveTheQueue() throws Exception {
            runQueuedBurst(true);
        }
    }

    // =========================================================================
    // write() blocks at the outbound bound
    // =========================================================================

    @Nested
    @DisplayName("write() blocks at a bounded outbound")
    class WriteBackpressure {

        private static final int WRITE_CHUNKS = 1024;
        private static final long PARK_SECONDS = 10L;
        private static final long WAKE_SECONDS = 2L;

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise write backpressure.
         */
        WriteBackpressure() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("write() blocks while the peer does not read and resumes, in order, when it drains")
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        void writeBlocksAtTheBoundAndResumesWhenThePeerDrains() throws Exception {
            Assumptions.assumeTrue(supportsWriteBackpressure(), "binding does not declare write backpressure");
            TransportStream writer = streams.writer();
            warmUp();
            ParkedWriter parked;
            try (var _ = holdOutboundEgress(writer)) {
                parked = parkWriter(writer);
                assertThat(parked.written.get())
                        .as("the writer parked before it had written everything")
                        .isLessThan(WRITE_CHUNKS - 1);
            }
            assertThat(parked.outcome(DRAIN_SECONDS))
                    .as("the blocked write resumes and completes once the peer drains")
                    .isNull();
            assertThat(drainAndVerify(streams.reader(), WRITE_CHUNKS, DRAIN_SECONDS))
                    .as("the peer receives every byte, in order")
                    .isNull();
        }

        @Test
        @DisplayName("a blocked write is released by its own stream's close() with EX-NET-4009")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void blockedWriteIsReleasedByCloseWithTheLocalCode() throws Exception {
            Assumptions.assumeTrue(supportsWriteBackpressure(), "binding does not declare write backpressure");
            TransportStream writer = streams.writer();
            warmUp();
            try (var _ = holdOutboundEgress(writer)) {
                ParkedWriter parked = parkWriter(writer);

                writer.close();

                assertThat(parked.outcome(WAKE_SECONDS))
                        .as("close() ends the stream for its own blocked writer")
                        .isInstanceOfSatisfying(TransportStreamClosedException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4009));
            }
        }

        @Test
        @DisplayName("a blocked write is released by the peer's reset() with EX-NET-4008")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void blockedWriteIsReleasedByThePeersResetWithThePeerCode() throws Exception {
            Assumptions.assumeTrue(supportsWriteBackpressure(), "binding does not declare write backpressure");
            TransportStream writer = streams.writer();
            warmUp();
            try (var _ = holdOutboundEgress(writer)) {
                ParkedWriter parked = parkWriter(writer);

                streams.reader().reset(0L);

                assertThat(parked.outcome(WAKE_SECONDS))
                        .as("the peer's reset ends the stream for a blocked writer, naming the peer")
                        .isInstanceOfSatisfying(TransportStreamClosedException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008));
            }
        }

        /**
         * One byte each way before anything is measured, so a TLS binding's handshake wait is not
         * mistaken for the park the cases look for.
         */
        private void warmUp() {
            try (LoanedBuffer probe = allocator.allocate(AllocationHint.MICRO)) {
                probe.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0x2A);
                streams.writer().write(probe.segment(), 1);
                assertThat(streams.reader().read(probe.segment(), 1)).as("the warm-up byte arrives").isEqualTo(1);
            }
        }

        /**
         * Leaves the first pattern chunk queued, so the outbound is already at its bound, then starts a
         * writer for the rest and waits until it is blocked.
         */
        private ParkedWriter parkWriter(TransportStream writer) {
            queuePatternLoan(writer, 0, new CountDownLatch(1));
            ParkedWriter parked = new ParkedWriter(writer);
            parked.thread.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PARK_SECONDS);
            while (parked.thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(parked.thread.getState())
                    .as("write() blocks while the peer does not read")
                    .isEqualTo(Thread.State.WAITING);
            return parked;
        }

        /** A virtual thread writing the pattern chunks after the first, with the way it ended exposed. */
        private final class ParkedWriter {

            private final AtomicInteger written = new AtomicInteger();
            private final AtomicReference<Throwable> failure = new AtomicReference<>();
            private final Thread thread;

            private ParkedWriter(TransportStream writer) {
                thread = Thread.ofVirtual().unstarted(() -> {
                    try (LoanedBuffer chunk = allocator.allocateNetwork(BURST_CHUNK)) {
                        for (int index = 1; index < WRITE_CHUNKS; index++) {
                            fillPattern(chunk.segment(), index);
                            writer.write(chunk.segment(), BURST_CHUNK);
                            written.incrementAndGet();
                        }
                    } catch (RuntimeException | Error ended) {
                        failure.set(ended);
                    }
                });
            }

            /** How the writer ended: {@code null} when it wrote everything, otherwise the failure. */
            private Throwable outcome(long seconds) throws InterruptedException {
                thread.join(TimeUnit.SECONDS.toMillis(seconds));
                if (thread.isAlive()) {
                    return new AssertionError("the blocked writer was not released within " + seconds + "s");
                }
                return failure.get();
            }
        }
    }

    /** A virtual thread blocked in {@code read()}, with the way it ended exposed as a future. */
    private final class BlockedRead {

        private final AtomicReference<Object> outcome = new AtomicReference<>();
        private final Thread thread;

        private BlockedRead(TransportStream stream) {
            thread = Thread.ofVirtual().unstarted(() -> {
                try (LoanedBuffer sink = allocator.allocate(AllocationHint.SMALL)) {
                    outcome.set(stream.read(sink.segment(), (int) sink.segment().byteSize()));
                } catch (RuntimeException failure) {
                    outcome.set(failure);
                }
            });
        }

        /** Waits, by thread state, until the reader is parked: the transport suite's idiom. */
        private void awaitParked() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (thread.getState() != Thread.State.WAITING
                    && thread.getState() != Thread.State.TIMED_WAITING
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(thread.getState())
                    .as("the reader parks waiting for data")
                    .isIn(Thread.State.WAITING, Thread.State.TIMED_WAITING);
        }

        /** The read's result: its byte count, or the exception it ended with. */
        private Object outcome(long seconds) throws Exception {
            thread.join(TimeUnit.SECONDS.toMillis(seconds));
            if (thread.isAlive()) {
                return fail("the blocked reader was not released within " + seconds + "s");
            }
            return outcome.get();
        }
    }
}
