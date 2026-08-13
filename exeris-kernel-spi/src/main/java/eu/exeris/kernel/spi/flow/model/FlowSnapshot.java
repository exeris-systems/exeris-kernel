/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.flow.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of a flow instance's persisted state.
 *
 * <h2>Persistence Contract</h2>
 * <p>Used by {@link FlowSnapshotStore} to persist and restore flow instances across
 * PARK/WAKE cycles and kernel restarts. Fields map 1:1 to the off-heap context
 * slab layout in the Enterprise tier (see {@code FLOW_CONTEXT_STRIDE}).
 *
 * <h2>Optimistic Concurrency (since 0.7.0)</h2>
 * <p>{@code schemaVersion} carries a monotonic version counter that durable, distributed
 * {@link FlowSnapshotStore} implementations use as an optimistic-locking discriminator
 * on UPSERT. New snapshots SHOULD start at {@link #SCHEMA_VERSION_INITIAL}; durable stores
 * MUST advance the on-disk value on every accepted write and MUST reject a write whose
 * incoming {@code schemaVersion} does not match the on-disk row, raising
 * {@code EX-FLOW-7002 / phase=OPTIMISTIC_LOCK_CONFLICT}. In-memory stores (which never
 * race across processes) MAY ignore the field entirely.
 *
 * <h2>Array Equality</h2>
 * <p>This record contains {@code int[]}, {@code String[]} and {@code byte[]} array fields.
 * The default record {@code equals()}/{@code hashCode()}/{@code toString()} use
 * reference equality for arrays, which is incorrect for structural comparison.
 * All three methods are overridden here to use {@link Arrays#equals} /
 * {@link Arrays#hashCode} / {@link Arrays#toString} for deep array semantics.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Mostly primitive fields. {@code Instant} and array fields are on the cold
 * persistence path only — not used in the hot dispatch loop. No identity operations.
 * Declared {@code value record} on the `preview` line (JEP 401); the distributed line compiles
 * the same source as an identity {@code record}, and the modifier is asserted by
 * {@code Class::isValue} in the module's value-carrier registry test. The hand-written value equality below is what makes
 * that honest: the generated {@code equals} would compare the array components by reference.
 *
 * @param instanceIdMost    most-significant bits of the 128-bit flow instance UUID
 * @param instanceIdLeast   least-significant bits of the 128-bit flow instance UUID
 * @param definitionName    name of the {@link FlowDefinition} this instance was compiled from;
 *                          must not be {@code null} or blank
 * @param definitionVersion version of that definition (ADR-064). {@link #VERSION_ABSENT} means the
 *                          snapshot predates definition versioning — such a snapshot is rejected
 *                          fail-closed on resume rather than bound to whichever version is registered
 * @param currentStepName   identity of the step {@code currentStep} pointed at when the snapshot
 *                          was written (ADR-062). {@link Optional#empty()} means the snapshot predates
 *                          step-identity recording — such a snapshot cannot be validated on resume and
 *                          is rejected fail-closed rather than replayed by position
 * @param currentStep       zero-based index of the step to resume execution at;
 *                          must be {@code >= 0}
 * @param state             current {@link FlowState}
 * @param lastUpdate        timestamp of the last state mutation (for LRU eviction ordering)
 * @param timeout           absolute expiry time of this flow instance
 * @param compensationStack plan <em>positions</em> whose compensations must execute in reverse order.
 *                          Not identities: a position means a different step after a reorder, which is
 *                          why {@code compensationStepNames} exists beside it
 * @param compensationStepNames identity of the step each live {@code compensationStack} entry addressed
 *                          when it was pushed (ADR-064 A5). A zero-length array means the snapshot
 *                          predates stack-identity recording — with a non-empty stack that is rejected
 *                          fail-closed on resume, for the same reason an absent {@code currentStepName} is
 * @param stackPointer      number of valid entries in {@code compensationStack}
 * @param opaqueState       raw byte payload for implementation-specific state (max 916 bytes)
 * @param schemaVersion     monotonic version for optimistic concurrency control on durable
 *                          stores; {@code >= 1L}; new snapshots use {@link #SCHEMA_VERSION_INITIAL}
 *
 * @since 0.5.0
 */
public value record FlowSnapshot(
        long      instanceIdMost,
        long      instanceIdLeast,
        String    definitionName,
        int       definitionVersion,
        int       currentStep,
        Optional<String> currentStepName,
        FlowState state,
        Instant   lastUpdate,
        Instant   timeout,
        int[]     compensationStack,
        String[]  compensationStepNames,
        int       stackPointer,
        byte[]    opaqueState,
        long      schemaVersion
) {

    /**
     * Maximum allowed size of the {@code opaqueState} payload (916 bytes), matching the
     * off-heap context slab reserved region documented in {@code FLOW_CONTEXT_STRIDE}.
     */
    public static final int MAX_OPAQUE_STATE_BYTES = 916;

    /**
     * Initial value of {@link #schemaVersion} for newly created snapshots. Durable stores
     * MUST advance from this value on every accepted write.
     *
     * @since 0.7.0
     */
    public static final long SCHEMA_VERSION_INITIAL = 1L;

    /**
     * {@link #definitionVersion} of a snapshot written before definition versioning existed.
     *
     * <p>Not a version: {@code FlowDefinition.INITIAL_VERSION} is 1, so this value can never name a
     * real definition. Resume rejects it fail-closed rather than guessing a version, for the same
     * reason ADR-062 rejects a snapshot carrying no step identity — a default here would be a
     * permanent route back to resuming against whatever happens to be registered.
     *
     * @since 0.11.0
     */
    public static final int VERSION_ABSENT = 0;

    /**
     * Compact constructor — validates all invariants eagerly (fail-fast) and defensively
     * copies both mutable array components to guarantee true immutability.
     *
     * <h2>Null Policy</h2>
     * <p>{@code compensationStack} and {@code opaqueState} must not be {@code null}.
     * Pass empty arrays ({@code new int[0]}, {@code new byte[0]}) when the data is absent.
     *
     * <h2>Bounds Validation</h2>
     * <ul>
     *   <li>{@code definitionName} must not be blank.</li>
     *   <li>{@code currentStep} must be {@code >= 0} (zero-based step index).</li>
     *   <li>{@code stackPointer} must be in {@code [0, compensationStack.length]}.</li>
     *   <li>{@code opaqueState.length} must not exceed {@link #MAX_OPAQUE_STATE_BYTES}.</li>
     * </ul>
     *
     * <p>This is the <em>cold</em> construction path (snapshot creation on PARK / eviction),
     * so the allocation cost is acceptable.
     */
    public FlowSnapshot {
        Objects.requireNonNull(definitionName,    "definitionName must not be null");
        Objects.requireNonNull(state,             "state must not be null");
        Objects.requireNonNull(lastUpdate,        "lastUpdate must not be null");
        Objects.requireNonNull(timeout,           "timeout must not be null");
        Objects.requireNonNull(compensationStack, "compensationStack must not be null — use new int[0] for empty");
        Objects.requireNonNull(opaqueState,       "opaqueState must not be null — use new byte[0] for empty");
        if (definitionName.isBlank()) {
            throw new IllegalArgumentException("definitionName must not be blank");
        }
        if (definitionVersion < VERSION_ABSENT) {
            throw new IllegalArgumentException(
                    "definitionVersion must be >= " + VERSION_ABSENT + ", got: " + definitionVersion);
        }
        if (currentStep < 0) {
            throw new IllegalArgumentException(
                    "currentStep must be >= 0 (zero-based step index), got: " + currentStep);
        }
        Objects.requireNonNull(currentStepName,
                "currentStepName must not be null — use Optional.empty() for a pre-0.11 snapshot");
        if (currentStepName.isPresent() && currentStepName.get().isBlank()) {
            // A blank identity would compare unequal to every real step name and read as corruption
            // rather than as absence, so it is rejected at construction where the cause is still known.
            throw new IllegalArgumentException("currentStepName must not be blank when present");
        }
        if (stackPointer < 0 || stackPointer > compensationStack.length) {
            throw new IllegalArgumentException(
                    "stackPointer out of bounds: " + stackPointer
                    + " (compensationStack.length=" + compensationStack.length + ')');
        }
        Objects.requireNonNull(compensationStepNames,
                "compensationStepNames must not be null — use new String[0] when identities are absent");
        // Absent is all-or-nothing. A partly-populated array would let a stack be validated entry by
        // entry, and the entries it could not cover are exactly the ones a caller had no name for —
        // i.e. the ones most likely to be stale. Either every live entry carries an identity or none do.
        if (compensationStepNames.length != 0 && compensationStepNames.length < stackPointer) {
            throw new IllegalArgumentException(
                    "compensationStepNames must cover the live stack or be empty: length="
                    + compensationStepNames.length + " < stackPointer=" + stackPointer);
        }
        for (int index = 0; index < stackPointer && index < compensationStepNames.length; index++) {
            String name = compensationStepNames[index];
            if (name == null || name.isBlank()) {
                // Same reason a blank currentStepName is rejected: it would compare unequal to every
                // real step name and read as corruption rather than as absence.
                throw new IllegalArgumentException(
                        "compensationStepNames[" + index + "] must not be null or blank");
            }
        }
        if (opaqueState.length > MAX_OPAQUE_STATE_BYTES) {
            throw new IllegalArgumentException(
                    "opaqueState exceeds max size: " + opaqueState.length
                    + " > " + MAX_OPAQUE_STATE_BYTES);
        }
        if (schemaVersion < SCHEMA_VERSION_INITIAL) {
            throw new IllegalArgumentException(
                    "schemaVersion must be >= " + SCHEMA_VERSION_INITIAL + ", got: " + schemaVersion);
        }
        compensationStack     = Arrays.copyOf(compensationStack, compensationStack.length);
        compensationStepNames = Arrays.copyOf(compensationStepNames, compensationStepNames.length);
        opaqueState           = Arrays.copyOf(opaqueState, opaqueState.length);
    }

    /**
     * The canonical constructor as it stood in 0.10.0 — restored, not left broken.
     *
     * <p>{@code eu.exeris.kernel.spi.flow} is declared <b>stable since 0.5.0</b> in
     * {@code docs/stability-matrix.md}. v0.11 adds three components to this record ({@code
     * currentStepName} for ADR-062, {@code definitionVersion} for ADR-064, {@code
     * compensationStepNames} for ADR-064 A5), which moves the canonical constructor from eleven
     * parameters to fourteen and would otherwise leave code compiled against 0.10.0 unable to
     * construct a snapshot at all. This overload restores that exact descriptor.
     *
     * <p>All three new components default to their <b>fail-closed</b> sentinels — {@link
     * Optional#empty()}, {@link #VERSION_ABSENT} and an empty identity array — so a snapshot built this
     * way is refused on resume rather than replayed by position against whichever definition happens to
     * be registered. The compatibility shim therefore cannot become the quiet route back that ADR-062
     * and ADR-064 each closed; it buys a caller compilation, not a bypass.
     *
     * <p><b>What this does not restore.</b> Adding a component to a record changes its component list,
     * and no overload can hide that: record deconstruction patterns and reflection over
     * {@code RecordComponent[]} still observe a different shape. That residue is irreducible for a
     * record and is recorded in the release notes rather than papered over here.
     *
     * @since 0.11.0
     */
    @SuppressWarnings("PMD.ExcessiveParameterList") // compatibility bridge — preserves the 0.10.0 shape
    public FlowSnapshot(
            long      instanceIdMost,
            long      instanceIdLeast,
            String    definitionName,
            int       currentStep,
            FlowState state,
            Instant   lastUpdate,
            Instant   timeout,
            int[]     compensationStack,
            int       stackPointer,
            byte[]    opaqueState,
            long      schemaVersion
    ) {
        this(instanceIdMost, instanceIdLeast, definitionName, VERSION_ABSENT, currentStep,
             Optional.empty(), state, lastUpdate, timeout, compensationStack, new String[0], stackPointer,
             opaqueState, schemaVersion);
    }

    /**
     * Convenience constructor that omits {@link #schemaVersion}, defaulting it to
     * {@link #SCHEMA_VERSION_INITIAL}. Preserves the pre-0.7 call shape so existing
     * callers (e.g., the runtime engine's {@code toSnapshot} path and in-memory stores)
     * compile unchanged. Durable stores that participate in optimistic concurrency
     * MUST use the canonical constructor and pass the current on-disk version.
     *
     * @since 0.7.0
     */
    @SuppressWarnings("PMD.ExcessiveParameterList") // backward-compat bridge — preserves v0.6 call shape
    public FlowSnapshot(
            long      instanceIdMost,
            long      instanceIdLeast,
            String    definitionName,
            int       currentStep,
            FlowState state,
            Instant   lastUpdate,
            Instant   timeout,
            int[]     compensationStack,
            int       stackPointer,
            byte[]    opaqueState
    ) {
        // Step identity, definition version and stack identities are all absent by construction here:
        // this shim preserves a call shape that predates ADR-062 and ADR-064 and cannot know any of
        // them. The resulting snapshot is rejected fail-closed on resume rather than replayed by
        // position against whatever plan is registered — deliberately, so the shim cannot become a
        // quiet route back to any of the three.
        this(instanceIdMost, instanceIdLeast, definitionName, VERSION_ABSENT, currentStep,
             Optional.empty(), state, lastUpdate, timeout, compensationStack, new String[0], stackPointer,
             opaqueState, SCHEMA_VERSION_INITIAL);
    }

    /**
     * Returns a <em>defensive copy</em> of the compensation stack array.
     *
     * <p>Callers must not modify the returned array. A copy is returned to preserve
     * the immutability guarantee documented in the compact constructor.
     * This accessor is on the cold persistence path; the allocation cost is acceptable.
     */
    public int[] compensationStack() {
        return Arrays.copyOf(compensationStack, compensationStack.length);
    }

    /**
     * Returns a <em>defensive copy</em> of the compensation-stack step identities.
     *
     * <p>Callers must not modify the returned array. A zero-length result with a non-zero
     * {@link #stackPointer()} means the identities are <b>absent</b>, not that the stack is empty —
     * the two are distinguishable precisely because a stack with nothing live has nothing to validate.
     *
     * @since 0.11.0
     */
    public String[] compensationStepNames() {
        return Arrays.copyOf(compensationStepNames, compensationStepNames.length);
    }

    /**
     * Returns a <em>defensive copy</em> of the opaque state byte array.
     *
     * <p>Callers must not modify the returned array. A copy is returned to preserve
     * the immutability guarantee documented in the compact constructor.
     * This accessor is on the cold persistence path; the allocation cost is acceptable.
     */
    public byte[] opaqueState() {
        return Arrays.copyOf(opaqueState, opaqueState.length);
    }

    /**
     * Returns a combined hex string for JFR events and diagnostic logging.
     *
     * <p><b>Cold-path only</b> — this method allocates a {@code String}.
     * Do not call from the hot dispatch loop; use {@link #instanceIdMost()} and
     * {@link #instanceIdLeast()} directly for zero-allocation identity checks.
     */
    public String instanceId() {
        return Long.toHexString(instanceIdMost) + "-" + Long.toHexString(instanceIdLeast);
    }

    /**
     * Deep equality check — uses {@link Arrays#equals} for {@code compensationStack}
     * and {@code opaqueState} to avoid reference-equality false negatives.
     *
     * <p><b>Allocation-free:</b> uses a simple type-pattern bind ({@code instanceof
     * FlowSnapshot other}) and accesses {@code other.compensationStack} /
     * {@code other.opaqueState} as fields directly, bypassing the defensive-copy
     * accessors. A record deconstruction pattern ({@code instanceof FlowSnapshot(...)})
     * would invoke those accessors and allocate two unnecessary array copies per call.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof FlowSnapshot other)) {
            return false;
        }
        return instanceIdMost   == other.instanceIdMost
               && instanceIdLeast  == other.instanceIdLeast
               && definitionVersion == other.definitionVersion
               && currentStep      == other.currentStep
               && stackPointer     == other.stackPointer
               && schemaVersion    == other.schemaVersion
               && state            == other.state
               && Objects.equals(definitionName, other.definitionName)
               && Objects.equals(currentStepName, other.currentStepName)
               && Objects.equals(lastUpdate,     other.lastUpdate)
               && Objects.equals(timeout,        other.timeout)
               && Arrays.equals(compensationStack,     other.compensationStack)
               && Arrays.equals(compensationStepNames, other.compensationStepNames)
               && Arrays.equals(opaqueState,           other.opaqueState);
    }

    /**
     * Deep hash code — uses {@link Arrays#hashCode} for {@code compensationStack}
     * and {@code opaqueState}.
     */
    @Override
    public int hashCode() {
        int result = Objects.hash(
                instanceIdMost, instanceIdLeast, definitionName, definitionVersion,
                currentStep, currentStepName, state, lastUpdate, timeout, stackPointer, schemaVersion);
        result = 31 * result + Arrays.hashCode(compensationStack);
        result = 31 * result + Arrays.hashCode(compensationStepNames);
        result = 31 * result + Arrays.hashCode(opaqueState);
        return result;
    }

    /**
     * Human-readable representation — uses {@link Arrays#toString} for array fields
     * to produce meaningful diagnostic output instead of identity hash codes.
     */
    @Override
    public String toString() {
        return "FlowSnapshot["
               + "instanceId=" + instanceId()
               + ", definitionName=" + definitionName
               + ", definitionVersion=" + definitionVersion
               + ", currentStep=" + currentStep
               + ", currentStepName=" + currentStepName.orElse(null)
               + ", state=" + state
               + ", lastUpdate=" + lastUpdate
               + ", timeout=" + timeout
               + ", compensationStack=" + Arrays.toString(compensationStack)
               + ", compensationStepNames=" + Arrays.toString(compensationStepNames)
               + ", stackPointer=" + stackPointer
               // Size, not contents. opaqueState is the application's payload — the one component of
               // this record that is user data rather than definition metadata — and toString is what
               // reaches logs, debuggers and exception text. The two compensation arrays above stay
               // rendered because step positions and step names are both definition metadata, not
               // instance data. Mirrors FlowMigrationState.
               + ", opaqueState=" + opaqueState.length + " bytes"
               + ", schemaVersion=" + schemaVersion
               + ']';
    }
}

