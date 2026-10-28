/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.core.memory.WatermarkLevel;
import eu.exeris.kernel.core.memory.WatermarkManager;
import eu.exeris.kernel.core.transport.scheduler.AdmissionController;
import eu.exeris.kernel.core.transport.scheduler.AdmissionController.Decision;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.time.TimeKernelProviders;
import eu.exeris.kernel.spi.time.TimeSource;
import eu.exeris.kernel.spi.transport.StreamPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real Community allocator with a pressure budget, a watermark manager sampling it, and the
 * arbiter and admission controller the transport builds: the chain that turns held bytes into an
 * admission decision, with no mock in it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Community: admission under a pressure budget")
class CommunityAdmissionUnderBudgetTest {

    private static final long KIB = 1_024L;
    private static final long BUDGET = 1_024L * KIB;
    private static final long PAST_GRACE_SECONDS = 31L;
    private static final int CEILING = 1_024;

    /** A source the test moves by hand. */
    private static final class MovableSource implements TimeSource {
        private final AtomicLong nowNs = new AtomicLong();

        void advanceSeconds(long seconds) {
            nowNs.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
        }

        @Override
        public long nanoTime() {
            return nowNs.get();
        }

        @Override
        public Instant wallTime() {
            return Instant.EPOCH;
        }
    }

    private MemoryAllocator allocator;
    private final List<LoanedBuffer> held = new ArrayList<>();

    @BeforeEach
    void openAllocator() {
        MemoryProviderConfig defaults = MemoryProviderConfig.defaults();
        allocator = new CommunityMemoryProvider().createAllocator(new MemoryProviderConfig(
                BUDGET, defaults.networkOffHeapThreshold(), defaults.carrierCount(),
                defaults.leakDetection(), defaults.jfrEnabled()));
    }

    @AfterEach
    void closeAllocator() {
        held.forEach(LoanedBuffer::close);
        allocator.close();
    }

    /** Holds {@code kib} KiB, samples it, and returns the controller an engine would admit through. */
    private AdmissionController holdAndRefresh(long kib, WatermarkLevel expected) {
        held.add(allocator.allocateInfrastructure(kib * KIB));
        WatermarkManager manager = new WatermarkManager(allocator);
        assertThat(manager.refresh()).isEqualTo(expected);
        MovableSource source = new MovableSource();
        ResourceArbiter[] arbiter = new ResourceArbiter[1];
        ScopedValue.where(TimeKernelProviders.TIME_SOURCE, source)
                .run(() -> arbiter[0] = new ResourceArbiter(manager));
        source.advanceSeconds(PAST_GRACE_SECONDS);
        return new AdmissionController(arbiter[0], CEILING);
    }

    @Test
    @DisplayName("768 KiB of 1 MiB (WARNING): a NORMAL stream is shed, a CRITICAL stream is admitted")
    void warning() {
        AdmissionController controller = holdAndRefresh(768, WatermarkLevel.WARNING);

        assertThat(controller.admit(StreamPriority.NORMAL)).isEqualTo(Decision.SHED_MEMORY);
        assertThat(controller.admit(StreamPriority.CRITICAL)).isEqualTo(Decision.ADMIT);
    }

    @Test
    @DisplayName("920 KiB of 1 MiB (CRITICAL): a HIGH stream is shed, a CRITICAL stream is admitted")
    void critical() {
        AdmissionController controller = holdAndRefresh(920, WatermarkLevel.CRITICAL);

        assertThat(controller.admit(StreamPriority.HIGH)).isEqualTo(Decision.SHED_MEMORY);
        assertThat(controller.admit(StreamPriority.CRITICAL)).isEqualTo(Decision.ADMIT);
    }

    @Test
    @DisplayName("1000 KiB of 1 MiB (SHEDDING): even a CRITICAL stream is shed")
    void shedding() {
        AdmissionController controller = holdAndRefresh(1_000, WatermarkLevel.SHEDDING);

        assertThat(controller.admit(StreamPriority.CRITICAL)).isEqualTo(Decision.SHED_MEMORY);
    }

    @Test
    @DisplayName("600 KiB of 1 MiB (NORMAL): a NORMAL stream is admitted")
    void normal() {
        AdmissionController controller = holdAndRefresh(600, WatermarkLevel.NORMAL);

        assertThat(controller.admit(StreamPriority.NORMAL)).isEqualTo(Decision.ADMIT);
    }
}
