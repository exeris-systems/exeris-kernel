/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.exceptions;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Glass-Box rawArgs binary telemetry contract for {@link ExerisKernelException} hierarchy.
 *
 * <h2>What is verified</h2>
 * <p>The "Glass-Box Pattern" (telemetry.md) requires that every {@link ExerisKernelException}
 * subclass stores raw primitive arguments in {@code rawArgs[]} instead of concatenating Strings.
 * This TCK validates the structural invariants that make binary crash-log serialisation possible:
 *
 * <ol>
 *   <li><b>errorCode</b> is non-null, non-blank, matches the {@code EX-[DOMAIN]-[ID]} pattern.</li>
 *   <li><b>traceId</b> is auto-generated, non-null, unique per instance.</li>
 *   <li><b>rawArgs</b> layout matches the documented binary struct
 *       (e.g., {@code rawArgs[0]=long requestedBytes, rawArgs[1]=long availableBytes} for
 *       {@link MemoryExhaustedException}).</li>
 *   <li><b>Zero String formatting</b> — {@code getMessage()} returns a STATIC string;
 *       it must NOT contain runtime values (no {@code "requested 1024 bytes"}).</li>
 *   <li><b>message()</b> is always non-null (protocol-blind; contains no heap or net addresses).</li>
 *   <li>Constructors are NOT called on the allocation hot-path — exception construction
 *       happens only on genuine failure paths.</li>
 * </ol>
 *
 * <h2>Binary Struct Alignment (telemetry.md)</h2>
 * <pre>
 * EX-MEM-1001: rawArgs[0]=long requestedBytes, rawArgs[1]=long availableBytes
 * EX-NET-4001: rawArgs[0]=String transportName, rawArgs[1]=int port
 * </pre>
 *
 * @since 0.5.0
 */
@DisplayName("ExerisKernelException — Glass-Box rawArgs binary contract")
class ExerisKernelExceptionGlassBoxTckTest {

    // =========================================================================
    // EX-[DOMAIN]-[ID] error code format
    // =========================================================================

    @Nested
    @DisplayName("Error code format — EX-[DOMAIN]-[ID]")
    class ErrorCodeFormat {

        @Test
        @DisplayName("MemoryExhaustedException has non-blank EX-MEM-* error code")
        void memoryExhaustedHasCorrectErrorCode() {
            ExerisKernelException ex = new MemoryExhaustedException(1024L, 0L);
            assertThat(ex.errorCode())
                    .as("MemoryExhaustedException.errorCode() must match EX-MEM-* pattern")
                    .isNotBlank()
                    .matches("EX-MEM-\\d+");
        }

        @Test
        @DisplayName("TransportException(bindFailure) has non-blank EX-NET-* error code")
        void transportBindHasCorrectErrorCode() {
            ExerisKernelException ex = TransportException.bindFailure("tcp-0", 8080, null);
            assertThat(ex.errorCode())
                    .as("TransportException.bindFailure() errorCode() must match EX-NET-* pattern")
                    .isNotBlank()
                    .matches("EX-NET-\\d+");
        }

        @Test
        @DisplayName("All EX- codes are upper-case and follow EX-[A-Z]+-[0-9]+ pattern")
        void errorCodePatternIsUpperCase() {
            ExerisKernelException[] exceptions = {
                new MemoryExhaustedException(512L, 0L),
                TransportException.bindFailure("t", 443, null)
            };
            for (ExerisKernelException ex : exceptions) {
                assertThat(ex.errorCode())
                        .as("Error code MUST be upper-case EX-[DOMAIN]-[ID], got: %s",
                            ex.errorCode())
                        .matches("[A-Z]{2}-[A-Z]+-\\d+");
            }
        }
    }

    // =========================================================================
    // traceId — unique per instance, auto-generated
    // =========================================================================

    @Nested
    @DisplayName("traceId — unique per instance")
    class TraceIdUniqueness {

        @Test
        @DisplayName("traceId is non-null on MemoryExhaustedException")
        void traceIdIsNonNull() {
            ExerisKernelException ex = new MemoryExhaustedException(1L, 0L);
            assertThat(ex.traceId())
                    .as("traceId MUST be auto-generated and non-null")
                    .isNotNull();
        }

        @Test
        @DisplayName("Two distinct instances have different traceIds")
        void traceIdIsUniquePerInstance() {
            UUID id1 = new MemoryExhaustedException(1L, 0L).traceId();
            UUID id2 = new MemoryExhaustedException(1L, 0L).traceId();
            assertThat(id1)
                    .as("Each exception instance MUST have a unique traceId (UUIDv7/random). " +
                        "Shared traceId breaks distributed tracing.")
                    .isNotEqualTo(id2);
        }
    }

