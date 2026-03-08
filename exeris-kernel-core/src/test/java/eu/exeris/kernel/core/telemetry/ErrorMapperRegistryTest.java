/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit: {@link ErrorMapperRegistry} — full domain coverage and edge cases.
 *
 * @since 0.5.0
 */
@DisplayName("ErrorMapperRegistry — unit")
class ErrorMapperRegistryTest {

    // =========================================================================
    // Happy path — all known domain prefixes
    // =========================================================================

    @Nested
    @DisplayName("Domain mapping — happy path")
    class DomainMapping {

        @Test
        @DisplayName("EX-MEM-1001 (exhausted) → RESOURCE_EXHAUSTED")
        void memExhausted() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_MEM_1001))
                    .isEqualTo(TransportErrorCode.RESOURCE_EXHAUSTED);
        }

        @Test
        @DisplayName("EX-MEM-1002 (arena leak) → RESOURCE_EXHAUSTED")
        void memArenaLeak() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_MEM_1002))
                    .isEqualTo(TransportErrorCode.RESOURCE_EXHAUSTED);
        }

        @Test
        @DisplayName("EX-MEM-1003 (hint conflict) → INTERNAL_ERROR")
        void memHintConflict() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_MEM_1003))
                    .isEqualTo(TransportErrorCode.INTERNAL_ERROR);
        }

        @Test
        @DisplayName("EX-NET-4001 (bind failure) → BIND_FAILURE")
        void netBindFailure() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_NET_4001))
                    .isEqualTo(TransportErrorCode.BIND_FAILURE);
        }

        @Test
        @DisplayName("EX-NET-4002 (send failure) → SEND_FAILURE")
        void netSendFailure() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_NET_4002))
                    .isEqualTo(TransportErrorCode.SEND_FAILURE);
        }

        @Test
        @DisplayName("EX-NET-4003 (receive timeout) → RECEIVE_TIMEOUT")
        void netReceiveTimeout() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_NET_4003))
                    .isEqualTo(TransportErrorCode.RECEIVE_TIMEOUT);
        }

        @Test
        @DisplayName("EX-NET-4004 (bootstrap failure) → BOOTSTRAP_FAILURE")
        void netBootstrapFailure() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_NET_4004))
                    .isEqualTo(TransportErrorCode.BOOTSTRAP_FAILURE);
        }

        @Test
        @DisplayName("EX-NET-4005 (engine start) → BIND_FAILURE")
        void netEngineStart() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_NET_4005))
                    .isEqualTo(TransportErrorCode.BIND_FAILURE);
        }

        @Test
        @DisplayName("EX-SEC-2001 → SECURITY_VIOLATION")
        void secMissing() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_SEC_2001))
                    .isEqualTo(TransportErrorCode.SECURITY_VIOLATION);
        }

        @Test
        @DisplayName("EX-SEC-2002 → SECURITY_VIOLATION")
        void secTokenFailure() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_SEC_2002))
                    .isEqualTo(TransportErrorCode.SECURITY_VIOLATION);
        }

        @Test
        @DisplayName("EX-PERS-5001 → PERSISTENCE_FAILURE")
        void persBootstrap() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_PERS_5001))
                    .isEqualTo(TransportErrorCode.PERSISTENCE_FAILURE);
        }

        @Test
        @DisplayName("EX-PERS-5003 (query failure) → PERSISTENCE_FAILURE")
        void persQueryFailure() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_PERS_5003))
                    .isEqualTo(TransportErrorCode.PERSISTENCE_FAILURE);
        }

        @Test
        @DisplayName("EX-BOOT-0001 → BOOTSTRAP_FAILURE")
        void bootCircular() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_BOOT_0001))
                    .isEqualTo(TransportErrorCode.BOOTSTRAP_FAILURE);
        }

        @Test
        @DisplayName("EX-BOOT-0003 (deadline exceeded) → BOOTSTRAP_FAILURE")
        void bootDeadline() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_BOOT_0003))
                    .isEqualTo(TransportErrorCode.BOOTSTRAP_FAILURE);
        }

        @Test
        @DisplayName("EX-RUN-3002 (carrier pinned) → CARRIER_PINNED")
        void runCarrierPinned() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_RUN_3002))
                    .isEqualTo(TransportErrorCode.CARRIER_PINNED);
        }

        @Test
        @DisplayName("EX-GRPH-5001 (graph bootstrap) → INTERNAL_ERROR")
        void grphBootstrap() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_GRPH_5001))
                    .isEqualTo(TransportErrorCode.INTERNAL_ERROR);
        }

        @Test
        @DisplayName("EX-CFG-1001 (missing config key) → BOOTSTRAP_FAILURE")
        void cfgMissingKey() {
            assertThat(ErrorMapperRegistry.mapCode(KernelErrorCodes.EX_CFG_1001))
                    .isEqualTo(TransportErrorCode.BOOTSTRAP_FAILURE);
        }

        @ParameterizedTest(name = "EX-FLOW / EX-EVENT codes → INTERNAL_ERROR [{index}]")
        @CsvSource({
                "EX-FLOW-7001",
                "EX-FLOW-7002",
                "EX-EVENT-6001",
                "EX-EVENT-6002"
        })
        @DisplayName("EX-FLOW-* and EX-EVENT-* always map to INTERNAL_ERROR")
        void flowAndEventCodes(String code) {
            assertThat(ErrorMapperRegistry.mapCode(code))
                    .isEqualTo(TransportErrorCode.INTERNAL_ERROR);
        }
    }

    // =========================================================================
    // map(ExerisKernelException) delegation
    // =========================================================================

    @Nested
    @DisplayName("map(ExerisKernelException) delegation")
    class ExceptionDelegation {

        @Test
        @DisplayName("map(MemoryExhaustedException) → RESOURCE_EXHAUSTED")
        void mapMemoryException() {
            assertThat(ErrorMapperRegistry.map(new MemoryExhaustedException(1L, 0L, null)))
                    .isEqualTo(TransportErrorCode.RESOURCE_EXHAUSTED);
        }

        @Test
        @DisplayName("map(TransportException bindFailure) → BIND_FAILURE")
        void mapTransportBindException() {
            assertThat(ErrorMapperRegistry.map(TransportException.bindFailure("t", 80, null)))
                    .isEqualTo(TransportErrorCode.BIND_FAILURE);
        }

        @Test
        @DisplayName("map(TransportException sendFailure) → SEND_FAILURE")
        void mapTransportSendException() {
            assertThat(ErrorMapperRegistry.map(TransportException.sendFailure("t", 0L, null)))
                    .isEqualTo(TransportErrorCode.SEND_FAILURE);
        }

        @Test
        @DisplayName("map(TransportException receiveTimeout) → RECEIVE_TIMEOUT")
        void mapTransportTimeoutException() {
            assertThat(ErrorMapperRegistry.map(TransportException.receiveTimeout("t", 500L)))
                    .isEqualTo(TransportErrorCode.RECEIVE_TIMEOUT);
        }
    }

    // =========================================================================
    // Edge cases — null, short, unknown codes
    // =========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("null errorCode → INTERNAL_ERROR")
        void nullCode() {
            assertThat(ErrorMapperRegistry.mapCode(null))
                    .isEqualTo(TransportErrorCode.INTERNAL_ERROR);
        }

        @Test
        @DisplayName("blank / short code → INTERNAL_ERROR")
        void shortCode() {
            assertThat(ErrorMapperRegistry.mapCode("EX-X"))
                    .isEqualTo(TransportErrorCode.INTERNAL_ERROR);
        }

        @Test
        @DisplayName("unknown domain prefix → INTERNAL_ERROR")
        void unknownDomain() {
            assertThat(ErrorMapperRegistry.mapCode("EX-UNK-9999"))
                    .isEqualTo(TransportErrorCode.INTERNAL_ERROR);
        }

        @Test
        @DisplayName("every TransportErrorCode has a unique wireCode()")
        void wireCodesAreUnique() {
            TransportErrorCode[] values = TransportErrorCode.values();
            long distinctWireCodes = java.util.Arrays.stream(values)
                    .mapToInt(TransportErrorCode::wireCode)
                    .distinct()
                    .count();
            assertThat(distinctWireCodes)
                    .as("Every TransportErrorCode must have a unique wireCode()")
                    .isEqualTo(values.length);
        }

        @Test
        @DisplayName("every TransportErrorCode wireCode() is non-negative")
        void wireCodesNonNegative() {
            for (TransportErrorCode code : TransportErrorCode.values()) {
                assertThat(code.wireCode()).isGreaterThanOrEqualTo(0);
            }
        }

        @Test
        @DisplayName("map(null) → INTERNAL_ERROR without NPE")
        void nullExceptionMapsToInternalError() {
            assertThat(ErrorMapperRegistry.map(null))
                    .isEqualTo(TransportErrorCode.INTERNAL_ERROR);
        }
    }
}
