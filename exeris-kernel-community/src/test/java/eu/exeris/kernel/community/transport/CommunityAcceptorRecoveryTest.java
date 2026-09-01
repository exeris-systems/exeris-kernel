/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportMode;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An accept that fails must not end the listener.
 *
 * <p>Until 0.12.0 an {@link IOException} from {@code accept()} took the listener down for the
 * lifetime of the JVM: {@code runAcceptorLoop} called {@code handleAsyncFailure} and returned,
 * clearing {@code running} and closing the server channel. That is how file-descriptor exhaustion
 * surfaces, and it is transient — descriptors come back as connections close — so a process that hit
 * its {@code ulimit -n} once needed a restart to serve again.
 *
 * <h2>Why the loop is driven rather than the classification asserted</h2>
 * <p>The defect was that the loop <em>ended</em>. A test that asked "is {@code EMFILE} classified as
 * transient?" would assert the judgement and not the consequence, and would still pass against a
 * loop that classified correctly and returned anyway. So these cases run the real
 * {@code acceptorLoop} with a pass that fails and then recovers, and assert on what the loop did
 * next.
 *
 * <p>Descriptor exhaustion is not induced for real: doing so needs the whole JVM's descriptors,
 * which would make every other test in the run collateral. What matters is the loop's response to a
 * failing accept, and the seam supplies exactly that.
 */
@DisplayName("NativeTcpCarrier — a failed accept is retried, not fatal")
class CommunityAcceptorRecoveryTest {

    private static final String RETRY_EVENT = "eu.exeris.kernel.transport.CommunityAcceptRetry";
    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @Nested
    @DisplayName("Recovery")
    class Recovery {

        @Test
        @Timeout(30)
        @DisplayName("the loop keeps accepting after accept() has failed")
        void loopSurvivesAFailedAccept(@TempDir Path tmp) throws Exception {
            // Two failures, then passes that succeed. Before the fix the first one ended the loop,
            // so `passes` stopped at 1 and the carrier was no longer running.
            NativeTcpCarrier carrier = carrier(tmp);
            AtomicInteger passes = new AtomicInteger();

            AtomicLong accepts = new AtomicLong();
            carrier.start();
            carrier.acceptorLoop(() -> {
                int pass = passes.incrementAndGet();
                if (pass <= 2) {
                    throw new IOException("Too many open files");
                }
                carrier.stop();
            }, accepts::get);

            assertThat(passes.get())
                    .as("the loop MUST have run again after the failures; before the fix it ended "
                            + "on the first one")
                    .isEqualTo(3);
        }

        @Test
        @Timeout(30)
        @DisplayName("an accept that worked ends the streak, so an earlier burst cannot end it later")
        void streakResetsOnASuccessfulAccept(@TempDir Path tmp) throws Exception {
            // Asserted through the event rather than a getter: fail, recover, fail again, and the
            // second failure must report streak 1. Without the reset the streak is a lifetime
            // budget rather than a streak, and a process that recovered from a burst hours ago
            // would be that much closer to giving up on the next one.
            NativeTcpCarrier carrier = carrier(tmp);
            Path jfr = tmp.resolve("streak.jfr");
            AtomicInteger passes = new AtomicInteger();
            AtomicLong accepts = new AtomicLong();

            try (Recording recording = new Recording()) {
                recording.enable(RETRY_EVENT);
                recording.start();

                carrier.start();
                carrier.acceptorLoop(() -> {
                    int pass = passes.incrementAndGet();
                    if (pass == 1 || pass == 3) {
                        throw new IOException("Too many open files");
                    }
                    accepts.incrementAndGet();
                    if (pass >= 4) {
                        carrier.stop();
                    }
                }, accepts::get);

                recording.dump(jfr);
            }

            assertThat(streaks(jfr))
                    .as("the accept between them resets the count; without the reset this is 1, 2")
                    .containsExactly(1, 1);
        }
    }

    @Nested
    @DisplayName("Partial progress")
    class PartialProgress {

