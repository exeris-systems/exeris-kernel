/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.kernel.tck.fake.AllocationWindowFixtureEvent;
import eu.exeris.kernel.tck.fake.FakeZeroAllocTck;
import jdk.jfr.Recording;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real recording through the real reader and generator - no hand-built {@code RecordedEvent}.
 * The fixture allocates from a production-shaped frame beneath a harness-shaped frame, inside a
 * marker window. A second, JVM-wide recording ({@code surefire.jfr}) is open around it and sees two
 * measurements, the way the surefire-started recording does in a real test JVM.
 */
class ReporterRoundTripTest {

    private static final int ITERATIONS = 3;
    private static final String FILE = "FakeZeroAllocTckTest-Fake-20260911-120000.jfr";
    private static final String OTHER_THREAD = "not-the-workload-thread";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a production frame beneath a harness frame is production, the window grounds the verdict, and surefire.jfr is not a subsystem")
    void roundTrip() throws Exception {
        Path core = tmp.resolve("core");
        Path reports = core.resolve("jfr-reports");
        Files.createDirectories(reports);
        Path recording = reports.resolve(FILE);

        try (Recording jvmWide = new Recording()) {
            jvmWide.setDestination(core.resolve("surefire.jfr"));
            jvmWide.start();

            // Marker objects are allocated before the recording starts, exactly as JfrAllocationMonitor does.
            AllocationWindowFixtureEvent start = marker("start", 1L);
            AllocationWindowFixtureEvent end = marker("end", 1L);
            try (Recording rec = new Recording()) {
                rec.enable("jdk.ObjectAllocationInNewTLAB");
                rec.enable("jdk.ObjectAllocationOutsideTLAB");
                rec.enable("jdk.ObjectAllocationSample").with("throttle", "off");
                rec.setDestination(recording);
                rec.start();
                start.commit();
                long requested = FakeZeroAllocTck.runWorkload(ITERATIONS);
                // Inside the window, on another thread: a carrier still draining from a previous
                // test is the real shape of this. The window is wall-clock, so it cannot exclude
                // such a thread - only the workload-thread scope can.
                Thread other = new Thread(() -> FakeZeroAllocTck.runWorkload(2), OTHER_THREAD);
                other.start();
                other.join();
                end.allocatedBytesDelta = requested;
                end.commit();
                rec.stop();
            }

            // A second measurement the JVM-wide recording sees but no TCK file was written for.
            marker("start", 2L).commit();
            FakeZeroAllocTck.runWorkload(1);
            marker("end", 2L).commit();
            jvmWide.stop();
        }

        Path out = tmp.resolve("report");
        new ReportGenerator(Map.of("core", core), "deadbeef", "test", out).generate();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode evidence = mapper.readTree(out.resolve("evidence.json").toFile());
        JsonNode summary = mapper.readTree(out.resolve("jfr-summary.json").toFile());

        assertThat(evidence.path("meta").path("schema").asInt()).isEqualTo(ReportGenerator.SCHEMA_VERSION);
        assertThat(evidence.path("core").fieldNames()).toIterable().containsExactly("fake");

        JsonNode fake = evidence.path("core").path("fake");
        assertThat(fake.path("verdict").asText()).isEqualTo(ReportGenerator.VERDICT_PASS);
        JsonNode rec0 = fake.path("recordings").get(0);
        assertThat(rec0.path("file").asText()).isEqualTo(FILE);
        assertThat(rec0.path("test_class").asText()).isEqualTo("FakeZeroAllocTckTest");
        assertThat(rec0.path("window_source").asText()).isEqualTo("marker");
        assertThat(rec0.path("contract").path("mode").asText()).isEqualTo("bounded");
        assertThat(rec0.path("contract").path("iterations").asInt()).isEqualTo(ITERATIONS);
        assertThat(rec0.path("contract").path("allocated_bytes_delta").asLong())
                .isEqualTo((long) ITERATIONS * eu.exeris.kernel.core.fake.FakeHotPath.ARRAY_BYTES);

        assertThat(fake.path("exeris_production_alloc_count").asLong())
                .as("the array the fake hot path allocated is owned by production, whatever its type")
                .isGreaterThanOrEqualTo(1L);
        assertThat(fake.path("owned").path("production").path("by_kind").path("array").asLong())
                .isGreaterThanOrEqualTo(1L);
        assertThat(fake.path("top_production_frames").get(0).path("frame").asText())
                .startsWith("eu.exeris.kernel.core.fake.FakeHotPath.allocate(");

        JsonNode unattributed = summary.path("core").path("unattributed");
        assertThat(unattributed).hasSize(1);
        assertThat(unattributed.get(0).path("file").asText()).isEqualTo("surefire.jfr");
        assertThat(unattributed.get(0).path("reason").asText()).isEqualTo(ReportGenerator.REASON_MULTIPLE_WINDOWS);
        assertThat(unattributed.get(0).path("windows").asInt()).isEqualTo(2);
        assertThat(fake.path("recordings")).hasSize(1);
        assertThat(summary.path("core").path("phaseBoundaries")).hasSize(1);

        assertThat(out.resolve("core").resolve("fake").resolve("timeline.json")).exists();
        assertThat(out.resolve("core").resolve("alloc-top-classes.json")).exists();

        // The two scopes, pinned together: the subsystem answers for its workload thread, the
        // module's thread table answers for the JVM. Collapsing them either way breaks one of these.
        JsonNode timeline = mapper.readTree(out.resolve("core").resolve("fake").resolve("timeline.json").toFile());
        assertThat(timeline).isNotEmpty();
        assertThat(timeline).allSatisfy(row -> assertThat(row.path("thread").asText())
                .as("another thread's allocation must not be attributed to the subsystem")
                .isNotEqualTo(OTHER_THREAD));
        assertThat(summary.path("core").path("topThreads").toString())
                .as("but the thread table is a diagnostic of the whole JVM and must still name it")
                .contains(OTHER_THREAD);
    }

    private static AllocationWindowFixtureEvent marker(String boundary, long measurementId) {
        AllocationWindowFixtureEvent e = new AllocationWindowFixtureEvent();
        e.boundary = boundary;
        e.measurementId = measurementId;
        e.subsystem = "Fake";
        e.testClass = "FakeZeroAllocTckTest";
        e.iterations = ITERATIONS;
        e.workloadThreadId = Thread.currentThread().threadId();
        e.contractMode = "bounded";
        e.budgetPerIteration = 5;
        e.allocatedBytesDelta = -1L;
        return e;
    }
}
