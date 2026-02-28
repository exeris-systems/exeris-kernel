/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: {@link SubsystemException} rawArgs binary layout.
 *
 * <h2>Binary Layout Pinned</h2>
 * <pre>
 * index 0 → String  subsystemName
 * index 1 → Phase   phase           (INITIALIZE | START | STOP)
 * index 2 → String  detailMessage
 * </pre>
 *
 * <h2>Phase Ordinal Stability</h2>
 * <p>The {@link SubsystemException.Phase} enum ordinals are pinned.
 * Reordering silently corrupts the CAS bitmask tables in the bootstrap orchestrator.
 *
 * @see SubsystemException
 * @since 0.5.0
 */
@DisplayName("L0: SubsystemException — rawArgs Binary Layout + Phase Ordinals")
class SubsystemExceptionTest {

    // -----------------------------------------------------------------------
    // 1. rawArgs binary layout
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("1. rawArgs binary layout — exact slot offsets")
    class RawArgsLayout {

        @Test
        @DisplayName("rawArgs[0]=subsystemName, [1]=phase(INITIALIZE), [2]=detail — no-cause ctor")
        void layoutNoCause() {
            SubsystemException ex = new SubsystemException(
                    "MemorySubsystem", SubsystemException.Phase.INITIALIZE, "allocator failed");

            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(3);
            assertThat(raw[0]).isEqualTo("MemorySubsystem");
            assertThat(raw[1]).isEqualTo(SubsystemException.Phase.INITIALIZE);
            assertThat(raw[2]).isEqualTo("allocator failed");
        }

        @Test
        @DisplayName("rawArgs[0]=subsystemName, [1]=phase(START), [2]=detail — cause ctor preserves layout")
        void layoutWithCause() {
            RuntimeException root = new RuntimeException("OpenSSL load failed");
            SubsystemException ex = new SubsystemException(
                    "TlsSubsystem", SubsystemException.Phase.START, "OpenSSL load failed", root);

            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(3);
            assertThat(raw[0]).isEqualTo("TlsSubsystem");
            assertThat(raw[1]).isEqualTo(SubsystemException.Phase.START);
            assertThat(raw[2]).isEqualTo("OpenSSL load failed");
            assertThat(ex.getCause()).isSameAs(root);
        }

        @Test
        @DisplayName("rawArgs[1]=phase(STOP) — STOP phase is correctly captured")
        void layoutStopPhase() {
            SubsystemException ex = new SubsystemException(
                    "CryptoSubsystem", SubsystemException.Phase.STOP, "shutdown error");
            assertThat(ex.rawArgs()[1]).isEqualTo(SubsystemException.Phase.STOP);
        }
    }

    // -----------------------------------------------------------------------
    // 2. Typed accessors — must agree with rawArgs slots
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("2. Typed accessors — aliases for rawArgs slots")
    class TypedAccessors {

        @Test
        @DisplayName("subsystemName() == rawArgs()[0]")
        void subsystemNameMatchesRawArgs() {
            SubsystemException ex = new SubsystemException(
                    "TlsSubsystem", SubsystemException.Phase.START, "OpenSSL load failed");
            assertThat(ex.subsystemName()).isEqualTo("TlsSubsystem");
            assertThat(ex.subsystemName()).isEqualTo(ex.rawArgs()[0]);
        }

        @Test
        @DisplayName("phase() == rawArgs()[1]")
        void phaseMatchesRawArgs() {
            SubsystemException ex = new SubsystemException(
                    "X", SubsystemException.Phase.INITIALIZE, "d");
            assertThat(ex.phase()).isEqualTo(SubsystemException.Phase.INITIALIZE);
            assertThat(ex.phase()).isEqualTo(ex.rawArgs()[1]);
        }

        @Test
        @DisplayName("detail() == rawArgs()[2]")
        void detailMatchesRawArgs() {
            SubsystemException ex = new SubsystemException(
                    "X", SubsystemException.Phase.STOP, "detail text");
            assertThat(ex.detail()).isEqualTo("detail text");
            assertThat(ex.detail()).isEqualTo(ex.rawArgs()[2]);
        }
    }

    // -----------------------------------------------------------------------
    // 3. Phase enum ordinal stability — regression shield
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("3. Phase ordinal stability — regression shield for CAS bitmask tables")
    class PhaseOrdinalStability {

        @Test
        @DisplayName("Phase.INITIALIZE ordinal == 0")
        void initializeOrdinal() {
            assertThat(SubsystemException.Phase.INITIALIZE.ordinal()).isZero();
        }

        @Test
        @DisplayName("Phase.START ordinal == 1")
        void startOrdinal() {
            assertThat(SubsystemException.Phase.START.ordinal()).isEqualTo(1);
        }

        @Test
        @DisplayName("Phase.STOP ordinal == 2")
        void stopOrdinal() {
            assertThat(SubsystemException.Phase.STOP.ordinal()).isEqualTo(2);
        }

        @Test
        @DisplayName("Phase count == 3 — new phases require CAS table review")
        void phaseCount() {
            assertThat(SubsystemException.Phase.values()).hasSize(3);
        }

        @Test
        @DisplayName("Phase.valueOf() correctly resolves all names")
        void phaseValueOf() {
            assertThat(SubsystemException.Phase.valueOf("INITIALIZE"))
                    .isEqualTo(SubsystemException.Phase.INITIALIZE);
            assertThat(SubsystemException.Phase.valueOf("START"))
                    .isEqualTo(SubsystemException.Phase.START);
            assertThat(SubsystemException.Phase.valueOf("STOP"))
                    .isEqualTo(SubsystemException.Phase.STOP);
        }
    }

    // -----------------------------------------------------------------------
    // 4. Error code and inheritance
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("4. Error code and inheritance contract")
    class ErrorCodeAndInheritance {

        @Test
        @DisplayName("errorCode() == KernelErrorCodes.EX_BOOT_0002")
        void errorCodePinned() {
            SubsystemException ex = new SubsystemException(
                    "X", SubsystemException.Phase.INITIALIZE, "d");
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_BOOT_0002);
        }

        @Test
        @DisplayName("Is unchecked (RuntimeException)")
        void isUnchecked() {
            assertThat(new SubsystemException("X", SubsystemException.Phase.INITIALIZE, "d"))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("Cause is null when no-cause constructor is used")
        void noCauseIsNull() {
            SubsystemException ex = new SubsystemException(
                    "X", SubsystemException.Phase.INITIALIZE, "d");
            assertThat(ex.getCause()).isNull();
        }
    }
}

