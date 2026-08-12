/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.openssl;

import eu.exeris.kernel.spi.exceptions.crypto.TlsDecryptException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * Immutable carrier for core OpenSSL Panama FFM method handles.
 *
 * <h2>Split Design — three inner records</h2>
 * <ul>
 *   <li>{@link CtxHandles}       — {@code SSL_CTX_*} lifecycle</li>
 *   <li>{@link HandshakeHandles} — connection setup (new/free/accept/connect/doHandshake)</li>
 *   <li>{@link IoHandles}        — data transfer (read/write/shutdown/error/alpn)</li>
 * </ul>
 * Each record stays under the PMD {@code CyclomaticComplexity} class threshold.
 * Callers access via {@link #ctx()}, {@link #handshake()}, {@link #ioHandles()}.
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>All buffer addresses passed as raw {@code long} — no heap wrapper allocation per call.
 *
 * @since 0.5.0
 */
public value class CoreSslHandles {

    private final CtxHandles ctx;
    private final HandshakeHandles handshake;
    private final IoHandles ioHandles;

    /* package */ CoreSslHandles(CtxHandles ctx, HandshakeHandles handshake,
                                 IoHandles ioHandles) {
        this.ctx = ctx;
        this.handshake = handshake;
        this.ioHandles = ioHandles;
    }

    /**
     * Returns handles for {@code SSL_CTX_*} lifecycle operations.
     */
    public CtxHandles ctx() {
        return ctx;
    }

    /**
     * Returns handles for connection setup (SSL_new, SSL_accept, SSL_connect, SSL_do_handshake).
     */
    public HandshakeHandles handshake() {
        return handshake;
    }

    /**
     * Returns handles for data transfer (SSL_read, SSL_write, SSL_shutdown, SSL_get_error).
     */
    public IoHandles ioHandles() {
        return ioHandles;
    }

    // =========================================================================
    // SSL_CTX lifecycle handles
    // =========================================================================

    /**
     * Handles for {@code SSL_CTX_*} — context bootstrap and teardown only.
     *
     * @since 0.5.0
     */
    public value record CtxHandles(
            MethodHandle sslServerMethod,
            MethodHandle sslClientMethod,
            MethodHandle sslCtxNewEx,
            MethodHandle sslCtxFree,
            MethodHandle sslCtxUseCertificateFile,
            MethodHandle sslCtxUsePrivateKeyFile,
            MethodHandle sslCtxCheckPrivateKey,
            MethodHandle sslCtxSetVerify,
            MethodHandle sslCtxSetAlpnProtos,
            MethodHandle sslCtxSetAlpnSelectCb) {

        /**
         * {@code TLS_server_method()} → native method pointer for {@code SSL_CTX_new_ex}.
         * Returns the pointer to the server-side TLS 1.3 method structure.
         */
        public long invokeServerMethod() {
            try {
                return (long) sslServerMethod.invokeExact();
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("TLS_server_method failed", t);
            }
        }

        /**
         * {@code TLS_client_method()} → native method pointer for {@code SSL_CTX_new_ex}.
         * Returns the pointer to the client-side TLS 1.3 method structure.
         */
        public long invokeClientMethod() {
            try {
                return (long) sslClientMethod.invokeExact();
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("TLS_client_method failed", t);
            }
        }

        /**
         * {@code SSL_CTX_new_ex(libctx, propq, methodPtr)} → ctx pointer or 0.
         *
         * <p>The {@code libctx} and {@code propq} arguments are passed as {@code 0L}
         * (NULL): the default library context and default property query. They are an
         * opaque seam for a future caller that may supply a non-default library context;
         * Core treats them as plain pointers and attaches no further meaning.
         *
         * @param methodPtr the {@code TLS_*_method()} pointer
         * @return the {@code SSL_CTX*} pointer, or 0 on failure
         * @since 0.9.0
         */
        public long invokeCtxNew(long methodPtr) {
            try {
                return (long) sslCtxNewEx.invokeExact(0L, 0L, methodPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_CTX_new_ex failed", t);
            }
        }

        /**
         * {@code SSL_CTX_free(ctxPtr)} — best effort.
         */
        public void invokeCtxFree(long ctxPtr) {
            try {
                sslCtxFree.invokeExact(ctxPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — best-effort cleanup
                FfmErrors.rethrowIfError(t);
                if (t instanceof RuntimeException rte) {
                    throw rte; //NOPMD PreserveStackTrace — rte is t via pattern match; identical object and stack trace
                }
            }
        }

        /**
         * {@code SSL_CTX_use_certificate_file} → 1 on success.
         */
        public int invokeCtxUseCertFile(long ctxPtr, long pathAddr, int fileType) {
            try {
                return (int) sslCtxUseCertificateFile.invokeExact(ctxPtr, pathAddr, fileType);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_CTX_use_certificate_file failed", t);
            }
        }

        /**
         * {@code SSL_CTX_use_PrivateKey_file} → 1 on success.
         */
        public int invokeCtxUseKeyFile(long ctxPtr, long pathAddr, int fileType) {
            try {
                return (int) sslCtxUsePrivateKeyFile.invokeExact(ctxPtr, pathAddr, fileType);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_CTX_use_PrivateKey_file failed", t);
            }
        }

        /**
         * {@code SSL_CTX_check_private_key} → 1 on success.
         */
        public int invokeCtxCheckKey(long ctxPtr) {
            try {
                return (int) sslCtxCheckPrivateKey.invokeExact(ctxPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_CTX_check_private_key failed", t);
            }
        }

        /**
         * {@code SSL_CTX_set_verify(ctxPtr, mode, NULL)}.
         */
        public void invokeCtxSetVerify(long ctxPtr, int mode) {
            try {
                sslCtxSetVerify.invokeExact(ctxPtr, mode, MemorySegment.NULL);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_CTX_set_verify failed", t);
            }
        }

        /**
         * {@code SSL_CTX_set_alpn_select_cb(ctx, cb, arg)} — installs server-side ALPN selection callback.
         * No-op if handle is {@code null} (symbol absent in this OpenSSL build).
         */
        public void invokeCtxSetAlpnSelectCb(long ctxPtr, long cbPtr, long argPtr) {
            if (sslCtxSetAlpnSelectCb == null) {
                return;
            }
            try {
                sslCtxSetAlpnSelectCb.invokeExact(ctxPtr, cbPtr, argPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_CTX_set_alpn_select_cb failed", t);
            }
        }
    }

    // =========================================================================
    // Connection setup handles
    // =========================================================================

    /**
     * Handles for SSL session setup — {@code SSL_new}, {@code SSL_free},
     * {@code SSL_accept}, {@code SSL_connect}, {@code SSL_do_handshake}.
     *
     * @since 0.5.0
     */
    public value record HandshakeHandles(
            MethodHandle sslNew,
            MethodHandle sslFree,
            MethodHandle sslAccept,
            MethodHandle sslConnect,
            MethodHandle sslDoHandshake) {

        /**
         * {@code SSL_new(ctxPtr)} → ssl pointer or 0.
         */
        public long invokeSslNew(long ctxPtr) {
            try {
                return (long) sslNew.invokeExact(ctxPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_new failed", t);
            }
        }

        /**
         * {@code SSL_free(sslPtr)}.
         *
         * <p>Propagates any {@link Throwable} thrown by the FFM invocation so that
         * {@code NativeCipherContext.release()} can absorb
         * {@link NativeCipherContextFreeFailureEvent}. Failure absorption belongs at
         * the destructor call-site, not here, to prevent silent native-heap leaks.
         *
         * @throws TlsException wrapping any FFM-layer throwable
         */
        public void invokeSslFree(long sslPtr) {
            try {
                sslFree.invokeExact(sslPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_free failed", t);
            }
        }


        /**
         * {@code SSL_accept(sslPtr)} → 1 on success.
         */
        public int invokeSslAccept(long sslPtr) {
            try {
                return (int) sslAccept.invokeExact(sslPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsHandshakeException("SSL_accept failed", t);
            }
        }

        /**
         * {@code SSL_connect(sslPtr)} → 1 on success.
         */
        public int invokeSslConnect(long sslPtr) {
            try {
                return (int) sslConnect.invokeExact(sslPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsHandshakeException("SSL_connect failed", t);
            }
        }

        /**
         * {@code SSL_do_handshake(sslPtr)} → 1 on success.
         */
        public int invokeDoHandshake(long sslPtr) {
            try {
                return (int) sslDoHandshake.invokeExact(sslPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsHandshakeException("SSL_do_handshake failed", t);
            }
        }
    }

    // =========================================================================
    // Data I/O handles
    // =========================================================================

    /**
     * Handles for data transfer — {@code SSL_read}, {@code SSL_write},
     * {@code SSL_shutdown}, {@code SSL_get_shutdown}, {@code SSL_get_error},
     * {@code SSL_get0_alpn_selected}, {@code SSL_get_current_cipher},
     * {@code SSL_CIPHER_get_name}.
     *
     * @since 0.5.0
     */
    public value record IoHandles(
            MethodHandle sslRead,
            MethodHandle sslWrite,
            MethodHandle sslShutdown,
            MethodHandle sslGetShutdown,
            MethodHandle sslGetError,
            MethodHandle sslGet0AlpnSelected,
            MethodHandle sslGetCurrentCipher,
            MethodHandle sslCipherGetName) {

        /**
         * {@code SSL_read(sslPtr, bufAddr, len)} — zero-copy raw address.
         *
         * @throws TlsDecryptException on FFM invocation failure ({@code EX-NET-2003})
         */
        public int invokeRead(long sslPtr, long bufAddr, int len) {
            try {
                return (int) sslRead.invokeExact(sslPtr, bufAddr, len);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsDecryptException("SSL_read failed", t);
            }
        }

        /**
         * {@code SSL_write(sslPtr, bufAddr, len)} — zero-copy raw address.
         */
        public int invokeWrite(long sslPtr, long bufAddr, int len) {
            try {
                return (int) sslWrite.invokeExact(sslPtr, bufAddr, len);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_write failed", t);
            }
        }

        /**
         * {@code SSL_shutdown(sslPtr)} → 0 or 1.
         */
        public int invokeShutdown(long sslPtr) {
            try {
                return (int) sslShutdown.invokeExact(sslPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_shutdown failed", t);
            }
        }

        /**
         * {@code SSL_get_shutdown(sslPtr)} → bitmask.
         */
        public int invokeGetShutdown(long sslPtr) {
            try {
                return (int) sslGetShutdown.invokeExact(sslPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_get_shutdown failed", t);
            }
        }

        /**
         * {@code SSL_get_error(sslPtr, retCode)} → SSL_ERROR_* constant.
         */
        public int invokeGetError(long sslPtr, int retCode) {
            try {
                return (int) sslGetError.invokeExact(sslPtr, retCode);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_get_error failed", t);
            }
        }

        /**
         * {@code SSL_get0_alpn_selected} — writes pointer+length into output slots.
         * No-op if handle is {@code null} (symbol absent in this OpenSSL build).
         */
        public void invokeGetAlpnSelected(long sslPtr, long dataAddr, long lenAddr) {
            if (sslGet0AlpnSelected == null) {
                return;
            }
            try {
                sslGet0AlpnSelected.invokeExact(sslPtr, dataAddr, lenAddr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_get0_alpn_selected failed", t);
            }
        }

        /**
         * {@code SSL_get_current_cipher(sslPtr)} → raw pointer to the {@code SSL_CIPHER} struct, or 0.
         * Returns 0 if the handle is {@code null} (symbol absent) or handshake is not complete.
         */
        public long invokeGetCurrentCipher(long sslPtr) {
            if (sslGetCurrentCipher == null) {
                return 0L;
            }
            try {
                return (long) sslGetCurrentCipher.invokeExact(sslPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_get_current_cipher failed", t);
            }
        }

        /**
         * {@code SSL_CIPHER_get_name(cipherPtr)} → native C string pointer (UTF-8), or 0.
         * Returns 0 if {@code cipherPtr == 0} or the handle is {@code null}.
         *
         * <p>The returned pointer is valid as long as the OpenSSL library is loaded.
         * Callers MUST read the string before the library is unloaded.
         */
        public long invokeCipherGetName(long cipherPtr) {
            if (sslCipherGetName == null || cipherPtr == 0L) {
                return 0L;
            }
            try {
                return (long) sslCipherGetName.invokeExact(cipherPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_CIPHER_get_name failed", t);
            }
        }
    }
}
