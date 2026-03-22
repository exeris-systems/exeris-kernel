/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap;

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
 * Core-tier Kahn BFS TCK — mirrors the contract defined in
 * {@code AbstractBootstrapOrchestratorTck} but is self-contained.
 *
 * <h2>Why keep this suite in Core?</h2>
 * <p>Keep this suite self-contained in Core for fast local feedback while
 * mirroring the contracts defined in {@code AbstractBootstrapOrchestratorTck}.
 *
 * <h2>Covered contracts (same 11 as AbstractBootstrapOrchestratorTck)</h2>
 * <ul>
 *   <li>Linear chain A→B→C sorted correctly</li>
 *   <li>Diamond graph A→{B,C}→D sorted correctly</li>
 *   <li>Independent subsystems before their dependants</li>
 *   <li>Empty input → empty output</li>
 *   <li>2-node cycle → {@link SubsystemCircularDependencyException}</li>
 *   <li>3-node cycle → {@link SubsystemCircularDependencyException}</li>
 *   <li>Exception message contains cycle-member names</li>
 *   <li>SENTINEL is pre-allocated (zero-heap abort path)</li>
 *   <li>Exception carries error code {@code EX-BOOT-0001}</li>
 *   <li>Unknown dependency → {@link IllegalArgumentException}</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("Kahn BFS TCK — Core self-contained (mirrors AbstractBootstrapOrchestratorTck)")
class CoreBootstrapOrchestratorTckTest {

    // =========================================================================
    // Reference Kahn BFS — mirrors AbstractBootstrapOrchestratorTck.kahn().
    // =========================================================================

    @SuppressWarnings("PMD.CyclomaticComplexity") // O(V+E) — inherent graph complexity
    private static List<Subsystem> kahn(List<Subsystem> subsystems) {
        Map<String, Subsystem> byName = new HashMap<>();
        for (Subsystem sub : subsystems) {
            byName.put(sub.name(), sub);
        }

        Map<String, Integer> inDegree = new HashMap<>();
        for (Subsystem sub : subsystems) {
            inDegree.put(sub.name(), 0);
        }
        for (Subsystem sub : subsystems) {
            for (String dep : sub.dependsOn()) {
                if (!byName.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            "Unknown dependency '" + dep + "' declared by '" + sub.name() + "'");
                }
                inDegree.merge(sub.name(), 1, Integer::sum);
            }
        }

