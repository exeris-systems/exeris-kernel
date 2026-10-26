/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.community.http.LeakTrackingAllocator;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.websocket.WebSocketOpcode;
import eu.exeris.kernel.spi.exceptions.http.WebSocketClosedException;
import eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.websocket.WebSocketSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Community: a WebSocket data write that waits for the peer never holds the lock the receive path
 * answers a ping or a close through, and the credit it waits on is the bytes the transport has not
 * yet released.
 *
 * <p>The transport holds every buffer it is handed, so the credit stays exhausted until the test
 * releases it. Every wait is a bound on a condition or a latch, never a sleep.
 */
@DisplayName("CommunityWebSocketEgress: data frames wait for credit, control frames never do")
class CommunityWebSocketEgressCreditTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final Duration BOUND = Duration.ofSeconds(10);
    /** Smaller than one frame, so a single admitted frame exhausts the credit. */
    private static final int TINY_CAP = 4;

    private WebSocketScriptedTransport transport;
    private LeakTrackingAllocator allocator;
    private final List<Task> tasks = new ArrayList<>();

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @BeforeEach
    void setUp() {
        transport = new WebSocketScriptedTransport(true);
        allocator = new LeakTrackingAllocator(ALLOCATOR);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        transport.close();
        for (Task task : tasks) {
            task.thread.interrupt();
            task.thread.join(BOUND.toMillis());
        }
    }

    private CommunityWebSocketExchange exchange(int cap) {
        return new CommunityWebSocketExchange(transport,
                new WebSocketSession(UUID.randomUUID(), Optional.empty(), Optional.empty()),
                allocator, 1 << 16, cap);
    }

    @Test
    @DisplayName("a data write parked for credit does not stall the pong for an incoming ping")
    void parkedDataWriteDoesNotStallPong() throws Exception {
        CommunityWebSocketExchange exchange = exchange(TINY_CAP);
        Task receiver = start("receiver", exchange::receive);
        exchange.send("first");
        Task parked = start("parked-sender", () -> exchange.send("second"));
        awaitParkedForCredit(parked.thread);

        transport.feed(WebSocketScriptedTransport.clientFrame(WebSocketScriptedTransport.PING, new byte[] {7}));
        WebSocketScriptedTransport.Frame pong = transport.awaitFrame(
                f -> f.opcode() == WebSocketScriptedTransport.PONG, BOUND);

        assertThat(pong.payload()).containsExactly(7);
        assertThat(parked.done.getCount()).as("the data writer is still parked").isOne();

        transport.releaseHeld();
        parked.join();

        assertThat(transport.seen()).extracting(WebSocketScriptedTransport.Frame::opcode)
                .containsExactly(WebSocketScriptedTransport.TEXT, WebSocketScriptedTransport.PONG,
                        WebSocketScriptedTransport.TEXT);
        assertThat(transport.seen().get(2).text()).isEqualTo("second");
        transport.feedEof();
        receiver.join();
        exchange.close();
        transport.releaseHeld();
        assertThat(allocator.outstanding()).as("every buffer is returned").isZero();
        assertThat(transport.plainWrites()).isZero();
    }

    @Test
    @DisplayName("a data write parked for credit does not stall the echo of a close")
    void parkedDataWriteDoesNotStallCloseEcho() throws Exception {
        CommunityWebSocketExchange exchange = exchange(TINY_CAP);
        Task receiver = start("receiver", exchange::receive);
        exchange.send("first");
        Task parked = start("parked-sender", () -> exchange.send("second"));
        awaitParkedForCredit(parked.thread);

        transport.feed(WebSocketScriptedTransport.clientFrame(WebSocketScriptedTransport.CLOSE,
                new byte[] {0x03, (byte) 0xE8}));
        WebSocketScriptedTransport.Frame echo = transport.awaitFrame(
                f -> f.opcode() == WebSocketScriptedTransport.CLOSE, BOUND);

        assertThat(echo.payload()).containsExactly(0x03, 0xE8);
        assertThat(parked.done.getCount()).as("the data writer is still parked").isOne();
        receiver.join();

        transport.releaseHeld();
        parked.join();
        exchange.close();
        transport.releaseHeld();
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("a frame larger than the cap is admitted when nothing is outstanding")
    void frameLargerThanTheCapIsAdmittedWhenNothingIsOutstanding() throws Exception {
        CommunityWebSocketExchange exchange = exchange(TINY_CAP);
        String big = "x".repeat(100);

        Task sender = start("sender", () -> exchange.send(big));
        sender.join();

        assertThat(transport.seen()).hasSize(1);
        assertThat(transport.seen().getFirst().text()).isEqualTo(big);
        exchange.close();
        transport.releaseHeld();
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("closing the connection releases a data writer parked for credit")
    void closingTheConnectionReleasesAParkedDataWriter() throws Exception {
        CommunityWebSocketExchange exchange = exchange(TINY_CAP);
        exchange.send("first");
        Task parked = start("parked-sender", () -> exchange.send("second"));
        awaitParkedForCredit(parked.thread);

        Task closer = start("closer", exchange::close);
        closer.join();
        parked.join();

        assertThat(parked.failure.get()).isInstanceOfAny(
                WebSocketClosedException.class, TransportStreamClosedException.class);
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("the transport ending the stream releases the credit, and the parked write is refused as closed")
    void streamEndReleasesTheCreditAndTheWriteIsRefused() throws Exception {
        CommunityWebSocketEgress egress =
                new CommunityWebSocketEgress(transport, allocator, TINY_CAP);
        assertThat(egress.writeText("first")).isTrue();
        Task parked = start("parked-sender", () -> egress.writeText("second"));
        awaitParkedForCredit(parked.thread);

        transport.close();
        parked.join();

        assertThat(parked.failure.get()).isInstanceOf(TransportStreamClosedException.class);
        egress.close();
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("closing the egress returns a parked data write as not written, without the transport's help")
    void closingTheEgressReturnsAParkedWriteAsNotWritten() throws Exception {
        CommunityWebSocketEgress egress =
                new CommunityWebSocketEgress(transport, allocator, TINY_CAP);
        assertThat(egress.writeText("first")).isTrue();
        AtomicBoolean written = new AtomicBoolean(true);
        Task parked = start("parked-sender", () -> written.set(egress.writeText("second")));
        awaitParkedForCredit(parked.thread);

        egress.close();
        parked.join();

        assertThat(written).as("nothing was written").isFalse();
        assertThat(transport.seen()).hasSize(1);
        transport.releaseHeld();
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("an interrupted data writer throws with its interrupt status restored")
    void interruptedWriterThrowsWithStatusRestored() throws Exception {
        CommunityWebSocketEgress egress =
                new CommunityWebSocketEgress(transport, allocator, TINY_CAP);
        assertThat(egress.writeText("first")).isTrue();
        AtomicBoolean statusKept = new AtomicBoolean();
        Task parked = start("parked-sender", () -> {
            try {
                egress.writeText("second");
            } finally {
                statusKept.set(Thread.currentThread().isInterrupted());
            }
        });
        awaitParkedForCredit(parked.thread);

        parked.thread.interrupt();
        parked.join();

        assertThat(parked.failure.get()).isInstanceOf(IllegalStateException.class);
        assertThat(statusKept).isTrue();
        assertThat(transport.seen()).hasSize(1);
        transport.releaseHeld();
    }

    @Test
    @DisplayName("with no outbound bound a data write never waits")
    void unboundedDataWritesNeverWait() throws Exception {
        CommunityWebSocketEgress egress = new CommunityWebSocketEgress(transport, allocator,
                TransportConfig.UNBOUNDED_QUEUED_WRITE_BYTES);

        Task sender = start("sender", () -> {
            for (int i = 0; i < 50; i++) {
                egress.writeText("message " + i);
            }
        });
        sender.join();

        assertThat(transport.seen()).hasSize(50);
        assertThat(transport.heldCount()).as("the transport holds all of them").isEqualTo(50);
        transport.releaseHeld();
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("a frame the transport refuses returns its credit and its buffer")
    void refusedFrameReturnsItsCreditAndBuffer() throws Exception {
        CommunityWebSocketEgress egress =
                new CommunityWebSocketEgress(transport, allocator, TINY_CAP);
        transport.failQueueWrites(new IllegalStateException("send failed"));
        assertThatThrownBy(() -> egress.writeText("lost")).isInstanceOf(IllegalStateException.class);
        assertThat(allocator.outstanding()).as("the refused buffer was closed").isZero();
        transport.stopFailingQueueWrites();

        Task next = start("next-sender", () -> egress.writeText("kept"));
        next.join();

        assertThat(transport.seen()).hasSize(1);
        transport.releaseHeld();
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("a control frame is sent while data credit is exhausted, and a data opcode on writeFrame waits")
    void controlFramesNeverWaitAndDataOpcodesDo() throws Exception {
        CommunityWebSocketEgress egress =
                new CommunityWebSocketEgress(transport, allocator, TINY_CAP);
        assertThat(egress.writeText("first")).isTrue();

        Task ping = start("ping-sender", () -> egress.writeFrame(WebSocketOpcode.PING, new byte[] {1}));
        ping.join();
        Task parked = start("parked-sender",
                () -> egress.writeFrame(WebSocketOpcode.TEXT, new byte[] {'x'}));
        awaitParkedForCredit(parked.thread);

        assertThat(transport.seen()).extracting(WebSocketScriptedTransport.Frame::opcode)
                .containsExactly(WebSocketScriptedTransport.TEXT, WebSocketScriptedTransport.PING);
        transport.releaseHeld();
        parked.join();
        transport.releaseHeld();
        assertThat(transport.seen()).hasSize(3);
        assertThat(allocator.outstanding()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** A thread run by a test, with the way it ended. */
    private static final class Task {
        final Thread thread;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Task(String name, Runnable body) {
            this.thread = Thread.ofPlatform().name(name).unstarted(() -> {
                try {
                    body.run();
                } catch (Throwable thrown) { // NOSONAR the outcome is what a test asserts
                    failure.set(thrown);
                } finally {
                    done.countDown();
                }
            });
        }

        void join() throws InterruptedException {
            assertThat(done.await(BOUND.toNanos(), TimeUnit.NANOSECONDS))
                    .as("%s ended within %s", thread.getName(), BOUND).isTrue();
        }
    }

    private Task start(String name, Runnable body) {
        Task task = new Task(name, body);
        tasks.add(task);
        task.thread.start();
        return task;
    }

    /** Waits until {@code thread} is parked inside the egress's credit wait, read from its stack. */
    private static void awaitParkedForCredit(Thread thread) {
        awaitCondition(() -> {
            if (thread.getState() != Thread.State.WAITING) {
                return false;
            }
            for (StackTraceElement frame : thread.getStackTrace()) {
                if ("await".equals(frame.getMethodName())
                        && frame.getClassName().endsWith("CommunityWebSocketEgressCredit")) {
                    return true;
                }
            }
            return false;
        }, "the writer is parked in the credit wait");
    }

    private static void awaitCondition(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within " + BOUND + ": " + what);
            }
            Thread.onSpinWait();
        }
    }
}