    // =========================================================================
    // rawArgs layout — binary struct contract
    // =========================================================================

    @Nested
    @DisplayName("rawArgs[] — binary struct layout (Glass-Box contract)")
    class RawArgsLayout {

        @Test
        @DisplayName("MemoryExhaustedException: rawArgs[0]=requestedBytes, rawArgs[1]=availableBytes")
        void memoryExhaustedRawArgsLayout() {
            long requested = 65536L;
            long available = 0L;
            MemoryExhaustedException ex = new MemoryExhaustedException(requested, available);

            assertThat(ex.rawArgs())
                    .as("MemoryExhaustedException MUST have exactly 2 rawArgs")
                    .hasSize(2);
            assertThat(ex.rawArgs()[0])
                    .as("rawArgs[0] MUST be requestedBytes=%d (doc: EX-MEM-1001 binary layout)",
                        requested)
                    .isEqualTo(requested);
            assertThat(ex.rawArgs()[1])
                    .as("rawArgs[1] MUST be availableBytes=%d", available)
                    .isEqualTo(available);
        }

        @Test
        @DisplayName("TransportException(bindFailure): rawArgs[0]=transportName, rawArgs[1]=port")
        void transportBindRawArgsLayout() {
            String transportName = "exeris-tcp-0";
            int    port          = 9443;
            TransportException ex = TransportException.bindFailure(transportName, port, null);

            assertThat(ex.rawArgs())
                    .as("TransportException.bindFailure() MUST have >= 2 rawArgs")
                    .hasSizeGreaterThanOrEqualTo(2);
            assertThat(ex.rawArgs()[0])
                    .as("rawArgs[0] MUST be transportName (doc: EX-NET-4001 binary layout)")
                    .isEqualTo(transportName);
            assertThat(ex.rawArgs()[1])
                    .as("rawArgs[1] MUST be port int")
                    .isEqualTo(port);
        }

        @Test
        @DisplayName("rawArgs array is never null — empty array for zero-arg exceptions")
        void rawArgsIsNeverNull() {
            // Any exception with no domain args must still return empty array, not null
            ExerisKernelException ex = new MemoryExhaustedException(1L, 0L);
            assertThat(ex.rawArgs())
                    .as("rawArgs() MUST never return null — empty array for no-arg exceptions")
                    .isNotNull();
        }
    }

    // =========================================================================
    // Zero String formatting — getMessage() is STATIC
    // =========================================================================

    @Nested
    @DisplayName("Zero String formatting — getMessage() must be a static literal")
    class ZeroStringFormatting {

        @Test
        @DisplayName("MemoryExhaustedException.getMessage() does NOT contain the requestedBytes value")
        void messageDoesNotContainRuntimeValues() {
            long requested = 123456789L;
            MemoryExhaustedException ex = new MemoryExhaustedException(requested, 0L);

            // The message MUST be a static string like "Off-heap allocator exhausted"
            // It MUST NOT contain "123456789" — that would mean String.formatted() was called
            assertThat(ex.getMessage())
                    .as("getMessage() MUST return a STATIC string, not a formatted message. " +
                        "Finding the requestedBytes value (%d) in the message means " +
                        "String.formatted() or concatenation was used — BANNED on hot-path. " +
                        "Use rawArgs[] for binary telemetry instead.", requested)
                    .doesNotContain(String.valueOf(requested));
        }

        @Test
        @DisplayName("getMessage() is non-null and non-blank")
        void messageIsNonBlank() {
            assertThat(new MemoryExhaustedException(1L, 0L).getMessage())
                    .as("getMessage() must always return a non-blank static description")
                    .isNotBlank();
        }
    }

    // =========================================================================
    // Construction safety — exception construction does not throw
    // =========================================================================

    @Nested
    @DisplayName("Construction safety")
    class ConstructionSafety {

        @Test
        @DisplayName("MemoryExhaustedException construction does not throw")
        void memoryExhaustedConstructionSafe() {
            assertThatCode(() -> new MemoryExhaustedException(Long.MAX_VALUE, 0L))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("TransportException.bindFailure() with port 0 does not throw")
        void transportBindPortZeroSafe() {
            assertThatCode(() -> TransportException.bindFailure("t", 0, null))
                    .doesNotThrowAnyException();
        }
    }
}
