/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions.telemetry;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;

/**
 * Thrown when the {@link TelemetryProvider} cannot initialise one or more sinks.
 *
 * @since 0.5.0
 */
public final class TelemetryBootstrapException extends ExerisKernelException {

    private static final String MESSAGE = "Telemetry provider bootstrap failed";

    public TelemetryBootstrapException(String providerName, String reason) {
        super(KernelErrorCodes.EX_BOOT_3001, MESSAGE, null, providerName, reason);
    }

    public TelemetryBootstrapException(String providerName, String reason, Throwable cause) {
        super(KernelErrorCodes.EX_BOOT_3001, MESSAGE, cause, providerName, reason);
    }
}

