/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.isolation;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.IsolationKeySpec;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit: {@link IsolationStamp} — every branch of the stamping rule (ADR-101).
 */
@DisplayName("IsolationStamp — resolves the isolation ordinal from the bound storage context")
class IsolationStampTest {

    private static final int ORD_EVENT_TYPE = 5;
    private static final int ORD_A          = 101;

    private final CoreIsolationKeyRegistry keys = new CoreIsolationKeyRegistry();

    IsolationStampTest() {
        keys.register(IsolationKeySpec.of("tenant-a", ORD_A));
    }

    private static EventDescriptor descriptor(int isolationOrdinal) {
        return EventDescriptor.of(1L, 2L, 3L, 4L, ORD_EVENT_TYPE, EventDescriptor.FLAG_PERSISTENT,
                1_700_000_000_000L, isolationOrdinal);
    }

    private static ScopedValue.Carrier bind(ImmutableStorageContext context) {
        return ScopedValue.where(KernelProviders.STORAGE_CONTEXT, context);
    }

    @Nested
    @DisplayName("no bound context, or an empty key: the publication is unscoped")
    class Unscoped {

        @Test
        @DisplayName("an unscoped descriptor with no bound context is returned as the same instance")
        void unboundUnscopedReturnsSameInstance() {
            EventDescriptor d = descriptor(0);
            assertThat(IsolationStamp.stamp(d, keys)).isSameAs(d);
        }

        @Test
        @DisplayName("an unscoped descriptor under system scope (empty key) is returned as the same instance")
        void systemScopeUnscopedReturnsSameInstance() throws Exception {
            EventDescriptor d = descriptor(0);
            EventDescriptor result = bind(ImmutableStorageContext.system())
                    .call(() -> IsolationStamp.stamp(d, keys));
            assertThat(result).isSameAs(d);
        }

        @Test
        @DisplayName("a non-zero ordinal with no bound context is refused as isolation-mismatch")
        void nonZeroWithoutContextRefused() {
            EventDescriptor d = descriptor(ORD_A);
            assertThatThrownBy(() -> IsolationStamp.stamp(d, keys))
                    .isInstanceOfSatisfying(EventBusException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6009);
                        assertThat(ex.rawArgs()).containsExactly(ORD_EVENT_TYPE,
                                IsolationStamp.REASON_MISMATCH);
                    });
        }
    }

    @Nested
    @DisplayName("bound registered key: the publication takes the key's ordinal")
    class Scoped {

        @Test
        @DisplayName("a pre-stamped descriptor carrying the bound ordinal is returned as the same instance")
        void matchingOrdinalReturnsSameInstance() throws Exception {
            EventDescriptor d = descriptor(ORD_A);
            EventDescriptor result = bind(ImmutableStorageContext.shared("tenant-a"))
                    .call(() -> IsolationStamp.stamp(d, keys));
            assertThat(result).isSameAs(d);
        }

        @Test
        @DisplayName("a descriptor carrying 0 is stamped with the bound ordinal, all other fields unchanged")
        void zeroIsStampedWithBoundOrdinal() throws Exception {
            EventDescriptor d = descriptor(0);
            EventDescriptor result = bind(ImmutableStorageContext.shared("tenant-a"))
                    .call(() -> IsolationStamp.stamp(d, keys));
            assertThat(result).isNotSameAs(d);
            assertThat(result.isolationOrdinal()).isEqualTo(ORD_A);
            assertThat(result.eventIdHigh()).isEqualTo(d.eventIdHigh());
            assertThat(result.eventIdLow()).isEqualTo(d.eventIdLow());
            assertThat(result.streamIdHigh()).isEqualTo(d.streamIdHigh());
            assertThat(result.streamIdLow()).isEqualTo(d.streamIdLow());
            assertThat(result.eventTypeOrdinal()).isEqualTo(d.eventTypeOrdinal());
            assertThat(result.flags()).isEqualTo(d.flags());
            assertThat(result.occurredAtEpochMs()).isEqualTo(d.occurredAtEpochMs());
        }

        @Test
        @DisplayName("a non-zero ordinal that disagrees with the bound key is refused as isolation-mismatch")
        void disagreeingOrdinalRefused() {
            EventDescriptor d = descriptor(102);
            assertThatThrownBy(() -> bind(ImmutableStorageContext.shared("tenant-a"))
                    .call(() -> IsolationStamp.stamp(d, keys)))
                    .isInstanceOfSatisfying(EventBusException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6009);
                        assertThat(ex.rawArgs()).containsExactly(ORD_EVENT_TYPE,
                                IsolationStamp.REASON_MISMATCH);
                    });
        }

        @Test
        @DisplayName("a bound key that is not registered is refused as unregistered-isolation-key")
        void unregisteredKeyRefused() {
            EventDescriptor d = descriptor(0);
            assertThatThrownBy(() -> bind(ImmutableStorageContext.shared("tenant-z"))
                    .call(() -> IsolationStamp.stamp(d, keys)))
                    .isInstanceOfSatisfying(EventBusException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6009);
                        assertThat(ex.rawArgs()).containsExactly(ORD_EVENT_TYPE,
                                IsolationStamp.REASON_UNREGISTERED_KEY);
                    });
        }
    }
}
