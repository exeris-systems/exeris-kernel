/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.flow.model.FlowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: {@link FlowState} — off-heap code stability and state machine semantics.
 *
 * <h2>Integer Code Stability</h2>
 * <p>Each {@code FlowState} carries an integer {@link FlowState#code} that is persisted
 * in the off-heap flow context slab. Any change to these codes silently corrupts all
 * existing PARKED flows in the slab ring buffer.
 *
 * <h2>fromCode() Round-Trip</h2>
 * <p>The {@link FlowState#fromCode(int)} factory is used by the slab deserializer.
 * This test ensures every valid code round-trips correctly.
 *
 * @since 0.5.0
 */
@DisplayName("L0: FlowState — Off-Heap Code Stability + State Machine")
class FlowStateTest {

    // -----------------------------------------------------------------------
    // 1. Integer code stability — pinned
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("1. Integer code stability — off-heap slab serialization contract")
    class CodeStability {

        @Test
        @DisplayName("CREATED.code == 0")
        void createdCode() {
            assertThat(FlowState.CREATED.code).isZero();
        }

        @Test
        @DisplayName("RUNNING.code == 1")
        void runningCode() {
            assertThat(FlowState.RUNNING.code).isEqualTo(1);
        }

        @Test
        @DisplayName("PARKED.code == 2")
        void parkedCode() {
            assertThat(FlowState.PARKED.code).isEqualTo(2);
        }

        @Test
        @DisplayName("COMPLETED.code == 3")
        void completedCode() {
            assertThat(FlowState.COMPLETED.code).isEqualTo(3);
        }

        @Test
        @DisplayName("COMPENSATING.code == 4")
        void compensatingCode() {
            assertThat(FlowState.COMPENSATING.code).isEqualTo(4);
        }

        @Test
        @DisplayName("FAILED_ROLLEDBACK.code == 5")
        void failedRolledbackCode() {
            assertThat(FlowState.FAILED_ROLLEDBACK.code).isEqualTo(5);
        }

        @Test
        @DisplayName("Total FlowState count == 6 — new states require slab layout review")
        void totalStateCount() {
            assertThat(FlowState.values()).hasSize(6);
        }
    }

    // -----------------------------------------------------------------------
    // 2. fromCode() round-trip — slab deserializer contract
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("2. fromCode() round-trip — slab deserializer contract")
    class FromCodeRoundTrip {

        @Test
        @DisplayName("fromCode(0) == CREATED")
        void fromCode0() {
            assertThat(FlowState.fromCode(0)).isEqualTo(FlowState.CREATED);
        }

        @Test
        @DisplayName("fromCode(1) == RUNNING")
        void fromCode1() {
            assertThat(FlowState.fromCode(1)).isEqualTo(FlowState.RUNNING);
        }

        @Test
        @DisplayName("fromCode(2) == PARKED")
        void fromCode2() {
            assertThat(FlowState.fromCode(2)).isEqualTo(FlowState.PARKED);
        }

        @Test
        @DisplayName("fromCode(3) == COMPLETED")
        void fromCode3() {
            assertThat(FlowState.fromCode(3)).isEqualTo(FlowState.COMPLETED);
        }

        @Test
        @DisplayName("fromCode(4) == COMPENSATING")
        void fromCode4() {
            assertThat(FlowState.fromCode(4)).isEqualTo(FlowState.COMPENSATING);
        }

        @Test
        @DisplayName("fromCode(5) == FAILED_ROLLEDBACK")
        void fromCode5() {
            assertThat(FlowState.fromCode(5)).isEqualTo(FlowState.FAILED_ROLLEDBACK);
        }

        @Test
        @DisplayName("fromCode(s.code) == s for every state — perfect round-trip")
        void roundTripAllStates() {
            for (FlowState state : FlowState.values()) {
                assertThat(FlowState.fromCode(state.code))
                        .as("fromCode(%d) must round-trip to %s", state.code, state)
                        .isEqualTo(state);
            }
        }

        @Test
        @DisplayName("fromCode(99) throws IllegalArgumentException — unknown code")
        void unknownCodeThrows() {
            assertThatThrownBy(() -> FlowState.fromCode(99))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("fromCode(-1) throws IllegalArgumentException — negative code")
        void negativeCodeThrows() {
            assertThatThrownBy(() -> FlowState.fromCode(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -----------------------------------------------------------------------
    // 3. isTerminal() semantics
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("3. isTerminal() — state machine terminal contract")
    class TerminalSemantics {

        @Test
        @DisplayName("COMPLETED.isTerminal() == true")
        void completedIsTerminal() {
            assertThat(FlowState.COMPLETED.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("FAILED_ROLLEDBACK.isTerminal() == true")
        void failedRolledbackIsTerminal() {
            assertThat(FlowState.FAILED_ROLLEDBACK.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("CREATED.isTerminal() == false")
        void createdIsNotTerminal() {
            assertThat(FlowState.CREATED.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("RUNNING.isTerminal() == false")
        void runningIsNotTerminal() {
            assertThat(FlowState.RUNNING.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("PARKED.isTerminal() == false")
        void parkedIsNotTerminal() {
            assertThat(FlowState.PARKED.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("COMPENSATING.isTerminal() == false")
        void compensatingIsNotTerminal() {
            assertThat(FlowState.COMPENSATING.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("Exactly 2 terminal states — adding more requires state machine graph review")
        void exactlyTwoTerminalStates() {
            long terminalCount = Arrays.stream(FlowState.values())
                    .filter(FlowState::isTerminal)
                    .count();
            assertThat(terminalCount).isEqualTo(2L);
        }
    }
}

