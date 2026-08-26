/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the hold-side half of pool telemetry.
 *
 * <p>The gap this covers is not "an event is missing" but "the only hold measurement the kernel had
 * was emitted from {@code PersistenceSessionBox}, so it could only see connections a request session
 * owned". A caller with no request session — a flow step, a job, a migration — produced an acquire
 * event and nothing else, which made pool residency for background work unmeasurable and made the
 * absence of session events on those threads look like a finding rather than a property of the
 * instrument. These assertions are therefore about the **discriminators**: an event that fired but
 * reported the wrong scope would be worse than no event.
 *
 * @since 0.12.0
 */
@DisplayName("Community: connection hold telemetry covers callers with no request session")
class CommunityConnectionHoldEventTest {

    private static final String HOLD_EVENT = "eu.exeris.kernel.persistence.ConnectionHold";
    private static final long HOLD_MILLIS = 60L;

    private static PersistenceConfig testConfig() {
        return new PersistenceConfig(
                "jdbc:h2:mem:conn_hold_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "", 4, 1, 5_000L, 60_000L, 600_000L, false, false, false, 0, Map.of());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("a bare acquire outside any request scope is reported, and reported as such")
    void bareAcquireOutsideRequestScopeIsReported() throws Exception {
        RecordedEvent event = captureHold(engine -> {
            try (PersistenceConnection conn = engine.openConnection()) {
                assertThat(conn).isNotNull();
                Thread.sleep(HOLD_MILLIS);
            }
        });

        // The whole point of the slice: this path emitted no hold measurement at all before.
        assertThat(event.getBoolean("withinRequestScope"))
                .as("no request session was bound, so the hold MUST NOT be attributed to one")
                .isFalse();
        assertThat(event.getLong("holdDurationNs"))
                .as("hold duration MUST cover the time the connection was actually kept")
                .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(HOLD_MILLIS) / 2);
        assertThat(event.getString("tenantKey")).isNotBlank();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("an acquire inside a bound request session is reported as within request scope")
    void acquireInsideRequestSessionIsReportedAsRequestScoped() throws Exception {
        RecordedEvent event = captureHold(engine -> {
            PersistenceSessionBox box =
                    new PersistenceSessionBox(engine, TransactionIsolation.READ_COMMITTED, false);
            try {
                ScopedValue.where(PersistenceSessionBox.REQUEST_SESSION, box).run(() -> {
                    box.getOrAcquire();
                    try {
                        Thread.sleep(HOLD_MILLIS);
                    } catch (InterruptedException cause) {
                        Thread.currentThread().interrupt();
                    }
                });
            } finally {
                // The real pool return happens here, not on the handle: the connection the box hands
                // out is non-owning and its close() is a no-op.
                box.release();
            }
        });

        assertThat(event.getBoolean("withinRequestScope"))
                .as("a request session was bound on the acquiring thread")
                .isTrue();
        assertThat(event.getLong("holdDurationNs"))
                .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(HOLD_MILLIS) / 2);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("an acquire on a bare virtual thread carries the background-work signature")
    void acquireOnVirtualThreadCarriesTheBackgroundSignature() throws Exception {
        RecordedEvent event = captureHold(engine -> {
            // The shape a flow step has: Thread.ofVirtual(), no inherited ScopedValue, so no request
            // session. This pair of flags is what makes pool residency apportionable at all.
            Thread worker = Thread.ofVirtual().unstarted(() -> {
                try (PersistenceConnection conn = engine.openConnection()) {
                    assertThat(conn).isNotNull();
                    Thread.sleep(HOLD_MILLIS);
                } catch (InterruptedException cause) {
                    Thread.currentThread().interrupt();
                } catch (Exception cause) {
                    throw new IllegalStateException(cause);
                }
            });
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException cause) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(event.getBoolean("acquiredOnVirtualThread"))
                .as("the acquiring thread was virtual")
                .isTrue();
        assertThat(event.getBoolean("withinRequestScope"))
                .as("a bare virtual thread inherits no ScopedValue, so no request session is in scope")
                .isFalse();
    }

    /** Runs {@code work} against a fresh engine with a recording open, returning the first hold event. */
    private RecordedEvent captureHold(EngineWork work) throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();

        try (PersistenceEngine engine = new CommunityPersistenceProvider().createEngine(testConfig());
             RecordingStream rs = new RecordingStream()) {
            rs.enable(HOLD_EVENT);
            rs.onEvent(HOLD_EVENT, event -> {
                if (captured.compareAndSet(null, event)) {
                    received.countDown();
                }
            });
            rs.startAsync();

            work.run(engine);

            assertThat(received.await(10, TimeUnit.SECONDS))
                    .as("%s MUST be emitted when the connection goes back to the pool", HOLD_EVENT)
                    .isTrue();
        }
        return captured.get();
    }

    @FunctionalInterface
    private interface EngineWork {
        void run(PersistenceEngine engine) throws Exception;
    }
}
