/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import eu.exeris.tools.jfr.JfrDirectoryReader.RecordingData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The verdict reproduces the TCK's own rule from the marker, and refuses to guess without one. */
class ContractEvaluationTest {

    private static final Instant T0 = Instant.parse("2026-09-11T10:00:00Z");
    private static final long WORKLOAD = 41L;
    private static final long OTHER = 99L;

    private static AllocEvent exeris(long threadId, long offsetMillis) {
        Frame owner = new Frame("eu.exeris.kernel.core.flow.CoreFlowRuntime", "launch", 1);
        return new AllocEvent(T0.plusMillis(offsetMillis), SizePolicy.SAMPLE,
                "eu.exeris.kernel.core.flow.FlowKey", "main", threadId, 100L,
                SizePolicy.SizeKind.SAMPLE_WEIGHT, 0L, List.of(owner), owner, Owner.PRODUCTION, ObjectKind.EXERIS);
    }

    private static AllocEvent loom(long threadId) {
        Frame owner = new Frame("eu.exeris.kernel.core.events.InMemoryEventBus", "publish", 1);
        return new AllocEvent(T0.plusMillis(5), SizePolicy.SAMPLE,
                "java.lang.VirtualThread", "main", threadId, 100L,
                SizePolicy.SizeKind.SAMPLE_WEIGHT, 0L, List.of(owner), owner, Owner.PRODUCTION, ObjectKind.LOOM);
    }

    private static RecordingData recording(String mode, int budget, int iterations, long bytesDelta, List<AllocEvent> events) {
        return recording(mode, budget, TckMarker.NO_BYTE_BUDGET, iterations, bytesDelta, events);
    }

    private static RecordingData recording(String mode, int budget, double budgetBytes, int iterations,
                                           long bytesDelta, List<AllocEvent> events) {
        TckMarker.Window w = new TckMarker.Window(T0, T0.plusMillis(10), "Flow", "FakeTest",
                iterations, WORKLOAD, mode, budget, budgetBytes, bytesDelta, 7L);
        return new RecordingData(Path.of("x.jfr"), RecordingIdentity.fromMarker("Flow", "FakeTest"),
                TckMarker.Pairing.of(List.of(w)), events);
    }

    @Test
    @DisplayName("bounded: events on the workload thread within budget pass, one over fails")
    void boundedBudget() {
        List<AllocEvent> within = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            within.add(exeris(WORKLOAD, 1));
        }
        assertThat(ReportGenerator.evaluate(recording("bounded", 2, 10, 4096L, within)).verdict())
                .isEqualTo(ReportGenerator.VERDICT_PASS);

