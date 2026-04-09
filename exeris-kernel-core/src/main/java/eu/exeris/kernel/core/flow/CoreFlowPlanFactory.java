/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.FlowDefinitionBuilder;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
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

final class CoreFlowPlanFactory implements FlowExecutionPlanFactory {

    private final FlowEngineConfig config;
    private final CoreFlowRegistry registry;
    private final ConcurrentMap<String, CoreFlowExecutionPlan> planCatalog;
    private final ConcurrentMap<String, List<FlowTransitionDescriptor>> transitionsByDefinition =
            new ConcurrentHashMap<>();

    /* default */ CoreFlowPlanFactory(FlowEngineConfig config, CoreFlowRegistry registry,
                                      ConcurrentMap<String, CoreFlowExecutionPlan> planCatalog) {
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.planCatalog = Objects.requireNonNull(planCatalog, "planCatalog");
    }

    @Override
    public FlowDefinitionBuilder newDefinition(String definitionName) {
        return new Builder(definitionName);
    }

    @Override
    public FlowExecutionPlan compile(FlowDefinition definition) {
        try {
            String definitionName = validatedDefinitionName(definition);
            FlowStepDescriptor[] steps = validatedSteps(definition);
            List<List<FlowTransitionDescriptor>> buckets = transitionBuckets(definitionName, steps.length);
            FlowTransitionDescriptor[][] adjacency = buildAdjacency(buckets, steps.length);
            int[] nextSteps = buildNextSteps(buckets, steps.length);

            CoreFlowExecutionPlan plan = new CoreFlowExecutionPlan(
                    definitionName,
                    steps,
                    adjacency,
                    nextSteps,
                    definition.timeoutDurationNanos()
            );
            registry.replace(steps, adjacency);
            synchronized (planCatalog) {
                if (!planCatalog.containsKey(definitionName)
                        && planCatalog.size() >= config.maxExecutionPlans()) {
                    throw new IllegalStateException(
                            "maxExecutionPlans limit reached: " + config.maxExecutionPlans());
                }
                planCatalog.put(definitionName, plan);
            }
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

    private List<List<FlowTransitionDescriptor>> transitionBuckets(String definitionName, int stepCount) {
        List<FlowTransitionDescriptor> transitions = transitionsByDefinition.getOrDefault(definitionName, List.of());
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
            if (unconditional.size() > 1) {
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

    private final class Builder implements FlowDefinitionBuilder {

        private final String definitionName;
        private final List<FlowStepDescriptor> steps = new ArrayList<>();
        private final List<FlowTransitionDescriptor> transitions = new ArrayList<>();
        private long timeoutDurationNanos = config.timeoutDurationNanos();
        private int maxRetries;

        private Builder(String definitionName) {
            this.definitionName = Objects.requireNonNull(definitionName, "definitionName");
        }

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

        @Override
        public FlowDefinitionBuilder transition(int fromStep, int toStep) {
            transitions.add(FlowTransitionDescriptor.unconditional(fromStep, toStep));
            return this;
        }

        @Override
        public FlowDefinitionBuilder transition(int fromStep, int toStep, String conditionTag) {
            transitions.add(new FlowTransitionDescriptor(fromStep, toStep, conditionTag));
            return this;
        }

        @Override
        public FlowDefinitionBuilder timeoutDuration(long durationNanos) {
            timeoutDurationNanos = durationNanos;
            return this;
        }

        @Override
        public FlowDefinitionBuilder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        @Override
        public FlowDefinition build() {
            FlowDefinition definition = new FlowDefinition(
                    definitionName,
                    List.copyOf(steps),
                    timeoutDurationNanos,
                    maxRetries
            );
            transitionsByDefinition.put(definitionName, List.copyOf(transitions));
            return definition;
        }
    }
}
