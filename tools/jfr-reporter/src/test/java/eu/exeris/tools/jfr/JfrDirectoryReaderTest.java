/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import eu.exeris.kernel.tck.fake.AllocationWindowFixtureEvent;
import jdk.jfr.AnnotationElement;
import jdk.jfr.Event;
import jdk.jfr.EventFactory;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.ValueDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a recording has to look like before the reader will call it a subsystem measurement.
 *
 * <p>The file name is the only identification a pre-marker recording has, and it is exactly the
 * shape a crashed measurement leaves behind — {@code JfrAllocationMonitor} builds the name before
 * it runs the workload. So a file that wrote boundaries it could not close must be refused on the
 * boundaries, not accepted on the name.
 */
class JfrDirectoryReaderTest {

    /** The name pattern RecordingIdentity accepts as a TCK measurement. */
    private static final String TCK_NAME = "CoreEventBusZeroAllocTckTest-EventBus-20260911-100000.jfr";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a file whose boundaries do not pair is not a measurement, however TCK-shaped its name")
    void anUnpairedBoundaryIsNotAMeasurement() throws IOException {
        Path file = write(List.of(marker("start", 1L), marker("end", 1L), marker("start", 2L)));

        JfrDirectoryReader.RecordingData data = JfrDirectoryReader.readFile(file);

        assertThat(data.identity().identified())
                .as("the name matches the TCK pattern, and that must not be enough")
                .isFalse();
        assertThat(data.pairing().unpaired()).hasSize(1);
        assertThat(ReportGenerator.partition(List.of(data)).unattributed())
                .extracting(ReportGenerator.Unattributed::reason)
                .containsExactly(ReportGenerator.REASON_UNPAIRED_BOUNDARY);
    }

    @Test
    @DisplayName("a window the workload aborted is not a measurement either")
    void anAbortedWindowIsNotAMeasurement() throws IOException {
        Path file = write(List.of(marker("start", 1L), marker("abort", 1L)));

        JfrDirectoryReader.RecordingData data = JfrDirectoryReader.readFile(file);

        assertThat(data.identity().identified()).isFalse();
        assertThat(ReportGenerator.partition(List.of(data)).unattributed())
                .extracting(ReportGenerator.Unattributed::reason)
                .containsExactly(ReportGenerator.REASON_ABORTED_WINDOW);
    }

    @Test
    @DisplayName("one clean pair is a measurement")
    void oneCleanPairIsAMeasurement() throws IOException {
        Path file = write(List.of(marker("start", 1L), marker("end", 1L)));

        JfrDirectoryReader.RecordingData data = JfrDirectoryReader.readFile(file);

        assertThat(data.identity().identified()).isTrue();
        assertThat(data.identity().subsystem()).isEqualTo("eventbus");
        assertThat(data.pairing().clean()).isTrue();
    }

    @Test
    @DisplayName("a marker from a differently-versioned TCK is skipped, and does not end the run")
    void aMarkerWithASkewedSchemaDoesNotEndTheRun() throws IOException {
        // The field set is the contract between two Maven builds with no dependency between them,
        // so a recording written by another exeris-kernel-tck is the expected skew, not an exotic
        // one: parse-jfr-to-json reads recordings that arrive as downloaded artefacts.
        EventFactory factory = EventFactory.create(
                List.of(new AnnotationElement(Name.class, TckMarker.EVENT_NAME)),
                List.of(new ValueDescriptor(String.class, TckMarker.F_BOUNDARY),
                        new ValueDescriptor(String.class, TckMarker.F_SUBSYSTEM)));
        factory.register();

        Path file = tmp.resolve(TCK_NAME);
        try (Recording rec = new Recording()) {
            rec.setDestination(file);
            rec.start();
            Event skewed = factory.newEvent();
            skewed.set(0, "start");
            skewed.set(1, "EventBus");
            skewed.commit();
            rec.stop();
        }

        JfrDirectoryReader.RecordingData data = JfrDirectoryReader.readFile(file);

        assertThat(data.identity().identified())
                .as("an unreadable marker must not leave the file to be identified by its name")
                .isFalse();
        assertThat(data.pairing().windows()).isEmpty();
    }

    private Path write(List<AllocationWindowFixtureEvent> markers) throws IOException {
        Path file = tmp.resolve(TCK_NAME);
        Files.createDirectories(tmp);
        try (Recording rec = new Recording()) {
            rec.setDestination(file);
            rec.start();
            markers.forEach(AllocationWindowFixtureEvent::commit);
            rec.stop();
        }
        return file;
    }

    private static AllocationWindowFixtureEvent marker(String boundary, long measurementId) {
        AllocationWindowFixtureEvent e = new AllocationWindowFixtureEvent();
        e.boundary = boundary;
        e.measurementId = measurementId;
        e.subsystem = "EventBus";
        e.testClass = "CoreEventBusZeroAllocTckTest";
        e.iterations = 1000;
        e.workloadThreadId = Thread.currentThread().threadId();
        e.contractMode = "zero";
        e.budgetPerIteration = -1;
        e.allocatedBytesDelta = -1L;
        return e;
    }
}
