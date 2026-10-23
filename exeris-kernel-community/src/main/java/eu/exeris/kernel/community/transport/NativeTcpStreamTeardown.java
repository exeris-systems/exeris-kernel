/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.io.IOException;
import java.nio.channels.SocketChannel;

/**
 * Package-private terminal release of what a {@link NativeTcpStream} owns once it is closed: the
 * TLS resources and the socket channel. Every step is best effort, because a failure here has no
 * caller to report to and must not keep a later step from running.
 *
 * <p><b>Thread confinement:</b> called once per stream, from the thread that wins the stream's
 * {@code closed} CAS.
 * <p><b>Ownership:</b> the callers keep ownership of their arguments; this class releases them and
 * holds no state.
 */
@SuppressWarnings("PMD.AvoidCatchingGenericException") // best effort: no failure here may stop a later step
final class NativeTcpStreamTeardown {

    private NativeTcpStreamTeardown() {
        // package-private static utility — never instantiated.
    }

    /**
     * Releases TLS-owned resources: the graceful shutdown record, the engine itself, and the
     * per-stream ciphertext placeholder.
     *
     * @param tlsEngine             the stream's engine, or {@code null} for a plaintext stream
     * @param allocator             allocator for the shutdown record
     * @param ciphertextPlaceholder the per-stream empty ciphertext placeholder, or {@code null}
     */
    /* default */ static void releaseTlsResources(TlsEngine tlsEngine, MemoryAllocator allocator,
                                                  LoanedBuffer ciphertextPlaceholder) {
        if (tlsEngine == null) {
            return;
        }
        try {
            try (LoanedBuffer outbound = allocator.allocateNetwork(1024)) {
                tlsEngine.initiateShutdown(outbound);
            }
        } catch (RuntimeException _) {
            // best effort
        }
        try {
            tlsEngine.close();
        } catch (RuntimeException _) {
            // best effort
        }
        if (ciphertextPlaceholder != null) {
            try {
                // Safe outside the stream's TLS lock: fd-owner unwrap never dereferences the ciphertext
                // segment (OffHeapTlsEngine#unwrap reads the socket BIO directly). Do NOT move this under
                // that lock: it is held on the ingress hot path, and the close must never block on it.
                ciphertextPlaceholder.close();
            } catch (RuntimeException _) {
                // best effort
            }
        }
    }

    /**
     * Closes the underlying channel. A graceful close half-closes the output first (FIN); an abortive
     * reset skips the half-close so the SO_LINGER-0 close emits a RST instead.
     *
     * @param channel the stream's channel
     * @param reset   whether the stream was reset rather than closed
     */
    /* default */ static void closeChannelQuietly(SocketChannel channel, boolean reset) {
        if (!reset) {
            try {
                channel.shutdownOutput();
            } catch (IOException | RuntimeException _) {
                // best effort half-close before full close
            }
        }
        try {
            channel.close();
        } catch (IOException _) {
            // best effort close
        }
    }
}
