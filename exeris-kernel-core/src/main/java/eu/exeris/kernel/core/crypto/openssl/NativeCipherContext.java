/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.openssl;

import eu.exeris.kernel.spi.exceptions.crypto.TlsException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * L1: Hardware-sympathetic lifecycle manager for OpenSSL {@code SSL*} pointers.
 *
 * <p>Uses a lock-free reference counting mechanism (identical in structure to
 * {@code AbstractLoanedBuffer}) to safely manage the C-pointer across virtual threads
 * without incurring the JVM Thread-Local Handshake penalty of
 * {@code Arena.ofShared().close()}.
 *
 * <h2>Reference-Count Protocol</h2>
 * <pre>
 *   NativeCipherContext ctx = new NativeCipherContext(handles, sslCtxPtr);
 *                             // refCount = 1 (base reference)
 *
 *   long ptr = ctx.retainSslPointer();   // refCount = 2
 *   try {
 *       handles.ioHandles().invokeWrite(ptr, bufAddr, len);
 *   } finally {
 *       ctx.release();                   // refCount = 1
 *   }
 *
 *   ctx.close();                         // refCount = 0 → SSL_free invoked
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>All mutations to {@code refCount} are performed via {@link VarHandle} CAS —
 * no monitors, no locks, no {@code synchronized} blocks.
 *
 * <h2>JFR-First</h2>
 * <p>{@code SSL_free} failures are silently absorbed at the call-site to preserve
 * kernel stability, but are always recorded via
 * {@link NativeCipherContextFreeFailureEvent} for flight-recorder diagnostics.
 *
 * @since 0.5.0
 */
public final class NativeCipherContext implements AutoCloseable {

    // =========================================================================
    // Lock-free reference counting — VarHandle CAS (mirrors AbstractLoanedBuffer)
    // =========================================================================

    private static final VarHandle REF_COUNT;

