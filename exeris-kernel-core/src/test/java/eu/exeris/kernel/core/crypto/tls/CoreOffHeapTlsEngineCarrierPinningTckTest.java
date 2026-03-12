/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.ArenaLoanedBuffer;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.tck.contract.crypto.CryptoCarrierPinningTck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.Arena;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK: Carrier-pinning verifier for {@link OffHeapTlsEngine}'s guard/sentinel path.
 *
 * <h2>What is verified</h2>
 * <p>The hot path under measurement is the wrap guard: {@link OffHeapTlsEngine#wrap}
 * called on an engine in {@code HANDSHAKE_IN_PROGRESS} state (post-{@code notifyBound},
 * pre-active session). This triggers:
 * <ol>
 *   <li>A {@link java.lang.invoke.VarHandle} acquire-read of {@code closedFlag} — L0 non-blocking.</li>
 *   <li>A {@link java.lang.invoke.VarHandle} read of the state machine phase — L0 non-blocking.</li>
 *   <li>The pre-allocated {@code HOT_PATH_WRAP_SENTINEL} singleton throw —
 *       zero heap allocation, zero carrier pin.</li>
 * </ol>
 * <p>No carrier thread is ever parked or blocked. The entire guard chain is pure
 * volatile-read + branch + exception-propagation — compatible with virtual-thread
 * continuations.
 *
 * <h2>Why the full SSL_write path is not tested here</h2>
 * <p>{@code SSL_write} on an fd-owner BIO requires an active TCP socket. Testing that
 * FFM path without a real peer is meaningless (it would fail immediately with
 * {@code SSL_ERROR_WANT_WRITE}, adding no additional carrier-pinning evidence beyond
 * the guard path). The loopback integration test ({@link OffHeapTlsEngineLoopbackIT})
 * validates the real data-transfer path.
 *
 * <h2>Per-VT slot isolation</h2>
 * <p>Each virtual thread owns a pre-allocated {@link OffHeapTlsEngine} +
 * {@link LoanedBuffer} pair. Zero allocations per iteration on the measured path.
 *
 * <h2>OpenSSL availability</h2>
 * <p>The suite is skipped when OpenSSL 3.x is not present on the host.
 *
 * @since 0.5.0
 * @see CryptoCarrierPinningTck
 * @see CoreOffHeapTlsEngineZeroAllocTckTest
 */
@DisplayName("L0: OffHeapTlsEngine carrier pinning TCK — guard/sentinel path")
class CoreOffHeapTlsEngineCarrierPinningTckTest extends CryptoCarrierPinningTck {

    // =========================================================================
    // One-time OpenSSL bootstrap
    // =========================================================================

    private static final Arena GLOBAL_ARENA = Arena.global();
    private static CoreSslHandles handles;
    private static long serverCtxPtr;
    private static Throwable loadError;

    static {
        try {
            handles = CoreOpenSslLoader.load(GLOBAL_ARENA);
            serverCtxPtr = handles.ctx().invokeCtxNew(handles.ctx().invokeServerMethod());
        } catch (Throwable t) { //NOPMD AvoidCatchingThrowable — FFM load may throw UnsatisfiedLinkError
            loadError = t;
        }
    }

    @BeforeAll
    static void requireOpenSsl() {
        assumeTrue(loadError == null,
                "OpenSSL 3.x not available on this host — skipping carrier pinning TCK: "
                        + (loadError != null ? loadError.getMessage() : ""));
    }

    // =========================================================================
    // CryptoCarrierPinningTck template methods (required by base class)
    // =========================================================================

    /**
     * Allocator bridge — set by {@link #createAllocator()} before any
     * {@link KernelCryptoProvider#createTlsEngine} call in {@link #bootstrapSubsystem()}.
     */
    private MemoryAllocator vtAllocator;

    @Override
    protected KernelCryptoProvider createProvider() {
        return new KernelCryptoProvider() {
            @Override public String  providerName() { return "CoreOffHeapTls/OpenSSL3-PinningTCK"; }
            @Override public int     priority()     { return 100; }
            @Override public boolean supportsQuic() { return false; }

            @Override
            public TlsEngine createTlsEngine(CryptoProviderConfig config) {
                OffHeapTlsEngine engine = new OffHeapTlsEngine(
                        handles, serverCtxPtr, true, vtAllocator);
                engine.notifyBound();
                return engine;
            }
        };
    }

    @Override
    protected MemoryAllocator createAllocator() {
        Arena arena = Arena.ofShared(); //NOPMD DirectArena — test harness only
        vtAllocator = sharedArenaAllocator(arena);
        return vtAllocator;
    }

    // =========================================================================
    // Core-specific hot-path overrides
    //
    // The base class (CryptoCarrierPinningTck) keeps its pre-allocation arrays
    // private. We override all three template methods with our own state to:
    //   (a) guarantee each VT owns an isolated OffHeapTlsEngine slot, and
    //   (b) absorb the expected HOT_PATH_WRAP_SENTINEL throw inside
    //       runSingleIteration() so runVtBatch() error counter stays zero.
    // =========================================================================

    private OffHeapTlsEngine[] coreEngines;
    private LoanedBuffer[]     corePlaintexts;
    private LoanedBuffer[]     coreCiphertexts;
    private final AtomicInteger coreVtIndex = new AtomicInteger(0);

    @Override
    protected void bootstrapSubsystem() {
        KernelCryptoProvider provider = createProvider();
        vtAllocator       = createAllocator();
        int slots         = warmupVtCount() + steadyVtCount();

        CryptoProviderConfig tlsConfig = new CryptoProviderConfig(
                CryptoProviderConfig.Protocol.TCP_TLS,
                null, null,
                List.of("h2"),
                0, false,
                CryptoProviderConfig.TLS_1_3);

        coreEngines     = new OffHeapTlsEngine[slots];
        corePlaintexts  = new LoanedBuffer[slots];
        coreCiphertexts = new LoanedBuffer[slots];

        for (int i = 0; i < slots; i++) {
            coreEngines[i]     = (OffHeapTlsEngine) provider.createTlsEngine(tlsConfig);
            corePlaintexts[i]  = vtAllocator.allocate(AllocationHint.MEDIUM);
            coreCiphertexts[i] = vtAllocator.allocate(AllocationHint.MEDIUM);
            corePlaintexts[i].segment().fill((byte) 0xAB);
            // beginHandshake() returns NEED_HANDSHAKE/NEED_UNWRAP without a real peer — accepted.
            try {
                coreEngines[i].beginHandshake(coreCiphertexts[i]);
            } catch (RuntimeException _) {
                // Unexpected on this path — SSL_accept() without BIO returns a status code.
            }
        }
        coreVtIndex.set(0);
    }

    /**
     * Exercises the wrap guard path (VarHandle read + pre-allocated sentinel throw).
     *
     * <p>The {@code HOT_PATH_WRAP_SENTINEL} is thrown because the engine is in
     * {@code HANDSHAKE_IN_PROGRESS}, not {@code ACTIVE}. Catching it here keeps
     * the VT batch error counter at zero while still exercising the exact path
     * that runs in production during concurrent handshake phases.
     */
    @Override
    protected void runSingleIteration() {
        int idx = coreVtIndex.getAndIncrement();
        try {
            coreEngines[idx].wrap(corePlaintexts[idx], coreCiphertexts[idx]);
        } catch (RuntimeException _) {
            // HOT_PATH_WRAP_SENTINEL: pre-allocated singleton, zero heap allocation.
        }
    }

    @Override
    protected void tearDownSubsystem() {
        if (coreEngines != null) {
            for (OffHeapTlsEngine e : coreEngines) {
                if (e != null) e.close();
            }
        }
        if (corePlaintexts != null) {
            for (LoanedBuffer b : corePlaintexts) {
                if (b != null) b.close();
            }
        }
        if (coreCiphertexts != null) {
            for (LoanedBuffer b : coreCiphertexts) {
                if (b != null) b.close();
            }
        }
        if (vtAllocator != null) vtAllocator.close();
    }

    // =========================================================================
    // Allocator factory
    // =========================================================================

    private static MemoryAllocator sharedArenaAllocator(Arena arena) {
        return new MemoryAllocator() {
            @Override
            public LoanedBuffer allocate(AllocationHint hint) {
                return ArenaLoanedBuffer.allocate(arena, hint, AllocationHint.SMALL.sizeBytes());
            }
            @Override public LoanedBuffer allocateNetwork(int bytes)         { return allocate(AllocationHint.MEDIUM); }
            @Override public LoanedBuffer allocateCarrierSlab(int slotIndex) { return allocate(AllocationHint.MEDIUM); }
            @Override public LoanedBuffer allocateInfrastructure(long bytes) { return allocate(AllocationHint.JUMBO); }
            @Override public MemoryStats  stats()                            { return MemoryStats.zero(); }
            @Override public void         close()                            { arena.close(); }
        };
    }
}


