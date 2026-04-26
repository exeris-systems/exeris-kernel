/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventEngineStats;
import eu.exeris.kernel.spi.events.EventLoop;
import eu.exeris.kernel.spi.events.EventProvider;
import eu.exeris.kernel.spi.events.EventQueue;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.events.EventProviderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("L1: EventBootstrap - priority selection and exception contract")
class EventBootstrapTest {

    private static EventEngine stubEngine() {
        return new EventEngine() {
            @Override public EventBus bus() { return null; }
            @Override public EventQueue queue() { return null; }
            @Override public EventLoop loop() { return null; }
            @Override public EventRegistry registry() { return null; }
            @Override public void start() { /* test stub -- no lifecycle work required */ }
            @Override public void close() { /* test stub -- no resources to release */ }
            @Override public EventEngineStats stats() { return null; }
        };
    }

    private static EventProvider stubProvider(int priority, String id) {
        return new EventProvider() {
            @Override public String providerName() { return id + "-provider"; }
            @Override public String providerId() { return id; }
            @Override public int priority() { return priority; }
            @Override public EventEngine createEngine(EventEngineConfig config) { return stubEngine(); }
        };
    }

    @Test
    @DisplayName("higher-priority provider wins in direct API comparison")
    void higherPriorityWins() {
        EventProvider community = stubProvider(0, "community");
        EventProvider enterprise = stubProvider(100, "enterprise");

        EventProvider winner = java.util.stream.Stream.of(community, enterprise)
                .max(java.util.Comparator.comparingInt(EventProvider::priority))
                .orElseThrow();

        assertThat(winner.providerId()).isEqualTo("enterprise");
    }

    @Test
    @DisplayName("single provider remains selected in direct API comparison")
    void singleProviderSelected() {
        EventProvider community = stubProvider(0, "community");

        EventProvider winner = java.util.stream.Stream.of(community)
                .max(java.util.Comparator.comparingInt(EventProvider::priority))
                .orElseThrow();

        assertThat(winner.providerId()).isEqualTo("community");
    }

    @Test
    @DisplayName("creationFailure() carries EX_EVENT_6004 error code")
    void exceptionCarriesCorrectErrorCode() {
        EventProviderException exception = EventProviderException.creationFailure(
                "EventBootstrap", "missing provider", null);

        assertThat(exception.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6004);
    }

    @Test
    @DisplayName("creationFailure() rawArgs[0] is providerName")
    void rawArgsProviderName() {
        EventProviderException exception = EventProviderException.creationFailure(
                "EventBootstrap", "missing provider", null);

        assertThat(exception.rawArgs()[0]).isEqualTo("EventBootstrap");
    }

    @Test
    @DisplayName("creationFailure() rawArgs[1] is reason string")
    void rawArgsReason() {
        EventProviderException exception = EventProviderException.creationFailure(
                "EventBootstrap", "missing provider", null);

        assertThat(exception.rawArgs()[1]).isEqualTo("missing provider");
    }
}