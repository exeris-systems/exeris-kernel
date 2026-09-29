/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.SocketChannel;

/**
 * {@code SSL_CTX} construction for the Core OpenSSL integration tests, which have no provider to
 * build one for them. The caller frees each context with {@code SSL_CTX_free}.
 */
final class TlsTestSslContexts {

    private TlsTestSslContexts() {
    }

    /**
     * A server context presenting {@code material}.
     *
     * @param handles the loaded handles
     * @param material certificate and key
     * @return the {@code SSL_CTX*}
     */
    static long server(CoreSslHandles handles, TlsTestPki.Issued material) {
        long ctx = handles.ctx().invokeCtxNew(handles.ctx().invokeServerMethod());
        if (ctx == 0L) {
            throw new IllegalStateException("SSL_CTX_new (server) returned NULL");
        }
        boolean ready = false;
        try (Arena arena = Arena.ofConfined()) { //NOPMD DirectArena — path strings for one call
            MemorySegment cert = arena.allocateFrom(material.certificate().toString());
            MemorySegment key = arena.allocateFrom(material.privateKey().toString());
            require(handles.ctx().invokeCtxUseCertFile(ctx, cert.address(), CoreOpenSslLoader.SSL_FILETYPE_PEM),
                    "SSL_CTX_use_certificate_file");
            require(handles.ctx().invokeCtxUseKeyFile(ctx, key.address(), CoreOpenSslLoader.SSL_FILETYPE_PEM),
                    "SSL_CTX_use_PrivateKey_file");
            require(handles.ctx().invokeCtxCheckKey(ctx), "SSL_CTX_check_private_key");
            handles.ctx().invokeCtxSetVerify(ctx, CoreOpenSslLoader.SSL_VERIFY_NONE);
            ready = true;
            return ctx;
        } finally {
            if (!ready) {
                handles.errorQueue().invokeClearError();
                handles.ctx().invokeCtxFree(ctx);
            }
        }
    }

    /**
     * A client context with the given verify mode and no trust loaded.
     *
     * @param handles    the loaded handles
     * @param verifyMode an {@code SSL_VERIFY_*} mode
     * @return the {@code SSL_CTX*}
     */
    static long client(CoreSslHandles handles, int verifyMode) {
        long ctx = handles.ctx().invokeCtxNew(handles.ctx().invokeClientMethod());
        if (ctx == 0L) {
            throw new IllegalStateException("SSL_CTX_new (client) returned NULL");
        }
        handles.ctx().invokeCtxSetVerify(ctx, verifyMode);
        return ctx;
    }

    /**
     * The POSIX descriptor behind {@code channel}; test harness only.
     *
     * @param channel an open socket channel
     * @return its file descriptor
     */
    static int fd(SocketChannel channel) {
        try {
            java.lang.reflect.Method getFdVal = channel.getClass().getDeclaredMethod("getFDVal");
            getFdVal.setAccessible(true); //NOPMD AvoidAccessibilityAlteration — test harness only
            return (int) getFdVal.invoke(channel);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "SocketChannel.getFDVal() needs --add-opens java.base/sun.nio.ch=ALL-UNNAMED", e);
        }
    }

    private static void require(int result, String call) {
        if (result != 1) {
            throw new IllegalStateException(call + " failed (result=" + result + ")");
        }
    }
}
