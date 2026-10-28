/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

/**
 * Test access to the stall hook of the Community TLS egress drain from outside this package. Tests
 * only: the hook runs on every stalled TLS write, so a test that installs one counts stalls as events
 * instead of polling for a full send buffer.
 */
public final class NativeTcpStreamTlsDrainHook {

    private NativeTcpStreamTlsDrainHook() {
        // static access only
    }

    /**
     * Installs the hook, replacing any other.
     *
     * @param onStall an idempotent action that runs once per stalled attempt
     */
    public static void install(Runnable onStall) {
        NativeTcpStreamTlsDrain.ON_WRITE_STALL_FOR_TEST.set(onStall);
    }

    /** Removes the hook. */
    public static void clear() {
        NativeTcpStreamTlsDrain.ON_WRITE_STALL_FOR_TEST.set(null);
    }
}
