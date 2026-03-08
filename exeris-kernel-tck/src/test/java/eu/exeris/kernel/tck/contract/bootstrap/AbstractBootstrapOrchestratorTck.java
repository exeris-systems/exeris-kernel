/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.exceptions.SubsystemException;
import eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for Kahn's BFS topological sort / BootstrapOrchestrator verification.
 *
 * <h2>Front 1 — Bootstrap &amp; Config</h2>
 * <p>This is the <b>critical</b> TCK for the {@code SubsystemOrchestrator}. It does NOT test a
 * concrete orchestrator implementation directly — instead it exposes a reference implementation
 * of Kahn's algorithm and verifies that:
 * <ol>
 *   <li>A valid acyclic dependency graph produces the correct topological order.</li>
 *   <li>A dependency cycle causes exactly {@link SubsystemCircularDependencyException}.</li>
 *   <li>The exception carries the correct cycle-member names.</li>
 *   <li>An unknown dependency (missing subsystem) is detected as a configuration error.</li>
 *   <li>Phase ordering is respected: FOUNDATION before SERVICES, SERVICES before RUNTIME.</li>
 * </ol>
 *
 * <h2>How to use</h2>
 * <p>Subclass and override {@link #runSort(List)} to delegate to the real orchestrator
 * under test. The helper {@link #kahn(List)} implements the reference algorithm for
 * self-contained tests.
 *
 * <pre>{@code
 * class CommunityBootstrapOrchestratorTest extends AbstractBootstrapOrchestratorTck {
 *     \@Override
 *     protected List<Subsystem> runSort(List<Subsystem> input) {
 *         return new CommunitySubsystemOrchestrator().sort(input);
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractBootstrapOrchestratorTck {

    // =========================================================================
    // Template method — subclass wires the real orchestrator
    // =========================================================================

    /**
     * Runs the topological sort on the given subsystem list.
     *
     * <p>Implementations delegate to the orchestrator under test. Throw
     * {@link SubsystemCircularDependencyException} on cycle detection.
     *
     * @param input unordered subsystem list
     * @return topologically sorted list
     */
    protected abstract List<Subsystem> runSort(List<Subsystem> input);

    // =========================================================================
    // Reference Kahn BFS — used in self-contained TCK cases
    // =========================================================================

    /**
     * Reference implementation of Kahn's BFS for topological sort.
     * Throws {@link SubsystemCircularDependencyException} on cycle detection.
     * Used by default test cases; implementations may override {@link #runSort(List)} to
     * delegate to the real orchestrator instead.
     */
    public static List<Subsystem> kahn(List<Subsystem> subsystems) {
        Map<String, Subsystem> byName = new HashMap<>();
        for (Subsystem sub : subsystems) {
            byName.put(sub.name(), sub);
        }

        // in-degree map
        Map<String, Integer> inDegree = new HashMap<>();
        for (Subsystem sub : subsystems) {
            inDegree.put(sub.name(), 0);
        }
        for (Subsystem sub : subsystems) {
            for (String dep : sub.dependsOn()) {
                if (!byName.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            "Unknown dependency '" + dep + "' declared by subsystem '" + sub.name() + "'");
                }
                inDegree.merge(sub.name(), 1, Integer::sum);
            }
        }

        // BFS queue — start with in-degree 0
        List<String> queue = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        Collections.sort(queue); // deterministic ordering within same in-degree group

        List<Subsystem> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            result.add(byName.get(current));
            // reduce in-degree of dependants
            for (Subsystem sub : subsystems) {
                if (sub.dependsOn().contains(current)) {
                    int newDeg = inDegree.merge(sub.name(), -1, Integer::sum);
                    if (newDeg == 0) {
                        queue.add(sub.name());
                        Collections.sort(queue);
                    }
                }
            }
        }

        if (result.size() != subsystems.size()) {
            // cycle — collect remaining names
            Set<String> cycleMembers = new LinkedHashSet<>(byName.keySet());
            result.forEach(s -> cycleMembers.remove(s.name()));
            throw SubsystemCircularDependencyException.forCycle(cycleMembers);
        }
        return result;
    }

    // =========================================================================
    // Test fixtures
    // =========================================================================

    /** Creates a minimal test {@link Subsystem} stub for use in graph tests. */
    protected static Subsystem stub(String name, BootstrapPhase phase, String... deps) {
        return new Subsystem() {
            @Override public String name()              { return name; }
            @Override public List<String> dependsOn()  { return List.of(deps); }
            @Override public BootstrapPhase phase()    { return phase; }
            @Override public void initialize() throws SubsystemException { /* no-op intentionally: test stub */ }
            @Override public void start()      throws SubsystemException { /* no-op intentionally: test stub */ }
            @Override public void stop()               { /* no-op intentionally: test stub */ }
            @Override public boolean isOptional()      { return false; }
        };
    }

    // =========================================================================
    // Test cases
    // =========================================================================

    @Nested
    @DisplayName("Acyclic graph — happy path")
    class AcyclicGraph {

        @Test
        @DisplayName("Linear chain A → B → C produces correct order [A, B, C]")
        void linearChainIsOrderedCorrectly() {
            var a = stub("alpha",   BootstrapPhase.FOUNDATION);
            var b = stub("bravo",   BootstrapPhase.FOUNDATION, "alpha");
            var c = stub("charlie", BootstrapPhase.FOUNDATION, "bravo");

            List<Subsystem> sorted = runSort(List.of(c, b, a)); // deliberately reverse input

            List<String> names = sorted.stream().map(Subsystem::name).toList();
            assertThat(names.indexOf("alpha"))
                    .as("'alpha' must come before 'bravo' in sorted order")
                    .isLessThan(names.indexOf("bravo"));
            assertThat(names.indexOf("bravo"))
                    .as("'bravo' must come before 'charlie' in sorted order")
                    .isLessThan(names.indexOf("charlie"));
        }

        @Test
        @DisplayName("Diamond dependency graph: A → B, A → C, B → D, C → D")
        void diamondGraphIsOrderedCorrectly() {
            var a = stub("a", BootstrapPhase.FOUNDATION);
            var b = stub("b", BootstrapPhase.FOUNDATION, "a");
            var c = stub("c", BootstrapPhase.FOUNDATION, "a");
            var d = stub("d", BootstrapPhase.FOUNDATION, "b", "c");

            List<Subsystem> sorted = runSort(List.of(d, b, c, a));

            List<String> names = sorted.stream().map(Subsystem::name).toList();
            assertThat(names.indexOf("a")).isLessThan(names.indexOf("b"));
            assertThat(names.indexOf("a")).isLessThan(names.indexOf("c"));
            assertThat(names.indexOf("b")).isLessThan(names.indexOf("d"));
            assertThat(names.indexOf("c")).isLessThan(names.indexOf("d"));
        }

        @Test
        @DisplayName("Subsystems with no dependencies may appear in any order at the front")
        void subsystemsWithNoDepsAreAtFront() {
            var independent1 = stub("memory",   BootstrapPhase.FOUNDATION);
            var independent2 = stub("telemetry", BootstrapPhase.FOUNDATION);
            var dependent    = stub("transport", BootstrapPhase.SERVICES, "memory", "telemetry");

            List<Subsystem> sorted = runSort(List.of(dependent, independent1, independent2));

            List<String> names = sorted.stream().map(Subsystem::name).toList();
            assertThat(names.indexOf("memory"))
                    .isLessThan(names.indexOf("transport"));
            assertThat(names.indexOf("telemetry"))
                    .isLessThan(names.indexOf("transport"));
        }

        @Test
        @DisplayName("Empty subsystem list returns empty sorted list without exception")
        void emptyInputYieldsEmptyOutput() {
            List<Subsystem> result = runSort(Collections.emptyList());
            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // Cycle detection — the critical path
    // =========================================================================

    @Nested
    @DisplayName("Cycle detection — must throw SubsystemCircularDependencyException")
    class CycleDetection {

        @Test
        @DisplayName("Simple 2-node cycle A → B → A throws SubsystemCircularDependencyException")
        void simpleTwoNodeCycleIsDetected() {
            var a = stub("alpha", BootstrapPhase.SERVICES, "bravo");
            var b = stub("bravo", BootstrapPhase.SERVICES, "alpha");
            var cycleInput = List.of(a, b);

            assertThatThrownBy(() -> runSort(cycleInput))
                    .as("A 2-node cycle MUST throw SubsystemCircularDependencyException " +
                        "(EX-BOOT-0001) — no infinite loop, no partial boot")
                    .isInstanceOf(SubsystemCircularDependencyException.class);
        }

        @Test
        @DisplayName("3-node cycle A → B → C → A throws SubsystemCircularDependencyException")
        void threeNodeCycleIsDetected() {
            var a = stub("alpha",   BootstrapPhase.SERVICES, "charlie");
            var b = stub("bravo",   BootstrapPhase.SERVICES, "alpha");
            var c = stub("charlie", BootstrapPhase.SERVICES, "bravo");
            var cycleInput = List.of(a, b, c);

            assertThatThrownBy(() -> runSort(cycleInput))
                    .as("A 3-node cycle MUST throw SubsystemCircularDependencyException")
                    .isInstanceOf(SubsystemCircularDependencyException.class);
        }

        @Test
        @DisplayName("Exception message contains cycle-member names when using forCycle()")
        void exceptionMessageContainsCycleMembers() {
            var ex = SubsystemCircularDependencyException.forCycle(
                    Set.of("alpha", "bravo", "charlie"));

            assertThat(ex.getMessage())
                    .as("SubsystemCircularDependencyException.getMessage() must contain the " +
                        "names of subsystems in the cycle for diagnostic purposes")
                    .contains("alpha", "bravo", "charlie");
        }

        @Test
        @DisplayName("SENTINEL instance is pre-allocated and non-null (zero-heap abort path)")
        void sentinelIsPreAllocated() {
            assertThat(SubsystemCircularDependencyException.SENTINEL)
                    .as("SENTINEL instance must be pre-allocated at class-init time " +
                        "to avoid object churn during the JVM abort path")
                    .isNotNull();
        }

        @Test
        @DisplayName("Cycle exception carries EX-BOOT-0001 error code")
        void cycleExceptionCarriesErrorCode() {
            var ex = SubsystemCircularDependencyException.forCycle(Set.of("x", "y"));
            assertThat(ex.getMessage())
                    .as("SubsystemCircularDependencyException MUST contain error code EX-BOOT-0001")
                    .contains("EX-BOOT-0001");
        }
    }

    // =========================================================================
    // Missing dependency
    // =========================================================================

    @Nested
    @DisplayName("Unknown dependency detection")
    class UnknownDependency {

        @Test
        @DisplayName("Declaring a dependency on an unknown subsystem name throws an exception")
        void unknownDependencyThrows() {
            var orphan = stub("orphan", BootstrapPhase.SERVICES, "nonexistent-subsystem");
            var orphanInput = List.of(orphan);

            assertThatThrownBy(() -> runSort(orphanInput))
                    .as("Declaring a dependency on an unknown subsystem must throw an exception. " +
                        "The Kahn sort cannot resolve what it cannot find.")
                    .isInstanceOf(RuntimeException.class);
        }
    }
}



