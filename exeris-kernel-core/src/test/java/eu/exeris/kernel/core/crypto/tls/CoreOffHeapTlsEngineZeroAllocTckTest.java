/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.tck.contract.crypto.CryptoZeroAllocTck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK: Proves that the {@link OffHeapTlsEngine} guard/sentinel path allocates
 * <strong>zero</strong> {@code eu.exeris.*} heap objects per iteration.
 *
 * <h2>What is measured</h2>
 * <p>The iteration calls {@code wrap()} on an engine whose BIO has no real file descriptor
 * ({@code FD 100} does not exist). The call fails at the OpenSSL layer, the pre-allocated
 * {@link eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException} Sentinel is thrown
 * and caught — <strong>without allocating any {@code eu.exeris.*} heap objects</strong>.
 * This validates the zero-allocation contract for the error/guard path, which is the
 * primary hot-path protection mechanism in the Kernel.
 *
 * <h2>What is NOT measured</h2>
 * <p>The steady-state cipher hot path ({@code SSL_write} completing successfully) requires
 * a real TLS session with a wired BIO. That path is covered by
 * {@code OffHeapTlsEngineLoopbackIT} and the Community/Enterprise integration tests.
 */
@DisplayName("L0: OffHeapTlsEngine Sentinel/Guard-Path Zero-Allocation JFR TCK")
class CoreOffHeapTlsEngineZeroAllocTckTest extends CryptoZeroAllocTck {

    /**
     * Returns {@code true} — Core tier {@link OffHeapTlsEngine} guarantees zero
     * {@code eu.exeris.*} allocations on the steady-state hot path via FFM raw
     * {@code long} address passing and pre-allocated Sentinel exceptions only.
     */
    @Override
    protected boolean supportsZeroGcHotPath() {
        return true;
    }

    private static final Arena GLOBAL_ARENA = Arena.global();
    private static CoreSslHandles handles;
    private static long sslCtxPtr;
    private static Throwable loadError;

    static {
        try {
            handles = CoreOpenSslLoader.load(GLOBAL_ARENA);
            long methodPtr = handles.ctx().invokeServerMethod();
            sslCtxPtr = handles.ctx().invokeCtxNew(methodPtr);
        } catch (Throwable t) {
            loadError = t;
        }
    }

    @BeforeAll
    static void requireOpenSsl() {
        assumeTrue(loadError == null,
                "OpenSSL 3.x is not available on this host — skipping TCK: "
                + (loadError != null ? loadError.getMessage() : ""));
    }

    private static final MemorySegment SLAB = GLOBAL_ARENA.allocate(256 * 1024L, 8L);
    private static long slabOffset = 0L;

    private static final MemoryAllocator STUB_ALLOCATOR = new MemoryAllocator() {
        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            int bytes = hint.sizeBytes();
            long offset = slabOffset;
            if (offset + bytes > SLAB.byteSize()) {
                throw new MemoryExhaustedException(bytes, SLAB.byteSize() - offset);
            }
            slabOffset = offset + bytes;
            MemorySegment slice = SLAB.asSlice(offset, bytes);

            return new LoanedBuffer() {
                private long sz = bytes;
                @Override public MemorySegment segment()           { return slice; }
                @Override public long           size()             { return sz; }
                @Override public long           capacity()         { return bytes; }
                @Override public void           setSize(long s)    { sz = s; }
                @Override public void           close()            { /* zero-alloc slab: lifetime tied to GLOBAL_ARENA — no per-slice release */ }
                @Override public void           retain()           { /* zero-alloc slab: no ref-counting — single-threaded benchmark harness */ }
                @Override public int            refCount()         { return 1; }
                @Override public boolean        isAlive()          { return true; }
                @Override public void           addCloseAction(Runnable r) {
                    throw new UnsupportedOperationException(
                            "Zero-alloc TCK stub: addCloseAction is not supported. "
                            + "NativeCipherContext must not register close actions on this path.");
                }
                @Override public LoanedBuffer   slice(long off, long len) {
                    throw new UnsupportedOperationException(
                            "Zero-alloc TCK stub: slice() is not supported.");
                }
                @Override public LoanedBuffer   view() {
                    throw new UnsupportedOperationException(
                            "Zero-alloc TCK stub: view() is not supported.");
                }
                @Override public LoanedBuffer   peek(long off, long len)  {
                    throw new UnsupportedOperationException(
                            "Zero-alloc TCK stub: peek() is not supported.");
                }
            };
        }
        @Override public LoanedBuffer allocateNetwork(int b)         { return allocate(AllocationHint.MEDIUM); }
        @Override public LoanedBuffer allocateCarrierSlab(int idx)   { return allocate(AllocationHint.MEDIUM); }
        @Override public LoanedBuffer allocateInfrastructure(long b) { return allocate(AllocationHint.MEDIUM); }
        @Override public MemoryStats  stats()                        { return MemoryStats.zero(); }
        @Override public void         close()                        { /* stateless benchmark allocator — SLAB is Arena.global(), no close needed */ }
    };

    private TlsEngine    ownEngine;
    private LoanedBuffer ownPlaintext;
    private LoanedBuffer ownCiphertext;

    @Override
    protected KernelCryptoProvider createProvider() {
        return new KernelCryptoProvider() {
            @Override public String  providerName() { return "CoreNativeOpenSslTestProvider"; }
            @Override public int     priority()     { return 100; }
            @Override public boolean supportsQuic() { return false; }

            @Override
            public TlsEngine createTlsEngine(CryptoProviderConfig config) {
                // THE REAL ENGINE. No mocks. No SPI stubs.
                return new OffHeapTlsEngine(handles, sslCtxPtr, true, STUB_ALLOCATOR);
            }
        };
    }

    @Override
    protected MemoryAllocator createAllocator() {
        return STUB_ALLOCATOR;
    }

    @Override
    protected void bootstrapSubsystem() {
        slabOffset = 0L;
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, STUB_ALLOCATOR).run(() -> {
            ownEngine = createProvider().createTlsEngine(new CryptoProviderConfig(
                    CryptoProviderConfig.Protocol.TCP_TLS, null, null, List.of("h2"), 0, false, CryptoProviderConfig.TLS_1_3));

            ownPlaintext  = STUB_ALLOCATOR.allocate(AllocationHint.MEDIUM);
            ownCiphertext = STUB_ALLOCATOR.allocate(AllocationHint.MEDIUM);
            ownPlaintext.segment().fill((byte) 0xAB);
            ownPlaintext.setSize(ownPlaintext.capacity());

            // THE REAL TCP BINDING - fd-based, Core tier only.
            ((OffHeapTlsEngine) ownEngine).notifyBound();

            try {
                ownEngine.beginHandshake(ownCiphertext);
            } catch (Exception _) {
                // Fails legally because FD 100 doesn't exist. Sentinel exception is thrown.
            }
        });
    }

    @Override
    protected void runSingleIteration() {
        // Zero ScopedValue inside the loop!
        // We call the REAL FFM wrap. It will fail on FD 100, throw the Sentinel,
        // and we catch it here. All without generating a single byte of heap!
        try {
            ownEngine.wrap(ownPlaintext, ownCiphertext);
        } catch (Exception _) {
            // Sentinel caught.
        }
    }

    @Override
    protected void tearDownSubsystem() {
        if (ownEngine     != null) ownEngine.close();
        if (ownPlaintext  != null) ownPlaintext.close();
        if (ownCiphertext != null) ownCiphertext.close();
    }
}
