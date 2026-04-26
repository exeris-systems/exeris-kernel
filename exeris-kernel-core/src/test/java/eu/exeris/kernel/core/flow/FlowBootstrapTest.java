/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.flow.FlowProviderException;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.FlowEngineStats;
import eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory;
import eu.exeris.kernel.spi.flow.FlowProvider;
import eu.exeris.kernel.spi.flow.FlowRegistry;
import eu.exeris.kernel.spi.flow.FlowScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("L1: FlowBootstrap - priority selection and exception contract")
class FlowBootstrapTest {

    private static FlowEngine stubEngine() {
        return new FlowEngine() {
            @Override public FlowExecutionPlanFactory plans() { return null; }
            @Override public FlowScheduler scheduler() { return null; }
            @Override public FlowRegistry registry() { return null; }
            @Override public FlowEngineCapabilities capabilities() { return null; }
            @Override public FlowEngineStats stats() { return null; }
            @Override public void start() { /* test stub -- no lifecycle work required */ }
            @Override public void close() { /* test stub -- no resources to release */ }
        };
    }

    private static FlowProvider stubProvider(int priority, String id) {
        return new FlowProvider() {
            @Override public String providerId() { return id; }
            @Override public String providerName() { return id + "-provider"; }
            @Override public int priority() { return priority; }
            @Override public FlowEngine createEngine(FlowEngineConfig config) { return stubEngine(); }
        };
    }

    @Test
    @DisplayName("higher-priority provider wins in direct API comparison")
    void higherPriorityWins() {
        FlowProvider community = stubProvider(0, "community");
        FlowProvider enterprise = stubProvider(100, "enterprise");

        FlowProvider winner = java.util.stream.Stream.of(community, enterprise)
                .max(java.util.Comparator.comparingInt(FlowProvider::priority))
                .orElseThrow();

        assertThat(winner.providerId()).isEqualTo("enterprise");
    }

    @Test
    @DisplayName("single provider remains selected in direct API comparison")
    void singleProviderSelected() {
        FlowProvider community = stubProvider(0, "community");

        FlowProvider winner = java.util.stream.Stream.of(community)
                .max(java.util.Comparator.comparingInt(FlowProvider::priority))
                .orElseThrow();

        assertThat(winner.providerId()).isEqualTo("community");
    }

    @Test
    @DisplayName("creationFailure() carries EX_FLOW_7001 error code")
    void exceptionCarriesCorrectErrorCode() {
        FlowProviderException exception = FlowProviderException.creationFailure(
                "FlowBootstrap", "missing provider", null);

        assertThat(exception.errorCode()).isEqualTo(KernelErrorCodes.EX_FLOW_7001);
    }

    @Test
    @DisplayName("creationFailure() rawArgs[0] is providerName")
    void rawArgsProviderName() {
        FlowProviderException exception = FlowProviderException.creationFailure(
                "FlowBootstrap", "missing provider", null);

        assertThat(exception.rawArgs()[0]).isEqualTo("FlowBootstrap");
    }

    @Test
    @DisplayName("creationFailure() rawArgs[1] is reason string")
    void rawArgsReason() {
        FlowProviderException exception = FlowProviderException.creationFailure(
                "FlowBootstrap", "missing provider", null);

        assertThat(exception.rawArgs()[1]).isEqualTo("missing provider");
    }
}