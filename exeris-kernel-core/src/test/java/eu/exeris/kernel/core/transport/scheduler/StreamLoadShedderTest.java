/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.transport.scheduler;

import eu.exeris.kernel.spi.transport.StreamPriority;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

import static eu.exeris.kernel.core.transport.scheduler.AdmissionController.Decision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link StreamLoadShedder} — stream close invocation, shed counter,
 * null guards.
 *
 * @since 0.5.0
 */
@DisplayName("L1: StreamLoadShedder")
class StreamLoadShedderTest {

    // =========================================================================
    // Test infrastructure
    // =========================================================================

    private static TransportStream stubStream(long id, AtomicBoolean closedFlag) {
        return new TransportStream() {
            @Override
            public int read(MemorySegment target, int maxBytes) {
                return 0;
            }

            @Override
            public void write(MemorySegment source, int length) {
                // test stub — write direction not exercised by StreamLoadShedder
            }

            @Override
            public void queueWrite(eu.exeris.kernel.spi.memory.LoanedBuffer buffer, int length) {
                // test stub — async write path not exercised by StreamLoadShedder
            }

            @Override
            public long streamId() {
                return id;
            }

            @Override
            public boolean isBidirectional() {
                return true;
            }

            @Override
            public boolean isClientInitiated() {
                return true;
            }

            @Override
            public TransportConnection connection() {
                return null;
            }

            @Override
            public boolean hasPendingData() {
                return false;
            }

            @Override
            public void close() {
                closedFlag.set(true);
            }
        };
    }

    // =========================================================================
    // Construction guards
    // =========================================================================

    @Nested
    @DisplayName("Construction guards")
    class ConstructionGuards {

        @Test
        @DisplayName("null engineName → IllegalArgumentException")
        void nullEngineNameThrows() {
            assertThatThrownBy(() -> new StreamLoadShedder(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("blank engineName → IllegalArgumentException")
        void blankEngineNameThrows() {
            assertThatThrownBy(() -> new StreamLoadShedder("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // shed() — stream close invocation
    // =========================================================================

    @Nested
    @DisplayName("shed() invokes stream.close()")
    class ShedClosesStream {

        @Test
        @DisplayName("stream.close() is called on shed")
        void streamIsClosedOnShed() {
            AtomicBoolean closed = new AtomicBoolean(false);
            TransportStream stream = stubStream(42L, closed);
            StreamLoadShedder shedder = new StreamLoadShedder("TestEngine");

            shedder.shed(stream, StreamPriority.LOW, Decision.SHED_MEMORY, 10);

            assertThat(closed).isTrue();
        }

        @Test
        @DisplayName("shed() for SHED_CAPACITY also closes stream")
        void shedCapacityClosesStream() {
            AtomicBoolean closed = new AtomicBoolean(false);
            TransportStream stream = stubStream(99L, closed);
            StreamLoadShedder shedder = new StreamLoadShedder("TestEngine");

            shedder.shed(stream, StreamPriority.TELEMETRY, Decision.SHED_CAPACITY, 5000);

            assertThat(closed).isTrue();
        }
    }

    // =========================================================================
    // Shed counter
    // =========================================================================

    @Nested
    @DisplayName("Shed counter is monotonically increasing")
    class ShedCounter {

        @Test
        @DisplayName("initial shed count is zero")
        void initialCountIsZero() {
            assertThat(new StreamLoadShedder("Engine").shedCount()).isZero();
        }

        @Test
        @DisplayName("shed count increments on each call")
        void counterIncrements() {
            StreamLoadShedder shedder = new StreamLoadShedder("Engine");
            for (int i = 0; i < 5; i++) {
                shedder.shed(stubStream(i, new AtomicBoolean()), StreamPriority.NORMAL, Decision.SHED_MEMORY, 0);
            }
            assertThat(shedder.shedCount()).isEqualTo(5L);
        }
    }
}
