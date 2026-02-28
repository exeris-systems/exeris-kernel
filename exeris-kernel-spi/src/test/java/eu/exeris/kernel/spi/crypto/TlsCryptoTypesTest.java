/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: TLS crypto type contracts — ordinal stability, Valhalla-readiness, singletons.
 *
 * <h2>TlsPhase Ordinal Stability</h2>
 * <p>Phase ordinals are used as raw int indices into the TlsStateMachine CAS bitmask table.
 * Any reordering of enum constants silently corrupts the state machine.
 *
 * <h2>TlsHandshakeResult / TlsShutdownResult Valhalla-Readiness</h2>
 * <p>Both are {@code record} types designed for future {@code value record} migration.
 * Tests verify structural equality, pre-allocated singletons, and absence of identity ops.
 *
 * @since 0.5.0
 */
@DisplayName("L0: TLS Crypto Types — Ordinal Stability + Valhalla-Readiness")
class TlsCryptoTypesTest {

    // -----------------------------------------------------------------------
    // TlsPhase ordinal stability
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TlsPhase ordinal stability — regression shield for CAS bitmask tables")
    class TlsPhaseOrdinalStability {

        @Test
        @DisplayName("UNINITIALIZED ordinal == 0")
        void uninitializedOrdinal() {
            assertThat(TlsPhase.UNINITIALIZED.ordinal()).isZero();
        }

        @Test
        @DisplayName("HANDSHAKE_IN_PROGRESS ordinal == 1")
        void handshakeInProgressOrdinal() {
            assertThat(TlsPhase.HANDSHAKE_IN_PROGRESS.ordinal()).isEqualTo(1);
        }

        @Test
        @DisplayName("HANDSHAKE_COMPLETE ordinal == 2")
        void handshakeCompleteOrdinal() {
            assertThat(TlsPhase.HANDSHAKE_COMPLETE.ordinal()).isEqualTo(2);
        }

        @Test
        @DisplayName("ACTIVE ordinal == 3")
        void activeOrdinal() {
            assertThat(TlsPhase.ACTIVE.ordinal()).isEqualTo(3);
        }

        @Test
        @DisplayName("SHUTDOWN_INITIATED ordinal == 4")
        void shutdownInitiatedOrdinal() {
            assertThat(TlsPhase.SHUTDOWN_INITIATED.ordinal()).isEqualTo(4);
        }

        @Test
        @DisplayName("SHUTDOWN_COMPLETE ordinal == 5")
        void shutdownCompleteOrdinal() {
            assertThat(TlsPhase.SHUTDOWN_COMPLETE.ordinal()).isEqualTo(5);
        }

        @Test
        @DisplayName("CLOSED ordinal == 6")
        void closedOrdinal() {
            assertThat(TlsPhase.CLOSED.ordinal()).isEqualTo(6);
        }

        @Test
        @DisplayName("ERROR ordinal == 7")
        void errorOrdinal() {
            assertThat(TlsPhase.ERROR.ordinal()).isEqualTo(7);
        }

        @Test
        @DisplayName("Total TlsPhase count == 8 — new phases require TlsStateMachine review")
        void totalPhaseCount() {
            assertThat(TlsPhase.values()).hasSize(8);
        }

