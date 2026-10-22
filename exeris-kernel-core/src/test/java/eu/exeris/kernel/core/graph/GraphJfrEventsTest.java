/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.graph;

import eu.exeris.kernel.spi.exceptions.graph.GraphSyncException;
import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.algorithm.EdgeWeightFunction;
import eu.exeris.kernel.spi.graph.algorithm.PathFinder;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphPathSpec;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each path-finder call and each graph sync outcome commits exactly one event, carrying its fields
 * and, in {@code elapsedNanos}, a duration that covers the work it measures.
 *
 * <p>The stubs occupy a known span of wall-clock time, so a duration measured from the wrong
 * starting point, or after the work, falls short of that span and fails.
 */
@DisplayName("Graph: algorithm and sync events are single-phase and carry elapsed time")
class GraphJfrEventsTest {

    private static final String ALGO = "eu.exeris.kernel.graph.AlgoOrchestrator";
    private static final String SYNC = "eu.exeris.kernel.graph.SyncOperation";
    private static final UUID SOURCE = new UUID(0, 1);
    private static final UUID TARGET = new UUID(0, 2);
    private static final int EXPECTED_EVENTS = 5;
    private static final long WORK_NANOS = Duration.ofMillis(2).toNanos();

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("each algorithm call and each sync outcome commits one event with its fields")
    void eventsCarryFieldsAndElapsedTime() throws InterruptedException {
        Queue<RecordedEvent> events = new ConcurrentLinkedQueue<>();
        CountDownLatch allSeen = new CountDownLatch(EXPECTED_EVENTS);

        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(ALGO);
            rs.enable(SYNC);
            rs.onEvent(ALGO, e -> {
                events.add(e);
                allSeen.countDown();
            });
            rs.onEvent(SYNC, e -> {
                events.add(e);
                allSeen.countDown();
            });
            CountDownLatch streaming = new CountDownLatch(1);
            rs.onFlush(streaming::countDown);
            rs.startAsync();
            assertThat(streaming.await(10, TimeUnit.SECONDS))
                    .as("the recording stream must be live before any event is emitted").isTrue();

            AlgoOrchestrator orchestrator = new AlgoOrchestrator(new FixedPathFinder());
            orchestrator.findShortestPath(SOURCE, TARGET, EdgeWeightFunction.DEFAULT, false);
            orchestrator.findShortestPaths(SOURCE, Set.of(TARGET), EdgeWeightFunction.DEFAULT);
            orchestrator.findKShortestPaths(SOURCE, TARGET, 2, EdgeWeightFunction.DEFAULT);

            new GraphSyncService(engineFailing(false)).syncNodeDelete("User", SOURCE);
            assertThatThrownBy(() -> new GraphSyncService(engineFailing(true)).syncNodeDelete("User", SOURCE))
                    .isInstanceOf(GraphSyncException.class);

            assertThat(allSeen.await(20, TimeUnit.SECONDS))
                    .as("every algorithm call and sync outcome must emit exactly one event").isTrue();
        }

        List<RecordedEvent> algo = events.stream().filter(e -> ALGO.equals(e.getEventType().getName())).toList();
        assertThat(algo).extracting(e -> e.getString("operationType"))
                .containsExactlyInAnyOrder("SHORTEST_PATH", "MULTI_TARGET_SHORTEST_PATH", "K_SHORTEST_PATHS");
        assertThat(algo).allSatisfy(e -> {
            assertThat(e.getString("algorithmName")).isEqualTo("fixed");
            assertThat(e.getBoolean("found")).isTrue();
            assertThat(e.getDuration("elapsedNanos")).isGreaterThanOrEqualTo(Duration.ofNanos(WORK_NANOS));
        });

