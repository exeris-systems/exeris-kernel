/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.transport.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counter a graceful shutdown waits on (issue #282).
 *
 * <p>Its invariants are cheap to state and expensive to get wrong: an over-decrement makes the drain
 * finish early and sever a live exchange, an under-decrement makes it hang to the deadline and get
 * SIGKILLed. The two socket-level tests in the transport TCK and the Community HTTP module prove the
 * mechanism end to end but cannot isolate the arithmetic from stream timing, so it is pinned here.
 */
@DisplayName("DrainCoordinator — what a graceful shutdown waits on")
class DrainCoordinatorTest {

    @Nested
    @DisplayName("Busy accounting")
    class BusyAccounting {

        @Test
        @DisplayName("a fresh coordinator has nothing to wait for")
        void startsEmpty() {
            assertThat(new DrainCoordinator().busyStreams()).isZero();
        }

        @Test
        @DisplayName("a registered stream is busy before its protocol says anything")
        void registeredStreamIsBusyByDefault() {
            DrainCoordinator coordinator = new DrainCoordinator();
            coordinator.registerStream();

            assertThat(coordinator.busyStreams())
                    .as("busy by default is the load-bearing direction: a protocol that reports "
                            + "nothing must still hold the drain, or teardown severs a handler "
                            + "still writing its response")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("markIdle() twice does not decrement twice")
        void markIdleIsIdempotent() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork first = coordinator.registerStream();
            coordinator.registerStream();

            first.markIdle();
            first.markIdle();

            assertThat(coordinator.busyStreams())
                    .as("a double decrement would drop the count below the work actually in flight, "
                            + "and the drain would finish while another stream is still being served")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("markBusy() after markIdle() puts the stream back on the drain's books")
        void markBusyRestoresTheStream() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork work = coordinator.registerStream();

            work.markIdle();
            work.markBusy();

            assertThat(coordinator.busyStreams())
                    .as("this is the keep-alive loop taking the next request: parked it is idle, "
                            + "reading a request it is busy again")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("markBusy() twice does not increment twice")
        void markBusyIsIdempotent() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork work = coordinator.registerStream();

            work.markBusy();
            work.markBusy();

            assertThat(coordinator.busyStreams())
                    .as("an inflated count never reaches zero, so the drain waits out its full "
                            + "deadline and the runtime SIGKILLs a shutdown that was already done")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("close() after markIdle() is a no-op")
        void closeAfterMarkIdleDoesNotDecrementAgain() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork first = coordinator.registerStream();
            coordinator.registerStream();

            first.markIdle();
            first.close();

            assertThat(coordinator.busyStreams())
                    .as("try-with-resources closes a handle the protocol loop already reported idle; "
                            + "that is the normal path, not an error path")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("close() releases a stream that never reported itself idle")
        void closeReleasesABusyStream() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork work = coordinator.registerStream();

            work.close();

            assertThat(coordinator.busyStreams())
                    .as("a raw handler stays busy for its whole life and is released only on exit")
                    .isZero();
        }

        @Test
        @DisplayName("streams account for themselves independently")
        void streamsAreIndependent() {
            DrainCoordinator coordinator = new DrainCoordinator();
            DrainCoordinator.StreamWork first = coordinator.registerStream();
            DrainCoordinator.StreamWork second = coordinator.registerStream();
            DrainCoordinator.StreamWork third = coordinator.registerStream();

            first.markIdle();
            third.markIdle();

            assertThat(coordinator.busyStreams()).isEqualTo(1);

            second.close();
            assertThat(coordinator.busyStreams()).isZero();
        }
    }

    @Nested
    @DisplayName("Draining flag")
    class DrainingFlag {

        @Test
        @DisplayName("a running engine is not draining")
        void notDrainingUntilMarked() {
            assertThat(new DrainCoordinator().isDraining()).isFalse();
        }

        @Test
        @DisplayName("markDraining() is one-way")
        void drainingIsMonotonic() {
            DrainCoordinator coordinator = new DrainCoordinator();

            coordinator.markDraining();
            coordinator.markDraining();

            assertThat(coordinator.isDraining())
                    .as("shutdown never un-starts; a protocol that saw the flag must not later be "
                            + "told the connection may be kept alive after all")
                    .isTrue();
        }

        @Test
        @DisplayName("draining does not by itself change what the drain waits for")
        void drainingDoesNotTouchTheBusyCount() {
            DrainCoordinator coordinator = new DrainCoordinator();
            coordinator.registerStream();

            coordinator.markDraining();

            assertThat(coordinator.busyStreams())
                    .as("marking the drain announces shutdown; it does not abandon work in flight")
                    .isEqualTo(1);
        }
    }
}