    static {
        try {
            REF_COUNT = MethodHandles.lookup()
                    .findVarHandle(NativeCipherContext.class, "refCount", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Sentinel value returned by {@code SSL_new} on allocation failure. */
    private static final long NULL_PTR = 0L;

    /**
     * The initial (base) reference count assigned at construction.
     * When {@link #release()} decrements from this value to zero, {@code SSL_free} is invoked.
     */
    private static final int BASE_REF_COUNT = 1;

    /**
     * Reference count — mutated exclusively via {@link #REF_COUNT} VarHandle CAS.
     * Declared package-private to suppress PMD "unused private field" false-positive
     * (identical pattern to {@code AbstractLoanedBuffer.refCount}).
     */
    /* default */ volatile int refCount = BASE_REF_COUNT;

    // =========================================================================
    // Native state
    // =========================================================================

    private final long sslPtr;
    private final CoreSslHandles.HandshakeHandles handles;

    /**
     * Off-heap SESSION slab tracked by {@link MemoryAllocator}.
     * Allocated at construction time; closed when {@code SSL_free} is invoked
     * (ref-count reaches zero). Ensures {@code WatermarkManager} sees the per-session
     * allocation and can apply backpressure via {@code EX-MEM-1001}.
     */
    private final LoanedBuffer sessionSlab;

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * Allocates a new {@code SSL*} session handle via {@code SSL_new} and registers
     * a {@link AllocationHint#SESSION} slab with the provided {@link MemoryAllocator}
     * so that {@code WatermarkManager} can track and backpressure session memory.
     *
     * @param handles       pre-resolved FFM method handles for session lifecycle
     * @param sslCtxPointer raw address of the shared {@code SSL_CTX*}
     * @param allocator     allocator used for the session slab tracking; must be non-null
     * @throws eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException
     *         ({@code EX-MEM-1001}) if the {@link AllocationHint#SESSION} slab cannot be
     *         satisfied — thrown by {@link MemoryAllocator#allocate(AllocationHint)} before
     *         any native memory is touched, enabling backpressure via {@code WatermarkManager}
     * @throws IllegalStateException if the provided {@link MemoryAllocator} has been closed
     * @throws TlsException if {@code SSL_new} returns a {@code NULL} pointer
     */
    public NativeCipherContext(CoreSslHandles.HandshakeHandles handles,
                               long sslCtxPointer,
                               MemoryAllocator allocator) {
        this.handles     = handles;
        this.sessionSlab = allocator.allocate(AllocationHint.SESSION);
        try {
            this.sslPtr = handles.invokeSslNew(sslCtxPointer);
            if (this.sslPtr == NULL_PTR) {
                sessionSlab.close();
                throw new TlsException("OpenSSL SSL_new returned NULL pointer. Native allocation failed.");
            }
        } catch (TlsException t) {
            throw t;
        } catch (Exception t) { //NOPMD AvoidCatchingGenericException — constructor must not leak larval state
            sessionSlab.close();
            throw new TlsException("Fatal error allocating native SSL pointer", t);
        }
        CryptoContextAllocEvent.emit(this.sslPtr, sslCtxPointer, "NativeCipherContext",
                AllocationHint.SESSION.sizeBytes());
    }

    // =========================================================================
    // Reference-counted pointer access
    // =========================================================================

    /**
     * Returns the raw {@code SSL*} address without incrementing the reference count.
     *
     * <p><b>Usage restriction:</b> this accessor is intended exclusively for diagnostic
     * logging, JFR event emission, and unit test assertions. Callers that need to pass
     * the pointer to an FFM call MUST use {@link #retainSslPointer()} to ensure the
     * lifecycle contract is upheld.
     *
     * @return raw {@code long} address of the native {@code SSL} structure
     */
    public long sslPointer() {
        return sslPtr;
    }

    /**
     * Atomically increments the reference count and returns the raw {@code SSL*} address.
     *
     * <p>The caller MUST call {@link #release()} in a {@code finally} block to avoid
     * a Use-After-Free segfault or a reference-count leak:
     * <pre>{@code
     * long ptr = ctx.retainSslPointer();
     * try {
     *     handles.ioHandles().invokeWrite(ptr, bufAddr, len);
     * } finally {
     *     ctx.release();
     * }
     * }</pre>
     *
     * @return the raw {@code long} address of the {@code SSL} structure
     * @throws IllegalStateException if the context has already been destroyed
     */
    public long retainSslPointer() {
        int current;
        do {
            current = (int) REF_COUNT.getVolatile(this);
            if (current <= 0) {
                throw new IllegalStateException(
                        "NativeCipherContext is closed — SSL* pointer has been freed.");
            }
        } while (!REF_COUNT.compareAndSet(this, current, current + 1));
        return sslPtr;
    }

    /**
     * Decrements the reference count.
     *
     * <p>When the count transitions from {@code 1} to {@code 0}, {@code SSL_free} is
     * invoked. Any failure from {@code SSL_free} is recorded via
     * {@link NativeCipherContextFreeFailureEvent} and does not propagate — kernel
     * stability takes priority over individual cleanup failures.
     *
     * @throws IllegalStateException if called more times than {@link #retainSslPointer()}
     *                               plus the initial base reference (double-release guard)
     */
    public void release() {
        int prev = (int) REF_COUNT.getAndAdd(this, -1);
        if (prev == BASE_REF_COUNT) {
            // Transitioned 1 → 0: we hold the last reference — safe to free.
            try { //NOPMD UseTryWithResources — sessionSlab.close() must run even if SSL_free throws
                handles.invokeSslFree(sslPtr);
            } catch (Exception t) { //NOPMD AvoidCatchingGenericException — SSL_free destructor path
                // Do NOT propagate: destructor paths must never throw.
                // Record the failure in JFR for flight-recorder diagnostics.
                NativeCipherContextFreeFailureEvent.emit(sslPtr, t);
            } finally {
                // Return tracked session memory to WatermarkManager regardless of SSL_free result.
                sessionSlab.close();
            }
        } else if (prev <= 0) {
            // Restore to 0 so subsequent diagnostics see a consistent state,
            // then fail fast — this is always a programming error.
            REF_COUNT.setRelease(this, 0);
            throw new IllegalStateException(
                    "Double release detected on NativeCipherContext (sslPtr=0x"
                    + Long.toHexString(sslPtr) + ")");
        }
        // prev > 1: other owners still active — nothing to do.
    }

    // =========================================================================
    // AutoCloseable — drops the initial base reference
    // =========================================================================

    /**
     * Drops the initial base reference (refCount 1→0 if no other owners remain).
     *
     * <p>If virtual threads are still actively retaining the pointer for I/O,
     * the actual {@code SSL_free} is deferred until the last {@link #release()} call.
     * This makes {@code close()} safe to call from a {@code try-with-resources} block
     * that outlives the asynchronous I/O operations it spawned.
     */
    @Override
    public void close() {
        release();
    }
}
