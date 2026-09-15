/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TckMarker#pairAll} against the shapes a JVM-wide recording actually holds: several
 * measurements in one file, a measurement whose start never closed, and a workload that threw.
 */
class MarkerPairingTest {

    private static final Instant T0 = Instant.parse("2026-09-11T10:00:00Z");

    private static TckMarker.Boundary boundary(String kind, long measurementId, long millisFromT0,
                                               String subsystem, String testClass, long bytesDelta) {
        return new TckMarker.Boundary(kind, T0.plusMillis(millisFromT0), 3L, measurementId, subsystem,
                testClass, 1000, 3L, "zero", -1, TckMarker.NO_BYTE_BUDGET, bytesDelta);
    }

    @Test
    @DisplayName("interleaved measurements stay separate: a second start does not steal the first's end")
    void aSecondStartDoesNotStealTheFirstEnd() {
        // The shape surefire.jfr holds when one fork runs two subsystems' TCKs.
        TckMarker.Boundary startA = boundary("start", 100L, 0, "EventBus", "EventBusTck", -1L);
        TckMarker.Boundary startB = boundary("start", 200L, 5, "FlowEngine", "FlowEngineTck", -1L);
        TckMarker.Boundary endA = boundary("end", 100L, 10, "EventBus", "EventBusTck", 641L);
        TckMarker.Boundary endB = boundary("end", 200L, 20, "FlowEngine", "FlowEngineTck", 1141L);

        TckMarker.Pairing pairing = TckMarker.pairAll(List.of(startA, startB, endA, endB));

        assertThat(pairing.clean()).isTrue();
        assertThat(pairing.windows()).hasSize(2);
        assertThat(pairing.windows()).extracting(TckMarker.Window::subsystem)
                .containsExactly("EventBus", "FlowEngine");
        assertThat(pairing.windows()).extracting(TckMarker.Window::allocatedBytesDelta)
                .as("each window carries its own measurement's bytes, not the other's")
                .containsExactly(641L, 1141L);
    }

    @Test
    @DisplayName("two measurements sharing subsystem, test class and thread are still two")
    void twoMeasurementsSharingSubsystemTestClassAndThreadStayTwo() {
        // AbstractCitadelGuardTck and AbstractStorageContextBridgeTck both run their contract in a
        // @Nested class called AllocationContract and both pass subsystem "Security", so the triple
        // is not a key. Only the measurement id separates them.
        // Interleaved on purpose: run sequentially, these two pair correctly even by accident, so
        // the test would pass against the defect it targets. Overlapping is what separates a real
        // correlation key from one that only works when nothing overlaps — including the tempting
        // composite key (subsystem, testClass, workloadThreadId), which these two share exactly.
        TckMarker.Boundary startA = boundary("start", 100L, 0, "Security", "AllocationContract", -1L);
        TckMarker.Boundary startB = boundary("start", 200L, 5, "Security", "AllocationContract", -1L);
        TckMarker.Boundary endA = boundary("end", 100L, 10, "Security", "AllocationContract", 64L);
        TckMarker.Boundary endB = boundary("end", 200L, 20, "Security", "AllocationContract", 128L);

        TckMarker.Pairing pairing = TckMarker.pairAll(List.of(startA, startB, endA, endB));

        assertThat(pairing.clean()).isTrue();
        assertThat(pairing.windows()).hasSize(2);
        assertThat(pairing.windows()).extracting(TckMarker.Window::allocatedBytesDelta)
                .as("the bytes must not be cross-wired between two measurements of one subsystem")
                .containsExactly(64L, 128L);
    }

    @Test
    @DisplayName("an orphaned start is reported, not silently overwritten by the next one")
    void anOrphanStartIsCountedNotSwallowed() {
        // What the ring buffer leaves behind when it drops a start but keeps its end, and what a
        // crashed workload left behind before the writer learned to close its window.
        TckMarker.Boundary orphan = boundary("start", 100L, 0, "EventBus", "EventBusTck", -1L);
        TckMarker.Boundary start = boundary("start", 100L, 5, "EventBus", "EventBusTck", -1L);
        TckMarker.Boundary end = boundary("end", 100L, 10, "EventBus", "EventBusTck", 641L);

        TckMarker.Pairing pairing = TckMarker.pairAll(List.of(orphan, start, end));

        assertThat(pairing.windows()).hasSize(1);
        assertThat(pairing.unpaired()).containsExactly(orphan);
        assertThat(pairing.clean())
                .as("one unpaired boundary is enough to disqualify the file as a measurement")
                .isFalse();
    }

    @Test
    @DisplayName("an aborted window is not a window")
    void anAbortedWindowIsNotAWindow() {
        TckMarker.Boundary start = boundary("start", 100L, 0, "Graph", "GraphTck", -1L);
        TckMarker.Boundary abort = boundary("abort", 100L, 10, "Graph", "GraphTck", -1L);

        TckMarker.Pairing pairing = TckMarker.pairAll(List.of(start, abort));

        assertThat(pairing.windows()).isEmpty();
        assertThat(pairing.aborted()).containsExactly(abort);
        assertThat(pairing.clean()).isFalse();
    }

    @Test
    @DisplayName("a recording written before the measurement id existed still pairs")
    void recordingsFromAnOlderJarStillPair() {
        long none = TckMarker.NO_MEASUREMENT_ID;
        TckMarker.Boundary startA = boundary("start", none, 0, "EventBus", "EventBusTck", -1L);
        TckMarker.Boundary endA = boundary("end", none, 10, "EventBus", "EventBusTck", 641L);
        TckMarker.Boundary startB = boundary("start", none, 12, "FlowEngine", "FlowEngineTck", -1L);
        TckMarker.Boundary endB = boundary("end", none, 20, "FlowEngine", "FlowEngineTck", 1141L);

        TckMarker.Pairing pairing = TckMarker.pairAll(List.of(endB, startA, endA, startB));

        assertThat(pairing.clean()).isTrue();
        assertThat(pairing.windows()).extracting(TckMarker.Window::subsystem)
                .containsExactly("EventBus", "FlowEngine");
        assertThat(pairing.windows()).extracting(TckMarker.Window::measurementId)
                .containsOnly(TckMarker.NO_MEASUREMENT_ID);
    }
}
