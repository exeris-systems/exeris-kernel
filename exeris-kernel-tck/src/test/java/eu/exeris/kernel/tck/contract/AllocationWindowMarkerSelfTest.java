/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract;

import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Config;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Contract;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Result;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Meta-test for the writer side of the window marker: {@link JfrAllocationMonitor#measure} writes
 * exactly one {@code start}/{@code end} pair carrying the config, the workload thread and the
 * measured bytes delta — and the pair does not consume a zero-allocation contract's budget.
 *
 * <p>The reader side lives in {@code tools/jfr-reporter}, which has no dependency on this jar and
 * carries its own fixture writer; the end-to-end run in the pull request's verification is where the
 * two halves meet.
 *
 * <p><b>One half of the allocation-ordering invariant has no test here, deliberately.</b> That the
 * marker objects are allocated <em>before</em> {@code rec.start()} cannot be observed: they are two
 * small objects, so JFR's samplers will not see them whichever side of the call they land on — a
 * test asserting their absence from the recording passes against an implementation that allocates
 * them inside the window, which was measured, not assumed. What is pinned is the half that has
 * consequences: {@link #markerIsInvisibleToTheZeroContract} fails if either commit moves inside the
 * {@code ThreadMXBean} bracket.
 */
class AllocationWindowMarkerSelfTest {

    private static final String EVENT_NAME = "eu.exeris.tck.AllocationWindow";
    private static final int ITERATIONS = 10;

    @Test
    @DisplayName("measure() writes one start/end pair carrying config, thread and the measured delta")
    void markerPairCarriesTheMeasurement() throws IOException {
        Config config = new Config("Marker", getClass().getSimpleName(), 0, ITERATIONS, Contract.bounded(3));

        Result result = JfrAllocationMonitor.measure(config, iterations -> { });

        List<RecordedEvent> markers = readMarkers(result);
        assertThat(markers).as("exactly one start and one end").hasSize(2);
        RecordedEvent start = markers.get(0);
        RecordedEvent end = markers.get(1);

        assertThat(start.getString("boundary")).isEqualTo("start");
        assertThat(end.getString("boundary")).isEqualTo("end");
        assertThat(end.getStartTime()).isAfterOrEqualTo(start.getStartTime());
        for (RecordedEvent marker : markers) {
            assertThat(marker.getString("subsystem")).isEqualTo("Marker");
            assertThat(marker.getString("testClass")).isEqualTo(getClass().getSimpleName());
            assertThat(marker.getInt("iterations")).isEqualTo(ITERATIONS);
            assertThat(marker.getLong("workloadThreadId")).isEqualTo(Thread.currentThread().threadId());
            assertThat(marker.getThread().getJavaThreadId()).isEqualTo(Thread.currentThread().threadId());
            assertThat(marker.getString("contractMode")).isEqualTo("bounded");
            assertThat(marker.getInt("budgetPerIteration")).isEqualTo(3);
        }
        assertThat(start.getLong("allocatedBytesDelta")).isEqualTo(JfrAllocationMonitor.ALLOCATED_BYTES_UNAVAILABLE);
        assertThat(end.getLong("allocatedBytesDelta")).isEqualTo(result.allocatedBytesDelta());
    }

    @Test
    @DisplayName("the marker does not consume a zero-allocation contract: neither as an eu.exeris.* sample nor in the bytes delta")
    void markerIsInvisibleToTheZeroContract() throws IOException {
        Config config = new Config("Marker", getClass().getSimpleName(), 0, ITERATIONS, Contract.zero());

        Result result = JfrAllocationMonitor.measure(config, iterations -> { });

        assertThat(result.exerisAllocations()).isEmpty();
        assertThatCode(() -> JfrAllocationMonitor.assertZeroExerisAllocations(result, "an empty workload"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a four-argument Config states no contract, and the marker says so")
    void unspecifiedContractIsWrittenAsSuch() throws IOException {
        Config config = new Config("Marker", getClass().getSimpleName(), 0, ITERATIONS);
        assertThat(config.contract()).isEqualTo(Contract.unspecified());

        Result result = JfrAllocationMonitor.measure(config, iterations -> { });

        List<RecordedEvent> markers = readMarkers(result);
        assertThat(markers).hasSize(2);
        assertThat(markers.get(0).getString("contractMode")).isEqualTo("unspecified");
        assertThat(markers.get(0).getInt("budgetPerIteration")).isEqualTo(-1);
    }

    @Test
    @DisplayName("a workload that throws still closes the window, with no bytes delta to report")
    void aThrowingWorkloadStillClosesTheWindow() throws IOException {
        Config config = new Config("MarkerThrow", getClass().getSimpleName(), 0, ITERATIONS, Contract.zero());
        IllegalStateException boom = new IllegalStateException("the workload failed");

        assertThatThrownBy(() -> JfrAllocationMonitor.measure(config, iterations -> {
            throw boom;
        })).isSameAs(boom);

        // An orphaned start is not a local defect: the same marker lands in the JVM-wide
        // target/surefire.jfr, where a reader pairing boundaries in sequence would match it with a
        // LATER test's end and mis-attribute that test's measurement.
        List<RecordedEvent> markers = readMarkers(newestRecordingFor("MarkerThrow"));
        assertThat(markers).as("the window is closed, so it can still be paired").hasSize(2);
        assertThat(markers).extracting(e -> e.getString("boundary"))
                .as("a truncated window is not evidence: it must not close as an ordinary end")
                .containsExactlyInAnyOrder(JfrAllocationMonitor.BOUNDARY_START,
                        JfrAllocationMonitor.BOUNDARY_ABORT);
        RecordedEvent abort = boundary(markers, JfrAllocationMonitor.BOUNDARY_ABORT);
        assertThat(abort.getLong("allocatedBytesDelta"))
                .isEqualTo(JfrAllocationMonitor.ALLOCATED_BYTES_UNAVAILABLE);
        assertThat(abort.getLong("measurementId"))
                .isEqualTo(boundary(markers, JfrAllocationMonitor.BOUNDARY_START).getLong("measurementId"));
    }

    @Test
    @DisplayName("a warm-up that throws leaves no temp recording behind")
    void aThrowingWarmUpLeavesNoTempFile() throws IOException {
        Config config = new Config("MarkerWarmup", getClass().getSimpleName(), 1, ITERATIONS, Contract.zero());
        // Before/after, not emptiness: the temp directory is shared, and a file left by an earlier
        // run of this very test would otherwise fail a fixed build.
        List<Path> before = warmupTempFiles("MarkerWarmup");

        assertThatThrownBy(() -> JfrAllocationMonitor.measure(config, iterations -> {
            throw new IllegalStateException("the warm-up failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(warmupTempFiles("MarkerWarmup"))
                .as("the delete sat outside its try, so a throwing warm-up orphaned the file")
                .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    @DisplayName("two measurements sharing subsystem, test class and thread stay two measurements")
    void twoMeasurementsInOneJvmAreTwoMeasurements() throws IOException {
        Config config = new Config("MarkerPair", getClass().getSimpleName(), 0, ITERATIONS, Contract.zero());
        Path jvmWide = Files.createTempFile("tck-jfr-jvmwide-", ".jfr");
        try {
            // Surefire opens exactly this shape for the whole fork, which is how one file comes to
            // hold every subsystem's markers.
            try (Recording wide = new Recording()) {
                wide.setDestination(jvmWide);
                wide.start();
                JfrAllocationMonitor.measure(config, iterations -> { });
                JfrAllocationMonitor.measure(config, iterations -> { });
                wide.stop();
            }

            Map<Long, List<String>> boundariesById = readMarkers(jvmWide).stream()
                    .collect(Collectors.groupingBy(e -> e.getLong("measurementId"),
                            Collectors.mapping(e -> e.getString("boundary"), Collectors.toList())));

            assertThat(boundariesById)
                    .as("the config is identical, so only the measurement id tells them apart")
                    .hasSize(2);
            assertThat(boundariesById.values()).allSatisfy(boundaries ->
                    assertThat(boundaries).containsExactlyInAnyOrder(
                            JfrAllocationMonitor.BOUNDARY_START, JfrAllocationMonitor.BOUNDARY_END));
        } finally {
            Files.deleteIfExists(jvmWide);
        }
    }


    private static List<Path> warmupTempFiles(String subsystem) throws IOException {
        String prefix = "tck-jfr-warmup-" + subsystem + "-";
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(f -> f.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    /** The marker with this boundary; position in the file is not time order and cannot be used. */
    private static RecordedEvent boundary(List<RecordedEvent> markers, String boundary) {
        return markers.stream()
                .filter(e -> boundary.equals(e.getString("boundary")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + boundary + " marker"));
    }

    /** The recording {@code measure} wrote for this subsystem, when it threw before returning one. */
    private static Path newestRecordingFor(String subsystem) throws IOException {
        String prefix = AllocationWindowMarkerSelfTest.class.getSimpleName() + "-" + subsystem + "-";
        try (Stream<Path> files = Files.list(Path.of("target", "jfr-reports"))) {
            return files.filter(f -> f.getFileName().toString().startsWith(prefix))
                    .max(Comparator.comparing(f -> f.getFileName().toString()))
                    .orElseThrow(() -> new AssertionError("measure() wrote no recording for " + subsystem));
        }
    }

    private static List<RecordedEvent> readMarkers(Result result) throws IOException {
        return readMarkers(result.recordingFile());
    }

    private static List<RecordedEvent> readMarkers(Path recordingFile) throws IOException {
        List<RecordedEvent> markers = new ArrayList<>();
        try (RecordingFile jfr = new RecordingFile(recordingFile)) {
            while (jfr.hasMoreEvents()) {
                RecordedEvent e = jfr.readEvent();
                if (EVENT_NAME.equals(e.getEventType().getName())) {
                    markers.add(e);
                }
            }
        }
        return markers;
    }
}
