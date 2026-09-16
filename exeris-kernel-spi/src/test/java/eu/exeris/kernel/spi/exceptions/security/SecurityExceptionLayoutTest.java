/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.security;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: Security exception rawArgs binary layouts.
 *
 * <h2>PrincipalContextMissingException</h2>
 * <p>No rawArgs — pure "missing context" signal. Verifies EX-SEC-2001.
 *
 * <h2>StorageContextMissingException</h2>
 * <p>No rawArgs — pure "missing context" signal. Verifies EX-SEC-2004.
 *
 * <h2>SecurityAuthenticationException rawArgs (EX-SEC-2002)</h2>
 * <pre>
 * index 0 → String  tokenType     (e.g. "JWT", "OPAQUE")
 * index 1 → String  failureReason (e.g. "expired", "malformed", "revoked")
 * </pre>
 *
 * @since 0.5.0
 */
@DisplayName("L0: Security Exception rawArgs Binary Layouts")
class SecurityExceptionLayoutTest {

    // -----------------------------------------------------------------------
    // PrincipalContextMissingException — EX-SEC-2001
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("PrincipalContextMissingException (EX-SEC-2001)")
    class PrincipalContextMissing {

        @Test
        @DisplayName("errorCode() == EX-SEC-2001")
        void errorCodePinned() {
            PrincipalContextMissingException ex = new PrincipalContextMissingException();
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2001);
        }

        @Test
        @DisplayName("rawArgs() is non-null (empty array — no domain context)")
        void rawArgsIsNonNull() {
            PrincipalContextMissingException ex = new PrincipalContextMissingException();
            assertThat(ex.rawArgs()).isNotNull();
        }

        @Test
        @DisplayName("getMessage() is non-null and describes the missing scope")
        void messageIsDescriptive() {
            PrincipalContextMissingException ex = new PrincipalContextMissingException();
            assertThat(ex.getMessage()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("getCause() is null — always a programming error, not a system fault")
        void noCause() {
            assertThat(new PrincipalContextMissingException().getCause()).isNull();
        }

        @Test
        @DisplayName("Is unchecked (RuntimeException)")
        void isUnchecked() {
            assertThat(new PrincipalContextMissingException())
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // -----------------------------------------------------------------------
    // StorageContextMissingException — EX-SEC-2004
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("StorageContextMissingException (EX-SEC-2004)")
    class StorageContextMissing {

        @Test
        @DisplayName("errorCode() == EX-SEC-2004")
        void errorCodePinned() {
            StorageContextMissingException ex = new StorageContextMissingException();
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2004);
        }

        @Test
        @DisplayName("rawArgs() is non-null")
        void rawArgsIsNonNull() {
            assertThat(new StorageContextMissingException().rawArgs()).isNotNull();
        }

        @Test
        @DisplayName("Is unchecked (RuntimeException)")
        void isUnchecked() {
            assertThat(new StorageContextMissingException())
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // -----------------------------------------------------------------------
    // SecurityAuthenticationException — EX-SEC-2002
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("SecurityAuthenticationException (EX-SEC-2002)")
    class SecurityAuthentication {

        @Test
        @DisplayName("rawArgs[0]=tokenType (String), [1]=failureReason (String)")
        void rawArgsLayout() {
            SecurityAuthenticationException ex = new SecurityAuthenticationException(
                    "JWT", "expired");

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("JWT");
            assertThat(raw[1]).isEqualTo("expired");
        }

        @Test
        @DisplayName("Three-arg constructor preserves cause and rawArgs layout")
        void threeArgConstructor() {
            RuntimeException root = new RuntimeException("sig invalid");
            SecurityAuthenticationException ex = new SecurityAuthenticationException(
                    "OPAQUE", "malformed", root);

            assertThat(ex.getCause()).isSameAs(root);
            assertThat(ex.rawArgs()[0]).isEqualTo("OPAQUE");
            assertThat(ex.rawArgs()[1]).isEqualTo("malformed");
        }

        @Test
        @DisplayName("Two-arg constructor: getCause() is null")
        void twoArgNoCause() {
            SecurityAuthenticationException ex = new SecurityAuthenticationException("JWT", "revoked");
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("Is unchecked (RuntimeException)")
        void isUnchecked() {
            assertThat(new SecurityAuthenticationException("JWT", "expired"))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}

