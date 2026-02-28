/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions;

import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.exceptions.memory.MemoryBootstrapException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: {@link ExerisKernelException} base binary telemetry contract.
 *
 * <h2>What This Proves</h2>
 * <p>This test suite is the "Law" for the Exeris Kernel exception hierarchy.
 * It verifies that:
 * <ol>
 *   <li>All exceptions are unchecked (extend {@code RuntimeException})</li>
 *   <li>{@code rawArgs()} never returns {@code null}</li>
 *   <li>{@code errorCode()} is always non-null and uses the {@code EX-[DOMAIN]-[ID]} format</li>
 *   <li>{@code traceId()} is unique per throw site (critical for distributed forensics)</li>
 *   <li>{@code timestamp()} is non-null and not in the future</li>
 *   <li>Messages are static strings — never re-allocated via {@code String.format()} on the hot path</li>
 * </ol>
 *
 * <h2>Zero-Magic Guard</h2>
 * <p>No Mockito, no reflection, no DI. All instances are constructed directly.
 *
 * @see ExerisKernelException
 * @since 0.5.0
 */
@DisplayName("L0: ExerisKernelException — Base Binary Contract")
class ExerisKernelExceptionContractTest {

    // -----------------------------------------------------------------------
    // 1. Inheritance — unchecked, RuntimeException
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("1. Inheritance — always unchecked (RuntimeException)")
    class InheritanceContract {

