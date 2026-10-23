/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

final class TransportConfigSupport {

    private static final int MIN_QUEUED_WRITE_BYTES = 1;

    private TransportConfigSupport() {
    }

    /* default */
    static int computeDefaultReactorCount(String propertyKey) {
        return TransportReactorDefaults.computeDefaultReactorCount(propertyKey);
    }

    /* default */
    static void validateNonDisabled(
            TransportMode mode,
            String bindAddress,
            int port,
            int reactorCount,
            int maxConnections,
            long idleTimeoutMillis,
            int maxActiveStreams,
            int maxQueuedWriteBytes
    ) {
        TransportNonDisabledValidator.validateNonDisabled(
                mode,
                bindAddress,
                port,
                reactorCount,
                maxConnections,
                idleTimeoutMillis,
                maxActiveStreams
        );
        validateQueuedWriteBound(maxQueuedWriteBytes);
    }

    // A zero bound would park every write, so it is refused where the value is named rather than
    // read as "off": the unbounded sentinel is the only way to remove the bound.
    private static void validateQueuedWriteBound(int maxQueuedWriteBytes) {
        if (maxQueuedWriteBytes < MIN_QUEUED_WRITE_BYTES
                && maxQueuedWriteBytes != TransportConfig.UNBOUNDED_QUEUED_WRITE_BYTES) {
            throw new IllegalArgumentException(
                    "maxQueuedWriteBytes must be >= " + MIN_QUEUED_WRITE_BYTES + ", or "
                            + TransportConfig.UNBOUNDED_QUEUED_WRITE_BYTES
                            + " for no bound, got: " + maxQueuedWriteBytes);
        }
    }
}
