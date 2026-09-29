/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import java.util.concurrent.locks.LockSupport;

/**
 * The pause between two handshake steps of a {@link NativeTcpStream} caller waiting for the
 * handshake; a seam so a test can run another thread's step in that window.
 */
@FunctionalInterface
interface NativeTcpHandshakeBackoff {

    /** Parks the caller for 250 µs, which leaves the reactor room to move the handshake on. */
    NativeTcpHandshakeBackoff PARK = () -> LockSupport.parkNanos(250_000L);

    /** Pauses the calling thread between two handshake steps. */
    void pause();
}