        List<RecordedEvent> sync = events.stream().filter(e -> SYNC.equals(e.getEventType().getName())).toList();
        assertThat(sync).extracting(e -> e.getBoolean("success")).containsExactlyInAnyOrder(true, false);
        assertThat(sync).allSatisfy(e -> {
            assertThat(e.getString("operationType")).isEqualTo("NODE_DELETE");
            assertThat(e.getString("edgeOrLabel")).isEqualTo("User");
            assertThat(e.getDuration("elapsedNanos")).isGreaterThanOrEqualTo(Duration.ofNanos(WORK_NANOS));
        });
    }

    /** Holds the calling thread for {@link #WORK_NANOS} of wall-clock time without sleeping. */
    private static void occupy() {
        long start = System.nanoTime();
        while (System.nanoTime() - start < WORK_NANOS) {
            Thread.onSpinWait();
        }
    }

    private static GraphEngine engineFailing(boolean fail) {
        return new GraphEngine() {
            @Override public GraphSession openSession() { return new StubSession(fail); }
            @Override public GraphDialect dialect() { return null; }
            @Override public void registerNodes(List<GraphNodeDescriptor> n) { /* test stub */ }
            @Override public void registerEdges(List<GraphEdgeDescriptor> e) { /* test stub */ }
            @Override public List<GraphNodeDescriptor> registeredNodes() { return List.of(); }
            @Override public List<GraphEdgeDescriptor> registeredEdges() { return List.of(); }
            @Override public String engineName() { return "Stub"; }
            @Override public boolean isRunning() { return true; }
            @Override public void close() { /* test stub */ }
        };
    }

    private static final class FixedPathFinder implements PathFinder {
        private static PathResult found(UUID src, UUID tgt) {
            return new PathResult(src, tgt, List.of(src, tgt), 1.0, 0, "fixed");
        }

        @Override public PathResult findShortestPath(UUID src, UUID tgt, EdgeWeightFunction fn) {
            occupy();
            return found(src, tgt);
        }

        @Override public Map<UUID, PathResult> findShortestPaths(UUID src, Set<UUID> targets, EdgeWeightFunction fn) {
            occupy();
            return Map.of(TARGET, found(src, TARGET));
        }

        @Override public List<PathResult> findKShortestPaths(UUID src, UUID tgt, int k, EdgeWeightFunction fn) {
            occupy();
            return List.of(found(src, tgt));
        }

        @Override public boolean pathExists(UUID src, UUID tgt) { return true; }
        @Override public String algorithmName() { return "fixed"; }
    }

    private static final class StubSession implements GraphSession {
        private final boolean fail;

        StubSession(boolean fail) { this.fail = fail; }

        @Override public void beginTransaction() { /* test stub */ }
        @Override public void commit() { /* test stub */ }
        @Override public void rollback() { /* test stub */ }
        @Override public void upsertNode(String l, UUID id, LoanedBuffer p) { /* test stub */ }
        @Override public void deleteNode(String l, UUID id) {
            occupy();
            if (fail) {
                throw new IllegalStateException("simulated delete failure");
            }
        }
        @Override public void upsertEdge(GraphEdgeDescriptor e, UUID s, UUID t, double w, String p) { /* test stub */ }
        @Override public void deleteEdge(GraphEdgeDescriptor e, UUID s, UUID t) { /* test stub */ }
        @Override public void createEdge(GraphEdgeDescriptor e, UUID s, UUID t, double w, String p) { /* test stub */ }
        @Override public List<UUID> traverseBreadthFirst(GraphTraversal t) { return List.of(); }
        @Override public LoanedBuffer streamBfsJson(GraphTraversal t) { return null; }
        @Override public List<UUID> traversePath(GraphPathSpec p) { return List.of(); }
        @Override public LoanedBuffer streamPathJson(GraphPathSpec p) { return null; }
        @Override public UUID getRootNode() { return SOURCE; }
        @Override public PathResult findShortestPath(GraphEdgeDescriptor e, UUID s, UUID t) {
            return PathResult.notFound(s, t, "stub");
        }
        @Override public void close() { /* test stub */ }
    }
}
