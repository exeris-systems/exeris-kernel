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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionTest {

    private static final Instant T0 = Instant.parse("2026-09-11T10:00:00Z");

    private static TckMarker.Window window(Instant start) {
        return new TckMarker.Window(start, start.plusMillis(10), "EventBus", "CommunityEventBusZeroAllocTckTest",
                100, 41L, "bounded", 4, 4096L);
    }

    private static RecordingData withWindows(String name, List<TckMarker.Window> windows, int events) {
        RecordingIdentity id = windows.size() == 1
                ? RecordingIdentity.fromMarker(windows.get(0).subsystem(), windows.get(0).testClass())
                : (windows.isEmpty() ? RecordingIdentity.fromFilename(name) : RecordingIdentity.none());
        List<AllocEvent> list = java.util.Collections.nCopies(events, null);
        return new RecordingData(Path.of(name), id, windows, list);
    }

    @Test
    @DisplayName("a pair is found by time, not by file order - JFR flushes the end marker first when it likes")
    void pairingIsByTimeNotFileOrder() {
        TckMarker.Boundary start = new TckMarker.Boundary("start", T0, 3L, "Persistence",
                "CommunityPersistenceZeroAllocTckTest", 1000, 3L, "bounded", 64, -1L);
        TckMarker.Boundary end = new TckMarker.Boundary("end", T0.plusMillis(28), 3L, "Persistence",
                "CommunityPersistenceZeroAllocTckTest", 1000, 3L, "bounded", 64, 7_752_024L);

        List<TckMarker.Window> windows = TckMarker.pairAll(List.of(end, start));

        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).start()).isEqualTo(T0);
        assertThat(windows.get(0).end()).isEqualTo(T0.plusMillis(28));
        assertThat(windows.get(0).allocatedBytesDelta()).isEqualTo(7_752_024L);
        assertThat(TckMarker.pairAll(List.of(end))).isEmpty();
        assertThat(TckMarker.pairAll(List.of(start))).isEmpty();
    }

    @Test
    @DisplayName("the JVM-wide recording that saw one measurement is a duplicate of that measurement's own file")
    void duplicateWindowLosesToTheTckFile() {
        RecordingData tck = withWindows("CommunityEventBusZeroAllocTckTest-EventBus-20260911-100000.jfr", List.of(window(T0)), 50);
        RecordingData jvmWide = withWindows("surefire.jfr", List.of(window(T0)), 5_000);

        ReportGenerator.Partition p = ReportGenerator.partition(List.of(jvmWide, tck));

        assertThat(p.bySubsystem()).containsOnlyKeys("eventbus");
        assertThat(p.bySubsystem().get("eventbus")).containsExactly(tck);
        assertThat(p.unattributed()).hasSize(1);
        assertThat(p.unattributed().get(0).recording()).isSameAs(jvmWide);
        assertThat(p.unattributed().get(0).reason()).isEqualTo(ReportGenerator.REASON_DUPLICATE_WINDOW);
    }

    @Test
    @DisplayName("a file with several windows is JVM-wide, a file with none and no TCK name is not a measurement")
    void multipleWindowsAndPlainFiles() {
        RecordingData jvmWide = withWindows("surefire.jfr", List.of(window(T0), window(T0.plusSeconds(5))), 9_000);
        RecordingData jmh = withWindows("jmh-benchmarks.jfr", List.of(), 300);
        RecordingData pin = withWindows("pin-eventbus-steady-20260911-100000.jfr", List.of(), 0);
        RecordingData old = withWindows("CoreFlowZeroAllocTckTest-FlowEngine-20260805-120000.jfr", List.of(), 40);

        ReportGenerator.Partition p = ReportGenerator.partition(List.of(jvmWide, jmh, pin, old));

        assertThat(p.bySubsystem()).containsOnlyKeys("flowengine");
        assertThat(p.unattributed()).extracting(ReportGenerator.Unattributed::reason).containsExactly(
                ReportGenerator.REASON_MULTIPLE_WINDOWS,
                ReportGenerator.REASON_NOT_A_MEASUREMENT,
                ReportGenerator.REASON_NOT_A_MEASUREMENT);
    }
}
