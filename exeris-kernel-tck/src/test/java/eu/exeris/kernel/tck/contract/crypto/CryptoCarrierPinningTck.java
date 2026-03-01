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

    private MemoryAllocator      allocator;
    private TlsEngine            engine;
    private LoanedBuffer         plaintext;
    private LoanedBuffer         ciphertext;

    @Override protected String subsystemName()      { return "Crypto"; }
    @Override protected String hotPathDescription() { return "TlsEngine.wrap(plaintext, ciphertext) — zero-copy cipher"; }

    @Override
    protected void bootstrapSubsystem() {
        KernelCryptoProvider provider = createProvider();
        allocator  = createAllocator();
        engine     = provider.createTlsEngine(new CryptoProviderConfig(
                CryptoProviderConfig.Protocol.TCP_TLS,
                null, null,
                List.of("h2"),
                0, false,
                CryptoProviderConfig.TLS_1_3));
        plaintext  = allocator.allocate(AllocationHint.MEDIUM);
        ciphertext = allocator.allocate(AllocationHint.MEDIUM);
        plaintext.segment().fill((byte) 0xAB);
        engine.beginHandshake(ciphertext);
    }

    @Override
    protected void runSingleIteration() {
        engine.wrap(plaintext, ciphertext);
    }

    @Override
    protected void tearDownSubsystem() {
        if (engine     != null) engine.close();
        if (plaintext  != null) plaintext.close();
        if (ciphertext != null) ciphertext.close();
        if (allocator  != null) allocator.close();
    }
}



