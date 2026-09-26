/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scripted OpenSSL for Community tests that need a real {@code OffHeapTlsEngine} whose handshake
 * outcome is chosen by the test rather than by a peer.
 *
 * <p>Lives in Core's package, in the Community test tree, because {@link CoreSslHandles} is built
 * only through its package-private constructor. Every return value is a field; every handle is bound
 * to this instance.
 */
public final class ScriptedOpenSsl {

    /** The {@code SSL*} every {@code SSL_new} returns. */
    public static final long SSL_PTR = 0x5EED_0000L;

    private final Deque<Integer> connectResults = new ArrayDeque<>();
    private final AtomicInteger connectCalls = new AtomicInteger();
    private final AtomicInteger clears = new AtomicInteger();
    private volatile int errorResult = CoreOpenSslLoader.SSL_ERROR_WANT_READ;
    private volatile long verifyResult = CoreOpenSslLoader.X509_V_OK;
    private volatile int set1HostResult = 1;

    /**
     * Queues the results of the next {@code SSL_connect} calls; once the queue is empty, each call
     * returns {@code -1}.
     *
     * @param results {@code 1} for success, anything else for a step whose outcome
     *                {@link #errorResult(int)} decides
     * @return this
     */
    public synchronized ScriptedOpenSsl connectResults(int... results) {
        for (int result : results) {
            connectResults.addLast(result);
        }
        return this;
    }

    /**
     * Sets what {@code SSL_get_error} reports.
     *
     * @param code an {@code SSL_ERROR_*} code
     * @return this
     */
    public ScriptedOpenSsl errorResult(int code) {
        this.errorResult = code;
        return this;
    }

    /**
     * Sets what {@code SSL_get_verify_result} reports.
     *
     * @param code an {@code X509_V_*} code
     * @return this
     */
    public ScriptedOpenSsl verifyResult(long code) {
        this.verifyResult = code;
        return this;
    }

    /**
     * Sets what {@code X509_VERIFY_PARAM_set1_host} returns.
     *
     * @param result {@code 1} to accept the host
     * @return this
     */
    public ScriptedOpenSsl set1HostResult(int result) {
        this.set1HostResult = result;
        return this;
    }

    /** @return how many times {@code SSL_connect} ran */
    public int connectCalls() {
        return connectCalls.get();
    }

    /** @return how many times {@code ERR_clear_error} ran */
    public int clears() {
        return clears.get();
    }

    /**
     * The handles, with every group scripted.
     *
     * @return handles an {@code OffHeapTlsEngine} can drive
     */
    public CoreSslHandles handles() {
        CoreSslHandles.CtxHandles ctx = new CoreSslHandles.CtxHandles(
                null, null, null, bind("ctxFree", void.class, long.class), null, null, null, null, null, null);
        CoreSslHandles.HandshakeHandles handshake = new CoreSslHandles.HandshakeHandles(
                bind("sslNew", long.class, long.class),
                bind("ctxFree", void.class, long.class),
                bind("sslAccept", int.class, long.class),
                bind("sslConnect", int.class, long.class),
                bind("sslAccept", int.class, long.class));
        CoreSslHandles.IoHandles io = new CoreSslHandles.IoHandles(
                bind("sslReadWrite", int.class, long.class, long.class, int.class),
                bind("sslReadWrite", int.class, long.class, long.class, int.class),
                bind("sslAccept", int.class, long.class),
                bind("zero", int.class, long.class),
                bind("sslGetError", int.class, long.class, int.class),
                null,
                null,
                null);
        CoreSslHandles.ErrorQueueHandles errorQueue = new CoreSslHandles.ErrorQueueHandles(
                bind("clearError", void.class));
        CoreSslHandles.PeerVerificationHandles verification = new CoreSslHandles.PeerVerificationHandles(
                bind("twoLongs", void.class, long.class, long.class),
                bind("get0Param", long.class, long.class),
                bind("set1Host", int.class, long.class, long.class, long.class),
                bind("hostflags", void.class, long.class, int.class),
                bind("one3", int.class, long.class, long.class, long.class),
                bind("ctrl", long.class, long.class, int.class, long.class, long.class),
                bind("getVerifyResult", long.class, long.class),
                bind("zeroLong", long.class, long.class));
        return new CoreSslHandles(ctx, handshake, io, errorQueue, verification, null);
    }

    /**
     * {@code SSL_set_fd}, accepting any descriptor.
     *
     * @return a handle that returns {@code 1}
     */
    public MethodHandle sslSetFd() {
        return bind("setFd", int.class, long.class, int.class);
    }

    private MethodHandle bind(String name, Class<?> returnType, Class<?>... parameters) {
        try {
            return MethodHandles.lookup()
                    .findVirtual(ScriptedOpenSsl.class, name, MethodType.methodType(returnType, parameters))
                    .bindTo(this);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // Bound reflectively by bind(); the parameters mirror the native signatures.

    @SuppressWarnings("unused")
    private long sslNew(long ctx) {
        return SSL_PTR;
    }

    @SuppressWarnings("unused")
    private void ctxFree(long pointer) {
        // nothing to free
    }

    @SuppressWarnings("unused")
    private int sslAccept(long ssl) {
        return -1;
    }

    @SuppressWarnings("unused")
    private synchronized int sslConnect(long ssl) {
        connectCalls.incrementAndGet();
        Integer next = connectResults.pollFirst();
        return next == null ? -1 : next;
    }

    @SuppressWarnings("unused")
    private int sslReadWrite(long ssl, long buffer, int length) {
        return -1;
    }

    @SuppressWarnings("unused")
    private int zero(long ssl) {
        return 0;
    }

    @SuppressWarnings("unused")
    private int sslGetError(long ssl, int ret) {
        return errorResult;
    }

    @SuppressWarnings("unused")
    private void clearError() {
        clears.incrementAndGet();
    }

    @SuppressWarnings("unused")
    private void twoLongs(long a, long b) {
        // SSL_CTX_set1_cert_store: nothing to hold
    }

    @SuppressWarnings("unused")
    private long get0Param(long ssl) {
        return 0xBEEFL;
    }

    @SuppressWarnings("unused")
    private int set1Host(long param, long name, long length) {
        return set1HostResult;
    }

    @SuppressWarnings("unused")
    private void hostflags(long param, int flags) {
        // accepted
    }

    @SuppressWarnings("unused")
    private int one3(long a, long b, long c) {
        return 1;
    }

    @SuppressWarnings("unused")
    private long ctrl(long ssl, int cmd, long larg, long parg) {
        return 1L;
    }

    @SuppressWarnings("unused")
    private long getVerifyResult(long ssl) {
        return verifyResult;
    }

    @SuppressWarnings("unused")
    private long zeroLong(long code) {
        return 0L;
    }

    @SuppressWarnings("unused")
    private int setFd(long ssl, int fd) {
        return 1;
    }
}
