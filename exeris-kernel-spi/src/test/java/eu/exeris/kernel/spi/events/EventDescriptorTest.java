/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EventDescriptor — isolation ordinal and the 7-argument bridge")
class EventDescriptorTest {

    @Test
    @DisplayName("the 7-argument constructor yields an unscoped descriptor (isolationOrdinal 0)")
    void sevenArgConstructorIsUnscoped() {
        EventDescriptor descriptor = new EventDescriptor(1L, 2L, 3L, 4L, 5, EventDescriptor.FLAG_PERSISTENT, 6L);
        assertThat(descriptor.isolationOrdinal()).isZero();
        assertThat(descriptor).isEqualTo(new EventDescriptor(1L, 2L, 3L, 4L, 5,
                EventDescriptor.FLAG_PERSISTENT, 6L, 0));
    }

    @Test
    @DisplayName("the 7-argument of() yields an unscoped descriptor (isolationOrdinal 0)")
    void sevenArgFactoryIsUnscoped() {
        assertThat(EventDescriptor.of(1L, 2L, 3L, 4L, 5, 0, 6L).isolationOrdinal()).isZero();
    }

    @Test
    @DisplayName("the 8-argument of() carries the isolation ordinal unchanged")
    void eightArgFactoryCarriesOrdinal() {
        EventDescriptor descriptor = EventDescriptor.of(1L, 2L, 3L, 4L, 5, 0, 6L, 101);
        assertThat(descriptor.isolationOrdinal()).isEqualTo(101);
        assertThat(descriptor.eventTypeOrdinal()).isEqualTo(5);
        assertThat(descriptor.occurredAtEpochMs()).isEqualTo(6L);
    }
}
