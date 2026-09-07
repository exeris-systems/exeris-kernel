/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @since 0.5
 */
public final class CoreSslHandles {

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
     *
     * @return the context-lifecycle handle group
     */
    public CtxHandles ctx() {
        return ctx;
    }

    /**
     * Returns handles for connection setup (SSL_new, SSL_accept, SSL_connect, SSL_do_handshake).
     *
     * @return the connection-setup handle group
     */
    public HandshakeHandles handshake() {
        return handshake;
    }

    /**
     * Returns handles for data transfer (SSL_read, SSL_write, SSL_shutdown, SSL_get_error).
     *
     * @return the data-transfer handle group
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
     * @param sslServerMethod          bound to {@code TLS_server_method}; {@code () -> long},
     *                                 returning the native server-method-structure pointer
     * @param sslClientMethod          bound to {@code TLS_client_method}; {@code () -> long},
     *                                 returning the native client-method-structure pointer
     * @param sslCtxNewEx              bound to {@code SSL_CTX_new_ex}; {@code (long libCtx, long propQuery,
     *                                 long methodPtr) -> long}, returning the new {@code SSL_CTX*} pointer,
     *                                 or {@code 0} on failure
     * @param sslCtxFree               bound to {@code SSL_CTX_free}; {@code (long ctxPtr) -> void}
     * @param sslCtxUseCertificateFile bound to {@code SSL_CTX_use_certificate_file};
     *                                 {@code (long ctxPtr, long pathAddr, int fileType) -> int}
     * @param sslCtxUsePrivateKeyFile  bound to {@code SSL_CTX_use_PrivateKey_file};
     *                                 {@code (long ctxPtr, long pathAddr, int fileType) -> int}
     * @param sslCtxCheckPrivateKey    bound to {@code SSL_CTX_check_private_key};
     *                                 {@code (long ctxPtr) -> int}
     * @param sslCtxSetVerify          bound to {@code SSL_CTX_set_verify};
     *                                 {@code (long ctxPtr, int mode, MemorySegment callback) -> void}
     * @param sslCtxSetAlpnProtos      bound to {@code SSL_CTX_set_alpn_protos} if the symbol is present
     *                                 in the loaded OpenSSL build, else {@code null};
     *                                 {@code (long ctxPtr, MemorySegment protos, int protosLen) -> int}
     * @param sslCtxSetAlpnSelectCb    bound to {@code SSL_CTX_set_alpn_select_cb} if the symbol is present
     *                                 in the loaded OpenSSL build, else {@code null};
     *                                 {@code (long ctxPtr, long callbackPtr, long argPtr) -> void}
     * @since 0.5
     */
    public record CtxHandles(
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
         *
         * @return the {@code TLS_server_method()} pointer
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
         *
         * @return the {@code TLS_client_method()} pointer
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
         * @since 0.9
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
         *
         * @param ctxPtr the {@code SSL_CTX*} pointer to free
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
         *
         * @param ctxPtr   the {@code SSL_CTX*} pointer
         * @param pathAddr address of the NUL-terminated certificate file path
         * @param fileType {@code SSL_FILETYPE_*} constant (see {@link CoreOpenSslLoader#SSL_FILETYPE_PEM})
         * @return {@code 1} on success, {@code 0} otherwise
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
         *
         * @param ctxPtr   the {@code SSL_CTX*} pointer
         * @param pathAddr address of the NUL-terminated private key file path
         * @param fileType {@code SSL_FILETYPE_*} constant (see {@link CoreOpenSslLoader#SSL_FILETYPE_PEM})
         * @return {@code 1} on success, {@code 0} otherwise
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
         *
         * @param ctxPtr the {@code SSL_CTX*} pointer
         * @return {@code 1} if the loaded private key matches the loaded certificate, {@code 0} otherwise
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
         *
         * @param ctxPtr the {@code SSL_CTX*} pointer
         * @param mode   {@code SSL_VERIFY_*} constant (see {@link CoreOpenSslLoader#SSL_VERIFY_NONE})
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
         *
         * @param ctxPtr the {@code SSL_CTX*} pointer
         * @param cbPtr  address of the native ALPN selection callback function
         * @param argPtr opaque argument pointer passed through to the callback on every invocation
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
     * @param sslNew         bound to {@code SSL_new}; {@code (long ctxPtr) -> long}, returning the
     *                       new {@code SSL*} pointer, or {@code 0} on failure
     * @param sslFree        bound to {@code SSL_free}; {@code (long sslPtr) -> void}
     * @param sslAccept      bound to {@code SSL_accept}; {@code (long sslPtr) -> int}
     * @param sslConnect     bound to {@code SSL_connect}; {@code (long sslPtr) -> int}
     * @param sslDoHandshake bound to {@code SSL_do_handshake}; {@code (long sslPtr) -> int}
     * @since 0.5
     */
    public record HandshakeHandles(
            MethodHandle sslNew,
            MethodHandle sslFree,
            MethodHandle sslAccept,
            MethodHandle sslConnect,
            MethodHandle sslDoHandshake) {

        /**
         * {@code SSL_new(ctxPtr)} → ssl pointer or 0.
         *
         * @param ctxPtr the {@code SSL_CTX*} pointer to create the session from
         * @return the new {@code SSL*} pointer, or {@code 0} on failure
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
         * @param sslPtr the {@code SSL*} pointer to free
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
         *
         * @param sslPtr the {@code SSL*} pointer
         * @return {@code 1} on success, {@code <= 0} on failure or a non-blocking retry condition
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
         *
         * @param sslPtr the {@code SSL*} pointer
         * @return {@code 1} on success, {@code <= 0} on failure or a non-blocking retry condition
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
         *
         * @param sslPtr the {@code SSL*} pointer
         * @return {@code 1} on success, {@code <= 0} otherwise
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
     * @param sslRead             bound to {@code SSL_read}; {@code (long sslPtr, long bufAddr, int len)
     *                            -> int}, reading up to {@code len} bytes into the buffer at
     *                            {@code bufAddr} and returning the byte count read, or {@code <= 0}
     * @param sslWrite            bound to {@code SSL_write}; {@code (long sslPtr, long bufAddr, int len)
     *                            -> int}, writing up to {@code len} bytes from the buffer at
     *                            {@code bufAddr} and returning the byte count written, or {@code <= 0}
     * @param sslShutdown         bound to {@code SSL_shutdown}; {@code (long sslPtr) -> int}
     * @param sslGetShutdown      bound to {@code SSL_get_shutdown}; {@code (long sslPtr) -> int},
     *                            returning a shutdown-state bitmask
     * @param sslGetError         bound to {@code SSL_get_error}; {@code (long sslPtr, int retCode) ->
     *                            int}, returning an {@code SSL_ERROR_*} constant
     * @param sslGet0AlpnSelected bound to {@code SSL_get0_alpn_selected} if the symbol is present in
     *                            the loaded OpenSSL build, else {@code null}; {@code (long sslPtr,
     *                            long dataAddr, long lenAddr) -> void}, writing the negotiated
     *                            protocol's pointer and length into the two output slots
     * @param sslGetCurrentCipher bound to {@code SSL_get_current_cipher} if the symbol is present in
     *                            the loaded OpenSSL build, else {@code null}; {@code (long sslPtr) ->
     *                            long}, returning the {@code SSL_CIPHER*} pointer, or {@code 0}
     * @param sslCipherGetName    bound to {@code SSL_CIPHER_get_name} if the symbol is present in the
     *                            loaded OpenSSL build, else {@code null}; {@code (long cipherPtr) ->
     *                            long}, returning a pointer to a native UTF-8 C string, or {@code 0}
     * @since 0.5
     */
    public record IoHandles(
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
         * @param sslPtr  the {@code SSL*} pointer
         * @param bufAddr address of the destination buffer
         * @param len     maximum number of bytes to read into the buffer
         * @return the number of bytes read, or {@code <= 0} on failure or a non-blocking retry
         *         condition
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
         *
         * @param sslPtr  the {@code SSL*} pointer
         * @param bufAddr address of the source buffer
         * @param len     number of bytes to write from the buffer
         * @return the number of bytes written, or {@code <= 0} on failure or a non-blocking retry
         *         condition
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
         *
         * @param sslPtr the {@code SSL*} pointer
         * @return {@code 1} if the shutdown handshake is complete, {@code 0} if only this side's
         *         close-notify has been sent, or a negative value on error
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
         *
         * @param sslPtr the {@code SSL*} pointer
         * @return the shutdown-state bitmask
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
         *
         * @param sslPtr  the {@code SSL*} pointer
         * @param retCode the return value of the preceding {@code SSL_read}/{@code SSL_write}/
         *                {@code SSL_accept}/{@code SSL_connect}/{@code SSL_do_handshake}/
         *                {@code SSL_shutdown} call to diagnose
         * @return an {@code SSL_ERROR_*} constant (see {@link CoreOpenSslLoader#SSL_ERROR_SSL} and
         *         its siblings)
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
         *
         * @param sslPtr   the {@code SSL*} pointer
         * @param dataAddr address of the output slot the negotiated protocol's data pointer is
         *                 written to
         * @param lenAddr  address of the output slot the negotiated protocol's byte length is
         *                 written to
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
         *
         * @param sslPtr the {@code SSL*} pointer
         * @return the {@code SSL_CIPHER*} pointer, or {@code 0}
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
         *
         * @param cipherPtr the {@code SSL_CIPHER*} pointer
         * @return native address of the NUL-terminated UTF-8 cipher name, or {@code 0}
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
