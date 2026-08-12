/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.flow.model.FlowMigrationState;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Contract: {@link FlowMigrationState} compact-constructor invariants and defensive copying.
 *
 * <p>The record shipped in v0.11 with no test of its own anywhere in the repository — its
 * invariants were exercised only indirectly, through migrations that happened to build a valid
 * one. This covers what a transform author can get wrong directly.
 *
 * @since 0.11.0
 */
@DisplayName("FlowMigrationState — what a transform may hand back")
class FlowMigrationStateTest {

    private static final String STEP_NAME = "await-payment";

    private static FlowMigrationState valid(byte[] opaqueState) {
        return new FlowMigrationState(0, STEP_NAME, new int[0], new String[0], 0, opaqueState);
    }

    @Nested
    @DisplayName("opaqueState is capped where it is authored")
    class OpaqueStateCap {

        @Test
        @DisplayName("a payload over the snapshot's cap is rejected here, not at persist time")
        void oversizedPayloadRejected() {
            byte[] tooLarge = new byte[FlowSnapshot.MAX_OPAQUE_STATE_BYTES + 1];

            // The bytes a transform returns go straight into a FlowSnapshot, which has always
            // enforced this limit. Without the check here the refusal arrived one type later, from
            // a constructor the transform author never called, naming neither the migration nor
            // the definition it belonged to.
            assertThatThrownBy(() -> valid(tooLarge))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("opaqueState");
        }

        @Test
        @DisplayName("a payload exactly at the cap is accepted")
        void payloadAtCapAccepted() {
            // The boundary in the other direction. A cap written with the wrong comparison rejects
            // the largest legal payload, and no oversized case would ever notice.
            assertThatCode(() -> valid(new byte[FlowSnapshot.MAX_OPAQUE_STATE_BYTES]))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the cap is the same number FlowSnapshot enforces")
        void capMatchesTheSnapshot() {
            // Pinned as a relationship rather than as a literal: the point is not that it is 916,
            // it is that the two agree. A future change to the row layout that moved one and not
            // the other would put the refusal back downstream without failing anything else.
            byte[] atSnapshotCap = new byte[FlowSnapshot.MAX_OPAQUE_STATE_BYTES];
            assertThat(valid(atSnapshotCap).opaqueState())
                    .hasSize(FlowSnapshot.MAX_OPAQUE_STATE_BYTES);
        }
    }

    @Nested
    @DisplayName("Arrays are copied in both directions")
    class DefensiveCopy {

        @Test
        @DisplayName("mutating the caller's array after construction does not alter the state")
        void constructionCopies() {
            byte[] payload = {1, 2, 3};
            FlowMigrationState state = valid(payload);

            payload[0] = 99;

            assertThat(state.opaqueState())
                    .as("the record copies on construction, so the caller's later write cannot "
                        + "reach the parked saga's payload")
                    .containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("mutating the accessor's result does not alter the state")
        void accessorCopies() {
            FlowMigrationState state = valid(new byte[]{1, 2, 3});

            state.opaqueState()[0] = 99;

            assertThat(state.opaqueState()).containsExactly(1, 2, 3);
        }
    }

    @Nested
    @DisplayName("Value equality compares array contents")
    class ValueEquality {

        @Test
        @DisplayName("two states describing the same parked saga are equal")
        void equalByContent() {
            // A record's generated equals compares array components by reference, which is both an
            // identity operation on a carrier and the reason the obvious way to assert a transform
            // is a no-op would be quietly always false. The hand-written equals exists for that.
            assertThat(valid(new byte[]{1, 2, 3}))
                    .isEqualTo(valid(new byte[]{1, 2, 3}))
                    .hasSameHashCodeAs(valid(new byte[]{1, 2, 3}));
        }

        @Test
        @DisplayName("differing payloads are not equal")
        void unequalByContent() {
            assertThat(valid(new byte[]{1, 2, 3})).isNotEqualTo(valid(new byte[]{1, 2, 4}));
        }
    }
}
