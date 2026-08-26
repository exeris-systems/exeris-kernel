/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link HttpClientEngine} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code engineName()} is non-null and non-blank, stable across calls</li>
 *   <li>Engine is NOT running after creation (before {@code start()})</li>
 *   <li>{@code start()} transitions engine to RUNNING state</li>
 *   <li>{@code send(null)} throws {@link NullPointerException}</li>
 *   <li>{@code close()} is idempotent — multiple calls do not throw</li>
 *   <li>{@code start()} after {@code close()} throws {@link IllegalStateException}</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractHttpClientEngineTck {

    /**
     * Creates an {@link HttpClientEngine} under test.
     *
     * @param config client configuration
     * @return a fresh engine in CREATED state; never {@code null}
     */
    protected abstract HttpClientEngine createEngine(HttpConfig config);

    /**
     * Returns a valid client {@link HttpConfig}.
     *
     * @return a valid client config; never {@code null}
     */
    protected HttpConfig testConfig() {
        return HttpConfig.defaultClient();
    }

    private HttpClientEngine engine;

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
        @DisplayName("send(null) throws NullPointerException")
        void sendNullThrows() {
            assertThatThrownBy(() -> engine.send(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Lifecycle: START")
    class StartState {

        @Test
        @DisplayName("Engine is running after start()")
        void runningAfterStart() {
            engine.start();
            assertThat(engine.isRunning()).isTrue();
        }
    }

    @Nested
    @DisplayName("Lifecycle: CLOSE (idempotency)")
    class Close {

        @Test
        @DisplayName("close() is idempotent — second call does not throw")
        void closeIdempotent() {
            assertThatCode(() -> {
                engine.close();
                engine.close();
            })
                    .as("close() must be idempotent across repeated invocations")
                    .doesNotThrowAnyException();
            assertThat(engine.isRunning())
                    .as("Closed client engine must not remain in running state")
                    .isFalse();
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

