/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.sse;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.http.StreamClosedException;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The engine's lifecycle over a {@link StreamEgress}: what it asks the egress to do, and what it keeps
 * for itself — the terminal signal, the JFR events and the rule that the connection's transport stream
 * is never closed or reset on a protocol that multiplexes streams over it.
 */
@DisplayName("HttpStreamEngine over a protocol egress")
class HttpStreamEngineEgressTest {

    private static final HttpRequest REQUEST =
            HttpRequest.noBody(HttpMethod.GET, "/events", HttpVersion.HTTP_2, List.of());
    private static final String OPENED = "eu.exeris.kernel.http.StreamOpened";
    private static final String CLOSED = "eu.exeris.kernel.http.StreamClosed";
    private static final String ABORTIVE = "eu.exeris.kernel.http.StreamAbortiveTeardown";

    @Test
    @DisplayName("open writes the head once and reports the protocol stream id on the opened event")
    void openWritesTheHeadOnceAndEmitsOpenedWithTheProtocolStreamId() throws IOException {
        RecordingEgress egress = new RecordingEgress(5);

        List<RecordedEvent> events = record(() -> HttpStreamEngine.open(REQUEST, egress, 0L), OPENED);

        assertThat(egress.heads.get()).isEqualTo(1);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getLong("streamId")).isEqualTo(egress.transport.streamId());
        assertThat(events.getFirst().getInt("protocolStreamId")).isEqualTo(5);
        assertThat(events.getFirst().getBoolean("hasAuthDeadline")).isFalse();
    }

    @Test
    @DisplayName("a stream that ended before its head opens closed, and its first emit throws EX-HTTP-4011")
    void aStreamEndedBeforeItsHeadOpensClosedAndTheFirstEmitThrows4011() {
        RecordingEgress egress = new RecordingEgress(1);
        egress.headWritten = false;

        HttpStreamEngine engine = HttpStreamEngine.open(REQUEST, egress, 0L);

        assertThatThrownBy(() -> engine.emit(StreamEvent.of("x")))
                .isInstanceOfSatisfying(StreamClosedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4011));
        assertThat(engine.terminalSignal()).isNotNull();
        assertThat(egress.events.get()).as("nothing is written to an ended stream").isZero();
    }

    @Test
    @DisplayName("an emit counts the event only when the egress wrote it")
    void writtenOutcomeCountsTheEvent() throws IOException {
        RecordingEgress egress = new RecordingEgress(1);
        HttpStreamEngine engine = HttpStreamEngine.open(REQUEST, egress, 0L);

        engine.emit(StreamEvent.of("one"));
        engine.emit(StreamEvent.of("two"));
        List<RecordedEvent> closed = record(engine::close, CLOSED);

        assertThat(egress.events.get()).isEqualTo(2);
        assertThat(egress.lastEncodedLength).isEqualTo(SseEventEncoder.encodedLength(StreamEvent.of("two")));
        assertThat(closed.getFirst().getLong("eventsEmitted")).isEqualTo(2);
        assertThat(closed.getFirst().getInt("protocolStreamId")).isEqualTo(1);
    }

    @Test
    @DisplayName("a CLOSED outcome aborts the stream once and throws EX-HTTP-4011")
    void closedOutcomeAbortsOnceAndThrows4011() throws IOException {
        RecordingEgress egress = new RecordingEgress(3);
        HttpStreamEngine engine = HttpStreamEngine.open(REQUEST, egress, 0L);
        egress.next = StreamEgress.Outcome.CLOSED;

        List<RecordedEvent> aborted = record(() -> {
            assertThatThrownBy(() -> engine.emit(StreamEvent.of("x")))
                    .isInstanceOfSatisfying(StreamClosedException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4011));
            assertThatThrownBy(() -> engine.emit(StreamEvent.of("y"))).isInstanceOf(StreamClosedException.class);
        }, ABORTIVE);

        assertThat(egress.aborts.get()).isEqualTo(1);
        assertThat(egress.transport.resets.get()).as("the connection's stream is not reset").isZero();
        assertThat(aborted).hasSize(1);
        assertThat(aborted.getFirst().getInt("protocolStreamId")).isEqualTo(3);
        assertThat(aborted.getFirst().getString("errorCode")).isEqualTo(KernelErrorCodes.EX_HTTP_4011);
        assertThat(egress.events.get()).as("the second emit never reached the egress").isEqualTo(1);
    }

    @Test
    @DisplayName("a DEADLINE outcome aborts the stream and throws EX-HTTP-4012")
    void deadlineOutcomeAbortsAndThrows4012() {
        RecordingEgress egress = new RecordingEgress(3);
        HttpStreamEngine engine = HttpStreamEngine.open(REQUEST, egress, System.currentTimeMillis() + 60_000L);
        egress.next = StreamEgress.Outcome.DEADLINE;

        assertThatThrownBy(() -> engine.emit(StreamEvent.of("x")))
                .isInstanceOfSatisfying(StreamClosedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4012));

        assertThat(egress.aborts.get()).isEqualTo(1);
        assertThat(engine.terminalSignal().errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4012);
        assertThat(egress.lastDeadline).as("the deadline reaches the egress").isGreaterThan(0L);
    }

    @Test
    @DisplayName("close finishes the stream once and never aborts it or touches the connection's stream")
    void closeFinishesOnceAndNeverAborts() {
        RecordingEgress egress = new RecordingEgress(1);
        HttpStreamEngine engine = HttpStreamEngine.open(REQUEST, egress, 0L);

        engine.close();
        engine.close();

        assertThat(egress.finishes.get()).isEqualTo(1);
        assertThat(egress.aborts.get()).isZero();
        assertThat(egress.transport.closes.get()).as("the connection stays open").isZero();
        assertThat(engine.terminalSignal()).isNull();
    }

    @Test
    @DisplayName("an emit after close is not sent, and neither is an abort")
    void abortAfterCloseIsNotSent() {
        RecordingEgress egress = new RecordingEgress(1);
        HttpStreamEngine engine = HttpStreamEngine.open(REQUEST, egress, 0L);
        engine.close();

        assertThatThrownBy(() -> engine.emit(StreamEvent.of("late"))).isInstanceOf(StreamClosedException.class);

        assertThat(egress.events.get()).isZero();
        assertThat(egress.aborts.get()).isZero();
    }

    // ------------------------------------------------------------------ support

    private static List<RecordedEvent> record(Runnable body, String eventName) throws IOException {
        Path file = Files.createTempFile("egress-engine", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(eventName).withoutStackTrace();
            recording.start();
            body.run();
            recording.stop();
            recording.dump(file);
        }
        try {
            List<RecordedEvent> events = new ArrayList<>(RecordingFile.readAllEvents(file));
            events.removeIf(e -> !e.getEventType().getName().equals(eventName));
            return events;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** An egress that records what the engine asks of it and answers with a settable outcome. */
    private static final class RecordingEgress implements StreamEgress {
        private final int protocolStreamId;
        private final CountingTransport transport = new CountingTransport();
        private final AtomicInteger heads = new AtomicInteger();
        private final AtomicInteger events = new AtomicInteger();
        private final AtomicInteger finishes = new AtomicInteger();
        private final AtomicInteger aborts = new AtomicInteger();
        private volatile boolean headWritten = true;
        private volatile Outcome next = Outcome.WRITTEN;
        private volatile int lastEncodedLength;
        private volatile long lastDeadline;

        private RecordingEgress(int protocolStreamId) {
            this.protocolStreamId = protocolStreamId;
        }

        @Override
        public TransportStream transport() {
            return transport;
        }

        @Override
        public int protocolStreamId() {
            return protocolStreamId;
        }

        @Override
        public boolean writeHead() {
            heads.incrementAndGet();
            return headWritten;
        }

        @Override
        public Outcome writeEvent(StreamEvent event, int encodedLength, long authDeadlineEpochMillis,
                                  long eventsEmitted) {
            events.incrementAndGet();
            lastEncodedLength = encodedLength;
            lastDeadline = authDeadlineEpochMillis;
            return next;
        }

        @Override
        public void finish() {
            finishes.incrementAndGet();
        }

        @Override
        public void abort() {
            aborts.incrementAndGet();
        }
    }

    /** A transport stream that counts the two calls a protocol-blind engine must not make on it. */
    private static final class CountingTransport implements TransportStream {
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger resets = new AtomicInteger();

        @Override
        public int read(MemorySegment target, int maxBytes) {
            return -1;
        }

        @Override
        public void write(MemorySegment source, int length) {
            throw new AssertionError("the engine writes nothing itself on an egress");
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
            throw new AssertionError("the engine writes nothing itself on an egress");
        }

        @Override
        public void reset(long errorCode) {
            resets.incrementAndGet();
        }

        @Override
        public long streamId() {
            return 11L;
        }

        @Override
        public boolean isBidirectional() {
            return true;
        }

        @Override
        public boolean isClientInitiated() {
            return true;
        }

        @Override
        public TransportConnection connection() {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