        within.add(exeris(WORKLOAD, 1));
        ReportGenerator.ContractResult over = ReportGenerator.evaluate(recording("bounded", 2, 10, 4096L, within));
        assertThat(over.verdict()).isEqualTo(ReportGenerator.VERDICT_FAIL);
        assertThat(over.exerisEvents()).isEqualTo(21L);
        assertThat(over.bytesPerIteration()).isEqualTo(409.6d);
    }

    @Test
    @DisplayName("the contract count is the TCK's: eu.exeris.* typed, workload thread, inside the window")
    void contractCountIsTypeAndThreadScoped() {
        List<AllocEvent> events = List.of(
                exeris(OTHER, 1),          // another thread: not counted
                exeris(WORKLOAD, 20),      // after the window: not counted
                loom(WORKLOAD));           // production-owned, but not eu.exeris typed: not counted
        ReportGenerator.ContractResult r = ReportGenerator.evaluate(recording("zero", -1, 10, 0L, events));
        assertThat(r.exerisEvents()).isZero();
        assertThat(r.verdict()).isEqualTo(ReportGenerator.VERDICT_PASS);
    }

    @Test
    @DisplayName("zero: one typed event fails; a bytes delta at the iteration count fails; an unavailable delta is skipped")
    void zeroMode() {
        assertThat(ReportGenerator.evaluate(recording("zero", -1, 10, 0L, List.of(exeris(WORKLOAD, 1)))).verdict())
                .isEqualTo(ReportGenerator.VERDICT_FAIL);
        assertThat(ReportGenerator.evaluate(recording("zero", -1, 10, 10L, List.of())).verdict())
                .isEqualTo(ReportGenerator.VERDICT_FAIL);
        assertThat(ReportGenerator.evaluate(recording("zero", -1, 10, 9L, List.of())).verdict())
                .isEqualTo(ReportGenerator.VERDICT_PASS);
        assertThat(ReportGenerator.evaluate(recording("zero", -1, 10, TckMarker.BYTES_UNAVAILABLE, List.of())).verdict())
                .isEqualTo(ReportGenerator.VERDICT_PASS);
    }

    @Test
    @DisplayName("bounded-bytes: under the byte budget passes, at it fails, and no delta is not a pass")
    void boundedBytesBudget() {
        // The graph churn TCK's bound expressed the way the marker can carry it: 23.0x over a
        // 500-id, 16-byte payload is 184 000 bytes per iteration.
        double budget = 23.0 * 500 * 16;

        assertThat(ReportGenerator.evaluate(
                recording("bounded-bytes", -1, budget, 10, (long) (budget * 10) - 10, List.of())).verdict())
                .isEqualTo(ReportGenerator.VERDICT_PASS);

        assertThat(ReportGenerator.evaluate(
                recording("bounded-bytes", -1, budget, 10, (long) (budget * 10), List.of())).verdict())
                .as("the TCK asserts isLessThan, so equal to the budget is a breach")
                .isEqualTo(ReportGenerator.VERDICT_FAIL);

        ReportGenerator.ContractResult noDelta = ReportGenerator.evaluate(
                recording("bounded-bytes", -1, budget, 10, TckMarker.BYTES_UNAVAILABLE, List.of()));
        assertThat(noDelta.verdict())
                .as("a JVM that cannot report allocated bytes leaves nothing to certify")
                .isEqualTo(ReportGenerator.VERDICT_NOT_MEASURED);
        assertThat(noDelta.notMeasuredReason()).isEqualTo(ReportGenerator.NOT_MEASURED_BYTES_UNAVAILABLE);
    }

    @Test
    @DisplayName("a value out of range in the marker is NOT_MEASURED with a reason - never a FAIL")
    void untrustedMarkerValuesAreNotAVerdict() {
        // The writer validates these, so the only way to see them is a file written by something
        // else: a different exeris-kernel-tck, or a corrupt recording. subsystemVerdict propagates
        // any FAIL to the whole subsystem, so rendering one here would publish an accusation
        // sourced from an unreadable file.
        ReportGenerator.ContractResult negativeBudget =
                ReportGenerator.evaluate(recording("bounded", -4, 10, 0L, List.of()));
        assertThat(negativeBudget.verdict()).isEqualTo(ReportGenerator.VERDICT_NOT_MEASURED);
        assertThat(negativeBudget.notMeasuredReason()).isEqualTo(ReportGenerator.NOT_MEASURED_INVALID_BUDGET);

        ReportGenerator.ContractResult noIterations =
                ReportGenerator.evaluate(recording("zero", -1, 0, 0L, List.of()));
        assertThat(noIterations.verdict()).isEqualTo(ReportGenerator.VERDICT_NOT_MEASURED);
        assertThat(noIterations.notMeasuredReason()).isEqualTo(ReportGenerator.NOT_MEASURED_INVALID_ITERATIONS);

        ReportGenerator.ContractResult unknownMode =
                ReportGenerator.evaluate(recording("Bounded", 1, 10, 0L, List.of()));
        assertThat(unknownMode.verdict()).isEqualTo(ReportGenerator.VERDICT_NOT_MEASURED);
        assertThat(unknownMode.notMeasuredReason())
                .isEqualTo(ReportGenerator.NOT_MEASURED_UNKNOWN_MODE + "Bounded");

        ReportGenerator.ContractResult nullMode =
                ReportGenerator.evaluate(recording(null, 1, 10, 0L, List.of()));
        assertThat(nullMode.verdict())
                .as("a String switch on a null mode threw, and nothing up the chain caught it")
                .isEqualTo(ReportGenerator.VERDICT_NOT_MEASURED);
    }

    @Test
    @DisplayName("no marker, or an unspecified mode, is NOT_MEASURED - never a pass")
    void notMeasured() {
        RecordingData noMarker = new RecordingData(Path.of("CoreFlowZeroAllocTckTest-FlowEngine-20260805-120000.jfr"),
                RecordingIdentity.fromFilename("CoreFlowZeroAllocTckTest-FlowEngine-20260805-120000.jfr"),
                TckMarker.Pairing.of(List.of()), List.of());
        assertThat(ReportGenerator.evaluate(noMarker).verdict()).isEqualTo(ReportGenerator.VERDICT_NOT_MEASURED);
        assertThat(ReportGenerator.evaluate(noMarker).notMeasuredReason())
                .isEqualTo(ReportGenerator.NOT_MEASURED_NO_WINDOW);
        assertThat(ReportGenerator.evaluate(recording("unspecified", -1, 10, 0L, List.of())).verdict())
                .isEqualTo(ReportGenerator.VERDICT_NOT_MEASURED);
        assertThat(ReportGenerator.evaluate(recording("unspecified", -1, 10, 0L, List.of())).notMeasuredReason())
                .as("a TCK that stated no contract must be distinguishable from a file this reader could not read")
                .isEqualTo(ReportGenerator.NOT_MEASURED_UNSPECIFIED);

        assertThat(ReportGenerator.subsystemVerdict(List.of(ReportGenerator.ContractResult.notMeasured(ReportGenerator.NOT_MEASURED_NO_WINDOW))))
                .isEqualTo(ReportGenerator.VERDICT_NOT_MEASURED);
        assertThat(ReportGenerator.subsystemVerdict(List.of(
                ReportGenerator.ContractResult.notMeasured(ReportGenerator.NOT_MEASURED_NO_WINDOW),
                ReportGenerator.evaluate(recording("bounded", 1, 10, 0L, List.of())))))
                .isEqualTo(ReportGenerator.VERDICT_PASS);
        assertThat(ReportGenerator.subsystemVerdict(List.of(
                ReportGenerator.evaluate(recording("bounded", 1, 10, 0L, List.of())),
                ReportGenerator.evaluate(recording("zero", -1, 10, 0L, List.of(exeris(WORKLOAD, 1)))))))
                .isEqualTo(ReportGenerator.VERDICT_FAIL);
    }
}
