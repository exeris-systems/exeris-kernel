/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import com.sun.management.ThreadMXBean;
import eu.exeris.kernel.core.contract.jfr.ContractViolationReporterTest;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;
import eu.exeris.kernel.spi.contract.WorkloadEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DeveloperWorkloadAdmission: GCRA request ceiling and bounded connection counter")
class DeveloperWorkloadAdmissionTest {

    private static final long MS = 1_000_000L;
    private static final WorkloadEnvelope DEVELOPER = new WorkloadEnvelope(50, 10, 0);

    private final ManualTimeSource time = new ManualTimeSource(1_000_000_000_000L);

    private DeveloperWorkloadAdmission gate(String id, WorkloadEnvelope envelope) {
        return new DeveloperWorkloadAdmission(id, envelope, time);
    }

    @Test
    @DisplayName("is enforced, whatever the envelope")
    void isEnforced() {
        assertThat(gate("EXR-ENFORCED", DEVELOPER).enforced()).isTrue();
    }

    @Test
    @DisplayName("a quiet gate admits one second of requests at once and refuses the next")
    void burstIsOneSecond() {
        DeveloperWorkloadAdmission gate = gate("EXR-BURST", DEVELOPER);

        for (int i = 0; i < 50; i++) {
            assertThat(gate.tryAdmitRequest()).as("request %d of the burst", i + 1).isTrue();
        }
        assertThat(gate.tryAdmitRequest()).as("request 51").isFalse();
    }

    @Test
    @DisplayName("after one emission interval exactly one more request is admitted")
    void oneIntervalAdmitsOne() {
        DeveloperWorkloadAdmission gate = gate("EXR-INTERVAL", DEVELOPER);
        for (int i = 0; i < 50; i++) {
            gate.tryAdmitRequest();
        }

        time.advance(20 * MS - 1);
        assertThat(gate.tryAdmitRequest()).as("one nanosecond short of the interval").isFalse();
        time.advance(1);
        assertThat(gate.tryAdmitRequest()).as("at the interval").isTrue();
        assertThat(gate.tryAdmitRequest()).as("the interval is spent").isFalse();
    }

    @Test
    @DisplayName("an idle gate does not bank more than one second of requests")
    void idleTimeDoesNotAccumulate() {
        DeveloperWorkloadAdmission gate = gate("EXR-IDLE", DEVELOPER);
        time.advance(60_000 * MS);

        int admitted = 0;
        while (gate.tryAdmitRequest()) {
            admitted++;
        }

        assertThat(admitted).isEqualTo(50);
    }

    @Test
    @DisplayName("a ceiling of zero refuses every request and every connection")
    void zeroRefusesEverything() {
        DeveloperWorkloadAdmission gate = gate("EXR-ZERO", new WorkloadEnvelope(0, 0, 0));

        assertThat(gate.tryAdmitRequest()).isFalse();
        assertThat(gate.tryAdmitConnection()).isFalse();
        time.advance(3_600_000 * MS);
        assertThat(gate.tryAdmitRequest()).isFalse();
    }

    @Test
    @DisplayName("an unlimited envelope admits without bound")
    void unlimitedAdmits() {
        DeveloperWorkloadAdmission gate = gate("EXR-UNLIMITED", WorkloadEnvelope.UNLIMITED);

        for (int i = 0; i < 100_000; i++) {
            assertThat(gate.tryAdmitRequest()).isTrue();
        }
        for (int i = 0; i < 1_000; i++) {
            assertThat(gate.tryAdmitConnection()).isTrue();
        }
    }

    @Test
    @DisplayName("16 racing threads under a held clock admit exactly the burst, never more")
    void concurrentRequestsNeverExceedTheBound() throws Exception {
        DeveloperWorkloadAdmission gate = gate("EXR-RACE-REQ", DEVELOPER);
        AtomicInteger admitted = new AtomicInteger();

        race(16, () -> {
            for (int i = 0; i < 500; i++) {
                if (gate.tryAdmitRequest()) {
                    admitted.incrementAndGet();
                }
            }
        });

        assertThat(admitted).hasValue(50);
    }

    @Test
    @DisplayName("16 racing threads holding their connections admit exactly the ceiling")
    void concurrentConnectionsNeverExceedTheCeiling() throws Exception {
        DeveloperWorkloadAdmission gate = gate("EXR-RACE-CONN", DEVELOPER);
        AtomicInteger admitted = new AtomicInteger();

        race(16, () -> {
            for (int i = 0; i < 500; i++) {
                if (gate.tryAdmitConnection()) {
                    admitted.incrementAndGet();
                }
            }
        });

        assertThat(admitted).hasValue(10);
    }

