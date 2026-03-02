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
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;

import java.util.List;

/**
 * TCK: JFR Zero-Allocation monitor for the TLS wrap/unwrap hot path.
 *
 * <h2>Hot Path Under Test (crypto.md)</h2>
 * <p>The cipher record loop: {@code wrap(plaintext, ciphertext)} using pre-allocated
 * off-heap {@link LoanedBuffer}s. The documentation mandates:
 * <blockquote>"Every packet encryption/decryption cycle must produce zero heap objects."</blockquote>
 *
 * <p>Enterprise implementation uses OpenSSL 3.x via Panama FFM — both buffers are
 * native off-heap {@code MemorySegment}s passed directly as raw addresses to
 * {@code SSL_write}. No intermediate {@code ByteBuffer}, no {@code byte[]} copy.
 *
 * <h2>Community Contract</h2>
 * <p>Community uses JSSE {@code SSLEngine} + {@code ByteBuffer} wrappers —
 * bounded allocation per record is acceptable (≤ 8 allocations/iteration).
 *
 * <h2>Enterprise Contract</h2>
 * <p>Enterprise implementations must override {@code supportsZeroGcHotPath()} (inherited
 * from {@link eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck}) to return
 * {@code true}. The TCK will then enforce 0 {@code eu.exeris.*} allocations per
 * {@code wrap()}/{@code unwrap()} call (0 B/op).
 *
 * @since 0.5.0
 */
public abstract class CryptoZeroAllocTck extends AbstractSubsystemZeroAllocTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /** Creates the {@link KernelCryptoProvider} under test. */
    protected abstract KernelCryptoProvider createProvider();

    /** Creates the {@link MemoryAllocator} for off-heap cipher buffers. */
    protected abstract MemoryAllocator createAllocator();

    // =========================================================================
    // State
    // =========================================================================

    private KernelCryptoProvider provider;
    private MemoryAllocator      allocator;
    private TlsEngine            engine;
    private LoanedBuffer         plaintext;
    private LoanedBuffer         ciphertext;

    @Override protected String subsystemName()      { return "Crypto"; }
    @Override protected String hotPathDescription()  {
        return "TlsEngine.wrap(plaintext LoanedBuffer, ciphertext LoanedBuffer) — zero-copy cipher";
    }
    @Override protected int warmupIterations()       { return 1_000; }
    @Override protected int hotPathIterations()      { return 10_000; }

    /**
     * Community boundary: ≤ 8 allocations per iteration (JSSE {@code ByteBuffer}
     * wrappers, {@code SSLEngineResult}).
     * <p>Enterprise implementations that override {@link #supportsZeroGcHotPath()}
     * to return {@code true} will have this limit overridden to 0 by the base TCK.
     */
    @Override
    protected int maxExerisAllocationsPerIteration() {
        return 8;
    }

    @Override
    protected void bootstrapSubsystem() {
        provider   = createProvider();
        allocator  = createAllocator();

        engine = provider.createTlsEngine(new CryptoProviderConfig(
                CryptoProviderConfig.Protocol.TCP_TLS,
                null, null,
                List.of("h2"),
                0, false,
                CryptoProviderConfig.TLS_1_3));

        // Pre-allocate MEDIUM buffers — RAII, closed in tearDownSubsystem().
        // These are NEVER re-allocated per iteration (Enterprise: 0 B/op after this point).
        plaintext  = allocator.allocate(AllocationHint.MEDIUM);
        ciphertext = allocator.allocate(AllocationHint.MEDIUM);

        // Write synthetic payload
        if (plaintext.segment().byteSize() > 0) {
            plaintext.segment().fill((byte) 0xAB);
        }

        // Initiate handshake (warm up TLS context)
        engine.beginHandshake(ciphertext);
    }

    @Override
    protected void runSingleIteration() {
        // Zero-copy wrap: plaintext slab → ciphertext slab via MemorySegment native address.
        // Enterprise must NOT create any java.lang.Object here.
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

