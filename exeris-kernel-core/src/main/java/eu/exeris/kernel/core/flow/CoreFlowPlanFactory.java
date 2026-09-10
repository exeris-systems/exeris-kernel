/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.FlowDefinitionBuilder;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowDefinitionMigration;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import eu.exeris.kernel.spi.flow.model.FlowStepDescriptor;
import eu.exeris.kernel.spi.flow.model.FlowTransitionDescriptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Compiles {@link FlowDefinition}s, assembled via the {@link Builder} returned from
 * {@link #newDefinition}, into {@link CoreFlowExecutionPlan}s.
 *
 * <p>{@link #compile} derives, for each step, the full adjacency of outgoing transitions and a
 * single precomputed next-step index: the unconditional ({@code "default"}-tagged) transition if
 * the step declares one, otherwise the first declared transition, otherwise {@code stepIndex + 1}
 * when the step declares none at all. {@link CoreFlowRuntime} advances a running instance through
 * that precomputed index rather than re-scanning the adjacency on every step. A successful
 * compilation also replaces the shared {@link CoreFlowRegistry}'s step and transition descriptors,
 * and discards the pending edges {@link Builder#build()} recorded for the compiled
 * {@code (name, version)} key.
 *
 * <p>{@link #registerMigration} delegates admission to the separate {@link CoreMigrationRegistry} —
 * see that type's comment for why plan compilation and migration admission are kept apart.
 */
// compile() and compile helpers are individually simple; aggregate is inflated by Builder inner class
@SuppressWarnings("PMD.CyclomaticComplexity")
final class CoreFlowPlanFactory implements FlowExecutionPlanFactory {

    private static final int MAX_UNCONDITIONAL_OUTGOING = 1;

    private final FlowEngineConfig config;
    private final CoreFlowRegistry registry;
    private final ConcurrentMap<PlanKey, CoreFlowExecutionPlan> planCatalog;
    private final CoreMigrationRegistry migrationRegistry;
    private final Runnable onPlanCompiled;
    /**
     * Edges handed over from {@link Builder#build()} to {@link #compile}, keyed by {@code (name,
     * version)} like {@link #planCatalog} — not by name. Keyed by name alone, building two versions
     * of one definition before compiling either made the second build overwrite the first's edges
     * and the first compile consume the entry, so the second plan compiled with none. That is not a
     * stuck saga: a step with no outgoing transition falls back to {@code index + 1}, so the loss is
     * invisible on a linear flow and silently takes the wrong branch on any definition whose
     * declared edge differs from the sequential default. Declaring two versions and then registering
     * them is exactly what ADR-064 coexistence asks an application to do.
     */
    private final ConcurrentMap<PlanKey, List<FlowTransitionDescriptor>> transitionsByDefinition =
            new ConcurrentHashMap<>();

    /* default */ CoreFlowPlanFactory(FlowEngineConfig config, CoreFlowRegistry registry,
                                      ConcurrentMap<PlanKey, CoreFlowExecutionPlan> planCatalog,
                                      ConcurrentMap<MigrationKey, FlowDefinitionMigration> migrations,
                                      Runnable onPlanCompiled) {
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.planCatalog = Objects.requireNonNull(planCatalog, "planCatalog");
        this.migrationRegistry = new CoreMigrationRegistry(config, migrations);
        this.onPlanCompiled = Objects.requireNonNull(onPlanCompiled, "onPlanCompiled");
    }

    @Override
    public FlowDefinitionBuilder newDefinition(String definitionName) {
        return new Builder(definitionName);
    }

    /**
     * {@inheritDoc}
     *
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException {@code EX-FLOW-7002} with
     *         {@code phase="COMPILE"} and {@code reasonCode="COMPILE_FAILED"} if {@code definition}
     *         is {@code null}, names no steps, exceeds {@link FlowEngineConfig#maxSteps()} or
     *         {@link FlowEngineConfig#maxTransitions()}, declares a transition to or from an
     *         out-of-range step index, declares more than one unconditional outgoing transition for
     *         a step, or would exceed {@link FlowEngineConfig#maxExecutionPlans()} distinct
     *         {@code (name, version)} entries in the plan catalog
     * @implNote The bound check against {@code maxExecutionPlans} and the catalog insert share one
     *           {@code synchronized(planCatalog)} block, so two threads compiling different versions
     *           of the same definition at the ceiling cannot both observe room and both land.
     */
    @Override
    @SuppressWarnings("PMD.ExceptionAsFlowControl") // wrapping SPI validation at the compile boundary
    public FlowExecutionPlan compile(FlowDefinition definition) {
        try {
            String definitionName = validatedDefinitionName(definition);
            FlowStepDescriptor[] steps = validatedSteps(definition);
            // Keyed by (name, version) since ADR-064: registering a changed definition must not
            // evict the one every in-flight saga parked under. The ceiling therefore bounds retained
            // versions as well as distinct definitions — an application that bumps on every deploy
            // and never retires an old version will reach it. The pending-edge map is keyed the same
            // way, so two versions built before either is compiled cannot consume each other's edges.
            PlanKey key = new PlanKey(definitionName, definition.version());
            List<List<FlowTransitionDescriptor>> buckets = transitionBuckets(key, steps.length);
            FlowTransitionDescriptor[][] adjacency = buildAdjacency(buckets, steps.length);
            int[] nextSteps = buildNextSteps(buckets, steps.length);

            CoreFlowExecutionPlan plan = new CoreFlowExecutionPlan(
                    definitionName,
                    definition.version(),
                    steps,
                    adjacency,
                    nextSteps,
                    definition.timeoutDurationNanos()
            );
            registry.replace(steps, adjacency);
            synchronized (planCatalog) {
                if (!planCatalog.containsKey(key)
                        && planCatalog.size() >= config.maxExecutionPlans()) {
                    throw new IllegalStateException(
                            "maxExecutionPlans limit reached: " + config.maxExecutionPlans());
                }
                planCatalog.put(key, plan);
            }
            transitionsByDefinition.remove(key);
            onPlanCompiled.run();
            return plan;
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException ex) {
            throw FlowEngineException.compileFailure(config.engineName(), ex);
        }
    }

    private static String validatedDefinitionName(FlowDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("FlowDefinition must not be null");
        }
        String definitionName = definition.name();
        if (definitionName == null) {
            throw new IllegalArgumentException("FlowDefinition name must not be null");
        }
        return definitionName;
    }

    private FlowStepDescriptor[] validatedSteps(FlowDefinition definition) {
        FlowStepDescriptor[] steps = definition.steps().toArray(FlowStepDescriptor[]::new);
        if (steps.length > config.maxSteps()) {
            throw new IllegalArgumentException("FlowDefinition exceeds maxSteps: " + steps.length);
        }
        return steps;
    }

    private List<List<FlowTransitionDescriptor>> transitionBuckets(PlanKey key, int stepCount) {
        List<FlowTransitionDescriptor> transitions = transitionsByDefinition.getOrDefault(key, List.of());
        if (config.maxTransitions() > 0 && transitions.size() > config.maxTransitions()) {
            throw new IllegalArgumentException("FlowDefinition exceeds maxTransitions: " + transitions.size());
        }
        List<List<FlowTransitionDescriptor>> buckets = new ArrayList<>(stepCount);
        for (int index = 0; index < stepCount; index++) {
            buckets.add(new ArrayList<>());
        }
        for (FlowTransitionDescriptor transition : transitions) {
            validateTransitionBounds(transition, stepCount);
            buckets.get(transition.fromStep()).add(transition);
        }
        return buckets;
    }

    private static void validateTransitionBounds(FlowTransitionDescriptor transition, int stepCount) {
        if (transition.fromStep() < 0
                || transition.toStep() < 0
                || transition.fromStep() >= stepCount
                || transition.toStep() >= stepCount) {
            throw new IllegalArgumentException("Transition references out-of-range step id");
        }
    }

    private static FlowTransitionDescriptor[][] buildAdjacency(List<List<FlowTransitionDescriptor>> buckets,
                                                               int stepCount) {
        FlowTransitionDescriptor[][] adjacency = new FlowTransitionDescriptor[stepCount][];
        for (int index = 0; index < stepCount; index++) {
            adjacency[index] = buckets.get(index).toArray(FlowTransitionDescriptor[]::new);
        }
        return adjacency;
    }

    private static int[] buildNextSteps(List<List<FlowTransitionDescriptor>> buckets, int stepCount) {
        int[] nextSteps = new int[stepCount];
        Arrays.fill(nextSteps, -1);
        for (int index = 0; index < stepCount; index++) {
            List<FlowTransitionDescriptor> outgoing = buckets.get(index);
            if (outgoing.isEmpty()) {
                if (index + 1 < stepCount) {
                    nextSteps[index] = index + 1;
                }
                continue;
            }
            List<FlowTransitionDescriptor> unconditional = outgoing.stream()
                    .filter(t -> "default".equals(t.conditionTag()))
                    .toList();
            if (unconditional.size() > MAX_UNCONDITIONAL_OUTGOING) {
                throw new IllegalArgumentException(
                        "Step " + index + " has " + unconditional.size()
                        + " unconditional outgoing transitions; at most one is permitted");
            }
            nextSteps[index] = unconditional.isEmpty()
                    ? outgoing.getFirst().toStep()
                    : unconditional.getFirst().toStep();
        }
        return nextSteps;
    }

    /**
     * Heap-backed {@link FlowDefinitionBuilder}: accumulates steps and transitions in plain
     * {@link ArrayList}s and hands the transitions to the enclosing {@link CoreFlowPlanFactory} on
     * {@link #build()}, keyed by {@code (name, version)} so {@link #compile} can find them.
     *
     * <p><b>Thread confinement:</b> owner thread — matches {@link FlowDefinitionBuilder}'s own
     * contract; the accumulating lists are unsynchronized.
     * <p><b>Ownership:</b> a caller-held builder confined to one definition; nothing is released.
     */
    private final class Builder implements FlowDefinitionBuilder {

        private final String definitionName;
        private final List<FlowStepDescriptor> steps = new ArrayList<>();
        private final List<FlowTransitionDescriptor> transitions = new ArrayList<>();
        private long timeoutDurationNanos = config.timeoutDurationNanos();
        private int maxRetries;
        private int version = FlowDefinition.INITIAL_VERSION;

        private Builder(String definitionName) {
            this.definitionName = Objects.requireNonNull(definitionName, "definitionName");
        }

        /**
         * {@inheritDoc}
         *
         * @implNote Checks the duplicate-name rule immediately, with a linear scan of the steps
         *           added so far, and throws {@link IllegalArgumentException} directly from this
         *           call rather than deferring the check to {@link #build()} or {@link #compile}.
         */
        @Override
        public FlowDefinitionBuilder step(String name, FlowStepAction action, FlowStepAction compensation) {
            Objects.requireNonNull(name, "step name must not be null");
            for (FlowStepDescriptor existing : steps) {
                if (name.equals(existing.name())) {
                    throw new IllegalArgumentException(
                            "Duplicate step name '" + name + "' in definition '" + definitionName + '\'');
                }
            }
            steps.add(new FlowStepDescriptor(steps.size(), name, action, compensation));
            return this;
        }

        /**
         * {@inheritDoc}
         *
         * @implNote Records the transition without checking that {@code fromStep} or {@code toStep}
         *           names a step added so far; out-of-range indices surface later, when
         *           {@link #compile} validates the accumulated transitions against the definition's
         *           final step count.
         */
        @Override
        public FlowDefinitionBuilder transition(int fromStep, int toStep) {
            transitions.add(FlowTransitionDescriptor.unconditional(fromStep, toStep));
            return this;
        }

        /**
         * {@inheritDoc}
         *
         * @implNote Same deferred bounds checking as {@link #transition(int, int)}.
         */
        @Override
        public FlowDefinitionBuilder transition(int fromStep, int toStep, String conditionTag) {
            transitions.add(new FlowTransitionDescriptor(fromStep, toStep, conditionTag));
            return this;
        }

        /**
         * {@inheritDoc}
         *
         * @implNote Stores {@code durationNanos} without checking it is positive; a non-positive
         *           value surfaces only when {@link #build()} constructs the {@link FlowDefinition},
         *           whose compact constructor enforces the bound.
         */
        @Override
        public FlowDefinitionBuilder timeoutDuration(long durationNanos) {
            timeoutDurationNanos = durationNanos;
            return this;
        }

        /**
         * {@inheritDoc}
         *
         * @implNote Stores {@code maxRetries} without checking it is non-negative; a negative value
         *           surfaces only when {@link #build()} constructs the {@link FlowDefinition}, whose
         *           compact constructor enforces the bound.
         */
        @Override
        public FlowDefinitionBuilder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * {@inheritDoc}
         *
         * @implNote Unlike {@link #timeoutDuration(long)} and {@link #maxRetries(int)}, checks the
         *           bound immediately rather than deferring to {@link #build()}: a caller that passes
         *           a sub-initial version finds out at the call that named it, not three chained
         *           methods later.
         */
        @Override
        public FlowDefinitionBuilder version(int version) {
            if (version < FlowDefinition.INITIAL_VERSION) {
                throw new IllegalArgumentException(
                        "flow definition version must be >= " + FlowDefinition.INITIAL_VERSION
                                + ", got: " + version);
            }
            this.version = version;
            return this;
        }

        /**
         * {@inheritDoc}
         *
         * @implNote Also records this definition's accumulated transitions into the enclosing
         *           factory's {@code transitionsByDefinition} map, keyed by {@code (name, version)},
         *           so a later {@link #compile} call for the same key can find them; {@link #compile}
         *           removes the entry once it has consumed it.
         */
        @Override
        public FlowDefinition build() {
            FlowDefinition definition = new FlowDefinition(
                    definitionName,
                    version,
                    List.copyOf(steps),
                    timeoutDurationNanos,
                    maxRetries
            );
            transitionsByDefinition.put(new PlanKey(definitionName, version), List.copyOf(transitions));
            return definition;
        }
    }
    /** Registers an adjacent-hop migration (ADR-064); admission rules live in the registry. */
    @Override
    public void registerMigration(String definitionName, int fromVersion, FlowDefinitionMigration migration) {
        migrationRegistry.register(definitionName, fromVersion, migration);
    }

}
