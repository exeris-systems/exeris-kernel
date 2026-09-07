/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.flow.model.FlowStepDescriptor;
import eu.exeris.kernel.spi.flow.model.FlowTransitionDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every refusal {@link FlowSnapshotValidator} can raise, one case each, plus the rows it must let
 * through.
 *
 * <p>These are the guards between a persisted row and a plan compiled now. Getting one wrong is not
 * a failed resume — it is a saga that resumes on the wrong step or unwinds a compensation it does
 * not own, which is a side effect nothing can take back. So each case asserts the <em>reason</em>
 * the refusal carries, not merely that something was thrown: a guard that fires for the wrong
 * reason sends an operator to the wrong runbook, and ADR-064 A5 turns on exactly that distinction.
 *
 * <p>The pass-through cases are here for the same reason as the refusals. A guard that refuses
 * everything satisfies every throw assertion above and is useless, so each refusal is paired with
 * the nearest row that must survive it.
 */
@DisplayName("FlowSnapshotValidator")
class FlowSnapshotValidatorTest {

    private static final String ENGINE = "test-engine";
    private static final String DEFINITION = "orderSaga";
    private static final long INSTANCE_HIGH = 0x0123456789ABCDEFL;
    private static final long INSTANCE_LOW = 0x76543210FEDCBA98L;

    /** Index into {@link FlowEngineException#rawArgs()} where the refusal reason is recorded. */
    private static final int REASON_INDEX = 2;

    private final FlowSnapshotValidator validator = new FlowSnapshotValidator(ENGINE);

    // --- fixtures --------------------------------------------------------------------------

    private static FlowStepDescriptor step(int id, String name) {
        return new FlowStepDescriptor(id, name, context -> null, null);
    }

    /** A plan of {@code names.length} steps at version {@code version}, named in order. */
    private static CoreFlowExecutionPlan plan(int version, String... names) {
        FlowStepDescriptor[] steps = new FlowStepDescriptor[names.length];
        int[] nextSteps = new int[names.length];
        FlowTransitionDescriptor[][] transitions = new FlowTransitionDescriptor[names.length][];
        for (int i = 0; i < names.length; i++) {
            steps[i] = step(i, names[i]);
            nextSteps[i] = i + 1;
            transitions[i] = new FlowTransitionDescriptor[0];
        }
        return new CoreFlowExecutionPlan(DEFINITION, version, steps, transitions, nextSteps, 0L);
    }

    /**
     * A snapshot with every component explicit, so each test states the one thing it varies rather
     * than inheriting it from a builder default.
     */
    private static FlowSnapshot snapshot(int definitionVersion,
                                         int currentStep,
                                         String currentStepName,
                                         FlowState state,
                                         int[] compensationStack,
                                         String[] compensationStepNames,
                                         int stackPointer) {
        return new FlowSnapshot(
                INSTANCE_HIGH, INSTANCE_LOW, DEFINITION, definitionVersion,
                currentStep, Optional.ofNullable(currentStepName), state,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                compensationStack, compensationStepNames, stackPointer,
                new byte[0], 1L);
    }

    /** The shape every refusal test starts from: in range, in version, identities present. */
    private static FlowSnapshot validRow() {
        return snapshot(2, 1, "charge", FlowState.PARKED, new int[]{0}, new String[]{"reserve"}, 1);
    }

    private static String reasonOf(Throwable thrown) {
        return (String) ((FlowEngineException) thrown).rawArgs()[REASON_INDEX];
    }

    // --- refuseRowThatCannotBeWalked -------------------------------------------------------

    @Nested
    @DisplayName("refuseRowThatCannotBeWalked")
    class RefuseRowThatCannotBeWalked {

        @Test
        @DisplayName("refuses a row with no step identity, naming STEP_IDENTITY_ABSENT rather than "
                + "letting it reach the version guard")
        void noStepIdentity() {
            FlowSnapshot row = snapshot(2, 1, null, FlowState.PARKED, new int[0], new String[0], 0);

            assertThatThrownBy(() -> validator.refuseRowThatCannotBeWalked(row))
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> assertThat(reasonOf(thrown))
                            .as("a row with no step identity is unresumable by deployment, so it must not "
                                    + "be reported as an unresolved version")
                            .isEqualTo(FlowEngineException.REASON_STEP_IDENTITY_ABSENT));
        }