        List<String> queue = new ArrayList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }
        Collections.sort(queue);

        List<Subsystem> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            result.add(byName.get(current));
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
            Set<String> cycleMembers = new LinkedHashSet<>(byName.keySet());
            result.forEach(s -> cycleMembers.remove(s.name()));
            throw SubsystemCircularDependencyException.forCycle(cycleMembers);
        }
        return result;
    }

    // -------------------------------------------------------------------------

    private static Subsystem stub(String name, BootstrapPhase phase, String... deps) {
        return new Subsystem() {
            @Override public String name()             { return name; }
            @Override public List<String> dependsOn() { return List.of(deps); }
            @Override public BootstrapPhase phase()   { return phase; }

            @Override public void initialize() throws SubsystemException { /* no-op */ }
            @Override public void start()      throws SubsystemException { /* no-op */ }
            @Override public void stop()                                 { /* no-op */ }
        };
    }

    // =========================================================================
    // Test cases
    // =========================================================================

    @Nested
    @DisplayName("Acyclic graph — happy path")
    class AcyclicGraph {

        @Test
        @DisplayName("Linear chain A → B → C sorted correctly")
        void linearChain() {
            Subsystem a = stub("alpha",   BootstrapPhase.FOUNDATION);
            Subsystem b = stub("bravo",   BootstrapPhase.FOUNDATION, "alpha");
            Subsystem c = stub("charlie", BootstrapPhase.FOUNDATION, "bravo");

            List<String> names = kahn(List.of(c, b, a)).stream().map(Subsystem::name).toList();

            assertThat(names.indexOf("alpha")).isLessThan(names.indexOf("bravo"));
            assertThat(names.indexOf("bravo")).isLessThan(names.indexOf("charlie"));
        }

        @Test
        @DisplayName("Diamond graph: A → {B,C} → D sorted correctly")
        void diamond() {
            Subsystem a = stub("a", BootstrapPhase.FOUNDATION);
            Subsystem b = stub("b", BootstrapPhase.FOUNDATION, "a");
            Subsystem c = stub("c", BootstrapPhase.FOUNDATION, "a");
            Subsystem d = stub("d", BootstrapPhase.FOUNDATION, "b", "c");

            List<String> names = kahn(List.of(d, b, c, a)).stream().map(Subsystem::name).toList();

            assertThat(names.indexOf("a")).isLessThan(names.indexOf("b"));
            assertThat(names.indexOf("a")).isLessThan(names.indexOf("c"));
            assertThat(names.indexOf("b")).isLessThan(names.indexOf("d"));
            assertThat(names.indexOf("c")).isLessThan(names.indexOf("d"));
        }

        @Test
        @DisplayName("Independent subsystems appear before their dependants")
        void independentsBeforeDependants() {
            Subsystem mem   = stub("memory",    BootstrapPhase.FOUNDATION);
            Subsystem telem = stub("telemetry", BootstrapPhase.FOUNDATION);
            Subsystem dep   = stub("transport", BootstrapPhase.SERVICES, "memory", "telemetry");

            List<String> names = kahn(List.of(dep, mem, telem))
                    .stream().map(Subsystem::name).toList();

            assertThat(names.indexOf("memory")).isLessThan(names.indexOf("transport"));
            assertThat(names.indexOf("telemetry")).isLessThan(names.indexOf("transport"));
        }

        @Test
        @DisplayName("Empty input yields empty output")
        void emptyInput() {
            assertThat(kahn(Collections.emptyList())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Cycle detection — 🜁 ENTROPY INTERVENTION")
    class CycleDetection {

        @Test
        @DisplayName("2-node cycle throws SubsystemCircularDependencyException")
        void twoNodeCycle() {
            Subsystem a = stub("alpha", BootstrapPhase.SERVICES, "bravo");
            Subsystem b = stub("bravo", BootstrapPhase.SERVICES, "alpha");

            assertThatThrownBy(() -> kahn(List.of(a, b)))
                    .isInstanceOf(SubsystemCircularDependencyException.class);
        }

        @Test
        @DisplayName("3-node cycle throws SubsystemCircularDependencyException")
        void threeNodeCycle() {
            Subsystem a = stub("alpha",   BootstrapPhase.SERVICES, "charlie");
            Subsystem b = stub("bravo",   BootstrapPhase.SERVICES, "alpha");
            Subsystem c = stub("charlie", BootstrapPhase.SERVICES, "bravo");

            assertThatThrownBy(() -> kahn(List.of(a, b, c)))
                    .isInstanceOf(SubsystemCircularDependencyException.class);
        }

        @Test
        @DisplayName("Exception message contains all cycle-member names")
        void cycleMessageContainsMembers() {
            SubsystemCircularDependencyException ex =
                    SubsystemCircularDependencyException.forCycle(
                            Set.of("alpha", "bravo", "charlie"));

            assertThat(ex.getMessage()).contains("alpha", "bravo", "charlie");
        }

        @Test
        @DisplayName("SENTINEL is pre-allocated (zero-heap abort path)")
        void sentinelPreAllocated() {
            assertThat(SubsystemCircularDependencyException.SENTINEL).isNotNull();
        }

        @Test
        @DisplayName("Exception carries error code EX-BOOT-0001")
        void errorCode() {
            SubsystemCircularDependencyException ex =
                    SubsystemCircularDependencyException.forCycle(Set.of("x", "y"));
            assertThat(ex.getMessage()).contains("EX-BOOT-0001");
        }
    }

    @Nested
    @DisplayName("Unknown dependency detection")
    class UnknownDependency {

        @Test
        @DisplayName("Unknown dependency name throws IllegalArgumentException immediately")
        void unknownDepThrows() {
            Subsystem orphan = stub("orphan", BootstrapPhase.SERVICES, "ghost");

            assertThatThrownBy(() -> kahn(List.of(orphan)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ghost");
        }
    }
}
