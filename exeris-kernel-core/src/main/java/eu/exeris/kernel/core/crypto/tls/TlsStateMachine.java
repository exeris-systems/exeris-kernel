/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.spi.crypto.TlsPhase;
import eu.exeris.kernel.spi.crypto.TlsSessionState;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Core: Lock-free TLS session lifecycle state machine.
 *
 * <h2>Design</h2>
 * <p>Implements {@link TlsSessionState} via a single {@code volatile int} field
 * holding the {@link TlsPhase#ordinal()}. All reads are O(1) acquire-reads via
 * {@link VarHandle}. Transitions use CAS — no monitors, no {@code synchronized},
 * no {@code ReentrantReadWriteLock}.
 *
 * <h2>Transition Table (encoded as bitmask per source phase)</h2>
 * <pre>
 *   UNINITIALIZED       → HANDSHAKE_IN_PROGRESS | ERROR
 *   HANDSHAKE_IN_PROGRESS → HANDSHAKE_COMPLETE  | ERROR
 *   HANDSHAKE_COMPLETE  → ACTIVE                | ERROR
 *   ACTIVE              → SHUTDOWN_INITIATED    | ERROR
 *   SHUTDOWN_INITIATED  → SHUTDOWN_COMPLETE     | ERROR
 *   SHUTDOWN_COMPLETE   → CLOSED
 *   CLOSED              — terminal
 *   ERROR               — terminal
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent calls from multiple Virtual Threads. CAS guarantees
 * exactly-once semantics for each transition — concurrent losers receive
 * {@link TlsHandshakeException} describing the actual vs. requested phases.
 *
 * <h2>JFR-First</h2>
 * <p>Every successful transition emits a {@link TlsPhaseTransitionEvent}.
 *
 * @since 0.5.0
 * @see TlsSessionState
 * @see TlsPhase
 */
public final class TlsStateMachine implements TlsSessionState {

    // =========================================================================
    // Static fields — DeclarationOrder: static fields first, then static init
    // =========================================================================

    /**
     * Pre-allocated, immutable snapshot of all {@link TlsPhase} constants.
     *
     * <h2>Zero-Allocation Contract</h2>
     * <p>{@code TlsPhase.values()} allocates a fresh {@code TlsPhase[]} array on every
     * call — one heap object per invocation. On the hot path ({@link #phase()},
     * {@link #forceError()}) this creates continuous object churn visible in JFR
     * allocation profiles. This field captures the array once at class-load time
     * and reuses it everywhere within the state machine.
     *
     * <p>The array is never mutated (enum constants are final); reading by index is O(1)
     * with no barrier beyond the initial class-load acquire.
     */
    private static final TlsPhase[] PHASES = TlsPhase.values();

    /** Number of distinct phases — derived from the pre-allocated array, not a second values() call. */
    private static final int PHASE_COUNT = PHASES.length;

    /**
     * Pre-computed transition bitmasks indexed by source phase ordinal.
     * Bit N is set if transition to phase with ordinal N is legal.
     */
    private static final int[] ALLOWED_TARGETS = buildTransitionTable();

    /** VarHandle for lock-free CAS on {@link #phaseOrdinal}. */
    private static final VarHandle PHASE;

    // =========================================================================
    // Static initializer — after all static fields
    // =========================================================================

    static {
        try {
            PHASE = MethodHandles.lookup()
                    .findVarHandle(TlsStateMachine.class, "phaseOrdinal", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // =========================================================================
    // Instance fields — after static initializer
    // =========================================================================

    /** Phase ordinal — mutated only via {@link #PHASE} VarHandle CAS. */
    /* default */ volatile int phaseOrdinal = TlsPhase.UNINITIALIZED.ordinal();

    // =========================================================================
    // Static helpers — build transition table
    // =========================================================================

    private static int[] buildTransitionTable() {
        int[] table = new int[PHASE_COUNT];
        // UNINITIALIZED → HANDSHAKE_IN_PROGRESS | ERROR
        table[ord(TlsPhase.UNINITIALIZED)] =
                bit(TlsPhase.HANDSHAKE_IN_PROGRESS) | bit(TlsPhase.ERROR);
        // HANDSHAKE_IN_PROGRESS → HANDSHAKE_COMPLETE | ERROR
        table[ord(TlsPhase.HANDSHAKE_IN_PROGRESS)] =
                bit(TlsPhase.HANDSHAKE_COMPLETE) | bit(TlsPhase.ERROR);
        // HANDSHAKE_COMPLETE → ACTIVE | ERROR
        table[ord(TlsPhase.HANDSHAKE_COMPLETE)] =
                bit(TlsPhase.ACTIVE) | bit(TlsPhase.ERROR);
        // ACTIVE → SHUTDOWN_INITIATED | ERROR
        table[ord(TlsPhase.ACTIVE)] =
                bit(TlsPhase.SHUTDOWN_INITIATED) | bit(TlsPhase.ERROR);
        // SHUTDOWN_INITIATED → SHUTDOWN_COMPLETE | ERROR
        table[ord(TlsPhase.SHUTDOWN_INITIATED)] =
                bit(TlsPhase.SHUTDOWN_COMPLETE) | bit(TlsPhase.ERROR);
        // SHUTDOWN_COMPLETE → CLOSED
        table[ord(TlsPhase.SHUTDOWN_COMPLETE)] =
                bit(TlsPhase.CLOSED);
        // CLOSED, ERROR — terminal: bitmask stays 0
        return table;
    }

    private static int ord(TlsPhase phase) {
        return phase.ordinal();
    }

    private static int bit(TlsPhase phase) {
        return 1 << phase.ordinal();
    }

    // =========================================================================
    // TlsSessionState — hot-path read (O(1), no allocation)
    // =========================================================================

    @Override
    public TlsPhase phase() {
        return PHASES[(int) PHASE.getAcquire(this)];
    }

    // =========================================================================
    // Transition API
    // =========================================================================

    /**
     * Attempts to transition from {@code expected} phase to {@code target} phase.
     *
     * <p>Uses CAS — if the current phase is not {@code expected}, the transition
     * fails with {@link TlsHandshakeException}. This prevents concurrent double-transitions.
     *
     * @param expected the phase the caller believes the machine is in
     * @param target   the desired next phase
     * @throws TlsHandshakeException if the transition is illegal or CAS failed
     */
    public void transitionTo(TlsPhase expected, TlsPhase target) {
        int fromOrd = expected.ordinal();
        int toOrd   = target.ordinal();

        if ((ALLOWED_TARGETS[fromOrd] & (1 << toOrd)) == 0) {
            throw new TlsHandshakeException(
                    "Illegal TLS state transition: " + expected + " → " + target);
        }

        if (!PHASE.compareAndSet(this, fromOrd, toOrd)) {
            TlsPhase actual = phase();
            throw new TlsHandshakeException(
                    "TLS state transition CAS failed: expected=" + expected
                    + " target=" + target + " actual=" + actual);
        }

        TlsPhaseTransitionEvent.emit(expected, target);
    }

    /**
     * Forces transition to {@link TlsPhase#ERROR} from any non-terminal phase.
     *
     * <p>Uses a spin-CAS to handle races: multiple threads may independently detect
     * an error. Only the first succeeds; subsequent calls are no-ops once ERROR is reached.
     */
    public void forceError() {
        int errorOrd = TlsPhase.ERROR.ordinal();
        int current;
        do {
            current = (int) PHASE.getAcquire(this);
            if (current == errorOrd || current == TlsPhase.CLOSED.ordinal()) {
                return; // already terminal
            }
        } while (!PHASE.compareAndSet(this, current, errorOrd));

        TlsPhaseTransitionEvent.emit(PHASES[current], TlsPhase.ERROR);
    }

    /**
     * Convenience: transitions from current phase to {@code target} if legal.
     *
     * <p>Reads current phase atomically and attempts CAS in one operation.
     * Suitable for single-threaded session drivers (e.g., one Virtual Thread per connection).
     *
     * @param target the desired next phase
     * @throws TlsHandshakeException if current→target is not a legal transition
     */
    public void transitionTo(TlsPhase target) {
        transitionTo(phase(), target);
    }

    // =========================================================================
    // Query helpers (all O(1), no allocation)
    // =========================================================================

    /** Returns {@code true} if the current phase allows the transition to {@code target}. */
    public boolean canTransitionTo(TlsPhase target) {
        int current = (int) PHASE.getAcquire(this);
        return (ALLOWED_TARGETS[current] & (1 << target.ordinal())) != 0;
    }

    @Override
    public String toString() {
        return "TlsStateMachine{phase=" + phase() + '}';
    }
}