        @Test
        @DisplayName("Terminal phases: only CLOSED and ERROR are terminal")
        void terminalPhases() {
            assertThat(TlsPhase.CLOSED.isTerminal()).isTrue();
            assertThat(TlsPhase.ERROR.isTerminal()).isTrue();
            assertThat(TlsPhase.ACTIVE.isTerminal()).isFalse();
            assertThat(TlsPhase.HANDSHAKE_COMPLETE.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("isHandshaking() — only HANDSHAKE_IN_PROGRESS returns true")
        void handshakingPhaseOnly() {
            assertThat(TlsPhase.HANDSHAKE_IN_PROGRESS.isHandshaking()).isTrue();
            assertThat(TlsPhase.HANDSHAKE_COMPLETE.isHandshaking()).isFalse();
            assertThat(TlsPhase.ACTIVE.isHandshaking()).isFalse();
        }

        @Test
        @DisplayName("canTransferData() — only ACTIVE returns true")
        void dataTransferOnlyInActive() {
            assertThat(TlsPhase.ACTIVE.canTransferData()).isTrue();
            assertThat(TlsPhase.HANDSHAKE_COMPLETE.canTransferData()).isFalse();
            assertThat(TlsPhase.CLOSED.canTransferData()).isFalse();
        }

        @Test
        @DisplayName("isShuttingDown() — SHUTDOWN_INITIATED and SHUTDOWN_COMPLETE")
        void shuttingDownPhases() {
            assertThat(TlsPhase.SHUTDOWN_INITIATED.isShuttingDown()).isTrue();
            assertThat(TlsPhase.SHUTDOWN_COMPLETE.isShuttingDown()).isTrue();
            assertThat(TlsPhase.ACTIVE.isShuttingDown()).isFalse();
        }

        @Test
        @DisplayName("description() is non-null and non-blank for every phase")
        void descriptionNonBlank() {
            for (TlsPhase phase : TlsPhase.values()) {
                assertThat(phase.description())
                        .as("description() must be non-blank for %s", phase)
                        .isNotBlank();
            }
        }
    }

    // -----------------------------------------------------------------------
    // TlsStatus completeness
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TlsStatus completeness — all expected values present")
    class TlsStatusCompleteness {

        @Test
        @DisplayName("TlsStatus has exactly 6 values")
        void statusCount() {
            assertThat(TlsStatus.values()).hasSize(6);
        }

        @Test
        @DisplayName("TlsStatus contains OK, NEED_WRAP, NEED_UNWRAP, NEED_HANDSHAKE, FINISHED, CLOSED")
        void statusNames() {
            assertThat(TlsStatus.values()).extracting(Enum::name)
                    .containsExactlyInAnyOrder(
                            "OK", "NEED_WRAP", "NEED_UNWRAP",
                            "NEED_HANDSHAKE", "FINISHED", "CLOSED");
        }
    }

    // -----------------------------------------------------------------------
    // TlsHandshakeResult — singletons and Valhalla-readiness
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TlsHandshakeResult — singletons + value record semantics")
    class TlsHandshakeResultTest {

        @Test
        @DisplayName("COMPLETE.isComplete() == true, all others false")
        void completePredicates() {
            assertThat(TlsHandshakeResult.COMPLETE.isComplete()).isTrue();
            assertThat(TlsHandshakeResult.COMPLETE.needsUnwrap()).isFalse();
            assertThat(TlsHandshakeResult.COMPLETE.needsWrap()).isFalse();
            assertThat(TlsHandshakeResult.COMPLETE.isError()).isFalse();
            assertThat(TlsHandshakeResult.COMPLETE.nativeErrorCode()).isZero();
        }

        @Test
        @DisplayName("NEED_UNWRAP.needsUnwrap() == true, others false")
        void needUnwrapPredicates() {
            assertThat(TlsHandshakeResult.NEED_UNWRAP.needsUnwrap()).isTrue();
            assertThat(TlsHandshakeResult.NEED_UNWRAP.isComplete()).isFalse();
            assertThat(TlsHandshakeResult.NEED_UNWRAP.isError()).isFalse();
        }

        @Test
        @DisplayName("NEED_WRAP.needsWrap() == true, others false")
        void needWrapPredicates() {
            assertThat(TlsHandshakeResult.NEED_WRAP.needsWrap()).isTrue();
            assertThat(TlsHandshakeResult.NEED_WRAP.isComplete()).isFalse();
            assertThat(TlsHandshakeResult.NEED_WRAP.isError()).isFalse();
        }

        @Test
        @DisplayName("error(5).isError() == true, nativeErrorCode == 5")
        void errorFactory() {
            TlsHandshakeResult err = TlsHandshakeResult.error(5);
            assertThat(err.isError()).isTrue();
            assertThat(err.nativeErrorCode()).isEqualTo(5);
            assertThat(err.isComplete()).isFalse();
        }

        @Test
        @DisplayName("COMPLETE is pre-allocated — same reference on every access")
        void completeSingletonIdentity() {
            TlsHandshakeResult first = TlsHandshakeResult.COMPLETE;
            TlsHandshakeResult second = TlsHandshakeResult.COMPLETE;
            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("Structural equals: two error(42) results are equal")
        void structuralEqualityForError() {
            TlsHandshakeResult a = TlsHandshakeResult.error(42);
            TlsHandshakeResult b = TlsHandshakeResult.error(42);
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Structural hashCode: two error(42) results have equal hashCode")
        void structuralHashCode() {
            TlsHandshakeResult a = TlsHandshakeResult.error(42);
            TlsHandshakeResult b = TlsHandshakeResult.error(42);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("COMPLETE != NEED_WRAP — structural inequality preserved")
        void differentStatusNotEqual() {
            assertThat(TlsHandshakeResult.COMPLETE)
                    .isNotEqualTo(TlsHandshakeResult.NEED_WRAP);
        }
    }

    // -----------------------------------------------------------------------
    // TlsShutdownResult — singletons and Valhalla-readiness
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TlsShutdownResult — singletons + value record semantics")
    class TlsShutdownResultTest {

        @Test
        @DisplayName("COMPLETE: isComplete=true, sent=true, received=true, needsMoreIo=false")
        void complete() {
            assertThat(TlsShutdownResult.COMPLETE.isComplete()).isTrue();
            assertThat(TlsShutdownResult.COMPLETE.sentCloseNotify()).isTrue();
            assertThat(TlsShutdownResult.COMPLETE.receivedCloseNotify()).isTrue();
            assertThat(TlsShutdownResult.COMPLETE.needsMoreIo()).isFalse();
            assertThat(TlsShutdownResult.COMPLETE.isError()).isFalse();
        }

        @Test
        @DisplayName("ERROR: isError=true, isComplete=false, sent=false, received=false")
        void error() {
            assertThat(TlsShutdownResult.ERROR.isError()).isTrue();
            assertThat(TlsShutdownResult.ERROR.isComplete()).isFalse();
            assertThat(TlsShutdownResult.ERROR.sentCloseNotify()).isFalse();
            assertThat(TlsShutdownResult.ERROR.receivedCloseNotify()).isFalse();
        }

        @Test
        @DisplayName("partial(true, false): needsMoreIo=true, sent=true, received=false")
        void partialFactory() {
            TlsShutdownResult r = TlsShutdownResult.partial(true, false);
            assertThat(r.needsMoreIo()).isTrue();
            assertThat(r.sentCloseNotify()).isTrue();
            assertThat(r.receivedCloseNotify()).isFalse();
            assertThat(r.isComplete()).isFalse();
        }

        @Test
        @DisplayName("COMPLETE is pre-allocated singleton — same reference")
        void completeSingleton() {
            TlsShutdownResult first = TlsShutdownResult.COMPLETE;
            TlsShutdownResult second = TlsShutdownResult.COMPLETE;
            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("ERROR is pre-allocated singleton — same reference")
        void errorSingleton() {
            TlsShutdownResult first = TlsShutdownResult.ERROR;
            TlsShutdownResult second = TlsShutdownResult.ERROR;
            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("Structural equals: two partial(true,false) are equal")
        void structuralEquality() {
            TlsShutdownResult a = TlsShutdownResult.partial(true, false);
            TlsShutdownResult b = TlsShutdownResult.partial(true, false);
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Structural hashCode: two partial(true,false) have equal hashCode")
        void structuralHashCode() {
            TlsShutdownResult a = TlsShutdownResult.partial(true, false);
            TlsShutdownResult b = TlsShutdownResult.partial(true, false);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("partial(true,false) != partial(false,true) — field differences matter")
        void differentPartialNotEqual() {
            TlsShutdownResult a = TlsShutdownResult.partial(true, false);
            TlsShutdownResult b = TlsShutdownResult.partial(false, true);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("COMPLETE != ERROR — singletons are not equal to each other")
        void completeDifferentFromError() {
            assertThat(TlsShutdownResult.COMPLETE).isNotEqualTo(TlsShutdownResult.ERROR);
        }
    }
}

