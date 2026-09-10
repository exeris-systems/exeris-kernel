/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineStats;
import eu.exeris.kernel.spi.events.EventLoop;
import eu.exeris.kernel.spi.events.EventQueue;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giving up on the FlowProgress ordinal must be observable.
 *
 * <p>When every candidate in the probe window collides, publication is disabled for the life of the
 * process and {@code publishProgress} returns on a cached sentinel from then on. Before v0.12
 * nothing recorded that, so a consumer subscribed to {@code FlowProgress} received nothing —
 * indistinguishable from a system in which no flow ever terminated.
 *
 * <p>The registry stub refuses every registration and resolves nothing, which is exactly the
 * exhausted-window condition; reaching it through a live flow would need a whole runtime instance
 * to assert one branch.
 */
@DisplayName("FlowProgressPublisher — a permanently disabled progress channel says so")
class FlowProgressDisabledEventTest {

    private static final String PROGRESS_DISABLED = "eu.exeris.kernel.flow.ProgressDisabled";
    private static final long SETTLE_SECONDS = 10L;

    @Test
    @DisplayName("exhausting the probe window emits the event and disables publication")
    void exhaustedWindowEmits() throws Exception {
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();
        CountDownLatch seen = new CountDownLatch(1);
        AtomicInteger registerAttempts = new AtomicInteger();

        int ordinal;
        try (RecordingStream stream = new RecordingStream()) {
            stream.enable(PROGRESS_DISABLED);
            stream.onEvent(PROGRESS_DISABLED, event -> {
                if (captured.compareAndSet(null, event)) {
                    seen.countDown();
                }
            });
            stream.startAsync();

            ordinal = new FlowProgressPublisher()
                    .resolveFlowProgressOrdinal(new AlwaysCollidingEngine(registerAttempts));

            assertThat(seen.await(SETTLE_SECONDS, TimeUnit.SECONDS))
                    .as("a channel that goes silent for the life of the process must say so once")
                    .isTrue();
        }

        assertThat(ordinal)
                .as("the sentinel is what publishProgress caches and returns on thereafter")
                .isNegative();
        assertThat(registerAttempts.get())
                .as("the whole window must be probed before giving up")
                .isEqualTo(captured.get().getInt("probeLimit"));
        assertThat(captured.get().getString("eventTypeName")).isEqualTo("FlowProgress");
    }

    /** Refuses every registration and resolves nothing — the exhausted-window condition. */
    private record AlwaysCollidingEngine(AtomicInteger registerAttempts) implements EventEngine {

        @Override
        public EventRegistry registry() {
            return new EventRegistry() {
                @Override
                public void register(EventTypeSpec spec) {
                    registerAttempts.incrementAndGet();
                    throw new IllegalStateException("ordinal taken");
                }

                @Override
                public EventTypeSpec resolve(String eventType) {
                    return null;
                }

                @Override
                public Set<String> registeredTypes() {
                    return Set.of();
                }

                @Override
                public int size() {
                    return 0;
                }
            };
        }

        @Override
        public EventBus bus() {
            throw new UnsupportedOperationException("not reached: no ordinal is ever claimed");
        }

        @Override
        public EventQueue queue() {
            throw new UnsupportedOperationException("not reached");
        }

        @Override
        public EventLoop loop() {
            throw new UnsupportedOperationException("not reached");
        }

        @Override
        public EventEngineStats stats() {
            throw new UnsupportedOperationException("not reached");
        }

        @Override
        public void start() {
            // Nothing to start: the registry is the only surface this case touches.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