        @Test
        @DisplayName("MemoryExhaustedException is RuntimeException")
        void memoryExhaustedIsUnchecked() {
            assertThat(new MemoryExhaustedException(1L, 0L, null))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("MemoryBootstrapException is RuntimeException")
        void memoryBootstrapIsUnchecked() {
            assertThat(new MemoryBootstrapException("P", null))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("TlsHandshakeException is RuntimeException")
        void tlsHandshakeIsUnchecked() {
            assertThat(new TlsHandshakeException("tls error"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // -----------------------------------------------------------------------
    // 2. rawArgs() contract — never null, array reference is stable
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("2. rawArgs() contract — never null, zero-copy reference")
    class RawArgsBaseContract {

        @Test
        @DisplayName("rawArgs() never returns null — even on zero-arg subclasses")
        void rawArgsNeverNull() {
            ExerisKernelException ex = new MemoryExhaustedException(1L, 0L, null);
            assertThat(ex.rawArgs()).isNotNull();
        }

        @Test
        @DisplayName("rawArgs() returns the same array reference on every call — no defensive copy")
        void rawArgsSameReference() {
            ExerisKernelException ex = new MemoryExhaustedException(4096L, 512L, null);
            assertThat(ex.rawArgs()).isSameAs(ex.rawArgs());
        }

        @Test
        @DisplayName("rawArgs length is fixed at construction — array cannot be extended")
        void rawArgsLengthIsFixed() {
            ExerisKernelException ex = new MemoryExhaustedException(4096L, 512L, null);
            assertThat(ex.rawArgs()).hasSize(2);
        }
    }

    // -----------------------------------------------------------------------
    // 3. errorCode() contract — format enforcement
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("3. errorCode() format — EX-[DOMAIN]-[ID]")
    class ErrorCodeFormatContract {

        @Test
        @DisplayName("errorCode() is never null")
        void errorCodeNeverNull() {
            ExerisKernelException ex = new MemoryExhaustedException(1L, 0L, null);
            assertThat(ex.errorCode()).isNotNull();
        }

        @Test
        @DisplayName("errorCode() always starts with 'EX-'")
        void errorCodeStartsWithPrefix() {
            ExerisKernelException ex = new MemoryExhaustedException(1L, 0L, null);
            assertThat(ex.errorCode()).startsWith("EX-");
        }

        @Test
        @DisplayName("errorCode() matches EX-[A-Z]+-[0-9]+ pattern")
        void errorCodeMatchesPattern() {
            ExerisKernelException ex = new MemoryExhaustedException(1L, 0L, null);
            assertThat(ex.errorCode()).matches("EX-[A-Z]+-\\d+");
        }

        @Test
        @DisplayName("errorCode() is non-blank")
        void errorCodeNonBlank() {
            ExerisKernelException ex = new MemoryBootstrapException("P", null);
            assertThat(ex.errorCode()).isNotBlank();
        }
    }

    // -----------------------------------------------------------------------
    // 4. traceId() contract — uniqueness for distributed forensics
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("4. traceId() uniqueness — distributed forensics contract")
    class TraceIdContract {

        @Test
        @DisplayName("traceId() is never null")
        void traceIdNeverNull() {
            ExerisKernelException ex = new MemoryExhaustedException(1L, 0L, null);
            assertThat(ex.traceId()).isNotNull();
        }

        @Test
        @DisplayName("Two exceptions at the same throw site have distinct traceIds")
        void traceIdsAreUnique() {
            ExerisKernelException a = new MemoryExhaustedException(1L, 0L, null);
            ExerisKernelException b = new MemoryExhaustedException(1L, 0L, null);
            assertThat(a.traceId()).isNotEqualTo(b.traceId());
        }

        @Test
        @DisplayName("traceId() returns the same UUID on repeated calls — stable reference")
        void traceIdIsStable() {
            ExerisKernelException ex = new MemoryExhaustedException(1L, 0L, null);
            assertThat(ex.traceId()).isSameAs(ex.traceId());
        }
    }

    // -----------------------------------------------------------------------
    // 5. timestamp() contract
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("5. timestamp() — non-null, in the past or present")
    class TimestampContract {

        @Test
        @DisplayName("timestamp() is never null")
        void timestampNeverNull() {
            ExerisKernelException ex = new MemoryExhaustedException(1L, 0L, null);
            assertThat(ex.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("timestamp() is not in the future")
        void timestampNotInFuture() {
            ExerisKernelException ex = new MemoryExhaustedException(1L, 0L, null);
            assertThat(ex.timestamp()).isBeforeOrEqualTo(java.time.Instant.now());
        }
    }

    // -----------------------------------------------------------------------
    // 6. getMessage() — static string, same reference per call
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("6. getMessage() — static string template, no runtime allocation")
    class MessageContract {

        @Test
        @DisplayName("getMessage() returns the same String reference on every call — no String.format()")
        void messageSameReference() {
            ExerisKernelException ex = new MemoryExhaustedException(4096L, 512L, null);
            assertThat(ex.getMessage()).isSameAs(ex.getMessage());
        }

        @Test
        @DisplayName("getMessage() is non-null")
        void messageNonNull() {
            ExerisKernelException ex = new MemoryExhaustedException(1L, 0L, null);
            assertThat(ex.getMessage()).isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // 7. Cause chain — preserved exactly
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("7. Cause chain — preserved exactly as supplied")
    class CauseContract {

        @Test
        @DisplayName("Cause is preserved by reference — no wrapping")
        void causePreservedByReference() {
            OutOfMemoryError root = new OutOfMemoryError("native");
            ExerisKernelException ex = new MemoryExhaustedException(8192L, 0L, root);
            assertThat(ex.getCause()).isSameAs(root);
        }

        @Test
        @DisplayName("Null cause is accepted — no NullPointerException")
        void nullCauseAccepted() {
            ExerisKernelException ex = new MemoryExhaustedException(1L, 0L, null);
            assertThat(ex.getCause()).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // 8. errorCode() must not be null — NPE documented by contract
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("8. Null errorCode guard — NPE on construction")
    class NullErrorCodeGuard {

        /**
         * A minimal concrete subclass to test the null-errorCode guard directly
         * against the base constructor without relying on specific subclasses.
         * This anonymous class is the SPI-internal proof — no Mockito needed.
         */
        @Test
        @DisplayName("null errorCode throws NullPointerException at construction time")
        void nullErrorCodeThrowsNpe() {
            assertThatThrownBy(() -> new ExerisKernelException(null, "msg", (Throwable) null) {})
                    .isInstanceOf(NullPointerException.class);
        }
    }
}

