/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.http.Http2ConnectionWriter.SendOutcome;
import eu.exeris.kernel.community.http.Http2ScriptedTransport.Frame;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
import eu.exeris.kernel.core.http.http2.Http2FlowController;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The writer's flow-control and ordering contract, driven directly: a "sender" is a test virtual
 * thread calling {@code sendBody}, and every interleaving of a window change, a reset or a
 * connection end with a sender about to park is forced through the writer's test seams, not through
 * timing.
 */
@DisplayName("Http2ConnectionWriter: flow control and one writer per connection")
class Http2ConnectionWriterTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final int DATA = Http2FrameType.DATA.code();
    private static final int RST_STREAM = Http2FrameType.RST_STREAM.code();
    private static final int PING = Http2FrameType.PING.code();

    private Http2ScriptedTransport transport;
    private LeakTrackingAllocator allocator;
    private Http2ConnectionWriter writer;
    private final Semaphore parks = new Semaphore(0);
    private final List<Sender> senders = new ArrayList<>();

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @BeforeEach
    void newWriter() {
        transport = new Http2ScriptedTransport();
        allocator = new LeakTrackingAllocator(ALLOCATOR);
        writer = Http2ConnectionWriter.create(transport, allocator, 100, 64 * 1024);
        writer.beforeParkForTest(parks::release);
    }

    @AfterEach
    void releaseStragglers() {
        for (Sender sender : senders) {
            sender.thread.interrupt();
        }
        closeBodies();
    }

    private void closeBodies() {
        senders.forEach(Sender::closeBody);
    }

    // ------------------------------------------------------------------ the park handshake

    @Test
    @DisplayName("a window update applied between the credit check and the park is not lost")
    void windowUpdateBetweenCheckAndParkIsNotLost() {
        writer.applyInitialWindowSize(0);
        Http2OutboundStream stream = writer.register(1, 0);
        writer.beforeParkForTest(() -> writer.applyStreamWindowUpdate(1, 10));

        Sender sender = sender(stream, body(10), 10).start();

        assertThat(sender.join()).isEqualTo(SendOutcome.WRITTEN);
        assertThat(dataBytes(1)).isEqualTo(10);
    }

    @Test
    @DisplayName("a parked sender resumes on a stream window update")
    void parkedSenderResumesOnStreamWindowUpdate() throws Exception {
        writer.applyInitialWindowSize(0);
        Http2OutboundStream stream = writer.register(1, 0);
        Sender sender = sender(stream, body(10), 10).start();
        awaitParks(1);

        writer.applyStreamWindowUpdate(1, 10);

        assertThat(sender.join()).isEqualTo(SendOutcome.WRITTEN);
        Frame frame = transport.awaitFrame(f -> f.type() == DATA && f.streamId() == 1, BOUND);
        assertThat(frame.payload()).hasSize(10);
        assertThat(frame.isEndStream()).isTrue();
    }

    // ------------------------------------------------------------------ windows

    @Test
    @DisplayName("the connection window is shared by every stream and never overrun")
    void connectionWindowIsSharedAndNeverOverrun() throws Exception {
        writer.applyInitialWindowSize(1_000_000);
        Http2OutboundStream drain = writer.register(5, 0);
        Http2OutboundStream first = writer.register(1, 0);
        Http2OutboundStream second = writer.register(3, 0);
        LoanedBuffer drainBody = body(Http2FlowController.DEFAULT_WINDOW_SIZE);
        assertThat(sender(drain, drainBody, drainBody.size()).start().join()).isEqualTo(SendOutcome.WRITTEN);

        Sender one = sender(first, body(1000), 1000).start();
        Sender two = sender(second, body(1000), 1000).start();
        awaitParks(2);

        assertThat(writer.applyConnectionWindowUpdate(1000)).isEqualTo(Http2ErrorCode.NO_ERROR);
        // One of the two takes the 1 000 bytes and the other finds none and parks again, so a third
        // park is the signal that the credit has been fully handed out.
        awaitParks(1);
        flush();

        assertThat(dataBytes(1) + dataBytes(3)).as("credit handed out by one update").isEqualTo(1000);

        writer.applyConnectionWindowUpdate(1000);
        assertThat(one.join()).isEqualTo(SendOutcome.WRITTEN);
        assertThat(two.join()).isEqualTo(SendOutcome.WRITTEN);
        assertThat(dataBytes(1) + dataBytes(3)).isEqualTo(2000);
    }

    @Test
    @DisplayName("a SETTINGS_INITIAL_WINDOW_SIZE change moves open windows, including below zero")
    void initialWindowDeltaAppliesToOpenStreamsIncludingNegative() throws Exception {
        writer.applyInitialWindowSize(100);
        Http2OutboundStream stream = writer.register(1, 0);
        Sender sender = sender(stream, body(160), 160).start();
        awaitParks(1);
        assertThat(dataBytes(1)).isEqualTo(100);

        writer.applyInitialWindowSize(50);
        awaitParks(1);
        flush();
        assertThat(dataBytes(1)).as("window is -50, nothing may be sent").isEqualTo(100);

        writer.applyStreamWindowUpdate(1, 60);
        awaitParks(1);
        flush();
        assertThat(dataBytes(1)).as("-50 + 60 leaves exactly 10 bytes of credit").isEqualTo(110);

        writer.applyStreamWindowUpdate(1, 1000);
        assertThat(sender.join()).isEqualTo(SendOutcome.WRITTEN);
        assertThat(dataBytes(1)).isEqualTo(160);
    }

    @Test
    @DisplayName("an initial-window change that overflows an open stream window is a flow-control error")
    void initialWindowDeltaThatOverflowsIsFlowControlError() {
        Http2OutboundStream stream = writer.register(1, 0);
        writer.applyStreamWindowUpdate(1, Http2FlowController.MAX_WINDOW_SIZE
                - Http2FlowController.DEFAULT_WINDOW_SIZE);
        assertThat(stream.sendWindow.windowSize()).isEqualTo(Http2FlowController.MAX_WINDOW_SIZE);

        assertThat(writer.applyInitialWindowSize(Http2FlowController.DEFAULT_WINDOW_SIZE + 1))
                .isEqualTo(Http2ErrorCode.FLOW_CONTROL_ERROR);
        assertThat(writer.applyInitialWindowSize(Http2FlowController.DEFAULT_WINDOW_SIZE))
                .as("a change that fits is applied")
                .isEqualTo(Http2ErrorCode.NO_ERROR);
        assertThat(writer.peerInitialWindowSize()).isEqualTo(Http2FlowController.DEFAULT_WINDOW_SIZE);
    }

    @Test
    @DisplayName("a stream window overflow resets only that stream and writes nothing for it afterwards")
    void streamWindowOverflowResetsOnlyThatStream() throws Exception {
        writer.applyInitialWindowSize(0);
        Http2OutboundStream overflowing = writer.register(1, 0);
        writer.applyStreamWindowUpdate(1, Http2FlowController.MAX_WINDOW_SIZE);
        LoanedBuffer bigBody = body(200_000);
        Sender a = sender(overflowing, bigBody, 200_000).start();
        awaitParks(1);
        assertThat(dataBytes(1)).as("the connection window ran out").isEqualTo(65_535);

        Http2OutboundStream sibling = writer.register(3, 0);
        writer.applyStreamWindowUpdate(3, 100);
        Sender b = sender(sibling, body(100), 100).start();
        awaitParks(1);

        writer.applyStreamWindowUpdate(1, Http2FlowController.DEFAULT_WINDOW_SIZE + 1);
        assertThat(a.join()).isEqualTo(SendOutcome.CLOSED);
        writer.applyConnectionWindowUpdate(1000);
        assertThat(b.join()).isEqualTo(SendOutcome.WRITTEN);
        flush();

        List<Frame> seen = transport.seen();
        int rst = indexOf(seen, f -> f.type() == RST_STREAM && f.streamId() == 1);
        assertThat(rst).as("RST_STREAM for the overflowing stream").isGreaterThanOrEqualTo(0);
        assertThat(Http2Frames.readInt32(seen.get(rst).payload(), 0))
                .isEqualTo(Http2ErrorCode.FLOW_CONTROL_ERROR.code());
        assertThat(seen.subList(rst + 1, seen.size()))
                .noneMatch(f -> f.type() == DATA && f.streamId() == 1);
        assertThat(dataBytes(3)).isEqualTo(100);
        assertThat(seen).noneMatch(f -> f.type() == RST_STREAM && f.streamId() == 3);
    }

    @Test
    @DisplayName("a zero stream increment resets the stream with PROTOCOL_ERROR")
    void zeroStreamIncrementIsStreamProtocolError() throws Exception {
        writer.register(1, 0);

        writer.applyStreamWindowUpdate(1, 0);

        Frame rst = transport.awaitFrame(f -> f.type() == RST_STREAM && f.streamId() == 1, BOUND);
        assertThat(Http2Frames.readInt32(rst.payload(), 0)).isEqualTo(Http2ErrorCode.PROTOCOL_ERROR.code());
    }

    @Test
    @DisplayName("a zero connection increment is a protocol error")
    void zeroConnectionIncrementIsProtocolError() {
        assertThat(writer.applyConnectionWindowUpdate(0)).isEqualTo(Http2ErrorCode.PROTOCOL_ERROR);
    }

    @Test
    @DisplayName("a connection window overflow is a flow-control error")
    void connectionWindowOverflowIsFlowControlError() {
        assertThat(writer.applyConnectionWindowUpdate(
                Http2FlowController.MAX_WINDOW_SIZE - Http2FlowController.DEFAULT_WINDOW_SIZE))
                .isEqualTo(Http2ErrorCode.NO_ERROR);

        assertThat(writer.applyConnectionWindowUpdate(1)).isEqualTo(Http2ErrorCode.FLOW_CONTROL_ERROR);
    }

    // ------------------------------------------------------------------ teardown

    @Test
    @DisplayName("a peer reset wakes the parked sender with CLOSED and nothing more is written")
    void peerResetWakesParkedSenderWithClosed() throws Exception {
        writer.applyInitialWindowSize(0);
        Http2OutboundStream stream = writer.register(1, 0);
        Sender sender = sender(stream, body(10), 10).start();
        awaitParks(1);

        writer.resetByPeer(1);

        assertThat(sender.join()).isEqualTo(SendOutcome.CLOSED);
        flush();
        assertThat(transport.seen()).noneMatch(f -> f.streamId() == 1);
    }

    @Test
    @DisplayName("ending the connection wakes every parked sender, and later writes are dropped")
    void closeConnectionWakesEveryParkedSender() throws Exception {
        writer.applyInitialWindowSize(0);
        List<Sender> parked = new ArrayList<>();
        for (int id = 1; id <= 5; id += 2) {
            parked.add(sender(writer.register(id, 0), body(10), 10).start());
        }
        awaitParks(3);

        writer.closeConnection();

        for (Sender sender : parked) {
            assertThat(sender.join()).isEqualTo(SendOutcome.CLOSED);
        }
        int before = transport.seen().size();
        writer.writeFrame(MemorySegment.ofArray(Http2Frames.frame(PING, 0, 0, new byte[8])), 17);
        assertThat(transport.seen()).hasSize(before);
    }

    @Test
    @DisplayName("a transport failure ends the connection for every sender and closes the failed buffer")
    void transportFailureClosesTheConnectionForEverySender() throws Exception {
        writer.applyInitialWindowSize(0);
        Http2OutboundStream sibling = writer.register(3, 0);
        Sender parkedSibling = sender(sibling, body(10), 10).start();
        awaitParks(1);
        Http2OutboundStream failing = writer.register(1, 0);
        writer.applyStreamWindowUpdate(1, 100);
        transport.failQueueWrites(new IllegalStateException("transport down"));

        Sender failed = sender(failing, body(100), 100).start();

        assertThat(failed.join()).isEqualTo(SendOutcome.CLOSED);
        assertThat(parkedSibling.join()).isEqualTo(SendOutcome.CLOSED);
        closeBodies();
        assertThat(allocator.outstanding()).as("every buffer the writer allocated is closed").isZero();
    }

    // ------------------------------------------------------------------ ordering

    @Test
    @DisplayName("frames from two senders never interleave on the wire")
    void framesFromTwoSendersNeverInterleave() throws Exception {
        Http2OutboundStream first = writer.register(1, 0);
        Http2OutboundStream second = writer.register(3, 0);
        transport.blockWritesMatching(f -> f.type() == DATA && f.streamId() == 1);
        Sender one = sender(first, body(100), 100).start();
        transport.awaitBlockedWrite(BOUND);

        Sender two = sender(second, body(100), 100);
        two.start();
        Http2ScriptedTransport.awaitCondition(
                () -> writer.hasQueuedWriter(two.thread) || transport.overlapObserved(),
                BOUND, "the second sender is queued behind the first, or overlapped it");
        transport.releaseBlockedWrite();

        assertThat(one.join()).isEqualTo(SendOutcome.WRITTEN);
        assertThat(two.join()).isEqualTo(SendOutcome.WRITTEN);
        assertThat(transport.overlapObserved()).isFalse();
        List<Frame> seen = transport.seen();
        assertThat(seen).extracting(Frame::streamId).containsExactly(1, 3);
        assertThat(seen).allSatisfy(f -> assertThat(f.payload()).hasSize(100));
    }

    @Test
    @DisplayName("a failed header allocation closes the slice and gives both windows their credit back")
    void failedFrameAllocationClosesTheSliceAndRestoresCredit() {
        FailingHeaderAllocator failing = new FailingHeaderAllocator(allocator);
        Http2ConnectionWriter failingWriter = Http2ConnectionWriter.create(transport, failing, 100, 64 * 1024);
        Http2OutboundStream stream = failingWriter.register(1, 0);
        LoanedBuffer body = body(100);
        failing.failNext = true;

        assertThatThrownBy(() -> failingWriter.sendBody(stream, body, 0, 100, true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(stream.sendWindow.windowSize()).isEqualTo(Http2FlowController.DEFAULT_WINDOW_SIZE);
        Http2OutboundStream sibling = failingWriter.register(3, 0);
        LoanedBuffer full = body(Http2FlowController.DEFAULT_WINDOW_SIZE);
        assertThat(failingWriter.sendBody(sibling, full, 0, Http2FlowController.DEFAULT_WINDOW_SIZE, true))
                .as("the connection window still holds all of its credit")
                .isEqualTo(SendOutcome.WRITTEN);
        body.close();
        full.close();
        assertThat(allocator.outstanding()).as("the slice taken before the failure was closed").isZero();
    }

    // ------------------------------------------------------------------ slots

    @Test
    @DisplayName("release frees a slot once, and the table stays searchable after the swap")
    void releaseFreesTheSlotOnlyOnce() {
        writer.applyInitialWindowSize(0);
        Http2OutboundStream first = writer.register(1, 0);
        Http2OutboundStream second = writer.register(3, 0);
        assertThat(writer.openStreamCount()).isEqualTo(2);

        writer.release(first);
        writer.release(first);

        assertThat(writer.openStreamCount()).isEqualTo(1);
        writer.applyStreamWindowUpdate(3, 10);
        assertThat(second.sendWindow.windowSize()).as("stream 3 is still found after the swap").isEqualTo(10);
        assertThat(sender(second, body(10), 10).start().join()).isEqualTo(SendOutcome.WRITTEN);
    }

    // ------------------------------------------------------------------ support

    private Sender sender(Http2OutboundStream stream, LoanedBuffer body, long length) {
        Sender sender = new Sender(stream, body, (int) length);
        senders.add(sender);
        return sender;
    }

    private LoanedBuffer body(int length) {
        LoanedBuffer buffer = allocator.allocateNetwork(length);
        MemorySegment segment = buffer.segment();
        for (int i = 0; i < length; i++) {
            segment.set(ValueLayout.JAVA_BYTE, i, (byte) (i * 31 + 7));
        }
        buffer.setSize(length);
        return buffer;
    }

    private void awaitParks(int count) throws InterruptedException {
        assertThat(parks.tryAcquire(count, BOUND.toSeconds(), TimeUnit.SECONDS))
                .as("%d sender park(s) within the bound", count)
                .isTrue();
    }

    /** Writes a PING and waits for it: every write queued before it has reached the transport. */
    private void flush() {
        writer.writeFrame(MemorySegment.ofArray(Http2Frames.frame(PING, 0, 0, new byte[8])), 17);
        transport.awaitFrame(f -> f.type() == PING, BOUND);
    }

    private int dataBytes(int streamId) {
        return transport.seen().stream()
                .filter(f -> f.type() == DATA && f.streamId() == streamId)
                .mapToInt(f -> f.payload().length)
                .sum();
    }

    private static int indexOf(List<Frame> frames, Predicate<Frame> match) {
        for (int i = 0; i < frames.size(); i++) {
            if (match.test(frames.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Fails the next 9-byte allocation, the size of a frame header, and forwards everything else. */
    private static final class FailingHeaderAllocator implements MemoryAllocator {
        private final MemoryAllocator delegate;
        private volatile boolean failNext;

        private FailingHeaderAllocator(MemoryAllocator delegate) {
            this.delegate = delegate;
        }

        @Override
        public LoanedBuffer allocate(eu.exeris.kernel.spi.memory.AllocationHint hint) {
            return delegate.allocate(hint);
        }

        @Override
        public LoanedBuffer allocateNetwork(int estimatedBytes) {
            if (failNext && estimatedBytes == 9) {
                failNext = false;
                throw new IllegalStateException("allocation refused");
            }
            return delegate.allocateNetwork(estimatedBytes);
        }

        @Override
        public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
            return delegate.allocateCarrierSlab(carrierIndex);
        }

        @Override
        public LoanedBuffer allocateInfrastructure(long sizeBytes) {
            return delegate.allocateInfrastructure(sizeBytes);
        }

        @Override
        public eu.exeris.kernel.spi.memory.MemoryStats stats() {
            return delegate.stats();
        }

        @Override
        public void close() {
            // The delegate is owned by the test.
        }
    }

    /** A virtual thread calling {@code sendBody} for one whole body. */
    private final class Sender {
        private final Thread thread;
        private final LoanedBuffer body;
        private volatile SendOutcome outcome;

        private Sender(Http2OutboundStream stream, LoanedBuffer body, int length) {
            this.body = body;
            this.thread = Thread.ofVirtual().unstarted(
                    () -> outcome = writer.sendBody(stream, body, 0, length, true));
        }

        private Sender start() {
            thread.start();
            return this;
        }

        private SendOutcome join() {
            try {
                thread.join(BOUND);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            assertThat(thread.isAlive()).as("sender finished within the bound").isFalse();
            return outcome;
        }

        private void closeBody() {
            body.close();
        }
    }
}