        @Test
        @Timeout(30)
        @DisplayName("a pass that accepts one connection and then fails does not build a streak")
        void acceptThenThrowDoesNotAccumulate(@TempDir Path tmp) throws Exception {
            // The shape descriptor pressure actually produces: descriptors free one at a time, so a
            // pass accepts a connection and then throws probing for the next. The pass leaves by the
            // THROW, so nothing it could have returned reaches the loop — which is why progress is
            // read from a counter. Built from return values, this streak climbs to the ceiling while
            // the listener is demonstrably still serving, and the fix defeats itself.
            NativeTcpCarrier carrier = carrier(tmp);
            Path jfr = tmp.resolve("partial.jfr");
            AtomicInteger passes = new AtomicInteger();
            AtomicLong accepts = new AtomicLong();

            try (Recording recording = new Recording()) {
                recording.enable(RETRY_EVENT);
                recording.start();

                carrier.start();
                carrier.acceptorLoop(() -> {
                    int pass = passes.incrementAndGet();
                    if (pass > 4) {
                        carrier.stop();
                        return;
                    }
                    accepts.incrementAndGet();
                    throw new IOException("Too many open files");
                }, accepts::get);

                recording.dump(jfr);
            }

            assertThat(streaks(jfr))
                    .as("every pass served a connection, so no failure is consecutive with another")
                    .containsExactly(1, 1, 1, 1);
        }
    }

    @Nested
    @DisplayName("The bound")
    class Bound {

        @Test
        @Timeout(60)
        @DisplayName("a failure that never clears still ends the loop, rather than retrying forever")
        void unclearingFailureIsEventuallyFatal(@TempDir Path tmp) throws Exception {
            // Retrying without a bound would trade a dead listener for a live one that never serves
            // and never says so. The ceiling is what makes "retry everything" safe enough to do
            // without classifying the exception.
            // A small ceiling, passed in: walking the shipped 64 would spend ~45s of real backoff
            // in every build to assert a property that does not depend on the number.
            NativeTcpCarrier carrier = carrier(tmp);
            AtomicInteger passes = new AtomicInteger();
            AtomicLong accepts = new AtomicLong();

            carrier.start();
            carrier.acceptorLoop(() -> {
                passes.incrementAndGet();
                throw new IOException("Too many open files");
            }, accepts::get, 3);

            assertThat(carrier.isRunning())
                    .as("the fatal path MUST still exist for a condition that does not clear")
                    .isFalse();
            assertThat(passes.get())
                    .as("bounded: the ceiling plus the pass that trips it")
                    .isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Observability")
    class Observability {

        @Test
        @Timeout(30)
        @DisplayName("each retry commits an event carrying the streak and the pause, class only")
        void retryIsRecorded(@TempDir Path tmp) throws Exception {
            NativeTcpCarrier carrier = carrier(tmp);
            Path jfr = tmp.resolve("accept-retry.jfr");

            try (Recording recording = new Recording()) {
                recording.enable(RETRY_EVENT);
                recording.start();

                carrier.start();
                AtomicInteger passes = new AtomicInteger();
                AtomicLong accepts = new AtomicLong();
                carrier.acceptorLoop(() -> {
                    if (passes.incrementAndGet() <= 2) {
                        throw new IOException("Too many open files");
                    }
                    carrier.stop();
                }, accepts::get);

                recording.dump(jfr);
            }

            List<RecordedEvent> events = RecordingFile.readAllEvents(jfr).stream()
                    .filter(event -> RETRY_EVENT.equals(event.getEventType().getName()))
                    .toList();

            assertThat(events).hasSize(2);
            assertThat(events.getFirst().getInt("consecutiveFailures")).isEqualTo(1);
            assertThat(events.getLast().getInt("consecutiveFailures"))
                    .as("the streak is what separates noise from a process that is not recovering")
                    .isEqualTo(2);
            assertThat(events.getFirst().getString("failureClass")).isEqualTo(IOException.class.getName());
            assertThat(events.getLast().getLong("backoffMillis"))
                    .as("the pause grows with the streak and is capped")
                    .isPositive();
        }
    }

    private static List<Integer> streaks(Path jfr) throws IOException {
        return RecordingFile.readAllEvents(jfr).stream()
                .filter(event -> RETRY_EVENT.equals(event.getEventType().getName()))
                .map(event -> event.getInt("consecutiveFailures"))
                .toList();
    }

    /**
     * A started carrier whose real acceptor is parked in {@code accept()} on a port nobody connects
     * to. The cases drive {@code acceptorLoop} directly on top of that; what {@code start()} is for
     * here is putting the carrier in the running state the loop reads, without a test-only setter
     * for it.
     */
    private static NativeTcpCarrier carrier(Path tmp) throws IOException {
        NativeTcpCarrier carrier = newCarrier(tmp);
        carrier.setStreamHandler(stream -> { });
        return carrier;
    }

    private static NativeTcpCarrier newCarrier(Path tmp) throws IOException {
        return new NativeTcpCarrier(
                new TransportConfig(TransportMode.SERVER, "127.0.0.1", freePort(), 1,
                        "unused.pem", "unused.key", 1024, 30_000),
                ALLOCATOR,
                null,
                CryptoProviderConfig.httpsServer(tmp.resolve("cert.pem"), tmp.resolve("key.pem")));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
