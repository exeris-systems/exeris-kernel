/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

/**
 * Package-private pure topological sort over {@link Subsystem} dependency
 * edges (Kahn's BFS, O(V+E)) used by {@link SubsystemOrchestrator}.
 *
 * <p>Extracted from {@link SubsystemOrchestrator} in v0.8 Sprint 3 (QA-018b)
 * to close the orchestrator's God-class suppression block. Pure — no LOG,
 * no JFR, no health-monitor side effects. Side-effecting cycle handling
 * (ENTROPY INTERVENTION banner, {@code BootstrapJfrEvents.emitCircularDependency},
 * forced JVM diagnostic) stays in the orchestrator's {@code initialize} path
 * which catches {@link SubsystemCircularDependencyException}.
 *
 * <p>The {@link DependencyGraph} record is intentionally pkg-private — its
 * adjacency representation is internal to the sort algorithm and not
 * intended for external consumption.
 */
final class SubsystemTopologicalSorter {

    private SubsystemTopologicalSorter() {
        // package-private static utility — never instantiated.
    }

    /**
     * Internal, Valhalla-ready value carrier for Kahn's adjacency data.
     *
     * @param inDegree   subsystem name → count of unsatisfied incoming edges
     * @param dependents subsystem name → list of names that depend on it
     */
    /* default */ record DependencyGraph(
            Map<String, Integer> inDegree,
            Map<String, List<String>> dependents) {
    }

    /**
     * Topologically sorts {@code subsystems} using Kahn's BFS.
     *
     * <p>The caller is expected to:
     * <ul>
     *   <li>Pass an already-deduplicated input — duplicate names throw
     *       {@link SubsystemOrchestrator.BootstrapException}.</li>
     *   <li>Catch {@link SubsystemCircularDependencyException} to emit any
     *       side-effecting diagnostics (logs, JFR) before propagating.</li>
     * </ul>
     *
     * @param subsystems subsystems to sort; order in this list does not affect
     *                   the result beyond tie-breaking via {@link PriorityQueue}
     *                   (lexicographic by name)
     * @return a freshly allocated list in valid topological order
     * @throws SubsystemOrchestrator.BootstrapException on duplicate names or
     *         when a subsystem depends on a name not present in the input
     * @throws SubsystemCircularDependencyException     on dependency cycle
     */
    /* default */ static List<Subsystem> sort(List<Subsystem> subsystems)
            throws SubsystemOrchestrator.BootstrapException {
        Map<String, Subsystem> byName = indexByName(subsystems);
        DependencyGraph graph = buildDependencyGraph(byName);
        List<Subsystem> result = runKahnBfs(byName, graph);

        if (result.size() != subsystems.size()) {
            Set<String> cycleMembers = new LinkedHashSet<>(byName.keySet());
            result.forEach(resolved -> cycleMembers.remove(resolved.name()));
            throw SubsystemCircularDependencyException.forCycle(cycleMembers);
        }
        return result;
    }

    private static Map<String, Subsystem> indexByName(List<Subsystem> subsystems)
            throws SubsystemOrchestrator.BootstrapException {
        Map<String, Subsystem> byName = new LinkedHashMap<>();
        for (Subsystem subsystem : subsystems) {
            if (byName.put(subsystem.name(), subsystem) != null) {
                throw new SubsystemOrchestrator.BootstrapException(
                        "Duplicate subsystem name: '" + subsystem.name() + "'. "
                        + "Two SubsystemProviders registered a subsystem with the same name.");
            }
        }
        return byName;
    }

    // computeIfAbsent allocates the per-dependent list only on first dep — bounded by graph size.
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static DependencyGraph buildDependencyGraph(Map<String, Subsystem> byName)
            throws SubsystemOrchestrator.BootstrapException {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (Subsystem subsystem : byName.values()) {
            inDegree.putIfAbsent(subsystem.name(), 0);
            for (String dep : subsystem.dependsOn()) {
                if (!byName.containsKey(dep)) {
                    throw new SubsystemOrchestrator.BootstrapException(
                            "Subsystem '" + subsystem.name() + "' depends on missing subsystem '"
                            + dep + "' in active registry. [EX-BOOT-0002]");
                }
                dependents.computeIfAbsent(dep, ignored -> new ArrayList<>())
                        .add(subsystem.name());
                inDegree.merge(subsystem.name(), 1, Integer::sum);
            }
        }
        return new DependencyGraph(inDegree, dependents);
    }

    private static List<Subsystem> runKahnBfs(Map<String, Subsystem> byName, DependencyGraph graph) {
        Queue<String> queue = new PriorityQueue<>();
        graph.inDegree().entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .forEach(queue::add);

        List<Subsystem> result = new ArrayList<>(byName.size());
        while (!queue.isEmpty()) {
            String name = queue.poll();
            result.add(byName.get(name));
            for (String dependent : graph.dependents().getOrDefault(name, List.of())) {
                if (graph.inDegree().merge(dependent, -1, Integer::sum) == 0) {
                    queue.add(dependent);
                }
            }
        }
        return result;
    }
}
