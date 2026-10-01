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
 * <h2>Split Design — one inner record per concern</h2>
 * <ul>
 *   <li>{@link CtxHandles}        — {@code SSL_CTX_*} lifecycle</li>
 *   <li>{@link HandshakeHandles}  — connection setup (new/free/accept/connect/doHandshake)</li>
 *   <li>{@link IoHandles}         — data transfer (read/write/shutdown/error/alpn)</li>
 *   <li>{@link ErrorQueueHandles} — the calling thread's OpenSSL error queue</li>
 *   <li>{@link PeerVerificationHandles} — the identity a client expects of its server, and the
 *       result of checking it</li>
 *   <li>{@link TrustStoreHandles} — the {@code X509_STORE} a client verifies against</li>
 * </ul>
 * Each record stays under the PMD {@code CyclomaticComplexity} class threshold.
 * Callers access via {@link #ctx()}, {@link #handshake()}, {@link #ioHandles()},
 * {@link #errorQueue()}, {@link #peerVerification()}, {@link #trustStore()}.
 *
 * <h2>C {@code long}</h2>
 * <p>A handle whose native signature uses C {@code long} is bound with the platform's canonical
 * {@code long} layout and then cast to a fixed {@code long}-typed Java signature, so every
 * {@code invokeExact} below has the same shape on LP64 and LLP64.
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
    private final ErrorQueueHandles errorQueue;
    private final PeerVerificationHandles peerVerification;
    private final TrustStoreHandles trustStore;

    /* package */ CoreSslHandles(CtxHandles ctx, HandshakeHandles handshake,
                                 IoHandles ioHandles, ErrorQueueHandles errorQueue,
                                 PeerVerificationHandles peerVerification,
                                 TrustStoreHandles trustStore) {
        this.ctx = ctx;
        this.handshake = handshake;
        this.ioHandles = ioHandles;
        this.errorQueue = errorQueue;
        this.peerVerification = peerVerification;
        this.trustStore = trustStore;
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

    /**
     * Returns the handle that empties the calling thread's OpenSSL error queue.
     *
     * @return the error-queue handle group
     * @since 0.12
     */
    public ErrorQueueHandles errorQueue() {
        return errorQueue;
    }

    /**
     * Returns the handles that set the identity a client expects of its server and read the
     * verification result.
     *
     * @return the peer-verification handle group
     * @since 0.12
     */
    public PeerVerificationHandles peerVerification() {
        return peerVerification;
    }

    /**
     * Returns the handles that build and fill an {@code X509_STORE}.
     *
     * @return the trust-store handle group
     * @since 0.12
     */
    public TrustStoreHandles trustStore() {
        return trustStore;
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
        // invokeExact declares Throwable: Errors are rethrown first, then a RuntimeException as is.
        @SuppressWarnings("PMD.AvoidInstanceofChecksInCatchClause")
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

    // =========================================================================
    // Error queue handles
    // =========================================================================

    /**
     * Handle for {@code ERR_clear_error} — empties the error queue OpenSSL keeps per OS thread.
     *
     * <p>{@code SSL_get_error} reports {@code SSL_ERROR_SSL} for a failed or retryable call whenever
     * that queue holds an entry, whichever connection left it there. An entry one connection leaves
     * behind therefore turns a neighbour's {@code SSL_ERROR_WANT_READ} into a fatal error on the
     * same thread, which is why every fatal outcome empties the queue before returning.
     *
     * @param errClearError bound to {@code ERR_clear_error}; {@code () -> void}
     * @since 0.12
     */
    public record ErrorQueueHandles(MethodHandle errClearError) {

        /**
         * {@code ERR_clear_error()} — empties the calling OS thread's OpenSSL error queue.
         *
         * @throws TlsException ({@code EX-NET-2001}) wrapping any FFM-layer throwable that is not an {@link Error}
         */
        public void invokeClearError() {
            try {
                errClearError.invokeExact();
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("ERR_clear_error failed", t);
            }
        }
    }

    // =========================================================================
    // Peer verification handles
    // =========================================================================

    /**
     * Handles for client-side server verification: the trust a context verifies against, the
     * identity a session expects, the server name it sends, and the result.
     *
     * <p>{@code sslGet0Param} returns a borrowed {@code X509_VERIFY_PARAM*} that the {@code SSL}
     * owns; a caller never frees it. {@code X509_VERIFY_PARAM_set1_host},
     * {@code X509_VERIFY_PARAM_set1_ip} and {@code SSL_ctrl(SSL_CTRL_SET_TLSEXT_HOSTNAME)} copy
     * their input, so the buffer a caller passes may be released as soon as the call returns.
     * {@code SSL_CTX_set1_cert_store} takes its own reference to the store.
     *
     * @param sslCtxSet1CertStore   bound to {@code SSL_CTX_set1_cert_store};
     *                              {@code (long ctxPtr, long storePtr) -> void}
     * @param sslGet0Param          bound to {@code SSL_get0_param}; {@code (long sslPtr) -> long}
     * @param paramSet1Host         bound to {@code X509_VERIFY_PARAM_set1_host};
     *                              {@code (long paramPtr, long nameAddr, long nameLen) -> int}
     * @param paramSetHostflags     bound to {@code X509_VERIFY_PARAM_set_hostflags};
     *                              {@code (long paramPtr, int flags) -> void}
     * @param paramSet1Ip           bound to {@code X509_VERIFY_PARAM_set1_ip};
     *                              {@code (long paramPtr, long ipAddr, long ipLen) -> int}
     * @param sslCtrl               bound to {@code SSL_ctrl}, cast to
     *                              {@code (long sslPtr, int cmd, long larg, long parg) -> long}
     * @param sslGetVerifyResult    bound to {@code SSL_get_verify_result}, cast to
     *                              {@code (long sslPtr) -> long}
     * @param verifyCertErrorString bound to {@code X509_verify_cert_error_string}, cast to
     *                              {@code (long code) -> long}, returning a constant C string
     * @since 0.12
     */
    public record PeerVerificationHandles(
            MethodHandle sslCtxSet1CertStore,
            MethodHandle sslGet0Param,
            MethodHandle paramSet1Host,
            MethodHandle paramSetHostflags,
            MethodHandle paramSet1Ip,
            MethodHandle sslCtrl,
            MethodHandle sslGetVerifyResult,
            MethodHandle verifyCertErrorString) {

        /**
         * {@code SSL_CTX_set1_cert_store(ctxPtr, storePtr)} — the context takes its own reference.
         *
         * @param ctxPtr   the {@code SSL_CTX*} pointer
         * @param storePtr the {@code X509_STORE*} pointer
         */
        public void invokeCtxSet1CertStore(long ctxPtr, long storePtr) {
            try {
                sslCtxSet1CertStore.invokeExact(ctxPtr, storePtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_CTX_set1_cert_store failed", t);
            }
        }

        /**
         * {@code SSL_get0_param(sslPtr)} — the session's own verification parameters, borrowed.
         *
         * @param sslPtr the {@code SSL*} pointer
         * @return the {@code X509_VERIFY_PARAM*} pointer; never to be freed by the caller
         */
        public long invokeGet0Param(long sslPtr) {
            try {
                return (long) sslGet0Param.invokeExact(sslPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_get0_param failed", t);
            }
        }

        /**
         * {@code X509_VERIFY_PARAM_set1_host(paramPtr, nameAddr, nameLen)} → 1 on success.
         *
         * @param paramPtr the {@code X509_VERIFY_PARAM*} pointer
         * @param nameAddr address of the host name bytes
         * @param nameLen  the name's length in bytes, never {@code 0}: OpenSSL reads a zero length
         *                 as "measure with strlen", and an empty result as "check no host"
         * @return {@code 1} on success, {@code 0} otherwise
         */
        public int invokeParamSet1Host(long paramPtr, long nameAddr, long nameLen) {
            try {
                return (int) paramSet1Host.invokeExact(paramPtr, nameAddr, nameLen);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("X509_VERIFY_PARAM_set1_host failed", t);
            }
        }

        /**
         * {@code X509_VERIFY_PARAM_set_hostflags(paramPtr, flags)}.
         *
         * @param paramPtr the {@code X509_VERIFY_PARAM*} pointer
         * @param flags    {@code X509_CHECK_FLAG_*} bits
         */
        public void invokeParamSetHostflags(long paramPtr, int flags) {
            try {
                paramSetHostflags.invokeExact(paramPtr, flags);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("X509_VERIFY_PARAM_set_hostflags failed", t);
            }
        }

        /**
         * {@code X509_VERIFY_PARAM_set1_ip(paramPtr, ipAddr, ipLen)} → 1 on success.
         *
         * @param paramPtr the {@code X509_VERIFY_PARAM*} pointer
         * @param ipAddr   address of the address bytes, in network order
         * @param ipLen    {@code 4} or {@code 16}
         * @return {@code 1} on success, {@code 0} otherwise
         */
        public int invokeParamSet1Ip(long paramPtr, long ipAddr, long ipLen) {
            try {
                return (int) paramSet1Ip.invokeExact(paramPtr, ipAddr, ipLen);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("X509_VERIFY_PARAM_set1_ip failed", t);
            }
        }

        /**
         * {@code SSL_ctrl(sslPtr, cmd, larg, parg)}.
         *
         * @param sslPtr the {@code SSL*} pointer
         * @param cmd    an {@code SSL_CTRL_*} command
         * @param larg   the command's integer argument
         * @param parg   the command's pointer argument
         * @return the command's result
         */
        public long invokeCtrl(long sslPtr, int cmd, long larg, long parg) {
            try {
                return (long) sslCtrl.invokeExact(sslPtr, cmd, larg, parg);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_ctrl failed", t);
            }
        }

        /**
         * {@code SSL_get_verify_result(sslPtr)} → an {@code X509_V_*} code.
         *
         * @param sslPtr the {@code SSL*} pointer
         * @return {@code X509_V_OK} ({@code 0}) or the first verification error
         */
        public long invokeGetVerifyResult(long sslPtr) {
            try {
                return (long) sslGetVerifyResult.invokeExact(sslPtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("SSL_get_verify_result failed", t);
            }
        }

        /**
         * {@code X509_verify_cert_error_string(code)} → address of a constant C string.
         *
         * @param code an {@code X509_V_*} code
         * @return the address of a NUL-terminated string OpenSSL owns and never frees
         */
        public long invokeVerifyCertErrorString(long code) {
            try {
                return (long) verifyCertErrorString.invokeExact(code);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("X509_verify_cert_error_string failed", t);
            }
        }
    }

    // =========================================================================
    // Trust store handles
    // =========================================================================

    /**
     * Handles for the {@code X509_STORE} a client context verifies its server against, and for the
     * locations OpenSSL reads when told to use its default trust.
     *
     * <p>{@code X509_STORE_new} returns a store holding one reference, which the creator releases
     * with {@code X509_STORE_free}. The {@code X509_get_default_cert_*} functions return constant
     * strings OpenSSL owns.
     *
     * @param storeNew             bound to {@code X509_STORE_new}; {@code () -> long}
     * @param storeFree            bound to {@code X509_STORE_free}; {@code (long storePtr) -> void}
     * @param storeSetDefaultPaths bound to {@code X509_STORE_set_default_paths};
     *                             {@code (long storePtr) -> int}
     * @param storeLoadFile        bound to {@code X509_STORE_load_file};
     *                             {@code (long storePtr, long pathAddr) -> int}
     * @param defaultCertFile      bound to {@code X509_get_default_cert_file}; {@code () -> long}
     * @param defaultCertDir       bound to {@code X509_get_default_cert_dir}; {@code () -> long}
     * @param defaultCertFileEnv   bound to {@code X509_get_default_cert_file_env};
     *                             {@code () -> long}
     * @param defaultCertDirEnv    bound to {@code X509_get_default_cert_dir_env}; {@code () -> long}
     * @since 0.12
     */
    public record TrustStoreHandles(
            MethodHandle storeNew,
            MethodHandle storeFree,
            MethodHandle storeSetDefaultPaths,
            MethodHandle storeLoadFile,
            MethodHandle defaultCertFile,
            MethodHandle defaultCertDir,
            MethodHandle defaultCertFileEnv,
            MethodHandle defaultCertDirEnv) {

        /**
         * {@code X509_STORE_new()} → store pointer or 0.
         *
         * @return the new {@code X509_STORE*}, holding one reference, or {@code 0}
         */
        public long invokeStoreNew() {
            try {
                return (long) storeNew.invokeExact();
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("X509_STORE_new failed", t);
            }
        }

        /**
         * {@code X509_STORE_free(storePtr)} — releases one reference.
         *
         * @param storePtr the {@code X509_STORE*} pointer
         */
        public void invokeStoreFree(long storePtr) {
            try {
                storeFree.invokeExact(storePtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("X509_STORE_free failed", t);
            }
        }

        /**
         * {@code X509_STORE_set_default_paths(storePtr)} → 1 on success.
         *
         * @param storePtr the {@code X509_STORE*} pointer
         * @return {@code 1} on success, {@code 0} otherwise
         */
        public int invokeStoreSetDefaultPaths(long storePtr) {
            try {
                return (int) storeSetDefaultPaths.invokeExact(storePtr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("X509_STORE_set_default_paths failed", t);
            }
        }

        /**
         * {@code X509_STORE_load_file(storePtr, pathAddr)} → 1 on success.
         *
         * @param storePtr the {@code X509_STORE*} pointer
         * @param pathAddr address of the NUL-terminated file path
         * @return {@code 1} on success, {@code 0} otherwise
         */
        public int invokeStoreLoadFile(long storePtr, long pathAddr) {
            try {
                return (int) storeLoadFile.invokeExact(storePtr, pathAddr);
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException("X509_STORE_load_file failed", t);
            }
        }

        /**
         * {@code X509_get_default_cert_file()} → address of a constant C string.
         *
         * @return the compiled-in default CA file path's address
         */
        public long invokeDefaultCertFile() {
            return invokeConstantString(defaultCertFile, "X509_get_default_cert_file failed");
        }

        /**
         * {@code X509_get_default_cert_dir()} → address of a constant C string.
         *
         * @return the compiled-in default CA directory path's address
         */
        public long invokeDefaultCertDir() {
            return invokeConstantString(defaultCertDir, "X509_get_default_cert_dir failed");
        }

        /**
         * {@code X509_get_default_cert_file_env()} → address of a constant C string.
         *
         * @return the address of the name of the environment variable overriding the default file
         */
        public long invokeDefaultCertFileEnv() {
            return invokeConstantString(defaultCertFileEnv, "X509_get_default_cert_file_env failed");
        }

        /**
         * {@code X509_get_default_cert_dir_env()} → address of a constant C string.
         *
         * @return the address of the name of the environment variable overriding the default
         *         directory
         */
        public long invokeDefaultCertDirEnv() {
            return invokeConstantString(defaultCertDirEnv, "X509_get_default_cert_dir_env failed");
        }

        private static long invokeConstantString(MethodHandle handle, String failure) {
            try {
                return (long) handle.invokeExact();
            } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
                FfmErrors.rethrowIfError(t);
                throw new TlsException(failure, t);
            }
        }
    }
}
