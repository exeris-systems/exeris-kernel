/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;

import java.lang.System.Logger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Package-private discovery and selector-closure resolution used by
 * {@link SubsystemOrchestrator}.
 *
 * <p>Extracted from {@link SubsystemOrchestrator} in v0.8 Sprint 3 (QA-018b)
 * to close the orchestrator's God-class suppression block. Owns:
 * <ul>
 *   <li>{@link #loadRegistry} — {@link ServiceLoader} discovery of all
 *       {@link SubsystemProvider}s, priority-sorted (descending), merged into
 *       a name-keyed registry. First-write-wins on name collision, so higher
 *       priority providers (Enterprise) shadow lower priority (Community).</li>
 *   <li>{@link #applySelectorClosure} — BFS fixed-point expansion of the
 *       selector's requested names through the dependency graph; throws if
 *       a requested subsystem is missing from the registry.</li>
 * </ul>
 */
final class SubsystemRegistryLoader {

    private SubsystemRegistryLoader() {
        // package-private static utility — never instantiated.
    }

    /**
     * Loads all {@link SubsystemProvider}s via {@link ServiceLoader},
     * priority-sorts them (highest first, with lexicographic tie-break), then
     * merges into a name-keyed {@link LinkedHashMap}. First-write-wins so the
     * higher-priority provider wins on a name collision.
     *
     * @param config       the active kernel config passed through to each
     *                     provider's {@code getSubsystems(config)} call
     * @param classLoader  class loader for service discovery
     * @param log          logger for the discovery banner; logs at INFO/WARNING/DEBUG
     * @return registry keyed by subsystem name; empty when no providers are found
     */
    /* default */ static Map<String, Subsystem> loadRegistry(ConfigProvider config,
                                                             ClassLoader classLoader,
                                                             Logger log) {
        List<SubsystemProvider> providers = new ArrayList<>();
        ServiceLoader.load(SubsystemProvider.class, classLoader).forEach(providers::add);
        providers.sort(Comparator
                .comparingInt(SubsystemProvider::priority)
                .reversed()
                .thenComparing(provider -> Objects.toString(provider.moduleName(), ""))
                .thenComparing(provider -> provider.getClass().getName()));

        if (providers.isEmpty()) {
            log.log(Logger.Level.WARNING,
                    "No SubsystemProvider registered — kernel starts with zero subsystems");
            return Map.of();
        }

        Map<String, Subsystem> registry = new LinkedHashMap<>();
        for (SubsystemProvider provider : providers) {
            log.log(Logger.Level.INFO,
                    "  SubsystemProvider: {0} (priority={1})",
                    provider.moduleName(), provider.priority());
            for (Subsystem subsystem : provider.getSubsystems(config)) {
                if (registry.putIfAbsent(subsystem.name(), subsystem) == null) {
                    log.log(Logger.Level.DEBUG, "    + [{0}]", subsystem.name());
                } else {
                    log.log(Logger.Level.DEBUG,
                            "    [{0}] shadowed by higher-priority provider",
                            subsystem.name());
                }
            }
        }
        return registry;
    }

    /**
     * BFS fixed-point closure: starts from the selector's requested names and
     * follows {@code dependsOn} edges until no new names are discovered. The
     * result preserves the registry's insertion order (higher-priority
     * providers first) which keeps the topological sort tie-break stable.
     *
     * @param selector selector whose requested names seed the BFS frontier
     * @param registry full name → subsystem registry from {@link #loadRegistry}
     * @return subsystems in registry order, restricted to the closure
     * @throws SubsystemOrchestrator.BootstrapException if a requested or
     *         transitively-required name is not present in the registry
     */
    /* default */ static List<Subsystem> applySelectorClosure(BootstrapSelector selector,
                                                              Map<String, Subsystem> registry)
            throws SubsystemOrchestrator.BootstrapException {
        if (selector.isAll()) {
            return new ArrayList<>(registry.values());
        }

        Set<String> closure = new LinkedHashSet<>();
        Deque<String> toVisit = new ArrayDeque<>(selector.requestedNames());

        while (!toVisit.isEmpty()) {
            String name = toVisit.poll();
            if (!closure.add(name)) {
                continue;
            }

            Subsystem candidate = registry.get(name);
            if (candidate == null) {
                throw new SubsystemOrchestrator.BootstrapException(
                        "Selector requests subsystem '" + name
                        + "' which is not provided by any SubsystemProvider on the classpath.");
            }

            for (String dep : candidate.dependsOn()) {
                if (!closure.contains(dep)) {
                    toVisit.add(dep);
                }
            }
        }

        return registry.values().stream()
                .filter(subsystem -> closure.contains(subsystem.name()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}