    @Test
    @DisplayName("10 connections are admitted, the 11th is refused, and a release frees exactly one slot")
    void connectionCeilingAndRelease() {
        DeveloperWorkloadAdmission gate = gate("EXR-CONN", DEVELOPER);
        for (int i = 0; i < 10; i++) {
            assertThat(gate.tryAdmitConnection()).as("connection %d", i + 1).isTrue();
        }
        assertThat(gate.tryAdmitConnection()).as("connection 11").isFalse();

        gate.connectionClosed();

        assertThat(gate.tryAdmitConnection()).as("the freed slot").isTrue();
        assertThat(gate.tryAdmitConnection()).as("only one slot was freed").isFalse();
    }

    @Test
    @DisplayName("a thousand admit and release cycles leave the counter at zero")
    void admitReleaseCyclesBalance() {
        DeveloperWorkloadAdmission gate = gate("EXR-CYCLES", DEVELOPER);
        for (int i = 0; i < 1_000; i++) {
            assertThat(gate.tryAdmitConnection()).isTrue();
            gate.connectionClosed();
        }

        for (int i = 0; i < 10; i++) {
            assertThat(gate.tryAdmitConnection()).as("slot %d is free", i + 1).isTrue();
        }
        assertThat(gate.tryAdmitConnection()).isFalse();
    }

    @Test
    @DisplayName("a release with nothing held leaves the ceiling where it was")
    void doubleReleaseDoesNotRaiseTheCeiling() {
        DeveloperWorkloadAdmission gate = gate("EXR-DOUBLE-RELEASE", DEVELOPER);
        assertThat(gate.tryAdmitConnection()).isTrue();
        gate.connectionClosed();
        gate.connectionClosed();
        gate.connectionClosed();

        int admitted = 0;
        while (gate.tryAdmitConnection()) {
            admitted++;
        }

        assertThat(admitted).as("connections admitted after surplus releases").isEqualTo(10);
    }

    @Test
    @DisplayName("the first refusal of each kind is reported once at HARD, later refusals report nothing")
    void firstRefusalOfEachKindIsReportedOnce() {
        DeveloperWorkloadAdmission gate = gate("EXR-REPORT", new WorkloadEnvelope(1, 1, 0));

        List<LogRecord> records = ContractViolationReporterTest.captureWarnings(() -> {
            gate.tryAdmitRequest();
            for (int i = 0; i < 5; i++) {
                gate.tryAdmitRequest();
            }
            gate.tryAdmitConnection();
            for (int i = 0; i < 5; i++) {
                gate.tryAdmitConnection();
            }
        });

        assertThat(records).hasSize(2);
        assertThat(records).extracting(r -> MessageFormat.format(r.getMessage(), r.getParameters()))
                .anySatisfy(m -> assertThat(m).contains("EXR-REPORT", "workloadEnvelope", "HARD", "maxThroughputRps"))
                .anySatisfy(m -> assertThat(m).contains("EXR-REPORT", "workloadEnvelope", "HARD", "maxConnections"));
    }

    @Test
    @DisplayName("admit, refuse and release allocate nothing once warm")
    void hotPathAllocatesNothing() {
        DeveloperWorkloadAdmission refusing = gate("EXR-ALLOC-REFUSE", new WorkloadEnvelope(1, 1, 0));
        DeveloperWorkloadAdmission admitting = gate("EXR-ALLOC-ADMIT", WorkloadEnvelope.UNLIMITED);
        refusing.tryAdmitRequest();
        refusing.tryAdmitConnection();
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < 200_000; i++) {
            exercise(refusing, admitting);
        }

        long before = threads.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 100_000; i++) {
            exercise(refusing, admitting);
        }
        long delta = threads.getThreadAllocatedBytes(thread) - before;

        assertThat(delta).as("bytes allocated across 100000 admit, refuse and release rounds").isZero();
    }

    private static void exercise(WorkloadAdmission refusing, WorkloadAdmission admitting) {
        refusing.tryAdmitRequest();
        refusing.tryAdmitConnection();
        admitting.tryAdmitRequest();
        if (admitting.tryAdmitConnection()) {
            admitting.connectionClosed();
        }
    }

    private static void race(int threads, Runnable body) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread.ofPlatform().start(() -> {
                ready.countDown();
                try {
                    go.await();
                    body.run();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        go.countDown();
        done.await();
    }
}
