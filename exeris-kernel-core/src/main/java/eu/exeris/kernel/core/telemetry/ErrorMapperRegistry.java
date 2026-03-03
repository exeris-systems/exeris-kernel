/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Core: Maps {@link ExerisKernelException} error codes to abstract {@link TransportErrorCode}
 * values for the network edge.
 *
 * <h2>Design — O(1) Lookup</h2>
 * <p>Translation is performed via a {@code switch} expression on the 6-character
 * {@code EX-[DOMAIN]} prefix (e.g., {@code "EX-MEM"}, {@code "EX-NET"}).
 * String {@code switch} in the JVM compiles to a hash-dispatch — O(1) average case,
 * zero heap allocation beyond the prefix substring already paid by the JIT constant pool.
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

    /**
     * All known domain prefixes are exactly 6 characters: {@code "EX-MEM"}, {@code "EX-NET"},
     * {@code "EX-SEC"}, {@code "EX-PER"} (covers {@code EX-PERS-*}),
     * {@code "EX-GRP"} (covers {@code EX-GRPH-*}), {@code "EX-BOO"} (covers {@code EX-BOOT-*}),
     * {@code "EX-RUN"}, {@code "EX-EVE"} (covers {@code EX-EVENT-*}),
     * {@code "EX-FLO"} (covers {@code EX-FLOW-*}), {@code "EX-CFG"}.
     */
    private static final int PREFIX_LENGTH = 6;

    private ErrorMapperRegistry() {
    }

    /**
     * Maps an {@link ExerisKernelException} to the closest abstract
     * {@link TransportErrorCode} for the network edge.
     *
     * <p>The mapping is intentionally coarse — internal kernel domains are collapsed
     * into a small set of edge-visible categories to prevent information leakage.
     *
     * @param exception the kernel exception to map; must not be {@code null}
     * @return a non-null {@link TransportErrorCode}
     */
    public static TransportErrorCode map(ExerisKernelException exception) {
        return mapCode(exception.errorCode());
    }

    /**
     * Maps a raw {@code EX-[DOMAIN]-[ID]} error code string to a {@link TransportErrorCode}.
     *
     * <p>Exposed as package-private for unit testing without constructing exception instances.
     *
     * @param errorCode the structured error code; must not be {@code null}
     * @return a non-null {@link TransportErrorCode}
     */
    /* default */
    static TransportErrorCode mapCode(String errorCode) {
        if (errorCode == null || errorCode.length() < PREFIX_LENGTH) {
            return TransportErrorCode.INTERNAL_ERROR;
        }
        String domain = errorCode.substring(0, PREFIX_LENGTH);
        return switch (domain) {
            case "EX-MEM" -> mapMemory(errorCode);
            case "EX-NET" -> mapNetwork(errorCode);
            case "EX-SEC" -> TransportErrorCode.SECURITY_VIOLATION;
            case "EX-PER" -> TransportErrorCode.PERSISTENCE_FAILURE;
            case "EX-BOO" -> mapBoot();
            case "EX-RUN" -> mapRuntime(errorCode);
            case "EX-EVE" -> TransportErrorCode.INTERNAL_ERROR;
            case "EX-FLO" -> TransportErrorCode.INTERNAL_ERROR;
            case "EX-CFG" -> TransportErrorCode.BOOTSTRAP_FAILURE;
            default -> TransportErrorCode.INTERNAL_ERROR;
        };
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
