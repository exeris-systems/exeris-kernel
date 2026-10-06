/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.telemetry;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when the {@link eu.exeris.kernel.spi.telemetry.TelemetryProvider} cannot initialise one or more sinks.
 *
 * <p>It is raised in place of a sink list, so a caller that catches it has no sinks from that
 * {@code createSinks} call to close.
 *
 * <h2>rawArgs Binary Layout</h2>
 * <pre>
 * index 0 → String providerName  (which provider failed to initialise)
 * index 1 → String reason        (failure cause — static constant, never formatted)
 * </pre>
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_BOOT_3001}
 *
 * @implNote The Community provider closes every sink it has already created before throwing, so a
 *           failed telemetry bootstrap leaves no open file handle or recording behind.
 * @since 0.5
 */
public final class TelemetryBootstrapException extends ExerisKernelException {

    private static final String MESSAGE = "Telemetry provider bootstrap failed";

    /**
     * Reports a sink-initialisation failure for which no underlying throwable is available — the
     * provider rejected the configuration itself.
     *
     * @param providerName display name of the provider that failed, stored at {@code rawArgs[0]}
     * @param reason       why initialisation failed, stored at {@code rawArgs[1]}; must be a static
     *                     constant, never a formatted string
     */
    public TelemetryBootstrapException(String providerName, String reason) {
        super(KernelErrorCodes.EX_BOOT_3001, MESSAGE, null, providerName, reason);
    }

    /**
     * Reports a sink-initialisation failure caused by an underlying throwable, which is chained so
     * that the originating I/O or native error survives into the crash record.
     *
     * @param providerName display name of the provider that failed, stored at {@code rawArgs[0]}
     * @param reason       why initialisation failed, stored at {@code rawArgs[1]}; must be a static
     *                     constant, never a formatted string
     * @param cause        the throwable that made a sink unusable; may be {@code null}
     */
    public TelemetryBootstrapException(String providerName, String reason, Throwable cause) {
        super(KernelErrorCodes.EX_BOOT_3001, MESSAGE, cause, providerName, reason);
    }
}
