/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.events;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: Event exception rawArgs binary layouts.
 *
 * <h2>EventBusException rawArgs (EX-EVENT-6002 publish overflow)</h2>
 * <pre>
 * index 0 → String  eventType
 * index 1 → long    queueDepth
 * index 2 → long    queueCapacity
 * </pre>
 *
 * <h2>EventRegistryException rawArgs (EX-EVENT-6003 duplicate conflict)</h2>
 * <pre>
 * index 0 → String  eventType
 * index 1 → int     ordinal
 * </pre>
 *
 * @since 0.5.0
 */
@DisplayName("L0: Event Exception rawArgs Binary Layouts")
class EventExceptionLayoutTest {

    @Nested
    @DisplayName("EventBusException.publishOverflow (EX-EVENT-6002)")
    class PublishOverflow {

        @Test
        @DisplayName("rawArgs[0]=eventType, [1]=queueDepth (long), [2]=queueCapacity (long)")
        void rawArgsLayout() {
            EventBusException ex = EventBusException.publishOverflow("UserCreated", 10_000L, 10_000L);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6002);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(3);
            assertThat(raw[0]).isEqualTo("UserCreated");
            assertThat(raw[1]).isEqualTo(10_000L);
            assertThat(raw[2]).isEqualTo(10_000L);
        }

        @Test
        @DisplayName("rawArgs[1] and [2] are Long type — binary serializer contract")
        void rawArgTypes() {
            EventBusException ex = EventBusException.publishOverflow("T", 1L, 100L);
            assertThat(ex.rawArgs()[1]).isInstanceOf(Long.class);
            assertThat(ex.rawArgs()[2]).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("getCause() is null — publishOverflow has no cause")
        void noCause() {
            assertThat(EventBusException.publishOverflow("T", 1L, 100L).getCause()).isNull();
        }

        @Test
        @DisplayName("Is unchecked (RuntimeException)")
        void isUnchecked() {
            assertThat(EventBusException.publishOverflow("T", 1L, 100L))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("EventBusException — general constructor")
    class EventBusGeneral {

        @Test
        @DisplayName("General constructor: errorCode=EX-EVENT-6002, rawArgs is non-null")
        void generalConstructorEmptyRawArgs() {
            EventBusException ex = new EventBusException("Bus shutdown in progress");
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6002);
            assertThat(ex.rawArgs()).isNotNull();
        }

        @Test
        @DisplayName("General constructor with cause — cause is preserved")
        void generalConstructorWithCause() {
            RuntimeException root = new RuntimeException("ring closed");
            EventBusException ex = new EventBusException("bus closed", root);
            assertThat(ex.getCause()).isSameAs(root);
        }
    }

    @Nested
    @DisplayName("EventRegistryException.duplicateConflict (EX-EVENT-6003)")
    class DuplicateConflict {

        @Test
        @DisplayName("rawArgs[0]=eventType (String), [1]=ordinal (int)")
        void rawArgsLayout() {
            EventRegistryException ex = EventRegistryException.duplicateConflict("OrderPlaced", 42);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6003);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("OrderPlaced");
            assertThat(raw[1]).isEqualTo(42);
        }

        @Test
        @DisplayName("rawArgs[1] is Integer type — not Long — binary serializer contract")
        void slot1IsInteger() {
            EventRegistryException ex = EventRegistryException.duplicateConflict("T", 7);
            assertThat(ex.rawArgs()[1]).isInstanceOf(Integer.class);
        }

        @Test
        @DisplayName("getCause() is null — duplicateConflict has no cause")
        void noCause() {
            assertThat(EventRegistryException.duplicateConflict("T", 1).getCause()).isNull();
        }
    }

    @Nested
    @DisplayName("EventRegistryException.postStartRejected (EX-EVENT-6003)")
    class PostStartRejected {

        @Test
        @DisplayName("rawArgs[0]=eventType, rawArgs[1]=-1 sentinel (post-start)")
        void rawArgsLayout() {
            EventRegistryException ex = EventRegistryException.postStartRejected("LateEvent");
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6003);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("LateEvent");
            assertThat(raw[1]).isEqualTo(-1);
        }

        @Test
        @DisplayName("Is unchecked (RuntimeException)")
        void isUnchecked() {
            assertThat(EventRegistryException.postStartRejected("T"))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}

