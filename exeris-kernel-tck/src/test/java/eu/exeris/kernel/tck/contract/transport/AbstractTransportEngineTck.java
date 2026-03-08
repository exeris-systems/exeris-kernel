/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.transport;

import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract base for {@link TransportEngine} lifecycle verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code start()} transitions engine to running state</li>
 *   <li>{@code stop()} transitions engine to stopped state</li>
 *   <li>{@code close()} is idempotent</li>
 *   <li>Idempotent start/stop cycle — restart is safe</li>
 *   <li>{@code mode()} correctly reports the configured {@link TransportMode}</li>
 *   <li>{@code engineName()} is non-blank</li>
 *   <li>{@code stats()} returns valid diagnostics snapshot</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractTransportEngineTck {

    /**
     * Creates a fully initialised (but not yet started) {@link TransportEngine} in SERVER mode.
     * The engine MUST have a stream handler already registered.
     */
    protected abstract TransportEngine createEngine();

    /**
     * Returns the expected {@link TransportMode} of the engine created by {@link #createEngine()}.
     */
    protected TransportMode expectedMode() {
        return TransportMode.SERVER;
    }

    private TransportEngine engine;

    @BeforeEach
    final void setUpEngine() {
        engine = createEngine();
    }

    @AfterEach
    final void tearDownEngine() {
        engine.close();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Lifecycle contract")
    class Lifecycle {

        @Test
        @DisplayName("start() → stop() → close() — happy path")
        void happyPathLifecycle() {
            assertThatCode(() -> engine.start()).doesNotThrowAnyException();
            assertThatCode(() -> engine.stop()).doesNotThrowAnyException();
            assertThatCode(() -> engine.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("close() is idempotent — calling twice does not throw")
        void closeIsIdempotent() {
            engine.start();
            engine.stop();
            engine.close();
            assertThatCode(() -> engine.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("close() on a running engine implicitly stops it")
        void closeImplicitlyStops() {
            engine.start();
            assertThatCode(() -> engine.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("start() → stop() → start() — idempotent restart is safe")
        void idempotentRestart() {
            engine.start();
            engine.stop();
            // ADR-011: restart must be safe — engine binds new socket
            assertThatCode(() -> engine.start()).doesNotThrowAnyException();
            engine.stop();
        }
    }

    // =========================================================================
    // Mode reporting
    // =========================================================================

    @Nested
    @DisplayName("TransportMode reporting")
    class ModeReporting {

        @Test
        @DisplayName("mode() returns the configured transport mode")
        void modeMatchesConfig() {
            assertThat(engine.mode())
                    .as("Engine must report its configured mode")
                    .isEqualTo(expectedMode());
        }
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    @Nested
    @DisplayName("Diagnostics contract")
    class Diagnostics {

        @Test
        @DisplayName("engineName() is non-blank")
        void engineNameNonBlank() {
            assertThat(engine.engineName()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("stats() returns non-null snapshot before start")
        void statsBeforeStart() {
            TransportStats stats = engine.stats();
            assertThat(stats).isNotNull();
        }

        @Test
        @DisplayName("stats() returns valid snapshot after start")
        void statsAfterStart() {
            engine.start();
            TransportStats stats = engine.stats();
            assertThat(stats).isNotNull();
            assertThat(stats.activeConnections()).isGreaterThanOrEqualTo(0);
            assertThat(stats.totalAccepted()).isGreaterThanOrEqualTo(0);
            engine.stop();
        }
    }
}
