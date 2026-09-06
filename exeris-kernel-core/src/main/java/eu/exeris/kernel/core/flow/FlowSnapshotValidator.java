/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;

import java.util.Optional;

/**
 * Refuses a persisted {@link FlowSnapshot} that the plan it names cannot walk.
 *
 * <p>A resume reads a row written by an earlier process, possibly an earlier version of the
 * definition, and hands it to a plan compiled now. The two can disagree in ways the plan's own
 * bounds checks would surface only as an index error deep in the run: a step ordinal past the end
 * of the plan, a compensation stack deeper than the step it unwinds, a step name that no longer
 * belongs to the ordinal it is stored against. This type turns each of those into a refusal at the
 * boundary, with the offending values named, before any step executes.
 *
 * <p>Every refusal emits a {@code SchemaMismatch} JFR event before it throws, so a resume that
 * fails in production leaves the two identities and the two ordinals in the recording rather than
 * only a stack trace. The event is emitted first on purpose: the throw is what the caller sees, and
 * a caller that swallows it would otherwise erase the only evidence of which row was refused.
 *
 * <p>The refusals implement ADR-064's amendments on definition versioning and stack identity. The
 * asymmetry between them is deliberate and documented on each method: a row whose recorded version
 * is outside the plan's range is refused, while a row inside the range that names the wrong entry
 * is not something a version check can catch, which is why the identity checks exist alongside it.
 *
 * <p><b>Allocation:</b> allocates only on the refusal path — the message and the {@code Optional}
 * wrappers a refusal builds. A snapshot that validates cleanly allocates nothing here.
 * <p><b>Thread confinement:</b> any thread — the instance holds only the engine name and is
 * otherwise a pure function of its arguments.
 * <p><b>Ownership:</b> holds nothing that needs releasing; snapshots and plans are the caller's.
 *
 * @since 0.12
 */
// Seven refusals, each individually simple: the highest single method scores 7 against a limit
// of 10, and the class total of 37 is what seven of them sum to. Splitting them further would
// separate checks that a resume runs together and that ADR-064 decided together.
@SuppressWarnings("PMD.CyclomaticComplexity")
final class FlowSnapshotValidator {

    /** Engine name carried into every refusal and every {@code SchemaMismatch} event. */
    private final String engineName;

    /**
     * Binds a validator to the engine whose refusals it will name.
     *
     * @param engineName the engine name recorded in refusals and telemetry
     */
    /* default */ FlowSnapshotValidator(String engineName) {
        this.engineName = engineName;
    }

    /**
     * Refuses a row that needs a walk but records no step identity, naming the reason that is true.
     *
     * <p>{@code applyHop} builds the transform's input from the parked step's name, so without this
     * the row would reach {@code orElseThrow} as a bare {@code NoSuchElementException} on a path where
     * every other refusal carries a reason.
     *
     * <p>Declining silently is not enough either, and that is the subtler half. Falling through leaves
     * the row to {@code resolveVersionedPlan}, which refuses first — before
     * {@link #validateSnapshotStepIdentity} ever runs — with {@code DEFINITION_VERSION_UNRESOLVED}.
     * That reason's documented remedy is "deploy the missing version, or register the missing
     * transform", and here a transform may well already be registered: the row is unresumable because
     * it records no step identity, which no deployment fixes. A refusal that steers an operator to the
     * wrong runbook is worse than a raw exception, because it looks actionable.
     */
    /* default */ void refuseRowThatCannotBeWalked(FlowSnapshot persisted) {
        if (persisted.currentStepName().isEmpty()) {
            emitSchemaMismatch(persisted, persisted.currentStep(), -1,
                    FlowEngineException.REASON_STEP_IDENTITY_ABSENT, null, null);
            throw FlowEngineException.schemaMismatchStepIdentityAbsent(
                    engineName, persisted.currentStep());
        }
        // Second input the transform cannot be handed: FlowMigrationState requires identities for the
        // live stack, so a row without them reaches its compact constructor as a bare
        // IllegalArgumentException — the same shape of unreasoned failure the cursor half above exists
        // to prevent, on the same path, one component over. Refused here rather than left to the
        // post-transform guard because there is no transform to run.
        int live = persisted.stackPointer();
        if (live > 0 && persisted.compensationStepNames().length == 0) {
            emitSchemaMismatch(persisted, live, -1,
                    FlowEngineException.REASON_COMPENSATION_STACK_IDENTITY_ABSENT, null, null);
            throw FlowEngineException.schemaMismatchCompensationStackIdentityAbsent(
                    engineName, live);
        }
    }

