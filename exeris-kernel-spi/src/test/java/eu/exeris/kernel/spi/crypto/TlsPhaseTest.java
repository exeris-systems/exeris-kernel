/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1: {@link TlsPhase} ordinal stability and {@link TlsStatus} completeness.
 *
 * <p>Ordinals are used as raw int indices into the TlsStateMachine CAS bitmask table.
 * Reordering enum constants silently breaks the state machine — these tests pin ordinals.
 *
 * @since 0.5.0
 */
@DisplayName("L1: TlsPhase & TlsStatus")
class TlsPhaseTest {

    @Test
    @DisplayName("Phase ordinals are pinned: UNINITIALIZED=0 .. ERROR=7")
    void phaseOrdinalsArePinned() {
        assertThat(TlsPhase.UNINITIALIZED.ordinal()).isZero();
        assertThat(TlsPhase.HANDSHAKE_IN_PROGRESS.ordinal()).isEqualTo(1);
        assertThat(TlsPhase.HANDSHAKE_COMPLETE.ordinal()).isEqualTo(2);
        assertThat(TlsPhase.ACTIVE.ordinal()).isEqualTo(3);
        assertThat(TlsPhase.SHUTDOWN_INITIATED.ordinal()).isEqualTo(4);
        assertThat(TlsPhase.SHUTDOWN_COMPLETE.ordinal()).isEqualTo(5);
        assertThat(TlsPhase.CLOSED.ordinal()).isEqualTo(6);
        assertThat(TlsPhase.ERROR.ordinal()).isEqualTo(7);
    }

    @Test
    @DisplayName("Total TlsPhase count == 8 — new phases require TlsStateMachine review")
    void totalPhaseCount() {
        assertThat(TlsPhase.values()).hasSize(8);
    }

    @Test
    @DisplayName("TlsStatus has exactly: OK, NEED_WRAP, NEED_UNWRAP, NEED_HANDSHAKE, FINISHED, CLOSED")
    void tlsStatusValues() {
        assertThat(TlsStatus.values()).extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "OK", "NEED_WRAP", "NEED_UNWRAP",
                        "NEED_HANDSHAKE", "FINISHED", "CLOSED");
    }

    @Test
    @DisplayName("TlsStatus.OK is the success/happy-path value")
    void okIsSuccessPath() {
        assertThat(TlsStatus.OK.name()).isEqualTo("OK");
    }

    @Test
    @DisplayName("TlsStatus.CLOSED indicates session-teardown path")
    void closedIsTerminal() {
        assertThat(TlsStatus.CLOSED.name()).isEqualTo("CLOSED");
    }
}
