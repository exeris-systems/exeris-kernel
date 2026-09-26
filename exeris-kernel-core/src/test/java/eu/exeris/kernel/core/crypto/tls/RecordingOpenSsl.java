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
        return CoreSslHandlesTestFactory.build(ctx, handshake, io, errorQueue);
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
    private int sslSetFd(long ssl, int fd) {
        calls.add("SSL_set_fd");
        return setFdResult;
    }
}
