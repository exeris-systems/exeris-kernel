/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.RowCursor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Package-private codec for {@link FlowSnapshot} ↔ {@code exeris_saga_state}
 * row mapping used by {@link JdbcFlowSnapshotStore}.
 *
 * <p>Owns the binding (snapshot → prepared statement parameters), the
 * {@code compensation_stack} BYTEA packing (4 bytes per int, big-endian, length
 * {@code stackPointer * 4} — H2 does not support native {@code INT[]}, see ADR-013 §5), and the
 * row decoder (cursor → snapshot).
 *
 * <h2>Column order</h2>
 * <p>Both {@link #readSnapshot} and the bind paths assume the canonical
 * column order documented at the call sites in {@link JdbcFlowSnapshotStore}:
 * <pre>
 *   0: instance_id_most       1: instance_id_least    2: definition_name
 *   3: current_step           4: state                5: last_update
 *   6: timeout_at             7: compensation_stack   8: stack_pointer
 *   9: opaque_state          10: step_name          11: schema_version
 *  12: definition_version    13: compensation_step_names
 * </pre>
 *
 * <h2>Instant.MAX encoding</h2>
 * <p>{@link Instant#MAX} cannot fit in {@code TIMESTAMPTZ} (range ~4713 BC..
 * 294276 AD); the codec encodes it as NULL on write and reconstructs it on
 * read. Matches {@code RuntimeFlowInstance.fromSnapshot} semantics for
 * "no timeout".
 */
final class JdbcFlowSnapshotCodec {

    private static final int COMPENSATION_STACK_INT_BYTES = 4;

    private JdbcFlowSnapshotCodec() {
        // package-private static utility — never instantiated.
    }

    /**
     * Binds the eleven payload columns (definition_name through compensation_step_names)
     * starting at the given parameter index. Used by both INSERT (offset 2)
     * and UPDATE (offset 0).
     */
    @SuppressWarnings("PMD.AssignmentInOperand") // idx++ is the canonical JDBC binding pattern.
    /* default */ static void bindSnapshotPayload(PersistenceStatement stmt,
                                                  FlowSnapshot snapshot,
                                                  int startIndex) {
        int idx = startIndex;
        stmt.bindString(idx++, snapshot.definitionName());
        stmt.bindInt(idx++, snapshot.currentStep());
        stmt.bindString(idx++, snapshot.state().name());
        stmt.bindInstant(idx++, snapshot.lastUpdate());
        Instant timeout = snapshot.timeout();
        if (Instant.MAX.equals(timeout)) {
            stmt.bindInstant(idx++, null);
        } else {
            stmt.bindInstant(idx++, timeout);
        }
        stmt.bindBytes(idx++, packCompensationStack(snapshot));
        stmt.bindInt(idx++, snapshot.stackPointer());
        byte[] opaque = snapshot.opaqueState();
        if (opaque.length == 0) {
            stmt.bindBytes(idx++, null);
        } else {
            stmt.bindBytes(idx++, opaque);
        }
        // NULL rather than a placeholder: the column has to be able to say "no identity recorded",
        // because that is what the resume guard keys on (ADR-062).
        stmt.bindString(idx++, snapshot.currentStepName().orElse(null));
        // Same reasoning one field along: 0 is VERSION_ABSENT, never a real version, and the resume
        // guard rejects it rather than binding the saga to whichever version is newest (ADR-064).
        stmt.bindInt(idx++, snapshot.definitionVersion());
        // NULL for the same reason step_name is NULL-able: the column must be able to say "no
        // identities recorded", which is what the resume guard keys on (ADR-064 A5). Empty maps to
        // NULL here rather than in the packer, mirroring opaqueState above.
        byte[] stepNames = packCompensationStepNames(snapshot);
        if (stepNames.length == 0) {
            stmt.bindBytes(idx, null);
        } else {
            stmt.bindBytes(idx, stepNames);
        }
    }

    /**
     * Packs the active prefix of the compensation stack into a BYTEA blob.
     * Length is {@code stackPointer * 4} (big-endian int sequence). Returns
     * a zero-length array when the stack is empty so the column is still
     * non-null on the wire.
     */
    /* default */ static byte[] packCompensationStack(FlowSnapshot snapshot) {
        int activeDepth = snapshot.stackPointer();
        if (activeDepth == 0) {
            return new byte[0];
        }
        int[] stack = snapshot.compensationStack();
        byte[] bytes = new byte[activeDepth * COMPENSATION_STACK_INT_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        for (int i = 0; i < activeDepth; i++) {
            buf.putInt(stack[i]);
        }
        return bytes;
    }

    /**
     * Packs the active prefix of the compensation-stack step identities into a BYTEA blob
     * (ADR-064 A5).
     *
     * <p>Each entry is a 4-byte big-endian UTF-8 byte count followed by that many bytes, so the blob
     * carries no delimiter and needs no escaping. A delimited encoding was not available: step names
     * are validated only as non-blank, so no character is reserved and any separator would have to
     * escape one that is not.
     *
     * <p>Returns a zero-length array when there is nothing to record; the bind site turns that into a
     * NULL column, the same way it already does for an empty {@code opaqueState}. Keeping the empty/
     * NULL translation at the binding rather than here means this method never returns {@code null}.
     */
    /* default */ static byte[] packCompensationStepNames(FlowSnapshot snapshot) {
        int activeDepth = snapshot.stackPointer();
        String[] names = snapshot.compensationStepNames();
        if (activeDepth == 0 || names.length == 0) {
            return new byte[0];
        }
        byte[][] encoded = new byte[activeDepth][];
        int total = 0;
        for (int i = 0; i < activeDepth; i++) {
            encoded[i] = names[i].getBytes(StandardCharsets.UTF_8);
            total += COMPENSATION_STACK_INT_BYTES + encoded[i].length;
        }
        ByteBuffer buf = ByteBuffer.allocate(total);
        for (byte[] entry : encoded) {
            buf.putInt(entry.length);
            buf.put(entry);
        }
        return buf.array();
    }

    /**
     * Reverse of {@link #packCompensationStepNames}. {@code null} or empty input yields an empty
     * array, which the resume guard reads as "no identities recorded" whenever the stack is live.
     */
    /* default */ static String[] unpackCompensationStepNames(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new String[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        List<String> names = new ArrayList<>();
        while (buf.remaining() >= COMPENSATION_STACK_INT_BYTES) {
            int length = buf.getInt();
            if (length < 0 || length > buf.remaining()) {
                // A truncated or corrupt blob is not silently shortened into a shorter identity list:
                // that would make the guard validate a prefix and wave the rest through. An empty
                // result reads as absent, which the guard refuses.
                return new String[0];
            }
            // Decoded straight out of the backing array: the only allocation per entry is the String
            // itself, where a copy-then-decode would allocate a throwaway byte[] alongside it.
            names.add(new String(bytes, buf.position(), length, StandardCharsets.UTF_8));
            buf.position(buf.position() + length);
        }
        return names.toArray(new String[0]);
    }

    /**
     * Reverse of {@link #packCompensationStack}. {@code null} or empty input
     * yields an empty array — the caller reconstructs an effective stack of
     * size {@code stackPointer} from the snapshot column.
     */
    /* default */ static int[] unpackCompensationStack(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new int[0];
        }
        int count = bytes.length / COMPENSATION_STACK_INT_BYTES;
        int[] result = new int[count];
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        for (int i = 0; i < count; i++) {
            result[i] = buf.getInt();
        }
        return result;
    }

    /**
     * Decodes one {@code exeris_saga_state} row into a {@link FlowSnapshot}.
     * Column order MUST match the SELECT lists in
     * {@link JdbcFlowSnapshotStore} ({@code SQL_SELECT_BY_PK} /
     * {@code SQL_LIST_PARKED}); see class Javadoc for the canonical layout.
     */
    /* default */ static FlowSnapshot readSnapshot(RowCursor row) {
        long instanceIdMost = row.getLong(0);
        long instanceIdLeast = row.getLong(1);
        String definitionName = row.getString(2);
        int currentStep = row.getInt(3);
        FlowState state = FlowState.valueOf(row.getString(4));
        Instant lastUpdate = row.getInstant(5);
        Instant timeoutValue = row.getInstant(6);
        Instant timeout = timeoutValue == null ? Instant.MAX : timeoutValue;
        int[] compensationStack = unpackCompensationStack(row.getBytes(7));
        int stackPointer = row.getInt(8);
        byte[] opaqueState = row.getBytes(9);
        if (opaqueState == null) {
            opaqueState = new byte[0];
        }
        // NULL means the row predates ADR-062 — absence, not a name. The resume guard rejects it.
        Optional<String> stepName = Optional.ofNullable(row.getString(10));
        long schemaVersion = row.getLong(11);
        // 0 is VERSION_ABSENT — the migration backfills it for rows written before the column
        // existed. Never NULL: the cursor's getInt has no NULL representation, so the migration
        // carries the absence instead of the read having to (ADR-064).
        int definitionVersion = row.getInt(12);
        // NULL means the row predates ADR-064 A5 — no identities recorded, not an empty stack. The
        // resume guard refuses it whenever the stack is live (ADR-064 A5).
        String[] compensationStepNames = unpackCompensationStepNames(row.getBytes(13));
        return new FlowSnapshot(
                instanceIdMost, instanceIdLeast,
                definitionName, definitionVersion, currentStep, stepName, state,
                lastUpdate, timeout,
                compensationStack, compensationStepNames, stackPointer,
                opaqueState, schemaVersion);
    }
}
