/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.crypto;

import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.tck.contract.AbstractSubsystemCarrierPinningTck;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCK: Carrier pinning verifier for the TLS wrap/unwrap hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>{@code TlsEngine.wrap(plaintext, ciphertext)} using pre-allocated off-heap
 * {@link LoanedBuffer}s. The cipher record loop must never pin a carrier thread —
 * no native blocking calls (e.g. {@code SSL_read} in blocking mode) allowed.
 *
 * @since 0.5.0
 * @see AbstractSubsystemCarrierPinningTck
 * @see CryptoZeroAllocTck
 */
@DisplayName("Crypto carrier pinning TCK")
public abstract class CryptoCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {

    protected abstract KernelCryptoProvider createProvider();
    protected abstract MemoryAllocator createAllocator();

    @Override protected String subsystemName()      { return "Crypto"; }
    @Override protected String hotPathDescription() { return "TlsEngine.wrap(plaintext, ciphertext) — zero-copy cipher"; }

    // =========================================================================
    // State
    // =========================================================================

    /**
     * Number of pre-allocated per-VT slots — covers warm-up (200) + steady (1 000)
     * phases with a large safety margin for implementation-level re-runs.
     * Stored as an instance field (not a compile-time constant) to satisfy static
     * analysis tools that flag {@code i < CONSTANT} as an always-true condition.
     */
    private final int vtSlotCount = 10_000;

    private MemoryAllocator allocator;

    /** Pre-allocated per-VT TLS engines — each VT owns exactly one slot. */
    private TlsEngine[]    engines;

    /** Pre-allocated per-VT plaintext buffers. */
    private LoanedBuffer[] plaintexts;

    /** Pre-allocated per-VT ciphertext buffers. */
    private LoanedBuffer[] ciphertexts;

    /** Monotonic slot counter — each executing VT claims the next available index. */
    private final AtomicInteger vtIndex = new AtomicInteger(0);

    // =========================================================================
    // AbstractSubsystemCarrierPinningTck bindings
    // =========================================================================

    @Override
    protected void bootstrapSubsystem() {
        KernelCryptoProvider provider = createProvider();
        allocator   = createAllocator();

        CryptoProviderConfig tlsConfig = new CryptoProviderConfig(
                CryptoProviderConfig.Protocol.TCP_TLS,
                null, null,
                List.of("h2"),
                0, false,
                CryptoProviderConfig.TLS_1_3);

        engines     = new TlsEngine[vtSlotCount];
        plaintexts  = new LoanedBuffer[vtSlotCount];
        ciphertexts = new LoanedBuffer[vtSlotCount];

        for (int i = 0; i < engines.length; i++) {
            engines[i]     = provider.createTlsEngine(tlsConfig);
            plaintexts[i]  = allocator.allocate(AllocationHint.MEDIUM);
            ciphertexts[i] = allocator.allocate(AllocationHint.MEDIUM);
            plaintexts[i].segment().fill((byte) 0xAB);
            engines[i].beginHandshake(ciphertexts[i]);
        }
        vtIndex.set(0);
    }

    @Override
    protected void runSingleIteration() {
        // Each VT claims its own pre-allocated engine + buffer pair — zero allocations on the hot path.
        int idx = vtIndex.getAndIncrement();
        engines[idx].wrap(plaintexts[idx], ciphertexts[idx]);
    }

    @Override
    protected void tearDownSubsystem() {
        if (engines != null) {
            for (TlsEngine e : engines) {
                if (e != null) e.close();
            }
        }
        if (plaintexts != null) {
            for (LoanedBuffer b : plaintexts) {
                if (b != null) b.close();
            }
        }
        if (ciphertexts != null) {
            for (LoanedBuffer b : ciphertexts) {
                if (b != null) b.close();
            }
        }
        if (allocator != null) allocator.close();
    }
}