    /**
     * Applies the version guard to a caller-supplied plan (ADR-064 obligation 4).
     *
     * <p>{@code schedule()} resubmits against a plan the application already holds — plausibly the
     * newest it compiled — for an instance that may be parked under an older one. Without this
     * guard, a caller-supplied plan whose version happens to place a valid step at the parked index
     * would let the saga resume silently on the wrong definition version through {@code schedule()},
     * even though {@code wake()}'s resume path already rejects the same mismatch.
     */
    /* default */ void validateSnapshotVersion(CoreFlowExecutionPlan plan, FlowSnapshot persisted) {
        if (persisted.state().isTerminal()) {
            return;
        }
        if (persisted.definitionVersion() == FlowSnapshot.VERSION_ABSENT) {
            throw FlowEngineException.schemaMismatchDefinitionVersionAbsent(
                    engineName, persisted.currentStep());
        }
        if (plan.definitionVersion() != persisted.definitionVersion()) {
            throw FlowEngineException.schemaMismatchDefinitionVersionUnresolved(
                    engineName, persisted.definitionVersion());
        }
    }

    /**
     * Fail-closed guard against resuming a persisted saga against an incompatible (changed) plan.
     *
     * <p>A snapshot persists {@code currentStep} as a bare zero-based index into the plan it was
     * parked under. If a later deployment removes (or reorders away) steps, that index can point past
     * the end of the currently-registered plan; replaying it blindly would resume at the wrong step —
     * a data-corruption-class outcome — so resume must reject the mismatch rather than proceed.
     *
     * <p>Per {@code docs/subsystems/flow.md}, waking a non-terminal saga whose persisted
     * {@code currentStep} no longer indexes a step in the active definition raises
     * {@code EX-FLOW-7002 / phase=SCHEMA_MISMATCH} (Glass-Box rawArgs via
     * {@link FlowEngineException#schemaMismatch(String, int)}) and requires manual intervention.
     *
     * <p>This method is the <b>bounds/arity</b> half. Since 0.11 it delegates to
     * {@link #validateSnapshotStepIdentity} for the half it structurally cannot cover: a same-arity
     * reorder leaves the index in range, so only comparing step identities detects it (ADR-062).
     * Terminal snapshots ({@link FlowState#isTerminal()}) are exempt — they are never resumed.
     */
    /* default */ void validateSnapshotStepBounds(CoreFlowExecutionPlan plan, FlowSnapshot persisted) {
        if (persisted.state().isTerminal()) {
            return;
        }
        int step = persisted.currentStep();
        int stepCount = plan.stepCount();
        // step < 0 makes the invariant explicit (a corrupted snapshot writing a sentinel index also
        // fails closed, not just the redeploy-removed-step case).
        if (step < 0 || step >= stepCount) {
            emitSchemaMismatch(persisted, step, stepCount, FlowEngineException.REASON_STEP_OUT_OF_RANGE, null, null);
            throw FlowEngineException.schemaMismatch(engineName, step);
        }
        validateSnapshotStepIdentity(plan, persisted, step, stepCount);
        validateCompensationStackBounds(persisted, stepCount);
        validateCompensationStackIdentity(plan, persisted);
    }

    /**
     * Rejects a resume whose compensation stack no longer indexes the plan.
     *
     * <p>The two guards above validate where the saga <em>resumes</em>. They say nothing about the
     * steps it has already completed, and those are exactly what a rollback walks — so a saga can pass
     * both and still hold a stack that is meaningless in the plan it just bound to. A migration makes
     * this ordinary rather than exceptional: a transform may rewrite the stack, and
     * {@code FlowMigrationState} documents that carrying it across a version boundary unchanged is
     * wrong, but documenting an obligation is not enforcing it.
     *
     * <p>Checked here rather than at compensation time because {@code runCompensationStep} reads the
     * plan by bare index <em>outside</em> its own catch, so a stale entry there aborts the remaining
     * unwind and skips {@code finalizeFailedInstance} — leaving the saga mid-compensation with its
     * idempotency guard still held. Refusing the resume leaves the row intact instead.
     *
     * <p>This is the bounds half only. An entry that indexes the plan but addresses a different step
     * than it did when it was pushed is not detectable from indices alone; that is
     * {@link #validateCompensationStackIdentity}, which runs after this one so that a structurally
     * broken stack is diagnosed as broken rather than as a mismatch.
     *
     * @param persisted the snapshot being resumed
     * @param stepCount the step count of the plan the resume would bind to
     */
    private void validateCompensationStackBounds(FlowSnapshot persisted, int stepCount) {
        int live = persisted.stackPointer();
        if (live == 0) {
            return;
        }
        // One defensive copy on the resume path. Cold — a resume already cost a snapshot-store read —
        // and FlowSnapshot exposes no per-entry accessor to borrow instead.
        int[] stack = persisted.compensationStack();
        for (int index = 0; index < live; index++) {
            int entry = stack[index];
            if (entry < 0 || entry >= stepCount) {
                // Both step-name fields stay absent: the offending value is a stack entry, so neither
                // "the step the snapshot names" nor "the step the plan has there" is a truthful answer.
                emitSchemaMismatch(persisted, entry, stepCount,
                        FlowEngineException.REASON_COMPENSATION_STACK_OUT_OF_RANGE, null, null);
                throw FlowEngineException.schemaMismatchCompensationStack(engineName, entry);
            }
        }
    }

