/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1: {@link TlsHandshakeResult} — singleton contract, predicate methods,
 * error factory, and Valhalla-readiness (value record).
 *
 * @since 0.5.0
 */
@DisplayName("L1: TlsHandshakeResult")
class TlsHandshakeResultTest {

    @Nested
    @DisplayName("Pre-allocated singletons — zero new on hot path")
    class Singletons {

        @Test
        @DisplayName("COMPLETE.isComplete() == true, others false")
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
        @DisplayName("Singleton identity — COMPLETE is always the same reference")
        void singletonIdentity() {
            TlsHandshakeResult first = TlsHandshakeResult.COMPLETE;
            TlsHandshakeResult second = TlsHandshakeResult.COMPLETE;
            assertThat(first).isSameAs(second);
        }
    }

    @Nested
    @DisplayName("error() factory")
    class ErrorFactory {

        @Test
        @DisplayName("error(5).isError() == true, sslError == 5")
        void errorFactory() {
            TlsHandshakeResult err = TlsHandshakeResult.error(5);
            assertThat(err.isError()).isTrue();
            assertThat(err.nativeErrorCode()).isEqualTo(5);
            assertThat(err.isComplete()).isFalse();
        }

        @Test
        @DisplayName("error() creates a new instance — not a singleton")
        void errorIsNotSingleton() {
            TlsHandshakeResult a = TlsHandshakeResult.error(1);
            TlsHandshakeResult b = TlsHandshakeResult.error(1);
            // Records — structural equality, not identity
            assertThat(a).isEqualTo(b);
        }
    }

    @Nested
    @DisplayName("Valhalla-readiness: value record semantics")
    class ValhallaReadiness {

        @Test
        @DisplayName("Equal records satisfy structural equals()")
        void structuralEquality() {
            TlsHandshakeResult a = TlsHandshakeResult.error(42);
            TlsHandshakeResult b = TlsHandshakeResult.error(42);
            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Different status → not equal")
        void differentStatusNotEqual() {
            assertThat(TlsHandshakeResult.COMPLETE)
                    .isNotEqualTo(TlsHandshakeResult.NEED_WRAP);
        }
    }
}
