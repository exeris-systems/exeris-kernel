/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Core: Maps {@link ExerisKernelException} error codes to abstract {@link TransportErrorCode}
 * values for the network edge.
 *
 * <h2>Design — O(1) Lookup</h2>
 * <p>Translation is performed via an allocation-free {@code if-else if} chain using
 * {@code String.startsWith()} — no {@code substring} allocation is incurred on the
 * failure path.</p>
 *
 * <h2>The Wall</h2>
 * <p>This class depends only on {@code exeris-kernel-spi}. It never imports JDBC,
 * io_uring, or any transport driver class.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TransportErrorCode code = ErrorMapperRegistry.map(exception);
 * int wireCode = code.wireCode(); // send as H3 / QUIC error frame
 * }</pre>
 *
 * @since 0.5.0
 */
// CyclomaticComplexity: mapCode() dispatches across all known EX-[DOMAIN] prefixes.
// A switch on 10 domain prefixes is the minimal O(1) structure — any factoring would
// split a single cognitive unit into 10 micro-methods, worsening discoverability.
@SuppressWarnings("PMD.CyclomaticComplexity")
public final class ErrorMapperRegistry {


    private ErrorMapperRegistry() {
    }

    /**
     * Maps an {@link ExerisKernelException} to the closest abstract
     * {@link TransportErrorCode} for the network edge.
     *
     * <p>The mapping is intentionally coarse — internal kernel domains are collapsed
     * into a small set of edge-visible categories to prevent information leakage.
     *
     * @param exception the kernel exception to map; {@code null} maps to
     *                  {@link TransportErrorCode#INTERNAL_ERROR}
     * @return a non-null {@link TransportErrorCode}
     */
    public static TransportErrorCode map(ExerisKernelException exception) {
        if (exception == null) {
            return TransportErrorCode.INTERNAL_ERROR;
        }
        return mapCode(exception.errorCode());
    }

    /**
     * Maps a raw {@code EX-[DOMAIN]-[ID]} error code string to a {@link TransportErrorCode}.
     *
     * <p>Exposed as package-private for unit testing without constructing exception instances.
     *
     * @param errorCode the structured error code; may be {@code null} or empty
     * @return a non-null {@link TransportErrorCode}
     */
    /* default */
    static TransportErrorCode mapCode(String errorCode) {
        if (errorCode == null || errorCode.isEmpty()) {
            return TransportErrorCode.INTERNAL_ERROR;
        }
        if (errorCode.startsWith("EX-MEM-")) {
            return mapMemory(errorCode);
        }
        if (errorCode.startsWith("EX-NET-")) {
            return mapNetwork(errorCode);
        }
        if (errorCode.startsWith("EX-SEC-")) {
            return TransportErrorCode.SECURITY_VIOLATION;
        }
        if (errorCode.startsWith("EX-PERS-")) {
            return TransportErrorCode.PERSISTENCE_FAILURE;
        }
        if (errorCode.startsWith("EX-BOOT-")) {
            return mapBoot();
        }
        if (errorCode.startsWith("EX-RUN-")) {
            return mapRuntime(errorCode);
        }
        if (errorCode.startsWith("EX-EVENT-")) {
            return TransportErrorCode.INTERNAL_ERROR;
        }
        if (errorCode.startsWith("EX-FLOW-")) {
            return TransportErrorCode.INTERNAL_ERROR;
        }
        if (errorCode.startsWith("EX-CFG-")) {
            return TransportErrorCode.BOOTSTRAP_FAILURE;
        }
        return TransportErrorCode.INTERNAL_ERROR;
    }

    private static TransportErrorCode mapMemory(String errorCode) {
        return switch (errorCode) {
            case KernelErrorCodes.EX_MEM_1001 -> TransportErrorCode.RESOURCE_EXHAUSTED;
            case KernelErrorCodes.EX_MEM_1002 -> TransportErrorCode.RESOURCE_EXHAUSTED;
            case KernelErrorCodes.EX_MEM_1003 -> TransportErrorCode.INTERNAL_ERROR;
            default -> TransportErrorCode.RESOURCE_EXHAUSTED;
        };
    }

    private static TransportErrorCode mapNetwork(String errorCode) {
        return switch (errorCode) {
            case KernelErrorCodes.EX_NET_4001 -> TransportErrorCode.BIND_FAILURE;
            case KernelErrorCodes.EX_NET_4002 -> TransportErrorCode.SEND_FAILURE;
            case KernelErrorCodes.EX_NET_4003 -> TransportErrorCode.RECEIVE_TIMEOUT;
            case KernelErrorCodes.EX_NET_4004 -> TransportErrorCode.BOOTSTRAP_FAILURE;
            case KernelErrorCodes.EX_NET_4005 -> TransportErrorCode.BIND_FAILURE;
            default -> TransportErrorCode.INTERNAL_ERROR;
        };
    }

    private static TransportErrorCode mapBoot() {
        return TransportErrorCode.BOOTSTRAP_FAILURE;
    }

    private static TransportErrorCode mapRuntime(String errorCode) {
        if (KernelErrorCodes.EX_RUN_3002.equals(errorCode)) {
            return TransportErrorCode.CARRIER_PINNED;
        }
        return TransportErrorCode.INTERNAL_ERROR;
    }
}
