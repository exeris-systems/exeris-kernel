/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link HttpServerEngine} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code engineName()} is non-null and non-blank, stable across calls</li>
 *   <li>Engine is NOT running after creation (before {@code start()})</li>
 *   <li>{@code start()} transitions engine to RUNNING state</li>
 *   <li>{@code stop()} transitions engine to STOPPED (not running)</li>
 *   <li>{@code setHandler(null)} throws {@link NullPointerException}</li>
 *   <li>{@code setHandler(handler)} after {@code start()} throws {@link IllegalStateException}</li>
 *   <li>{@code start()} after {@code close()} throws {@link IllegalStateException}</li>
 *   <li>{@code close()} is idempotent — multiple calls do not throw</li>
 * </ul>
 *
 * @since 0.5
 */
public abstract class AbstractHttpServerEngineTck {

    /**
     * Creates the contract; subclasses supply the engine under test via {@link #createEngine(HttpConfig)}.
     */
    public AbstractHttpServerEngineTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Creates an {@link HttpServerEngine} under test bound to the given config.
     *
     * @param config server configuration
     * @return a fresh engine in CREATED state; never {@code null}
     */
    protected abstract HttpServerEngine createEngine(HttpConfig config);

    /**
     * Returns a valid {@link HttpConfig} for a server using an available ephemeral port.
     *
     * @return a valid server config; never {@code null}
     */
    protected HttpConfig testConfig() {
        return new HttpConfig(
                HttpMode.SERVER,
                HttpConfig.DEFAULT_BIND_HOST,
                0,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                true,
                eu.exeris.kernel.spi.http.HttpVersion.HTTP_2
        );
    }

    private HttpServerEngine engine;

    /** A trivial handler that responds 200 OK for every request. */
    protected static final HttpHandler TRIVIAL_HANDLER =
            exchange -> exchange.respond(HttpStatus.OK);

    @BeforeEach
    final void createTestEngine() {
        engine = createEngine(testConfig());
    }

    @AfterEach
    final void closeTestEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @Nested
    @DisplayName("Engine identity")
    class Identity {

        @Test
        @DisplayName("engineName() is non-blank")
        void engineNameNonBlank() {
            assertThat(engine.engineName()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("engineName() is stable across calls")
        void engineNameStable() {
            assertThat(engine.engineName()).isEqualTo(engine.engineName());
        }
    }

    @Nested
    @DisplayName("Lifecycle: CREATED state")
    class CreatedState {

        @Test
        @DisplayName("Engine is not running after creation")
        void notRunningAfterCreation() {
            assertThat(engine.isRunning()).isFalse();
        }

        @Test
        @DisplayName("setHandler(null) throws NullPointerException")
        void setHandlerNullThrows() {
            assertThatThrownBy(() -> engine.setHandler(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Lifecycle: START → STOP")
    class StartStop {

        @Test
        @DisplayName("Engine is running after start()")
        void runningAfterStart() {
            engine.setHandler(TRIVIAL_HANDLER);
            engine.start();
            assertThat(engine.isRunning()).isTrue();
        }

        @Test
        @DisplayName("Engine is not running after stop()")
        void notRunningAfterStop() {
            engine.setHandler(TRIVIAL_HANDLER);
            engine.start();
            engine.stop();
            assertThat(engine.isRunning()).isFalse();
        }

        @Test
        @DisplayName("setHandler() after start() throws IllegalStateException")
        void setHandlerAfterStartThrows() {
            engine.setHandler(TRIVIAL_HANDLER);
            engine.start();
            assertThatThrownBy(() -> engine.setHandler(TRIVIAL_HANDLER))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Lifecycle: CLOSE (idempotency)")
    class Close {

        @Test
        @DisplayName("close() is idempotent — second call does not throw")
        void closeIdempotent() {
            engine.close();
            engine.close();
            assertThat(engine.isRunning()).isFalse();
        }

        @Test
        @DisplayName("start() after close() throws IllegalStateException")
        void startAfterCloseThrows() {
            engine.close();
            assertThatThrownBy(() -> engine.start())
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
