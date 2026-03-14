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
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslRuntime;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.tck.contract.crypto.AbstractCryptoEngineTck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.Arena;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK: Concrete {@link AbstractCryptoEngineTck} for the Core-tier
 * {@link OffHeapTlsEngine} backed by OpenSSL 3.x via Panama FFM.
 *
 * <h2>What this suite verifies for the Core tier</h2>
 * <ul>
 *   <li>Provider metadata contract (name, priority, supportsQuic)</li>
 *   <li>ServiceLoader zero-arg constructor idiom (not applicable — no-arg ctor exists)</li>
 *   <li>{@code createTlsEngine(TCP_TLS)} returns a non-null engine</li>
 *   <li>{@code createTlsEngine(QUIC)} throws {@link CryptoBootstrapException}
 *       (QUIC is Enterprise Memory-BIO only — "The Wall")</li>
 *   <li>{@code close()} idempotency</li>
 *   <li>{@code beginHandshake()} returns a non-null, non-CLOSED status even without
 *       a real peer (OpenSSL returns {@code SSL_ERROR_WANT_READ} or similar)</li>
 *   <li>{@link LoanedBuffer#segment()} is off-heap ({@code isNative() == true})</li>
 *   <li>Error-code contract: {@code unwrap()} on a closed engine throws
 *       {@link eu.exeris.kernel.spi.exceptions.crypto.TlsDecryptException}
 *       (EX-NET-2003), not the generic {@code TlsException} (EX-NET-2001)</li>
 * </ul>
 *
 * <h2>I/O tests (wrap/unwrap) require a real socket — skipped here</h2>
 * <p>{@link OffHeapTlsEngine} uses {@code SSL_set_fd} (fd-owner BIO) — wrap/unwrap
 * cannot execute without an active TCP socket. Those paths are validated by
 * {@link OffHeapTlsEngineLoopbackIT} (Linux-only integration test) and the
 * zero-allocation guard path is covered by
 * {@code CoreOffHeapTlsEngineZeroAllocTckTest}.
 *
 * <h2>OpenSSL availability</h2>
 * <p>The entire suite is skipped via {@link org.junit.jupiter.api.Assumptions}
 * when OpenSSL 3.x is not present on the host.
 *
 * @since 0.5.0
 * @see AbstractCryptoEngineTck
 * @see CoreOffHeapTlsEngineZeroAllocTckTest
 * @see OffHeapTlsEngineLoopbackIT
 */
@DisplayName("L0: OffHeapTlsEngine contract TCK (AbstractCryptoEngineTck)")
class CoreOffHeapTlsEngineTckTest extends AbstractCryptoEngineTck {

    // =========================================================================
    // One-time OpenSSL bootstrap
    // =========================================================================

    private static final Arena GLOBAL_ARENA = Arena.global();
    private static CoreOpenSslRuntime runtime;
    private static CoreSslHandles handles;
    private static long serverCtxPtr;
    private static Throwable loadError;

    static {
        try {
            runtime = CoreOpenSslLoader.load(GLOBAL_ARENA);
            handles = runtime.handles();
            serverCtxPtr = handles.ctx().invokeCtxNew(handles.ctx().invokeServerMethod());
        } catch (Throwable t) { //NOPMD AvoidCatchingThrowable — FFM load may throw UnsatisfiedLinkError
            loadError = t;
        }
    }

    @BeforeAll
    static void requireOpenSsl() {
        assumeTrue(loadError == null,
                "OpenSSL 3.x not available on this host — skipping Core TCK: "
                        + (loadError != null ? loadError.getMessage() : ""));
    }

    // =========================================================================
    // Allocator bridge — set by createAllocator() before createTlsEngine() runs
    // =========================================================================

    /**
     * Typed provider reference. {@code AbstractCryptoEngineTck#setUp()} calls
     * {@code createProvider()} before {@code createAllocator()}, so the allocator
     * is injected into {@link TckProvider#allocator} after construction.
     */
    private TckProvider tckProvider;

    // =========================================================================
    // AbstractCryptoEngineTck template methods
    // =========================================================================

    @Override
    protected KernelCryptoProvider createProvider() {
        tckProvider = new TckProvider();
        return tckProvider;
    }

    @Override
    protected MemoryAllocator createAllocator() {
        Arena arena = Arena.ofShared(); //NOPMD DirectArena — test harness only
        MemoryAllocator alloc = sharedArenaAllocator(arena);
        tckProvider.allocator = alloc;
        return alloc;
    }

    @Override
    protected boolean isCommunityTier() {
        return false;
    }

    /**
     * {@code OffHeapTlsEngine} uses an fd-owner BIO ({@code SSL_set_fd}).
     * wrap/unwrap require an active TCP socket — skipped and covered by
     * {@link OffHeapTlsEngineLoopbackIT}.
     */
    @Override
    protected boolean isIoReady() {
        return false;
    }

    // =========================================================================
    // Named provider — satisfies ServiceLoader public no-arg constructor contract
    // =========================================================================

    /**
     * Named static class so that {@code provider.getClass().getDeclaredConstructor()}
     * succeeds (TCK {@code ProviderMetadata.publicNoArgConstructorExists}).
     *
     * <p>Static nested classes in Java can access {@code private} members of the
     * enclosing class ({@code handles}, {@code serverCtxPtr}). The {@code allocator}
     * field is injected by {@link CoreOffHeapTlsEngineTckTest#createAllocator()} via
     * direct package-private field write after this instance is constructed.
     */
    static final class TckProvider implements KernelCryptoProvider {

        /** Injected by {@link CoreOffHeapTlsEngineTckTest#createAllocator()} before first engine creation. */
        MemoryAllocator allocator;

        /** Public no-arg constructor — satisfies {@code ServiceLoader} discovery contract. */
        public TckProvider() {
            // No-op — fields are set by the test harness after construction.
        }

        @Override public String  providerName() { return "CoreOffHeapTls/OpenSSL3-TCK"; }
        @Override public int     priority()     { return 100; }
        @Override public boolean supportsQuic() { return false; }

        @Override
        public TlsEngine createTlsEngine(CryptoProviderConfig config) {
            if (config != null && config.protocol() == CryptoProviderConfig.Protocol.QUIC) {
                throw new CryptoBootstrapException(providerName(),
                        "QUIC requires Enterprise Memory-BIO tier — fd-owner Core does not support QUIC");
            }
            OffHeapTlsEngine engine = new OffHeapTlsEngine(handles, serverCtxPtr, true, allocator);
            engine.notifyBound();
            return engine;
        }
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
            @Override public LoanedBuffer allocateNetwork(int bytes)          { return allocate(AllocationHint.MEDIUM); }
            @Override public LoanedBuffer allocateCarrierSlab(int slotIndex)  { return allocate(AllocationHint.MEDIUM); }
            @Override public LoanedBuffer allocateInfrastructure(long bytes)  { return allocate(AllocationHint.JUMBO); }
            @Override public MemoryStats  stats()                             { return MemoryStats.zero(); }
            @Override public void         close()                             { arena.close(); }
        };
    }
}
