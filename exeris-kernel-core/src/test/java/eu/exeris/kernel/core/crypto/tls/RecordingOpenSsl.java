/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandlesTestFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A scripted OpenSSL for {@link OffHeapTlsEngine} unit tests: every downcall the engine makes is
 * appended to {@link #calls()} in order, and each return value is a field the test sets.
 *
 * <p>The handles are bound to this instance, not to static state, so two tests never share a
 * script.
 */
final class RecordingOpenSsl {

    static final long SSL_PTR = 0xCAFEBABEL;

    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    int acceptResult = 1;
    int connectResult = 1;
    int readResult = -1;
    int writeResult = -1;
    int shutdownResult = 1;
    int errorResult = CoreOpenSslLoader.SSL_ERROR_WANT_READ;
    int setFdResult = 1;
    long verifyResult = 0L;
    int set1HostResult = 1;
    int set1IpResult = 1;
    long ctrlResult = 1L;
    long paramPtr = 0xBEEFL;

    List<String> calls() {
        return calls;
    }

    void forget() {
        calls.clear();
    }

    long count(String call) {
        synchronized (calls) {
            return calls.stream().filter(call::equals).count();
        }
    }

    int indexOf(String call) {
        return calls.indexOf(call);
    }

    CoreSslHandles handles() {
        CoreSslHandles.CtxHandles ctx = new CoreSslHandles.CtxHandles(
                null, null, null, null, null, null, null, null, null, null);
        CoreSslHandles.HandshakeHandles handshake = new CoreSslHandles.HandshakeHandles(
                bind("sslNew", long.class, long.class),
                bind("sslFree", void.class, long.class),
                bind("sslAccept", int.class, long.class),
                bind("sslConnect", int.class, long.class),
                bind("sslDoHandshake", int.class, long.class));
        CoreSslHandles.IoHandles io = new CoreSslHandles.IoHandles(
                bind("sslRead", int.class, long.class, long.class, int.class),
                bind("sslWrite", int.class, long.class, long.class, int.class),
                bind("sslShutdown", int.class, long.class),
                bind("sslGetShutdown", int.class, long.class),
                bind("sslGetError", int.class, long.class, int.class),
                null,
                null,
                null);
        CoreSslHandles.ErrorQueueHandles errorQueue = new CoreSslHandles.ErrorQueueHandles(
                bind("errClearError", void.class));
        CoreSslHandles.PeerVerificationHandles verification = new CoreSslHandles.PeerVerificationHandles(
                bind("ctxSet1CertStore", void.class, long.class, long.class),
                bind("get0Param", long.class, long.class),
                bind("paramSet1Host", int.class, long.class, long.class, long.class),
                bind("paramSetHostflags", void.class, long.class, int.class),
                bind("paramSet1Ip", int.class, long.class, long.class, long.class),
                bind("sslCtrl", long.class, long.class, int.class, long.class, long.class),
                bind("getVerifyResult", long.class, long.class),
                bind("verifyCertErrorString", long.class, long.class));
        return CoreSslHandlesTestFactory.build(ctx, handshake, io, errorQueue, verification,
                CoreSslHandlesTestFactory.unsupportedTrustStore());
    }

    MethodHandle sslSetFd() {
        return bind("sslSetFd", int.class, long.class, int.class);
    }

    private MethodHandle bind(String name, Class<?> returnType, Class<?>... parameters) {
        try {
            return MethodHandles.lookup()
                    .findVirtual(RecordingOpenSsl.class, name, MethodType.methodType(returnType, parameters))
                    .bindTo(this);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // Bound reflectively by bind(); the parameters mirror the native signatures.

    @SuppressWarnings("unused")
    private long sslNew(long ctx) {
        calls.add("SSL_new");
        return SSL_PTR;
    }

    @SuppressWarnings("unused")
    private void sslFree(long ssl) {
        calls.add("SSL_free");
    }

    @SuppressWarnings("unused")
    private int sslAccept(long ssl) {
        calls.add("SSL_accept");
        return acceptResult;
    }

    @SuppressWarnings("unused")
    private int sslConnect(long ssl) {
        calls.add("SSL_connect");
        return connectResult;
    }

    @SuppressWarnings("unused")
    private int sslDoHandshake(long ssl) {
        calls.add("SSL_do_handshake");
        return 1;
    }

    @SuppressWarnings("unused")
    private int sslRead(long ssl, long buffer, int length) {
        calls.add("SSL_read");
        return readResult;
    }

    @SuppressWarnings("unused")
    private int sslWrite(long ssl, long buffer, int length) {
        calls.add("SSL_write");
        return writeResult;
    }

    @SuppressWarnings("unused")
    private int sslShutdown(long ssl) {
        calls.add("SSL_shutdown");
        return shutdownResult;
    }

    @SuppressWarnings("unused")
    private int sslGetShutdown(long ssl) {
        return 0;
    }

    @SuppressWarnings("unused")
    private int sslGetError(long ssl, int ret) {
        calls.add("SSL_get_error");
        return errorResult;
    }

    @SuppressWarnings("unused")
    private void errClearError() {
        calls.add("ERR_clear_error");
    }

    @SuppressWarnings("unused")
    private void ctxSet1CertStore(long ctx, long store) {
        calls.add("SSL_CTX_set1_cert_store");
    }

    @SuppressWarnings("unused")
    private long get0Param(long ssl) {
        calls.add("SSL_get0_param");
        return paramPtr;
    }

    @SuppressWarnings("unused")
    private int paramSet1Host(long param, long name, long length) {
        calls.add("X509_VERIFY_PARAM_set1_host:" + length);
        return set1HostResult;
    }

    @SuppressWarnings("unused")
    private void paramSetHostflags(long param, int flags) {
        calls.add("X509_VERIFY_PARAM_set_hostflags:0x" + Integer.toHexString(flags));
    }

    @SuppressWarnings("unused")
    private int paramSet1Ip(long param, long ip, long length) {
        calls.add("X509_VERIFY_PARAM_set1_ip:" + length);
        return set1IpResult;
    }

    @SuppressWarnings("unused")
    private long sslCtrl(long ssl, int cmd, long larg, long parg) {
        calls.add("SSL_ctrl:" + cmd + ":" + larg);
        return ctrlResult;
    }

    @SuppressWarnings("unused")
    private long getVerifyResult(long ssl) {
        calls.add("SSL_get_verify_result");
        return verifyResult;
    }

    @SuppressWarnings("unused")
    private long verifyCertErrorString(long code) {
        calls.add("X509_verify_cert_error_string");
        return 0L;
    }

    @SuppressWarnings("unused")
    private int sslSetFd(long ssl, int fd) {
        calls.add("SSL_set_fd");
        return setFdResult;
    }
}
