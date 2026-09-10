/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import com.zaxxer.hikari.HikariDataSource;
import eu.exeris.kernel.community.persistence.jdbc.JdbcPersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the connection-acquire JFR event on virtual threads.
 *
 * <p>Background: {@code ConnectionAcquireEvent} used to be {@code begin()}'d before the
 * blocking pool checkout and {@code commit()}'d after it. On a virtual thread the checkout
 * parks and unmounts the carrier; remounting on a different carrier and committing the held
 * event flushed a stale, carrier-bound {@code JfrBuffer}, crashing the JVM in
 * {@code JfrStorage::flush_regular_buffer} (JDK 26 GA, build 26+35). The fix made the event
 * single-phase: it is constructed and committed entirely <em>after</em> the checkout returns.
 *
 * <p>A JVM SIGSEGV cannot be asserted from inside the same JVM (it would kill the test runner),
 * so these tests instead exercise the exact triggering conditions — acquire on a virtual thread
 * that genuinely parks on an exhausted pool, under an active JFR recording — and assert that the
 * acquire completes and the event is recorded with correct fields across the unmount/remount
 * boundary. The structural single-phase guarantee is what removes the crash; these tests lock in
 * the observable behavior of that path.
 */
@DisplayName("ConnectionAcquireEvent on virtual threads under JFR recording")
class CommunityConnectionAcquireJfrVirtualThreadTest {

    private static final String EVENT_NAME = "eu.exeris.kernel.persistence.ConnectionAcquire";

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("acquire that parks an exhausted pool on a virtual thread commits the event correctly")
    void parkedVirtualThreadAcquireRecordsEventAcrossUnmount() throws Exception {
        PersistenceConfig config = config(/* maxPoolSize */ 1);

        try (HikariDataSource pool = CommunityHikariSupport.buildPool(config, null);
             Recording recording = new Recording()) {
            CommunityHikariSupport support = CommunityHikariSupport.with(pool);
            recording.enable(EVENT_NAME);
            recording.start();

            // Hold the only connection so the next acquire is forced to park (and unmount the VT).
            JdbcPersistenceConnection held = support.acquireConnection("postgres-community", "shared", () -> { });

            CountDownLatch parked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<JdbcPersistenceConnection> acquired = new AtomicReference<>();

            Thread vt = Thread.ofVirtual().start(() -> {
                parked.countDown();
                try {
                    acquired.set(support.acquireConnection("postgres-community", "shared", () -> { }));
                } catch (Throwable t) {
                    failure.set(t);
                }
            });

            // Let the virtual thread reach the blocking checkout, then release the held connection.
            parked.await(5, TimeUnit.SECONDS);
            Thread.sleep(200);
            held.close();

            vt.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(failure.get()).as("acquire on virtual thread must not fail").isNull();
            assertThat(acquired.get()).as("virtual thread must obtain a connection").isNotNull();
            acquired.get().close();

            List<RecordedEvent> events = stopAndRead(recording);
            assertThat(events).as("at least the held + parked acquires are recorded").hasSizeGreaterThanOrEqualTo(2);
            assertThat(events).allSatisfy(event -> {
                assertThat(event.getString("providerId")).isEqualTo("postgres-community");
                assertThat(event.getString("tenantKey")).isEqualTo("shared");
                assertThat(event.getBoolean("fromPool")).isTrue();
                assertThat(event.getLong("acquireLatencyNs")).isGreaterThanOrEqualTo(0L);
            });
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("repeated virtual-thread acquire churn under JFR recording does not crash and records every event")
    void repeatedVirtualThreadAcquireUnderRecordingIsClean() throws Exception {
        int threads = 32;
        int acquiresPerThread = 16;
        PersistenceConfig config = config(/* maxPoolSize */ 4);

        try (HikariDataSource pool = CommunityHikariSupport.buildPool(config, null);
             Recording recording = new Recording()) {
            CommunityHikariSupport support = CommunityHikariSupport.with(pool);
            recording.enable(EVENT_NAME);
            recording.start();

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            for (int t = 0; t < threads; t++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < acquiresPerThread; i++) {
                            // maxPoolSize=4 with 32 contending virtual threads guarantees frequent
                            // parks → unmount/remount across the begin/commit window.
                            try (JdbcPersistenceConnection conn =
                                         support.acquireConnection("postgres-community", "shared", () -> { })) {
                                assertThat(conn).isNotNull();
                            }
                        }
                    } catch (Throwable th) {
                        failure.compareAndSet(null, th);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(45, TimeUnit.SECONDS)).as("all virtual threads finish").isTrue();
            assertThat(failure.get()).as("no acquire failed across the churn").isNull();

            List<RecordedEvent> events = stopAndRead(recording);
            assertThat(events)
                    .as("every acquire emits exactly one event")
                    .hasSize(threads * acquiresPerThread);
        }
    }

    private static List<RecordedEvent> stopAndRead(Recording recording) throws Exception {
        Path dump = Files.createTempFile("connection-acquire-jfr", ".jfr");
        try {
            recording.stop();
            recording.dump(dump);
            return RecordingFile.readAllEvents(dump).stream()
                    .filter(event -> EVENT_NAME.equals(event.getEventType().getName()))
                    .toList();
        } finally {
            Files.deleteIfExists(dump);
        }
    }

    private static PersistenceConfig config(int maxPoolSize) {
        return new PersistenceConfig(
                "jdbc:h2:mem:community_acquire_jfr_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                maxPoolSize,
                0,
                5_000L,
                60_000L,
                600_000L,
                false,
                false,
                false,
                0,
                Map.of()
        );
    }
}
