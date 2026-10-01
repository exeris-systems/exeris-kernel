/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything {@link FlowSnapshotStore} needs to reconstruct one flow instance — its identity, where
 * it stopped, and what a rollback would still owe.
 *
 * <p>A snapshot is written on the PARK transition and on eviction, and read back to resume an
 * instance after a restart or on another engine sharing the store. Its components map 1:1 to the
 * off-heap context slab layout in the Enterprise tier (see {@code FLOW_CONTEXT_STRIDE}).
 *
 * <p>Equality, hash code and string form are structural over the array components, so two snapshots
 * describing the same parked saga compare equal.
 *
 * <p><b>Allocation:</b> allocates — the constructor copies {@code compensationStack},
 * {@code compensationStepNames} and {@code opaqueState} in, and each of those accessors copies out
 * again on every call. All of it is on the cold persistence path (PARK, eviction, resume); nothing
 * here is reached from step dispatch.
 * <p><b>Thread confinement:</b> any thread — a constructed snapshot is deeply immutable and safe to
 * publish to any thread without further synchronisation.
 * <p><b>Ownership:</b> the caller owns both the arrays it passes in and the arrays it reads back;
 * each crossing is a copy, so mutating either side cannot reach the snapshot. Nothing here holds a
 * resource that needs releasing.
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
 * @implSpec {@code schemaVersion} is the optimistic-locking discriminator on UPSERT. A durable,
 *           distributed {@link FlowSnapshotStore} MUST advance the on-disk value by one on every
 *           accepted write, and MUST reject a write whose incoming {@code schemaVersion} does not
 *           match the on-disk row, raising {@code EX-FLOW-7002} with
 *           {@code phase="OPTIMISTIC_LOCK_CONFLICT"}. An in-memory store, which cannot race across
 *           processes, MAY ignore the component entirely. New snapshots SHOULD start at
 *           {@link #SCHEMA_VERSION_INITIAL}.
 * @implNote {@code equals}, {@code hashCode} and {@code toString} are overridden because a record's
 *           generated versions compare array components by reference, which would make two snapshots
 *           of the same saga unequal. Components are otherwise primitives; the {@link Instant} and
 *           array components are read on the cold persistence path only, and the record performs no
 *           identity operations.
 * @since 0.5
 */
public record FlowSnapshot(
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
     * @since 0.7
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
     * @since 0.11
     */
    public static final int VERSION_ABSENT = 0;

    /**
     * Validates every invariant eagerly and defensively copies the three mutable array components,
     * so a snapshot is deeply immutable the moment it exists.
     *
     * @throws NullPointerException     if {@code definitionName}, {@code currentStepName},
     *                                  {@code state}, {@code lastUpdate}, {@code timeout},
     *                                  {@code compensationStack}, {@code compensationStepNames} or
     *                                  {@code opaqueState} is {@code null}
     * @throws IllegalArgumentException if {@code definitionName} is blank; if
     *                                  {@code definitionVersion} is below {@link #VERSION_ABSENT};
     *                                  if {@code currentStep} is negative; if
     *                                  {@code currentStepName} is present but blank; if
     *                                  {@code stackPointer} falls outside
     *                                  {@code [0, compensationStack.length]}; if
     *                                  {@code compensationStepNames} is non-empty yet does not cover
     *                                  the live stack, or carries a {@code null} or blank name below
     *                                  {@code stackPointer}; if {@code opaqueState.length} exceeds
     *                                  {@link #MAX_OPAQUE_STATE_BYTES}; or if {@code schemaVersion}
     *                                  is below {@link #SCHEMA_VERSION_INITIAL}
     * @apiNote Absence is expressed with empty arrays ({@code new int[0]}, {@code new String[0]},
     *          {@code new byte[0]}) and {@link Optional#empty()}, never with {@code null}. A blank
     *          step name is rejected rather than treated as absent, because it would compare unequal
     *          to every real step and read as corruption. This is the cold construction path — PARK
     *          and eviction — so the copies are affordable.
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
     * Builds a snapshot from the component list as it stands without {@code definitionVersion},
     * {@code currentStepName} and {@code compensationStepNames}, keeping that call shape available to
     * source compiled against it.
     *
     * <p>All three omitted components take their <b>fail-closed</b> sentinels — {@link #VERSION_ABSENT},
     * {@link Optional#empty()} and an empty identity array — so a snapshot built this way is refused on
     * resume rather than replayed by position against whichever definition happens to be registered.
     * This overload therefore cannot become the quiet route back that ADR-062 and ADR-064 each closed;
     * it buys a caller compilation, not a bypass.
     *
     * <p><b>What it does not restore.</b> A record's component list is part of its shape, and no
     * overload hides that: record deconstruction patterns and reflection over
     * {@code RecordComponent[]} observe the full fourteen components regardless of which constructor
     * built the instance.
     *
     * @param instanceIdMost    most-significant bits of the 128-bit flow instance UUID
     * @param instanceIdLeast   least-significant bits of the 128-bit flow instance UUID
     * @param definitionName    name of the {@link FlowDefinition} this instance was compiled from
     * @param currentStep       zero-based index of the step to resume execution at
     * @param state             lifecycle state the instance occupied when the snapshot was taken
     * @param lastUpdate        timestamp of the last state mutation
     * @param timeout           absolute expiry time of this flow instance
     * @param compensationStack plan positions whose compensations must execute in reverse order
     * @param stackPointer      number of live entries in {@code compensationStack}
     * @param opaqueState       raw payload for implementation-specific state
     * @param schemaVersion     monotonic version for optimistic concurrency control
     * @throws NullPointerException     as the canonical constructor
     * @throws IllegalArgumentException as the canonical constructor
     * @since 0.11
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
     * Builds a snapshot with no schema version of its own, defaulting it to
     * {@link #SCHEMA_VERSION_INITIAL} — the shape an in-memory store needs, since it never races
     * across processes and has nothing to lock optimistically against.
     *
     * <p>Step identity, definition version and compensation-stack identities are absent here too, and
     * take the same fail-closed sentinels the eleven-argument overload gives them: a snapshot built
     * this way is refused on resume rather than replayed by position.
     *
     * @param instanceIdMost    most-significant bits of the 128-bit flow instance UUID
     * @param instanceIdLeast   least-significant bits of the 128-bit flow instance UUID
     * @param definitionName    name of the {@link FlowDefinition} this instance was compiled from
     * @param currentStep       zero-based index of the step to resume execution at
     * @param state             lifecycle state the instance occupied when the snapshot was taken
     * @param lastUpdate        timestamp of the last state mutation
     * @param timeout           absolute expiry time of this flow instance
     * @param compensationStack plan positions whose compensations must execute in reverse order
     * @param stackPointer      number of live entries in {@code compensationStack}
     * @param opaqueState       raw payload for implementation-specific state
     * @throws NullPointerException     as the canonical constructor
     * @throws IllegalArgumentException as the canonical constructor
     * @implSpec A durable store participating in optimistic concurrency MUST NOT build its snapshots
     *           here: it MUST use the canonical constructor and pass the current on-disk version.
     *           Every snapshot built here carries {@link #SCHEMA_VERSION_INITIAL}, so once the row it
     *           addresses has advanced past that, every write is refused as stale.
     * @since 0.7
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
     * Plan positions whose compensations a rollback would still run, in reverse order, as a copy the
     * caller owns.
     *
     * <p>Only the first {@link #stackPointer()} entries are live; anything above it is a dead
     * high-water mark left by an earlier, deeper unwind, and treating it as state refuses sound
     * sagas.
     *
     * @return a fresh copy of the compensation stack; never {@code null}, and mutating it cannot
     *         reach this snapshot
     * @apiNote Cold path — this allocates a copy on every call, so read it once and keep the result
     *          rather than indexing through the accessor.
     */
    public int[] compensationStack() {
        return Arrays.copyOf(compensationStack, compensationStack.length);
    }

    /**
     * Identity of the step each live {@link #compensationStack()} entry addressed when it was pushed,
     * as a copy the caller owns.
     *
     * <p>A zero-length result with a non-zero {@link #stackPointer()} means the identities are
     * <b>absent</b>, not that the stack is empty — the two are distinguishable precisely because a
     * stack with nothing live has nothing to validate. Resume refuses the first case rather than
     * trusting the positions beneath it.
     *
     * @return a fresh copy of the stack identities, or a zero-length array when the snapshot records
     *         none; mutating it cannot reach this snapshot
     * @apiNote Cold path — this allocates a copy on every call.
     * @since 0.11
     */
    public String[] compensationStepNames() {
        return Arrays.copyOf(compensationStepNames, compensationStepNames.length);
    }

    /**
     * The application's own payload, carried across PARK and restart untouched, as a copy the caller
     * owns.
     *
     * <p>Kernel-opaque: nothing here interprets these bytes, and no guard validates them on resume.
     * Bounded by {@link #MAX_OPAQUE_STATE_BYTES}.
     *
     * @return a fresh copy of the opaque payload; never {@code null}, possibly zero-length, and
     *         mutating it cannot reach this snapshot
     * @apiNote Cold path — this allocates a copy on every call.
     */
    public byte[] opaqueState() {
        return Arrays.copyOf(opaqueState, opaqueState.length);
    }

    /**
     * Renders the 128-bit instance UUID as its two hex halves joined by {@code -}, for JFR payloads
     * and diagnostic text.
     *
     * @return the instance identity as {@code <mostHex>-<leastHex>}
     * @apiNote Cold path — this allocates a {@code String}. Compare {@link #instanceIdMost()} and
     *          {@link #instanceIdLeast()} directly for a zero-allocation identity check.
     */
    public String instanceId() {
        return Long.toHexString(instanceIdMost) + "-" + Long.toHexString(instanceIdLeast);
    }

    /**
     * Structural equality: two snapshots describing the same parked saga compare equal, including
     * their array components, which a record's generated {@code equals} would compare by reference.
     *
     * @implNote Allocation-free. A type-pattern bind ({@code instanceof FlowSnapshot other}) reads
     *           the array components as fields, bypassing the defensive-copy accessors; a record
     *           deconstruction pattern would call those accessors and allocate three array copies per
     *           comparison.
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
     * Hash code consistent with the structural {@link #equals(Object)} — array components hash over
     * their contents via {@link Arrays#hashCode}, not their identity.
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
     * Diagnostic rendering: array components print their contents via {@link Arrays#toString} rather
     * than an identity hash code, except {@code opaqueState}, which prints its size only.
     *
     * <p>{@code opaqueState} is the one component that is application data rather than definition
     * metadata, and this text reaches logs, debuggers and exception messages. The two compensation
     * arrays stay rendered because step positions and step names are both definition metadata.
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

