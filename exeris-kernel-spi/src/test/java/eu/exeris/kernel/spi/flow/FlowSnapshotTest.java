/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Contract: {@link FlowSnapshot} compact-constructor invariants, defensive array copy,
 * convenience-constructor compatibility, and the 0.7.0 {@code schemaVersion} optimistic-concurrency
 * field semantics.
 *
 * <h2>Scope</h2>
 * <ul>
 *   <li>All constructor invariants reject malformed input (null, blank, out-of-bounds).</li>
 *   <li>{@code compensationStack} and {@code opaqueState} are defensively copied on construction
 *       AND on accessor read — caller mutations cannot alter snapshot state.</li>
 *   <li>The 10-arg convenience constructor preserves the v0.6 call shape and defaults
 *       {@link FlowSnapshot#schemaVersion()} to {@link FlowSnapshot#SCHEMA_VERSION_INITIAL}.</li>
 *   <li>{@code schemaVersion} participates in {@code equals}/{@code hashCode} and appears in
 *       {@code toString} — two snapshots that differ ONLY in schemaVersion are not equal
 *       (optimistic-concurrency contract from ADR-013 §5).</li>
 * </ul>
 *
 * @since 0.7.0
 */
@DisplayName("L1: FlowSnapshot — invariants + defensive copy + schemaVersion (FLOW-101)")
class FlowSnapshotTest {

    private static final long      ID_MOST  = 0x0123456789ABCDEFL;
    private static final long      ID_LEAST = 0xFEDCBA9876543210L;
    private static final String    DEF_NAME = "checkout-saga";
    private static final FlowState STATE    = FlowState.PARKED;
    private static final Instant   T_NOW    = Instant.parse("2026-05-01T10:00:00Z");
    private static final Instant   T_END    = Instant.parse("2026-05-01T10:30:00Z");
    private static final int[]     STACK    = {1, 2, 3};
    private static final String[]  NAMES    = {"reserve", "charge", "ship"};
    private static final byte[]    OPAQUE   = {0x10, 0x20, 0x30};
    private static final Optional<String> STEP_NAME = Optional.of("charge-card");

    private static FlowSnapshot canonical(long schemaVersion) {
        return new FlowSnapshot(
                ID_MOST, ID_LEAST, DEF_NAME, FlowDefinition.INITIAL_VERSION, 5, STEP_NAME, STATE, T_NOW, T_END,
                STACK.clone(), NAMES.clone(), STACK.length, OPAQUE.clone(), schemaVersion);
    }

    private static FlowSnapshot canonical() {
        return canonical(FlowSnapshot.SCHEMA_VERSION_INITIAL);
    }

    @Nested
    @DisplayName("Constructor invariants — fail-fast on malformed input")
    class ConstructorInvariants {

        @Test
        @DisplayName("Null definitionName -> NPE")
        void nullDefinitionName() {
            assertThatThrownBy(() -> new FlowSnapshot(
                    ID_MOST, ID_LEAST, null, FlowDefinition.INITIAL_VERSION, 0, STEP_NAME, STATE, T_NOW, T_END,
                    new int[0], new String[0], 0, new byte[0], 1L))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("definitionName");
        }

        @Test
        @DisplayName("Blank definitionName -> IAE")
        void blankDefinitionName() {
            assertThatThrownBy(() -> new FlowSnapshot(
                    ID_MOST, ID_LEAST, "  ", FlowDefinition.INITIAL_VERSION, 0, STEP_NAME, STATE, T_NOW, T_END,
                    new int[0], new String[0], 0, new byte[0], 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("definitionName");
        }

        @Test
        @DisplayName("Negative currentStep -> IAE")
        void negativeCurrentStep() {
            assertThatThrownBy(() -> new FlowSnapshot(
                    ID_MOST, ID_LEAST, DEF_NAME, FlowDefinition.INITIAL_VERSION, -1, STEP_NAME, STATE, T_NOW, T_END,
                    new int[0], new String[0], 0, new byte[0], 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currentStep");
        }

        @Test
        @DisplayName("stackPointer > compensationStack.length -> IAE")
        void stackPointerOutOfBounds() {
            assertThatThrownBy(() -> new FlowSnapshot(
                    ID_MOST, ID_LEAST, DEF_NAME, FlowDefinition.INITIAL_VERSION, 0, STEP_NAME, STATE, T_NOW, T_END,
                    new int[2], new String[0], 3, new byte[0], 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("stackPointer");
        }

        @Test
        @DisplayName("opaqueState exceeding MAX_OPAQUE_STATE_BYTES -> IAE")
        void opaqueStateTooLarge() {
            byte[] tooLarge = new byte[FlowSnapshot.MAX_OPAQUE_STATE_BYTES + 1];
            assertThatThrownBy(() -> new FlowSnapshot(
                    ID_MOST, ID_LEAST, DEF_NAME, FlowDefinition.INITIAL_VERSION, 0, STEP_NAME, STATE, T_NOW, T_END,
                    new int[0], new String[0], 0, tooLarge, 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("opaqueState");
        }

        @Test
        @DisplayName("Null compensationStack -> NPE (use new int[0] for empty)")
        void nullCompensationStack() {
            assertThatThrownBy(() -> new FlowSnapshot(
                    ID_MOST, ID_LEAST, DEF_NAME, FlowDefinition.INITIAL_VERSION, 0, STEP_NAME, STATE, T_NOW, T_END,
                    null, new String[0], 0, new byte[0], 1L))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("compensationStack");
        }

        @Test
        @DisplayName("Null opaqueState -> NPE (use new byte[0] for empty)")
        void nullOpaqueState() {
            assertThatThrownBy(() -> new FlowSnapshot(
                    ID_MOST, ID_LEAST, DEF_NAME, FlowDefinition.INITIAL_VERSION, 0, STEP_NAME, STATE, T_NOW, T_END,
                    new int[0], new String[0], 0, null, 1L))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("opaqueState");
        }
    }

    @Nested
    @DisplayName("Defensive array copy — caller mutations cannot alter snapshot state")
    class DefensiveCopyContract {

        @Test
        @DisplayName("Mutating the input compensationStack after construction does not affect the snapshot")
        void inputStackMutationIsolated() {
            int[] input = {7, 8, 9};
            FlowSnapshot s = new FlowSnapshot(
                    ID_MOST, ID_LEAST, DEF_NAME, FlowDefinition.INITIAL_VERSION, 0, STEP_NAME, STATE, T_NOW, T_END,
                    input, NAMES.clone(), input.length, new byte[0], 1L);
            input[0] = 999;
            assertThat(s.compensationStack()).containsExactly(7, 8, 9);
        }

        @Test
        @DisplayName("Mutating the input opaqueState after construction does not affect the snapshot")
        void inputOpaqueMutationIsolated() {
            byte[] input = {0x01, 0x02, 0x03};
            FlowSnapshot s = new FlowSnapshot(
                    ID_MOST, ID_LEAST, DEF_NAME, FlowDefinition.INITIAL_VERSION, 0, STEP_NAME, STATE, T_NOW, T_END,
                    new int[0], new String[0], 0, input, 1L);
            input[0] = (byte) 0xFF;
            assertThat(s.opaqueState()).containsExactly(0x01, 0x02, 0x03);
        }

        @Test
        @DisplayName("Mutating the returned compensationStack does not affect the snapshot")
        void returnedStackMutationIsolated() {
            FlowSnapshot s = canonical();
            int[] returned = s.compensationStack();
            returned[0] = 999;
            assertThat(s.compensationStack()).containsExactly(STACK);
        }

        @Test
        @DisplayName("Mutating the returned opaqueState does not affect the snapshot")
        void returnedOpaqueMutationIsolated() {
            FlowSnapshot s = canonical();
            byte[] returned = s.opaqueState();
            returned[0] = (byte) 0xFF;
            assertThat(s.opaqueState()).containsExactly(OPAQUE);
        }
    }

    @Nested
    @DisplayName("schemaVersion (FLOW-101) — optimistic-concurrency field semantics")
    class SchemaVersionContract {

        @Test
        @DisplayName("SCHEMA_VERSION_INITIAL == 1L (durable stores advance from this baseline)")
        void initialConstant() {
            assertThat(FlowSnapshot.SCHEMA_VERSION_INITIAL).isEqualTo(1L);
        }

        @Test
        @DisplayName("a blank step identity is rejected — absence and corruption must not look alike")
        void blankStepNameRejected() {
            assertThatThrownBy(() -> new FlowSnapshot(
                    ID_MOST, ID_LEAST, DEF_NAME, FlowDefinition.INITIAL_VERSION, 5, Optional.of("  "), STATE, T_NOW, T_END,
                    STACK.clone(), NAMES.clone(), STACK.length, OPAQUE.clone(), 1L))
                    .as("a blank name compares unequal to every real step, so it would surface as a "
                            + "mismatch rather than as the missing identity it actually is")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a null step identity is rejected — Optional.empty() is how absence is spelled")
        void nullStepNameRejected() {
            assertThatThrownBy(() -> new FlowSnapshot(
                    ID_MOST, ID_LEAST, DEF_NAME, FlowDefinition.INITIAL_VERSION, 5, null, STATE, T_NOW, T_END,
                    STACK.clone(), NAMES.clone(), STACK.length, OPAQUE.clone(), 1L))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("10-arg convenience constructor defaults schemaVersion to SCHEMA_VERSION_INITIAL — preserves v0.6 call shape")
        void convenienceConstructorDefaultsToInitial() {
            FlowSnapshot s = new FlowSnapshot(
                    ID_MOST, ID_LEAST, DEF_NAME, 0, STATE, T_NOW, T_END,
                    new int[0], 0, new byte[0]);
            assertThat(s.schemaVersion()).isEqualTo(FlowSnapshot.SCHEMA_VERSION_INITIAL);
            assertThat(s.currentStepName())
                    .as("the v0.6 shim cannot know the step's name, and must say so rather than "
                            + "fabricate one — an absent identity is rejected on resume (ADR-062)")
                    .isEmpty();
        }

        @Test
        @DisplayName("schemaVersion = 0L rejected (must be >= SCHEMA_VERSION_INITIAL)")
        void zeroSchemaVersionRejected() {
            assertThatThrownBy(() -> canonical(0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("schemaVersion");
        }

        @Test
        @DisplayName("Negative schemaVersion rejected")
        void negativeSchemaVersionRejected() {
            assertThatThrownBy(() -> canonical(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("schemaVersion");
        }

        @Test
        @DisplayName("schemaVersion = 1L accepted (boundary)")
        void initialSchemaVersionAccepted() {
            assertThat(canonical(1L).schemaVersion()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Large schemaVersion accepted (no upper bound)")
        void largeSchemaVersionAccepted() {
            assertThat(canonical(Long.MAX_VALUE).schemaVersion()).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("equals / hashCode / toString — schemaVersion participates")
    class EqualityContract {

        @Test
        @DisplayName("Two snapshots with identical content (including arrays) are equal — deep array semantics")
        void deepEquality() {
            FlowSnapshot a = canonical();
            FlowSnapshot b = canonical();
            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Snapshots differing ONLY in schemaVersion are NOT equal — OCC discriminator (ADR-013 §5)")
        void schemaVersionDeltaBreaksEquality() {
            FlowSnapshot v1 = canonical(1L);
            FlowSnapshot v2 = canonical(2L);
            assertThat(v1).isNotEqualTo(v2);
        }

        @Test
        @DisplayName("equals(null) -> false (no NPE)")
        void equalsNull() {
            assertThat(canonical().equals(null)).isFalse();
        }

        @Test
        @DisplayName("equals(differentType) -> false")
        @SuppressWarnings("unlikely-arg-type")
        void equalsDifferentType() {
            assertThat(canonical().equals("not-a-snapshot")).isFalse();
        }

        @Test
        @DisplayName("equals is reflexive")
        void equalsReflexive() {
            FlowSnapshot s = canonical();
            assertThat(s).isEqualTo(s);
        }

        @Test
        @DisplayName("toString includes schemaVersion field")
        void toStringContainsSchemaVersion() {
            assertThat(canonical(42L).toString()).contains("schemaVersion=42");
        }
    }
}
