/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler;

import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.core.memory.ResourceArbiterTestHelper;
import eu.exeris.kernel.core.memory.WatermarkLevel;
import eu.exeris.kernel.core.memory.WatermarkManager;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.spi.transport.StreamPriority;
import eu.exeris.kernel.spi.transport.TransportConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static eu.exeris.kernel.core.transport.scheduler.AdmissionController.Decision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link AdmissionController} — decision matrix, counter correctness,
 * capacity limit enforcement.
 *
 * @since 0.5.0
 */
@DisplayName("L1: AdmissionController")
class AdmissionControllerTest {

    // =========================================================================
    // Test infrastructure
    // =========================================================================

    private static MemoryAllocator stubAllocator(long allocated, long total) {
        return new MemoryAllocator() {
            @Override
            public MemoryStats stats() {
                return new MemoryStats(total, allocated, total - allocated,
                        0, 0, allocated, 0, 0, LeakDetectionMode.DISABLED);
            }

            @Override
            public LoanedBuffer allocate(AllocationHint h) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateNetwork(int b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateCarrierSlab(int i) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateInfrastructure(long s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                // test stub — no off-heap resources to release
            }
        };
    }

    private static AdmissionController controllerAtLevel(WatermarkLevel level) {
        long total = 1_000_000L;
        long allocated = utilizationForLevel(level, total);
        WatermarkManager mgr = new WatermarkManager(stubAllocator(allocated, total));
        mgr.refresh();
        ResourceArbiter arbiter = ResourceArbiterTestHelper.expiredGraceArbiter(mgr);
        return new AdmissionController(arbiter);
    }

    private static AdmissionController controllerWithCeiling(int maxActiveStreams) {
        long total = 1_000_000L;
        WatermarkManager mgr = new WatermarkManager(
                stubAllocator(utilizationForLevel(WatermarkLevel.NORMAL, total), total));
        mgr.refresh();
        return new AdmissionController(
                ResourceArbiterTestHelper.expiredGraceArbiter(mgr), maxActiveStreams);
    }

    private static int admitUntilShed(AdmissionController controller, int attempts) {
        int admitted = 0;
        for (int i = 0; i < attempts; i++) {
            if (controller.admit(StreamPriority.NORMAL) != Decision.ADMIT) {
                break;
            }
            admitted++;
        }
        return admitted;
    }

    private static long utilizationForLevel(WatermarkLevel level, long total) {
        return switch (level) {
            case NORMAL -> (long) (total * 0.50);
            case WARNING -> (long) (total * 0.75);
            case CRITICAL -> (long) (total * 0.90);
            case SHEDDING -> (long) (total * 0.97);
        };
    }

    // =========================================================================
    // Null validation
    // =========================================================================

    @Nested
    @DisplayName("Construction guards")
    class ConstructionGuards {

        @Test
        @DisplayName("null arbiter → IllegalArgumentException")
        void nullArbiterThrows() {
            assertThatThrownBy(() -> new AdmissionController(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("zero ceiling is refused — an engine that admits nothing serves nothing")
        void zeroCeilingRefused() {
            assertThatThrownBy(() -> controllerWithCeiling(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxActiveStreams");
        }

        @Test
        @DisplayName("a negative that is not the unbounded sentinel is refused")
        void negativeCeilingRefused() {
            assertThatThrownBy(() -> controllerWithCeiling(-2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxActiveStreams");
        }
    }

    // =========================================================================
    // Configured ceiling
    // =========================================================================

    @Nested
    @DisplayName("Configured ceiling (transport.paqs.maxActiveStreams)")
    class ConfiguredCeiling {

        @Test
        @DisplayName("a lowered ceiling sheds at the configured number, not at the default")
        void loweredCeilingEnforced() {
            AdmissionController controller = controllerWithCeiling(3);

            assertThat(admitUntilShed(controller, 10)).isEqualTo(3);
            assertThat(controller.admit(StreamPriority.CRITICAL)).isEqualTo(Decision.SHED_CAPACITY);
            assertThat(controller.maxActiveStreams()).isEqualTo(3);
        }

        @Test
        @DisplayName("a raised ceiling admits past the default")
        void raisedCeilingAdmitsPastDefault() {
            int raised = TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS + 10;
            AdmissionController controller = controllerWithCeiling(raised);

            assertThat(admitUntilShed(controller, raised + 5)).isEqualTo(raised);
        }

        @Test
        @DisplayName("the unbounded sentinel admits past the default and reports itself unrewritten")
        void unboundedAdmitsPastDefault() {
            AdmissionController controller =
                    controllerWithCeiling(TransportConfig.UNBOUNDED_ACTIVE_STREAMS);

            int attempts = TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS + 100;
            assertThat(admitUntilShed(controller, attempts)).isEqualTo(attempts);
            assertThat(controller.maxActiveStreams())
                    .isEqualTo(TransportConfig.UNBOUNDED_ACTIVE_STREAMS);
        }

        @Test
        @DisplayName("the no-ceiling argument constructor keeps the pre-key default")
        void defaultConstructorKeepsDocumentedDefault() {
            assertThat(controllerAtLevel(WatermarkLevel.NORMAL).maxActiveStreams())
                    .isEqualTo(TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS);
        }
    }

    // =========================================================================
    // Decision matrix — NORMAL pressure
    // =========================================================================

    @Nested
    @DisplayName("ALLOW pressure (NORMAL watermark)")
    class AllowPressure {

        @Test
        @DisplayName("CRITICAL priority → ADMIT")
        void criticalAdmitted() {
            assertThat(controllerAtLevel(WatermarkLevel.NORMAL).admit(StreamPriority.CRITICAL))
                    .isEqualTo(Decision.ADMIT);
        }

        @Test
        @DisplayName("TELEMETRY priority → ADMIT (no pressure)")
        void telemetryAdmitted() {
            assertThat(controllerAtLevel(WatermarkLevel.NORMAL).admit(StreamPriority.TELEMETRY))
                    .isEqualTo(Decision.ADMIT);
        }
    }

    // =========================================================================
    // Decision matrix — THROTTLE pressure (WARNING watermark)
    // =========================================================================

    @Nested
    @DisplayName("THROTTLE pressure (WARNING watermark)")
    class ThrottlePressure {

        @Test
        @DisplayName("CRITICAL → ADMIT under throttle")
        void criticalAdmittedUnderThrottle() {
            assertThat(controllerAtLevel(WatermarkLevel.WARNING).admit(StreamPriority.CRITICAL))
                    .isEqualTo(Decision.ADMIT);
        }

        @Test
        @DisplayName("HIGH → ADMIT under throttle")
        void highAdmittedUnderThrottle() {
            assertThat(controllerAtLevel(WatermarkLevel.WARNING).admit(StreamPriority.HIGH))
                    .isEqualTo(Decision.ADMIT);
        }

        @Test
        @DisplayName("NORMAL → SHED_MEMORY under throttle")
        void normalShedUnderThrottle() {
            assertThat(controllerAtLevel(WatermarkLevel.WARNING).admit(StreamPriority.NORMAL))
                    .isEqualTo(Decision.SHED_MEMORY);
        }

        @Test
        @DisplayName("LOW → SHED_MEMORY under throttle")
        void lowShedUnderThrottle() {
            assertThat(controllerAtLevel(WatermarkLevel.WARNING).admit(StreamPriority.LOW))
                    .isEqualTo(Decision.SHED_MEMORY);
        }

        @Test
        @DisplayName("TELEMETRY → SHED_MEMORY under throttle")
        void telemetryShedUnderThrottle() {
            assertThat(controllerAtLevel(WatermarkLevel.WARNING).admit(StreamPriority.TELEMETRY))
                    .isEqualTo(Decision.SHED_MEMORY);
        }
    }

    // =========================================================================
    // Decision matrix — REJECT pressure (CRITICAL watermark, TRANSPORT_IO)
    // =========================================================================

    @Nested
    @DisplayName("REJECT pressure (CRITICAL watermark → REJECT for TRANSPORT_IO)")
    class RejectPressure {

        @Test
        @DisplayName("CRITICAL → ADMIT under reject")
        void criticalAdmittedUnderReject() {
            assertThat(controllerAtLevel(WatermarkLevel.CRITICAL).admit(StreamPriority.CRITICAL))
                    .isEqualTo(Decision.ADMIT);
        }

        @Test
        @DisplayName("HIGH → SHED_MEMORY under reject")
        void highShedUnderReject() {
            assertThat(controllerAtLevel(WatermarkLevel.CRITICAL).admit(StreamPriority.HIGH))
                    .isEqualTo(Decision.SHED_MEMORY);
        }

        @Test
        @DisplayName("NORMAL → SHED_MEMORY under reject")
        void normalShedUnderReject() {
            assertThat(controllerAtLevel(WatermarkLevel.CRITICAL).admit(StreamPriority.NORMAL))
                    .isEqualTo(Decision.SHED_MEMORY);
        }
    }

    // =========================================================================
    // Decision matrix — SHED_LOAD (SHEDDING watermark)
    // =========================================================================

    @Nested
    @DisplayName("SHED_LOAD pressure (SHEDDING watermark)")
    class ShedLoadPressure {

        @Test
        @DisplayName("CRITICAL → SHED_MEMORY even at CRITICAL level when SHED_LOAD")
        void criticalShedUnderShedLoad() {
            assertThat(controllerAtLevel(WatermarkLevel.SHEDDING).admit(StreamPriority.CRITICAL))
                    .isEqualTo(Decision.SHED_MEMORY);
        }

        @Test
        @DisplayName("TELEMETRY → SHED_MEMORY under SHED_LOAD")
        void telemetryShedUnderShedLoad() {
            assertThat(controllerAtLevel(WatermarkLevel.SHEDDING).admit(StreamPriority.TELEMETRY))
                    .isEqualTo(Decision.SHED_MEMORY);
        }
    }

    // =========================================================================
    // Active stream counter correctness
    // =========================================================================

    @Nested
    @DisplayName("Active stream counter")
    class ActiveStreamCounter {

        @Test
        @DisplayName("initial count is zero")
        void initialCountIsZero() {
            AdmissionController controller = controllerAtLevel(WatermarkLevel.NORMAL);
            assertThat(controller.activeStreamCount()).isZero();
        }

        @Test
        @DisplayName("admit() reserves slot; onStreamCompleted decrements")
        void incrementAndDecrement() {
            AdmissionController controller = controllerAtLevel(WatermarkLevel.NORMAL);
            controller.admit(StreamPriority.NORMAL);
            controller.admit(StreamPriority.NORMAL);
            assertThat(controller.activeStreamCount()).isEqualTo(2);
            controller.onStreamCompleted();
            assertThat(controller.activeStreamCount()).isEqualTo(1);
            controller.onStreamCompleted();
            assertThat(controller.activeStreamCount()).isZero();
        }
    }

    // =========================================================================
    // Capacity limit
    // =========================================================================

    @Nested
    @DisplayName("Capacity limit (default ceiling)")
    class CapacityLimit {

        @Test
        @DisplayName("SHED_CAPACITY returned when active count equals the default ceiling")
        void shedCapacityWhenFull() {
            AdmissionController controller = controllerAtLevel(WatermarkLevel.NORMAL);
            int maxStreams = TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS;
            for (int i = 0; i < maxStreams; i++) {
                assertThat(controller.admit(StreamPriority.NORMAL)).isEqualTo(Decision.ADMIT);
            }
            assertThat(controller.activeStreamCount()).isEqualTo(maxStreams);
            assertThat(controller.admit(StreamPriority.CRITICAL)).isEqualTo(Decision.SHED_CAPACITY);
        }
    }

    // =========================================================================
    // Decision#isAdmit contract
    // =========================================================================

    @Nested
    @DisplayName("Decision.isAdmit() contract")
    class IsAdmitContract {

        @Test
        @DisplayName("ADMIT.isAdmit() → true")
        void admitIsTrue() {
            assertThat(Decision.ADMIT.isAdmit()).isTrue();
        }

        @Test
        @DisplayName("SHED_MEMORY.isAdmit() → false")
        void shedMemoryIsFalse() {
            assertThat(Decision.SHED_MEMORY.isAdmit()).isFalse();
        }

        @Test
        @DisplayName("SHED_CAPACITY.isAdmit() → false")
        void shedCapacityIsFalse() {
            assertThat(Decision.SHED_CAPACITY.isAdmit()).isFalse();
        }
    }
}
