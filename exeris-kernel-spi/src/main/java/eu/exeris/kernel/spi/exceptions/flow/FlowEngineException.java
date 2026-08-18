/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.flow;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when the {@link eu.exeris.kernel.spi.flow.FlowEngine} fails to start,
 * stop, or perform a lifecycle operation.
 *
 * <h2>rawArgs Binary Layout — {@value KernelErrorCodes#EX_FLOW_7002}</h2>
 * <ul>
 *   <li>index 0 – {@code String} engineName</li>
 *   <li>index 1 – {@code String} phase — one of: {@code "START"}, {@code "STOP"},
 *       {@code "COMPILE"}, {@code "SCHEDULE"}, {@code "OPTIMISTIC_LOCK_CONFLICT"} (since 0.7),
 *       {@code "SCHEMA_MISMATCH"} (since 0.10)</li>
 *   <li>index 2 – {@code String} staticReasonCode — stable identifier, never user-supplied
 *       (e.g. {@code "STARTUP_FAILED"}, {@code "COMPILE_FAILED"}, {@code "QUEUE_FULL"},
 *       {@code "STALE_VERSION"}, {@code "STEP_OUT_OF_RANGE"})</li>
 *   <li>index 3 – {@code int} contextValue — phase-specific numeric context
 *       (e.g. current queue depth for SCHEDULE phase, incoming schemaVersion for
 *       OPTIMISTIC_LOCK_CONFLICT, persisted resume step for SCHEMA_MISMATCH); {@code -1} when not applicable</li>
 * </ul>
 *
 * <p><b>The {@code WAKE} phase carries five slots, not four (since 0.12).</b> Its context is a flow
 * instance identity — 128 bits — which does not fit the {@code int} at index 3, and dropping it
 * would remove the one thing an operator needs to act on the refusal. So for
 * {@code phase = "WAKE"}:
 * <ul>
 *   <li>index 3 – {@code long} instanceIdMost</li>
 *   <li>index 4 – {@code long} instanceIdLeast</li>
 * </ul>
 * A consumer must therefore read this layout <em>by phase</em> rather than assume a fixed arity.
 * Index 2 stays the reason code on every phase, which is what makes
 * {@link #isNotParked(Throwable)} safe to apply to any {@code EX-FLOW-7002}.
 *
 * @since 0.5.0
 */
// TooManyMethods: the count is the contract. One named factory per rawArgs layout is what keeps
// string literals out of throw sites (a repo-wide hard constraint) and keeps each layout documented
// in exactly one place. Collapsing the schemaMismatch* family into a reason-taking factory would move
// those literals back to the call sites, which is the drift the Glass-Box layout exists to prevent.
@SuppressWarnings("PMD.TooManyMethods")
public final class FlowEngineException extends ExerisKernelException {

    /**
     * {@code rawArgs[2]} reason: the persisted step index no longer addresses any step (since 0.10).
     *
     * <p>Public because it is already a stable tooling key — the TCK asserts on it, operators filter
     * on it, and the JFR event reports it. A private copy here plus a literal at the emission site is
     * how the two silently drift apart.
     */
    public static final String REASON_STEP_OUT_OF_RANGE = "STEP_OUT_OF_RANGE";

    /**
     * {@code rawArgs[2]} reason: the index is in range but names a different step (since 0.11).
     */
    public static final String REASON_STEP_IDENTITY_MISMATCH = "STEP_IDENTITY_MISMATCH";

    /**
     * {@code rawArgs[2]} reason: the snapshot records no step identity at all (since 0.11).
     */
    public static final String REASON_STEP_IDENTITY_ABSENT = "STEP_IDENTITY_ABSENT";

    /**
     * {@code rawArgs[2]} reason: the snapshot records no definition version at all (since 0.11).
     *
     * <p>Distinct from {@link #REASON_DEFINITION_VERSION_UNRESOLVED}: nothing is missing from the
     * deployment, the row simply predates versioning. The remedy is the drain ADR-062 already
     * prescribes for the 0.10→0.11 upgrade, not deploying another definition.
     */
    public static final String REASON_DEFINITION_VERSION_ABSENT = "DEFINITION_VERSION_ABSENT";

    /**
     * {@code rawArgs[2]} reason: the snapshot names a definition version this engine does not have
     * registered, and no migration path reaches one it does (since 0.11).
     *
     * <p>The parked row is left untouched, so deploying the missing version — or registering the
     * missing migration — recovers the saga (ADR-064).
     */
    public static final String REASON_DEFINITION_VERSION_UNRESOLVED = "DEFINITION_VERSION_UNRESOLVED";

    /**
     * {@code rawArgs[2]} reason: a live compensation-stack entry does not index the plan the resume
     * would bind to (since 0.11).
     *
     * <p>The cursor guards ({@link #REASON_STEP_OUT_OF_RANGE} / {@link #REASON_STEP_IDENTITY_MISMATCH})
     * validate where the saga resumes. They say nothing about the steps it has already completed, and
     * those are what a rollback walks. An out-of-range entry reaches the plan as a bare array index at
     * compensation time — inside failure handling, where an exception truncates the remaining unwind
     * and skips the terminal write. Refusing the resume keeps the row intact and recoverable instead.
     */
    public static final String REASON_COMPENSATION_STACK_OUT_OF_RANGE = "COMPENSATION_STACK_OUT_OF_RANGE";

    /**
     * {@code rawArgs[2]} reason: a live compensation-stack entry indexes the plan, but names a
     * different step than it did when it was pushed (since 0.11, ADR-064 A5).
     *
     * <p>The half {@link #REASON_COMPENSATION_STACK_OUT_OF_RANGE} structurally cannot reach, and the
     * more dangerous of the two. An out-of-range entry throws at compensation time — loud, and the
     * parked row survives. An in-range entry naming the wrong step throws nothing: the rollback either
     * silently skips a step that needed compensating, or executes a <em>different</em> step's
     * compensation. Both are irreversible in a way a refused resume is not, because a compensation is a
     * side effect that has already happened by the time anyone can look.
     */
    public static final String REASON_COMPENSATION_STACK_IDENTITY_MISMATCH =
            "COMPENSATION_STACK_IDENTITY_MISMATCH";

    /**
     * {@code rawArgs[2]} reason: the snapshot carries a live compensation stack but no identities for
     * it (since 0.11, ADR-064 A5).
     *
     * <p>Distinct from {@link #REASON_STEP_IDENTITY_ABSENT}, which a pre-0.11 row hits first: this is
     * reachable for a row that has a cursor identity and a definition version but no stack identities —
     * what an application {@code FlowSnapshotStore} produces when it round-trips through a schema that
     * does not carry the column. Refused for the same reason ADR-062 obligation 6 refuses an absent
     * cursor identity: admitting it leaves a permanent branch where the stack is trusted by position.
     */
    public static final String REASON_COMPENSATION_STACK_IDENTITY_ABSENT =
            "COMPENSATION_STACK_IDENTITY_ABSENT";

    private static final String MSG_ENGINE_FAILURE  = "Flow engine lifecycle failure";
    private static final String MSG_SCHEMA_MISMATCH = "Flow definition changed under a parked saga";

    /** {@code rawArgs[1]} phase shared by every resume-compatibility refusal. */
    private static final String PHASE_SCHEMA_MISMATCH = "SCHEMA_MISMATCH";
    private static final String REASON_STARTUP      = "STARTUP_FAILED";
    private static final String REASON_COMPILE      = "COMPILE_FAILED";
    private static final String REASON_QUEUE_FULL   = "QUEUE_FULL";
    private static final String REASON_STALE_VERSION = "STALE_VERSION";
    private static final String REASON_NOT_PARKED   = "NOT_PARKED";

    public FlowEngineException(String message) {
        super(KernelErrorCodes.EX_FLOW_7002, message, (Throwable) null);
    }

    public FlowEngineException(String message, Throwable cause) {
        super(KernelErrorCodes.EX_FLOW_7002, message, cause);
    }

    private FlowEngineException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    public static FlowEngineException startupFailure(String engineName, Throwable cause) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_ENGINE_FAILURE, cause,
                engineName, "START", REASON_STARTUP, -1);
    }

    public static FlowEngineException compileFailure(String engineName, Throwable cause) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_ENGINE_FAILURE, cause,
                engineName, "COMPILE", REASON_COMPILE, -1);
    }

    /**
     * Creates an exception for a full scheduler queue.
     *
     * <p>rawArgs layout: {@code [engineName, "SCHEDULE", "QUEUE_FULL", queueDepth]}.
     * {@code queueDepth} is stored as a typed {@code int} rawArg — no String formatting.
     *
     * @param engineName the engine name
     * @param queueDepth current depth of the scheduler queue at the time of overflow
     */
    public static FlowEngineException schedulerFull(String engineName, int queueDepth) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_ENGINE_FAILURE, null,
                engineName, "SCHEDULE", REASON_QUEUE_FULL, queueDepth);
    }

    /**
     * Creates the refusal for a wake aimed at an instance that is not parked.
     *
     * <p>rawArgs layout: {@code [engineName, "WAKE", "NOT_PARKED", instanceIdMost, instanceIdLeast]}.
     * The identity rides as two {@code long}s rather than a formatted key, per the Glass-Box
     * primitive layout — and the {@code NOT_PARKED} reason exists so callers can tell this refusal
     * from every other {@code EX-FLOW-7002} without matching on message text. That distinction is
     * load-bearing for {@code lookupParked(...).ifPresent(wake)}, which is inherently check-then-act:
     * a concurrent waker between the two calls makes this refusal the expected outcome rather than a
     * fault, and there was previously no way to say so structurally.
     *
     * @param engineName     the engine name
     * @param instanceIdMost high 64 bits of the flow instance id
     * @param instanceIdLeast low 64 bits of the flow instance id
     * @since 0.12.0
     */
    public static FlowEngineException notParked(String engineName,
                                                long instanceIdMost,
                                                long instanceIdLeast) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_ENGINE_FAILURE, null,
                engineName, "WAKE", REASON_NOT_PARKED, instanceIdMost, instanceIdLeast);
    }

    /**
     * Whether {@code throwable} is the not-parked refusal above — the check a caller doing
     * {@code lookupParked(...).ifPresent(wake)} needs, since that pattern cannot be made atomic
     * from outside the engine.
     *
     * @param throwable the throwable to classify; {@code null} yields {@code false}
     * @return {@code true} if this is an {@code EX-FLOW-7002} refusal with reason {@code NOT_PARKED}
     * @since 0.12.0
     */
    public static boolean isNotParked(Throwable throwable) {
        if (!(throwable instanceof FlowEngineException flowEngineException)) {
            return false;
        }
        Object[] rawArgs = flowEngineException.rawArgs();
        return rawArgs.length > 2 && REASON_NOT_PARKED.equals(rawArgs[2]);
    }

    /**
     * Creates an exception for an optimistic-lock conflict on a durable snapshot store
     * (see ADR-013). Raised when a durable {@code FlowSnapshotStore} implementation
     * finds the on-disk {@code schemaVersion} no longer matches the incoming snapshot's
     * version — another kernel advanced the saga first.
     *
     * <p>rawArgs layout: {@code [engineName, "OPTIMISTIC_LOCK_CONFLICT", "STALE_VERSION",
     * incomingSchemaVersion]}. The version is bound to {@code int}; saga rows that exceed
     * {@code Integer.MAX_VALUE} versions are not anticipated within the operational
     * lifetime, but if needed callers may clamp via {@link Math#min}.
     *
     * @param engineName            the engine name
     * @param incomingSchemaVersion the schemaVersion the caller attempted to write
     * @since 0.7.0
     */
    public static FlowEngineException optimisticLockConflict(String engineName, long incomingSchemaVersion) {
        return optimisticLockConflict(engineName, incomingSchemaVersion, null);
    }

    /**
     * Cause-preserving variant of {@link #optimisticLockConflict(String, long)}. Used when the
     * conflict surfaces through an underlying driver exception — for example, a composite-PK
     * integrity-constraint violation raised by a concurrent INSERT race-loser in a durable
     * binding. The original {@code SQLException}-shaped chain is preserved for diagnostics;
     * the caller still observes the same OCC contract from ADR-013 §5.
     *
     * @param engineName            the engine name
     * @param incomingSchemaVersion the schemaVersion the caller attempted to write
     * @param cause                 underlying driver exception that surfaced the conflict; may be {@code null}
     * @since 0.7.0
     */
    public static FlowEngineException optimisticLockConflict(
            String engineName, long incomingSchemaVersion, Throwable cause) {
        int contextValue = (int) Math.min(incomingSchemaVersion, Integer.MAX_VALUE);
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_ENGINE_FAILURE, cause,
                engineName, "OPTIMISTIC_LOCK_CONFLICT", REASON_STALE_VERSION, contextValue);
    }

    /**
     * Creates an exception for a parked saga whose persisted resume step no longer exists in the
     * (redeployed) flow definition — the definition was changed (a step removed, or the plan shrank)
     * while the saga was parked. Raised <strong>fail-closed</strong> at resume instead of replaying the
     * stale step index into a different step (a data-corruption-class outcome). Manual intervention /
     * a definition-versioned migration (the v0.11 epic) is required.
     *
     * <p>This is the bounds/arity guard — the persisted index no longer addresses a step at all.
     * The same-arity reorder, where the index still addresses <em>something</em>, is
     * {@link #schemaMismatchStepIdentity} (ADR-062). Documented in {@code docs/subsystems/flow.md}.
     *
     * <p>rawArgs layout: {@code [engineName, "SCHEMA_MISMATCH", "STEP_OUT_OF_RANGE", persistedStep]}.
     *
     * @param engineName   the engine name
     * @param persistedStep the persisted resume step index the current definition no longer has
     * @since 0.10.0
     */
    public static FlowEngineException schemaMismatch(String engineName, int persistedStep) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_SCHEMA_MISMATCH, null,
                engineName, PHASE_SCHEMA_MISMATCH, REASON_STEP_OUT_OF_RANGE, persistedStep);
    }

    /**
     * Raised when a parked saga's persisted step index is in range but names a different step than
     * the one it parked at — the same-arity reorder the bounds guard cannot see (ADR-062).
     *
     * <p>Separate reason from {@code STEP_OUT_OF_RANGE} because the operator response differs: one
     * says the step is gone, the other says the step at that position is now something else.
     *
     * <p>rawArgs layout: {@code [engineName, "SCHEMA_MISMATCH", "STEP_IDENTITY_MISMATCH", persistedStep]}.
     *
     * @param engineName    the engine name
     * @param persistedStep the persisted resume step index whose identity no longer matches
     * @return the exception
     * @since 0.11.0
     */
    public static FlowEngineException schemaMismatchStepIdentity(String engineName, int persistedStep) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_SCHEMA_MISMATCH, null,
                engineName, PHASE_SCHEMA_MISMATCH, REASON_STEP_IDENTITY_MISMATCH, persistedStep);
    }

    /**
     * Raised when a parked saga carries no step identity at all — a snapshot written before 0.11.
     *
     * <p>It is rejected rather than resumed by position, because admitting it would leave a live route
     * back to the behaviour ADR-062 removes. Operators drain in-flight sagas before upgrading.
     *
     * <p>rawArgs layout: {@code [engineName, "SCHEMA_MISMATCH", "STEP_IDENTITY_ABSENT", persistedStep]}.
     *
     * @param engineName    the engine name
     * @param persistedStep the persisted resume step index that cannot be validated
     * @return the exception
     * @since 0.11.0
     */
    public static FlowEngineException schemaMismatchStepIdentityAbsent(String engineName, int persistedStep) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_SCHEMA_MISMATCH, null,
                engineName, PHASE_SCHEMA_MISMATCH, REASON_STEP_IDENTITY_ABSENT, persistedStep);
    }

    /**
     * Raised when a live compensation-stack entry does not index the plan the resume would bind to.
     *
     * <p>Refused on the resume path rather than discovered on the rollback path. The compensation
     * walk reads the plan by bare index outside its own failure handling, so a stale entry aborts the
     * remaining unwind and skips the terminal write — the saga is left mid-compensation with its
     * idempotency guard still held. Refusing the resume leaves the row untouched and recoverable.
     *
     * <p>rawArgs layout:
     * {@code [engineName, "SCHEMA_MISMATCH", "COMPENSATION_STACK_OUT_OF_RANGE", offendingEntry]}.
     *
     * @param engineName     the engine name
     * @param offendingEntry the stack entry that does not index the plan
     * @return the exception
     * @since 0.11.0
     */
    public static FlowEngineException schemaMismatchCompensationStack(String engineName, int offendingEntry) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_SCHEMA_MISMATCH, null,
                engineName, PHASE_SCHEMA_MISMATCH, REASON_COMPENSATION_STACK_OUT_OF_RANGE, offendingEntry);
    }

    /**
     * Raised when a live compensation-stack entry indexes the plan but names a different step than it
     * did when it was pushed.
     *
     * <p>rawArgs carries the offending <em>entry</em> — the plan position the stack pointed at — rather
     * than the stack offset, matching {@link #schemaMismatchCompensationStack} so the one int slot in
     * {@code EX-FLOW-7002} means the same thing across both compensation-stack refusals. The two step
     * names, and the offset, ride on {@code FlowSchemaMismatchEvent} where there is room for them.
     *
     * <p>rawArgs layout:
     * {@code [engineName, "SCHEMA_MISMATCH", "COMPENSATION_STACK_IDENTITY_MISMATCH", offendingEntry]}.
     *
     * @param engineName     the engine name
     * @param offendingEntry the plan position whose identity no longer matches
     * @return the exception
     * @since 0.11.0
     */
    public static FlowEngineException schemaMismatchCompensationStackIdentity(String engineName,
                                                                             int offendingEntry) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_SCHEMA_MISMATCH, null,
                engineName, PHASE_SCHEMA_MISMATCH,
                REASON_COMPENSATION_STACK_IDENTITY_MISMATCH, offendingEntry);
    }

    /**
     * Raised when a snapshot carries a live compensation stack but no identities for it.
     *
     * <p>rawArgs layout:
     * {@code [engineName, "SCHEMA_MISMATCH", "COMPENSATION_STACK_IDENTITY_ABSENT", stackPointer]} —
     * the live depth, because there is no single offending entry when every one of them is unnamed.
     *
     * @param engineName   the engine name
     * @param stackPointer the number of live entries that carry no identity
     * @return the exception
     * @since 0.11.0
     */
    public static FlowEngineException schemaMismatchCompensationStackIdentityAbsent(String engineName,
                                                                                    int stackPointer) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_SCHEMA_MISMATCH, null,
                engineName, PHASE_SCHEMA_MISMATCH,
                REASON_COMPENSATION_STACK_IDENTITY_ABSENT, stackPointer);
    }

    /**
     * Raised when a parked saga carries no definition version — a snapshot written before 0.11.
     *
     * <p>{@code rawArgs} layout: {@code [0]}=engineName, {@code [1]}="SCHEMA_MISMATCH",
     * {@code [2]}={@link #REASON_DEFINITION_VERSION_ABSENT}, {@code [3]}=persisted step index.
     *
     * @param engineName    the engine that refused the resume
     * @param persistedStep the step index the snapshot carried
     * @return the exception to throw
     * @since 0.11.0
     */
    public static FlowEngineException schemaMismatchDefinitionVersionAbsent(String engineName,
                                                                           int persistedStep) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_SCHEMA_MISMATCH, null,
                engineName, PHASE_SCHEMA_MISMATCH, REASON_DEFINITION_VERSION_ABSENT, persistedStep);
    }

    /**
     * Raised when a parked saga names a definition version this engine hosts no plan for.
     *
     * <p>The engine hosts the definition — some version of it — so this is not "another node owns
     * this flow"; it is "the version this saga parked under is not deployed here". The parked row is
     * left untouched, so deploying that version recovers the saga (ADR-064).
     *
     * <p>{@code rawArgs} layout: {@code [0]}=engineName, {@code [1]}="SCHEMA_MISMATCH",
     * {@code [2]}={@link #REASON_DEFINITION_VERSION_UNRESOLVED}, {@code [3]}=persisted version.
     *
     * @param engineName        the engine that refused the resume
     * @param persistedVersion  the definition version the snapshot carried
     * @return the exception to throw
     * @since 0.11.0
     */
    public static FlowEngineException schemaMismatchDefinitionVersionUnresolved(String engineName,
                                                                               int persistedVersion) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_SCHEMA_MISMATCH, null,
                engineName, PHASE_SCHEMA_MISMATCH, REASON_DEFINITION_VERSION_UNRESOLVED, persistedVersion);
    }
}

