/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.time.TimeSource;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowState;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A saga's timeout is decided on a clock a test can move (ADR-082).
 *
 * <p>Before the seam this could not be written. The expiry comparison read {@code System.nanoTime()}
 * directly, so a test either slept for the real timeout or did not exercise expiry at all — and the
 * shipped timeout default is measured in seconds.
 *
 * <h2>Why a bound {@code ScopedValue} is not enough on its own</h2>
 * <p>A flow runs on a bare {@code Thread.ofVirtual()}, which inherits <b>no</b> {@code ScopedValue}
 * binding. So {@code CoreFlowRuntime} captures the source in {@code start()} — inside the carrier
 * scope, where {@code snapshotStore} and {@code guard} are captured for the same reason — and the
 * flow thread reads the field. This test binds the slot around {@code start()} for exactly that
 * reason, and it is what makes the seam real rather than decorative: a slot read on the flow thread
 * would always find the system clock and this test would time out instead of failing loudly.
 */
@DisplayName("CoreFlowRuntime — a saga TTL is decided on the bound TimeSource")
class CoreFlowTimeSourceTest {

    private static final String TIMEOUT_EVENT = "eu.exeris.kernel.flow.Timeout";
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    @Nested
    @DisplayName("Virtual clock")
    class Virtual {

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        @DisplayName("advancing the clock past the deadline expires the saga, with no sleeping")
        void advancingTheClockExpiresTheSaga() throws Exception {
            ManualTimeSource clock = new ManualTimeSource();
            CountDownLatch timedOut = new CountDownLatch(1);
            AtomicReference<RecordedEvent> captured = new AtomicReference<>();
            CountDownLatch firstStepEntered = new CountDownLatch(1);
            CountDownLatch clockMoved = new CountDownLatch(1);

            try (RecordingStream rs = new RecordingStream()) {
                rs.enable(TIMEOUT_EVENT);
                rs.onEvent(TIMEOUT_EVENT, event -> {
                    captured.compareAndSet(null, event);
                    timedOut.countDown();
                });
                rs.startAsync();

                CoreFlowEngine engine = engineBoundTo(clock);
                try {
                    FlowDefinition definition = engine.plans().newDefinition("ttl-virtual")
                            // Holds the saga IN FLIGHT while the clock moves. Without this the two
                            // steps run to completion in microseconds and there is no later step
                            // left for an expired deadline to refuse — the first draft of this test
                            // failed exactly that way.
                            .step("first", _ -> {
                                firstStepEntered.countDown();
                                awaitQuietly(clockMoved);
                                return FlowOutcome.CONTINUE;
                            }, null)
                            .step("second", _ -> FlowOutcome.COMPLETE, null)
                            .transition(0, 1)
                            .build();
                    FlowExecutionPlan plan = engine.plans().compile(definition);

                    // The deadline is built from the same clock, so it sits 30 virtual seconds out
                    // and no wall-clock time can reach it.
                    engine.scheduler().schedule(plan, context("ttl-instance", "ttl-virtual"));

                    assertThat(firstStepEntered.await(5, TimeUnit.SECONDS))
                            .as("the saga must actually start before the clock is moved")
                            .isTrue();

                    // The whole point: time moves because the test says so.
                    clock.advance(TIMEOUT_NANOS * 2);
                    clockMoved.countDown();

                    assertThat(timedOut.await(10, TimeUnit.SECONDS))
                            .as("the deadline is now in the past on the bound clock, so the next "
                                    + "step must be refused as timed out — without this the seam is "
                                    + "decorative and the flow thread is reading the system clock")
                            .isTrue();
                } finally {
                    engine.close();
                }
            }

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().getLong("overrunNanos"))
                    .as("the overrun is measured on the bound clock too, not on the wall")
                    .isPositive();
        }

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        @DisplayName("a clock that does not move leaves the saga alone — the discriminating half")
        void aStandingClockDoesNotExpireTheSaga() throws Exception {
            // Without this, the suite passes against an engine that times every saga out. The pair
            // is what says the clock DECIDES rather than that expiry merely happens.
            ManualTimeSource clock = new ManualTimeSource();
            CountDownLatch completed = new CountDownLatch(1);

            CoreFlowEngine engine = engineBoundTo(clock);
            try {
                FlowDefinition definition = engine.plans().newDefinition("ttl-standing")
                        .step("only", _ -> {
                            completed.countDown();
                            return FlowOutcome.COMPLETE;
                        }, null)
                        .build();

                engine.scheduler().schedule(
                        engine.plans().compile(definition),
                        context("standing-instance", "ttl-standing"));

                assertThat(completed.await(5, TimeUnit.SECONDS))
                        .as("the clock never advanced, so nothing may expire")
                        .isTrue();
            } finally {
                engine.close();
            }
        }
    }

    /**
     * Starts an engine with {@code clock} bound, so {@code start()} captures it.
     *
     * <p>The binding wraps {@code start()} and not the whole test: after start the flow threads read
     * the captured field, which is the arrangement under test.
     */
    private static CoreFlowEngine engineBoundTo(TimeSource clock) {
        FlowEngineConfig defaults = FlowEngineConfig.defaults("CoreFlowTimeSourceTest");
        CoreFlowEngine engine = new CoreFlowEngine(
                new FlowEngineConfig(
                        defaults.engineName(),
                        defaults.maxConcurrentFlows(),
                        TIMEOUT_NANOS,
                        defaults.maxSteps(),
                        defaults.maxTransitions(),
                        defaults.maxExecutionPlans(),
                        defaults.schedulerQueueCapacity(),
                        defaults.partitionName(),
                        defaults.partitionBytes(),
                        false,
                        defaults.compensationEnabled()),
                FlowEngineCapabilities.COMMUNITY.withProvider("core-flow-timesource-test"));
        ScopedValue.where(KernelProviders.TIME_SOURCE, clock).run(engine::start);
        return engine;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never advanced the clock");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /**
     * A context with {@code timeoutNanos = 0}, so the runtime derives the deadline from the plan on
     * the bound clock — which is the arrangement under test. A context carrying its own deadline
     * would bypass the read this test exists to drive.
     */
    private static FlowContext context(String instanceId, String definitionName) {
        UUID uuid = UUID.nameUUIDFromBytes(instanceId.getBytes(StandardCharsets.UTF_8));
        return new HeapFlowContext(
                uuid.getMostSignificantBits(),
                uuid.getLeastSignificantBits(),
                definitionName,
                0,
                FlowState.RUNNING,
                0L);
    }

    /** A clock that moves only when the test moves it. */
    private static final class ManualTimeSource implements TimeSource {

        private final AtomicLong nanos = new AtomicLong(0L);
        private final Instant origin = Instant.parse("2026-09-01T00:00:00Z");

        @Override
        public long nanoTime() {
            return nanos.get();
        }

        @Override
        public Instant wallTime() {
            // Moves with the monotonic reading, because the saga path converts between the two and
            // a wall clock that stood still while nanos advanced would drift the conversion.
            return origin.plusNanos(nanos.get());
        }

        void advance(long byNanos) {
            nanos.addAndGet(byNanos);
        }
    }
}
