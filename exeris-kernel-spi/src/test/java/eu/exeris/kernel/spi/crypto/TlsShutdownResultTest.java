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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1: {@link TlsShutdownResult} — singletons, partial factory, fromReturnValue mapping.
 *
 * @since 0.5.0
 */
@DisplayName("L1: TlsShutdownResult")
class TlsShutdownResultTest {

    @Nested
    @DisplayName("Singletons")
    class Singletons {

        @Test
        @DisplayName("COMPLETE: isComplete=true, sent=true, received=true")
        void complete() {
            assertThat(TlsShutdownResult.COMPLETE.isComplete()).isTrue();
            assertThat(TlsShutdownResult.COMPLETE.sentCloseNotify()).isTrue();
            assertThat(TlsShutdownResult.COMPLETE.receivedCloseNotify()).isTrue();
            assertThat(TlsShutdownResult.COMPLETE.needsMoreIo()).isFalse();
            assertThat(TlsShutdownResult.COMPLETE.isError()).isFalse();
        }

        @Test
        @DisplayName("ERROR: isError=true, sent=false, received=false")
        void error() {
            assertThat(TlsShutdownResult.ERROR.isError()).isTrue();
            assertThat(TlsShutdownResult.ERROR.isComplete()).isFalse();
            assertThat(TlsShutdownResult.ERROR.sentCloseNotify()).isFalse();
        }
    }

    @Nested
    @DisplayName("partial() factory")
    class PartialFactory {

        @Test
        @DisplayName("partial(true, false): needsMoreIo=true, sent=true, received=false")
        void partialSentNotReceived() {
            TlsShutdownResult r = TlsShutdownResult.partial(true, false);
            assertThat(r.needsMoreIo()).isTrue();
            assertThat(r.sentCloseNotify()).isTrue();
            assertThat(r.receivedCloseNotify()).isFalse();
            assertThat(r.isComplete()).isFalse();
        }
    }

    @Nested
    @DisplayName("Singleton and partial factory mapping")
    class FactoryMapping {

        @Test
        @DisplayName("COMPLETE is pre-allocated singleton")
        void completeSingleton() {
            TlsShutdownResult first = TlsShutdownResult.COMPLETE;
            TlsShutdownResult second = TlsShutdownResult.COMPLETE;
            assertThat(first).isSameAs(second);
            assertThat(TlsShutdownResult.COMPLETE.isComplete()).isTrue();
        }

        @Test
        @DisplayName("partial(true, false) → needsMoreIo, not complete")
        void partialSentOnly() {
            TlsShutdownResult r = TlsShutdownResult.partial(true, false);
            assertThat(r.needsMoreIo()).isTrue();
            assertThat(r.sentCloseNotify()).isTrue();
            assertThat(r.receivedCloseNotify()).isFalse();
            assertThat(r.isComplete()).isFalse();
        }

        @Test
        @DisplayName("ERROR is pre-allocated singleton")
        void errorSingleton() {
            TlsShutdownResult first = TlsShutdownResult.ERROR;
            TlsShutdownResult second = TlsShutdownResult.ERROR;
            assertThat(first).isSameAs(second);
            assertThat(TlsShutdownResult.ERROR.isError()).isTrue();
        }
    }

    @Nested
    @DisplayName("Valhalla-readiness")
    class ValhallaReadiness {

        /**
         * The {@code value} modifier is the only difference between this carrier on the two
         * distribution lines, and nothing else in the suite would notice if it were lost to a merge
         * or a reformat — the structural equality cases below pass for an identity record too. This
         * asserts the modifier itself, so the bytecode check that first proved it is repeatable
         * rather than a one-time inspection.
         */
        @Test
        @DisplayName("TlsShutdownResult is a value class on the preview line (JEP 401)")
        void isValueClass() {
            assertThat(TlsShutdownResult.class.isValue())
                    .as("TlsShutdownResult must carry the value modifier; ACC_IDENTITY must be clear")
                    .isTrue();
        }

        @Test
        @DisplayName("Equal partial results satisfy structural equals()")
        void equalPartials() {
            TlsShutdownResult a = TlsShutdownResult.partial(true, false);
            TlsShutdownResult b = TlsShutdownResult.partial(true, false);
            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }
    }
}

