/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract;

import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Config;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Contract;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Result;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Meta-test for the writer side of the window marker: {@link JfrAllocationMonitor#measure} writes
 * exactly one {@code start}/{@code end} pair carrying the config, the workload thread and the
 * measured bytes delta — and the pair does not consume a zero-allocation contract's budget.
 *
 * <p>The reader side lives in {@code tools/jfr-reporter}, which has no dependency on this jar and
 * carries its own fixture writer; the end-to-end run in the pull request's verification is where the
 * two halves meet.
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

    private static List<RecordedEvent> readMarkers(Result result) throws IOException {
        List<RecordedEvent> markers = new ArrayList<>();
        try (RecordingFile jfr = new RecordingFile(result.recordingFile())) {
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
