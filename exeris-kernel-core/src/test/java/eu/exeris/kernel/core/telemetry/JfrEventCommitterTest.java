/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry;

import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit: {@link JfrEventCommitter} — proves {@code commit()} runs OFF the producer (virtual)
 * thread, on a non-virtual platform thread named {@code exeris/jfr-committer}.
 *
 * <p>The VT-JFR SIGSEGV this class fixes cannot be asserted in-process (it would kill the runner),
 * so the load-bearing guarantee tested here is structural: the JFR-recorded committing thread is the
 * platform committer, regardless of which (virtual) thread called {@link JfrEventCommitter#offer}.
 * {@code jdk.jfr.Event.commit()} is {@code final} and cannot be overridden, so the committing thread
 * is observed via {@link RecordedEvent#getThread()} read back from a {@link Recording}.
 */
@DisplayName("L1 Unit: JfrEventCommitter (off-VT JFR commit)")
class JfrEventCommitterTest {

    private static final String EVENT_NAME = "eu.exeris.test.CommitterProbe";

    @Name(EVENT_NAME)
    @StackTrace(false)
    static final class ProbeEvent extends Event {
        @SuppressWarnings("unused") // read by the JFR runtime
        int seq;
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("commit() runs on the platform committer thread, not the producer virtual thread")
    void commitsOnPlatformThread() throws Exception {
        try (JfrEventCommitter committer = JfrEventCommitter.start();
             Recording recording = new Recording()) {
            recording.enable(EVENT_NAME);
            recording.start();

            // Offer from a VIRTUAL thread — the producer side this fix protects.
            Thread producer = Thread.ofVirtual().name("test-producer-vt").start(() -> {
                ProbeEvent e = new ProbeEvent();
                e.seq = 1;
                assertThat(committer.offer(e)).isTrue();
            });
            producer.join(TimeUnit.SECONDS.toMillis(5));

            committer.close(); // flush so the async commit is observable

            List<RecordedEvent> events = stopAndRead(recording);
            assertThat(events).hasSize(1);
            String committingThread = events.getFirst().getThread().getJavaName();
            assertThat(committingThread)
                    .as("commit must run on the platform committer thread, not the producer VT")
                    .isEqualTo("exeris/jfr-committer");
            assertThat(committingThread).isNotEqualTo("test-producer-vt");
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("close() drains all enqueued events before returning")
    void closeDrainsRemaining() throws Exception {
        int n = 200;
        try (Recording recording = new Recording()) {
            recording.enable(EVENT_NAME);
            recording.start();

            JfrEventCommitter committer = JfrEventCommitter.start();
            for (int i = 0; i < n; i++) {
                ProbeEvent e = new ProbeEvent();
                e.seq = i;
                committer.offer(e);
            }
            committer.close(); // must drain the backlog on the platform thread

            List<RecordedEvent> events = stopAndRead(recording);
            assertThat(events)
                    .as("every enqueued event is committed by close()-time drain")
                    .hasSize(n);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("offer() after close drops the event and increments droppedCount")
    void dropsAfterClose() {
        JfrEventCommitter committer = JfrEventCommitter.start();
        committer.close();

        long before = committer.droppedCount();
        boolean accepted = committer.offer(new ProbeEvent());

        assertThat(accepted).as("a closed committer must not accept events").isFalse();
        assertThat(committer.droppedCount())
                .as("a dropped offer increments the drop counter")
                .isEqualTo(before + 1);
    }

    private static List<RecordedEvent> stopAndRead(Recording recording) throws Exception {
        Path dump = Files.createTempFile("jfr-committer", ".jfr");
        try {
            recording.stop();
            recording.dump(dump);
            return RecordingFile.readAllEvents(dump).stream()
                    .filter(e -> EVENT_NAME.equals(e.getEventType().getName()))
                    .toList();
        } finally {
            Files.deleteIfExists(dump);
        }
    }
}