    /**
     * Rejects a resume whose compensation stack indexes the plan but no longer addresses the same steps
     * (ADR-064 A5).
     *
     * <p>ADR-062's argument for the cursor, applied to the stack: a same-arity reorder leaves every
     * entry in range, so bounds cannot see it. What makes this the more dangerous of the two halves is
     * that nothing throws. An out-of-range entry raises at {@code plan.stepAt} inside failure handling —
     * loud, and the parked row survives to be fixed. An in-range entry that now addresses a different
     * step resolves to a perfectly valid descriptor, and the unwind either skips a compensation that was
     * owed (the addressed step happens to declare none) or runs a <em>different</em> step's
     * compensation. Both are silent, and a compensation is a side effect: by the time anything can
     * observe the mistake it has already been made.
     *
     * <p>A live stack with no identities at all is refused rather than admitted, on ADR-062 obligation
     * 6's reasoning — admitting it would leave a permanent branch where the stack is still trusted by
     * position. That case is reachable independently of the cursor guards: a row carrying a definition
     * version and a cursor identity but no stack identities is what an application
     * {@code FlowSnapshotStore} produces when its schema does not carry the column.
     *
     * <p>One further defensive copy on the cold resume path, over the one the bounds guard already
     * makes. Kept separate rather than threaded through a shared array, because each guard owning its
     * own contract is the shape the cursor pair already established in this class.
     *
     * @param plan      the resolved plan the resume would bind to
     * @param persisted the snapshot being resumed, whose stack is already known to index that plan
     */
    private void validateCompensationStackIdentity(CoreFlowExecutionPlan plan, FlowSnapshot persisted) {
        int live = persisted.stackPointer();
        if (live == 0) {
            return;
        }
        String[] names = persisted.compensationStepNames();
        if (names.length == 0) {
            // No offending entry to name when none of them is named, so the live depth is the
            // diagnostic — it says how much rollback the refusal is protecting.
            emitSchemaMismatch(persisted, live, plan.stepCount(),
                    FlowEngineException.REASON_COMPENSATION_STACK_IDENTITY_ABSENT, null, null);
            throw FlowEngineException.schemaMismatchCompensationStackIdentityAbsent(
                    engineName, live);
        }
        int[] stack = persisted.compensationStack();
        for (int index = 0; index < live; index++) {
            int entry = stack[index];
            String planStepName = plan.stepAt(entry).name();
            if (!names[index].equals(planStepName)) {
                emitSchemaMismatch(persisted, entry, plan.stepCount(),
                        FlowEngineException.REASON_COMPENSATION_STACK_IDENTITY_MISMATCH,
                        names[index], planStepName);
                throw FlowEngineException.schemaMismatchCompensationStackIdentity(
                        engineName, entry);
            }
        }
    }

    /**
     * Rejects a resume whose persisted step index is in range but no longer names the same step
     * (ADR-062).
     *
     * <p>This is the case the bounds check cannot reach. A same-arity reorder leaves the index valid,
     * so without comparing identities the saga would resume on a different step than it parked at —
     * silently, and with the wrong compensation stack semantics behind it.
     *
     * @param plan      the resolved plan the resume would bind to
     * @param persisted the snapshot being resumed
     * @param step      the persisted step index, already known to be in range
     * @param stepCount the plan's step count, for the diagnostic event
     */
    private void validateSnapshotStepIdentity(CoreFlowExecutionPlan plan,
                                              FlowSnapshot persisted,
                                              int step,
                                              int stepCount) {
        String planStepName = plan.stepAt(step).name();
        Optional<String> persistedName = persisted.currentStepName();
        if (persistedName.isEmpty()) {
            // Written before 0.11. Resuming it would mean trusting the index again, which is the
            // behaviour this guard exists to remove — so it is refused rather than assumed safe.
            emitSchemaMismatch(persisted, step, stepCount,
                    FlowEngineException.REASON_STEP_IDENTITY_ABSENT, null, planStepName);
            throw FlowEngineException.schemaMismatchStepIdentityAbsent(engineName, step);
        }
        if (!persistedName.get().equals(planStepName)) {
            emitSchemaMismatch(persisted, step, stepCount, FlowEngineException.REASON_STEP_IDENTITY_MISMATCH,
                    persistedName.get(), planStepName);
            throw FlowEngineException.schemaMismatchStepIdentity(engineName, step);
        }
    }

    private void emitSchemaMismatch(FlowSnapshot persisted, int step, int stepCount,
                                    String reason, String persistedStepName, String planStepName) {
        FlowSchemaMismatchEvent.emit(
                engineName, persisted.definitionName(),
                persisted.instanceIdMost(), persisted.instanceIdLeast(), step, stepCount,
                reason, persistedStepName, planStepName);
    }
}