        @Test
        @DisplayName("refuses a live compensation stack carrying no identities")
        void liveStackWithoutIdentities() {
            FlowSnapshot row = snapshot(2, 1, "charge", FlowState.PARKED,
                    new int[]{0}, new String[0], 1);

            assertThatThrownBy(() -> validator.refuseRowThatCannotBeWalked(row))
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> assertThat(reasonOf(thrown))
                            .isEqualTo(FlowEngineException.REASON_COMPENSATION_STACK_IDENTITY_ABSENT));
        }

        @Test
        @DisplayName("lets an empty compensation stack through even with no identities, because "
                + "there is nothing to unwind")
        void emptyStackWithoutIdentitiesPasses() {
            FlowSnapshot row = snapshot(2, 1, "charge", FlowState.PARKED,
                    new int[0], new String[0], 0);

            assertThatCode(() -> validator.refuseRowThatCannotBeWalked(row)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("lets a complete row through")
        void validRowPasses() {
            assertThatCode(() -> validator.refuseRowThatCannotBeWalked(validRow()))
                    .doesNotThrowAnyException();
        }
    }

    // --- validateSnapshotVersion -----------------------------------------------------------

    @Nested
    @DisplayName("validateSnapshotVersion")
    class ValidateSnapshotVersion {

        @Test
        @DisplayName("refuses a row whose definition version was never recorded")
        void versionAbsent() {
            FlowSnapshot row = snapshot(FlowSnapshot.VERSION_ABSENT, 1, "charge", FlowState.PARKED,
                    new int[0], new String[0], 0);

            assertThatThrownBy(() -> validator.validateSnapshotVersion(plan(2, "reserve", "charge"), row))
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> assertThat(reasonOf(thrown))
                            .isEqualTo(FlowEngineException.REASON_DEFINITION_VERSION_ABSENT));
        }

        @Test
        @DisplayName("refuses a caller-supplied plan whose version is not the one the row parked under")
        void versionMismatch() {
            assertThatThrownBy(() ->
                    validator.validateSnapshotVersion(plan(3, "reserve", "charge"), validRow()))
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> assertThat(reasonOf(thrown))
                            .isEqualTo(FlowEngineException.REASON_DEFINITION_VERSION_UNRESOLVED));
        }

        @Test
        @DisplayName("accepts a plan at the row's own version")
        void versionMatches() {
            assertThatCode(() ->
                    validator.validateSnapshotVersion(plan(2, "reserve", "charge"), validRow()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("exempts a terminal row, which is never resumed, even with no version recorded")
        void terminalIsExempt() {
            FlowSnapshot terminal = snapshot(FlowSnapshot.VERSION_ABSENT, 1, "charge",
                    FlowState.COMPLETED, new int[0], new String[0], 0);

            assertThatCode(() ->
                    validator.validateSnapshotVersion(plan(99, "reserve", "charge"), terminal))
                    .doesNotThrowAnyException();
        }
    }

    // --- validateSnapshotStepBounds and what it delegates to -------------------------------

    @Nested
    @DisplayName("validateSnapshotStepBounds")
    class ValidateSnapshotStepBounds {

        private final CoreFlowExecutionPlan twoSteps = plan(2, "reserve", "charge");

        @Test
        @DisplayName("refuses a cursor past the end of the plan")
        void stepPastEnd() {
            FlowSnapshot row = snapshot(2, 2, "charge", FlowState.PARKED,
                    new int[0], new String[0], 0);

            assertThatThrownBy(() -> validator.validateSnapshotStepBounds(twoSteps, row))
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> assertThat(reasonOf(thrown))
                            .isEqualTo(FlowEngineException.REASON_STEP_OUT_OF_RANGE));
        }

        @Test
        @DisplayName("a negative cursor never reaches the validator: FlowSnapshot's own constructor "
                + "refuses it first, which is why the guard's `step < 0` half cannot be exercised here")
        void negativeCursorIsRefusedByTheRecord() {
            assertThatThrownBy(() -> snapshot(2, -1, "charge", FlowState.PARKED,
                    new int[0], new String[0], 0))
                    .as("the validator's comment justifies `step < 0` as catching a corrupted sentinel "
                            + "index; the record makes such a snapshot unconstructible, so that half is "
                            + "defence in depth rather than a reachable path")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currentStep must be >= 0");
        }

        @Test
        @DisplayName("refuses an in-range cursor that no longer names the step it parked at — the "
                + "same-arity reorder bounds cannot see")
        void stepIdentityMismatch() {
            CoreFlowExecutionPlan reordered = plan(2, "charge", "reserve");
            FlowSnapshot row = snapshot(2, 1, "charge", FlowState.PARKED,
                    new int[0], new String[0], 0);

            assertThatThrownBy(() -> validator.validateSnapshotStepBounds(reordered, row))
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> assertThat(reasonOf(thrown))
                            .as("index 1 is in range in both plans; only the name distinguishes them")
                            .isEqualTo(FlowEngineException.REASON_STEP_IDENTITY_MISMATCH));
        }

        @Test
        @DisplayName("refuses an in-range cursor written before step identities existed")
        void stepIdentityAbsent() {
            FlowSnapshot row = snapshot(2, 1, null, FlowState.PARKED,
                    new int[0], new String[0], 0);

            assertThatThrownBy(() -> validator.validateSnapshotStepBounds(twoSteps, row))
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> assertThat(reasonOf(thrown))
                            .isEqualTo(FlowEngineException.REASON_STEP_IDENTITY_ABSENT));
        }

        @Test
        @DisplayName("refuses a compensation stack entry that does not index the plan")
        void compensationStackOutOfRange() {
            FlowSnapshot row = snapshot(2, 1, "charge", FlowState.PARKED,
                    new int[]{5}, new String[]{"reserve"}, 1);

            assertThatThrownBy(() -> validator.validateSnapshotStepBounds(twoSteps, row))
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> assertThat(reasonOf(thrown))
                            .isEqualTo(FlowEngineException.REASON_COMPENSATION_STACK_OUT_OF_RANGE));
        }

        @Test
        @DisplayName("refuses a negative compensation stack entry — reachable, because FlowSnapshot "
                + "validates the pointer's bounds but never the array's contents")
        void compensationStackEntryNegative() {
            FlowSnapshot row = snapshot(2, 1, "charge", FlowState.PARKED,
                    new int[]{-1}, new String[]{"reserve"}, 1);

            assertThatThrownBy(() -> validator.validateSnapshotStepBounds(twoSteps, row))
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> assertThat(reasonOf(thrown))
                            .isEqualTo(FlowEngineException.REASON_COMPENSATION_STACK_OUT_OF_RANGE));
        }

        @Test
        @DisplayName("ignores stack slots above the pointer, which are not part of the live stack")
        void staleSlotsAbovePointerAreIgnored() {
            FlowSnapshot row = snapshot(2, 1, "charge", FlowState.PARKED,
                    new int[]{0, 99}, new String[]{"reserve"}, 1);

            assertThatCode(() -> validator.validateSnapshotStepBounds(twoSteps, row))
                    .as("entry 99 sits above stackPointer=1, so it is not live and must not be judged")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a live compensation stack carrying no identities at all")
        void compensationStackIdentityAbsent() {
            FlowSnapshot row = snapshot(2, 1, "charge", FlowState.PARKED,
                    new int[]{0}, new String[0], 1);

            assertThatThrownBy(() -> validator.validateSnapshotStepBounds(twoSteps, row))
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> assertThat(reasonOf(thrown))
                            .isEqualTo(FlowEngineException.REASON_COMPENSATION_STACK_IDENTITY_ABSENT));
        }

        @Test
        @DisplayName("refuses a stack entry that indexes the plan but addresses a different step "
                + "than when it was pushed — the silent half of ADR-064 A5")
        void compensationStackIdentityMismatch() {
            CoreFlowExecutionPlan reordered = plan(2, "charge", "reserve");
            FlowSnapshot row = snapshot(2, 0, "charge", FlowState.PARKED,
                    new int[]{1}, new String[]{"charge"}, 1);

            assertThatThrownBy(() -> validator.validateSnapshotStepBounds(reordered, row))
                    .isInstanceOf(FlowEngineException.class)
                    .satisfies(thrown -> assertThat(reasonOf(thrown))
                            .as("entry 1 resolves to a valid descriptor in the reordered plan; without "
                                    + "the identity check the unwind would run the wrong compensation")
                            .isEqualTo(FlowEngineException.REASON_COMPENSATION_STACK_IDENTITY_MISMATCH));
        }

        @Test
        @DisplayName("exempts a terminal row even when its cursor is out of range")
        void terminalIsExempt() {
            FlowSnapshot terminal = snapshot(2, 99, "charge", FlowState.FAILED_ROLLEDBACK,
                    new int[0], new String[0], 0);

            assertThatCode(() -> validator.validateSnapshotStepBounds(twoSteps, terminal))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts a row whose cursor, identity and whole live stack still match the plan")
        void validRowPasses() {
            assertThatCode(() -> validator.validateSnapshotStepBounds(twoSteps, validRow()))
                    .doesNotThrowAnyException();
        }
    }
}
