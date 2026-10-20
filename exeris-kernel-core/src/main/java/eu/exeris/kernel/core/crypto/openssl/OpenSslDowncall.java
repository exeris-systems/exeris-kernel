/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Descriptor table for all OpenSSL downcalls used by Core.
 *
 * <p>Descriptors are built once during enum class initialisation. This enum is first touched only after
 * {@link CoreOpenSslLoader#load(java.lang.foreign.Arena)} has called {@link Linker#nativeLinker()},
 * ensuring an unsupported platform fails in {@code load()} with {@link UnsupportedOperationException}
 * rather than in {@code <clinit>} with {@link ExceptionInInitializerError}.
 *
 * <p>Each constant also states which library it is resolved from and whether its absence is fatal, so
 * the loader and the ABI symbol-resolution conformance suite read the same table.
 *
 * @since 0.13
 */
enum OpenSslDowncall {

    TLS_SERVER_METHOD("TLS_server_method", FunctionDescriptor.of(JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    TLS_CLIENT_METHOD("TLS_client_method", FunctionDescriptor.of(JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    SSL_CTX_NEW_EX("SSL_CTX_new_ex", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    SSL_CTX_FREE("SSL_CTX_free", FunctionDescriptor.ofVoid(JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    SSL_CTX_USE_CERTIFICATE_FILE("SSL_CTX_use_certificate_file",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT),
            Library.EITHER, Resolution.REQUIRED),
    SSL_CTX_USE_CERTIFICATE_CHAIN_FILE("SSL_CTX_use_certificate_chain_file",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    SSL_CTX_USE_PRIVATEKEY_FILE("SSL_CTX_use_PrivateKey_file",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT),
            Library.EITHER, Resolution.REQUIRED),
    SSL_CTX_CHECK_PRIVATE_KEY("SSL_CTX_check_private_key", FunctionDescriptor.of(JAVA_INT, JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    SSL_CTX_SET_VERIFY("SSL_CTX_set_verify", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, ADDRESS),
            Library.EITHER, Resolution.REQUIRED),
    SSL_CTX_SET_ALPN_PROTOS("SSL_CTX_set_alpn_protos",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT),
            Library.EITHER, Resolution.OPTIONAL),
    SSL_CTX_SET_ALPN_SELECT_CB("SSL_CTX_set_alpn_select_cb",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG),
            Library.EITHER, Resolution.OPTIONAL),
    SSL_CTX_CTRL("SSL_CTX_ctrl",
            FunctionDescriptor.of(cLong(), JAVA_LONG, JAVA_INT, cLong(), JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),

    SSL_NEW("SSL_new", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    SSL_FREE("SSL_free", FunctionDescriptor.ofVoid(JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    SSL_ACCEPT("SSL_accept", FunctionDescriptor.of(JAVA_INT, JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    SSL_CONNECT("SSL_connect", FunctionDescriptor.of(JAVA_INT, JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    SSL_DO_HANDSHAKE("SSL_do_handshake", FunctionDescriptor.of(JAVA_INT, JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),

    SSL_READ("SSL_read", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT),
            Library.EITHER, Resolution.REQUIRED),
    SSL_WRITE("SSL_write", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT),
            Library.EITHER, Resolution.REQUIRED),
    SSL_SHUTDOWN("SSL_shutdown", FunctionDescriptor.of(JAVA_INT, JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    SSL_GET_SHUTDOWN("SSL_get_shutdown", FunctionDescriptor.of(JAVA_INT, JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    SSL_GET_ERROR("SSL_get_error", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT),
            Library.EITHER, Resolution.REQUIRED),
    SSL_GET0_ALPN_SELECTED("SSL_get0_alpn_selected",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG),
            Library.EITHER, Resolution.OPTIONAL),
    SSL_GET_CURRENT_CIPHER("SSL_get_current_cipher", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG),
            Library.EITHER, Resolution.OPTIONAL),
    SSL_CIPHER_GET_NAME("SSL_CIPHER_get_name", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG),
            Library.EITHER, Resolution.OPTIONAL),

    ERR_CLEAR_ERROR("ERR_clear_error", FunctionDescriptor.ofVoid(),
            Library.CRYPTO, Resolution.REQUIRED),

    SSL_CTX_SET1_CERT_STORE("SSL_CTX_set1_cert_store", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
            Library.SSL, Resolution.REQUIRED),
    SSL_GET0_PARAM("SSL_get0_param", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG),
            Library.SSL, Resolution.REQUIRED),
    X509_VERIFY_PARAM_SET1_HOST("X509_VERIFY_PARAM_set1_host",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG),
            Library.CRYPTO, Resolution.REQUIRED),
    X509_VERIFY_PARAM_SET_HOSTFLAGS("X509_VERIFY_PARAM_set_hostflags",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT),
            Library.CRYPTO, Resolution.REQUIRED),
    X509_VERIFY_PARAM_SET1_IP("X509_VERIFY_PARAM_set1_ip",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG),
            Library.CRYPTO, Resolution.REQUIRED),
    SSL_CTRL("SSL_ctrl",
            FunctionDescriptor.of(cLong(), JAVA_LONG, JAVA_INT, cLong(), JAVA_LONG),
            Library.SSL, Resolution.REQUIRED),
    SSL_GET_VERIFY_RESULT("SSL_get_verify_result", FunctionDescriptor.of(cLong(), JAVA_LONG),
            Library.SSL, Resolution.REQUIRED),
    X509_VERIFY_CERT_ERROR_STRING("X509_verify_cert_error_string", FunctionDescriptor.of(JAVA_LONG, cLong()),
            Library.CRYPTO, Resolution.REQUIRED),

    X509_STORE_NEW("X509_STORE_new", FunctionDescriptor.of(JAVA_LONG),
            Library.CRYPTO, Resolution.REQUIRED),
    X509_STORE_FREE("X509_STORE_free", FunctionDescriptor.ofVoid(JAVA_LONG),
            Library.CRYPTO, Resolution.REQUIRED),
    X509_STORE_SET_DEFAULT_PATHS("X509_STORE_set_default_paths", FunctionDescriptor.of(JAVA_INT, JAVA_LONG),
            Library.CRYPTO, Resolution.REQUIRED),
    X509_STORE_LOAD_FILE("X509_STORE_load_file", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG),
            Library.CRYPTO, Resolution.REQUIRED),
    X509_GET_DEFAULT_CERT_FILE("X509_get_default_cert_file", FunctionDescriptor.of(JAVA_LONG),
            Library.CRYPTO, Resolution.REQUIRED),
    X509_GET_DEFAULT_CERT_DIR("X509_get_default_cert_dir", FunctionDescriptor.of(JAVA_LONG),
            Library.CRYPTO, Resolution.REQUIRED),
    X509_GET_DEFAULT_CERT_FILE_ENV("X509_get_default_cert_file_env", FunctionDescriptor.of(JAVA_LONG),
            Library.CRYPTO, Resolution.REQUIRED),
    X509_GET_DEFAULT_CERT_DIR_ENV("X509_get_default_cert_dir_env", FunctionDescriptor.of(JAVA_LONG),
            Library.CRYPTO, Resolution.REQUIRED),

    OPENSSL_CLEANSE("OPENSSL_cleanse", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
            Library.CRYPTO, Resolution.REQUIRED),

    OPENSSL_VERSION_NUM("OpenSSL_version_num", FunctionDescriptor.of(JAVA_LONG),
            Library.EITHER, Resolution.REQUIRED),
    OPENSSL_VERSION_MAJOR("OPENSSL_version_major", FunctionDescriptor.of(JAVA_INT),
            Library.EITHER, Resolution.REQUIRED),
    OPENSSL_VERSION_MINOR("OPENSSL_version_minor", FunctionDescriptor.of(JAVA_INT),
            Library.EITHER, Resolution.REQUIRED),
    OPENSSL_VERSION("OpenSSL_version", FunctionDescriptor.of(ADDRESS, JAVA_INT),
            Library.EITHER, Resolution.OPTIONAL);

    /** Which library a symbol is resolved from. */
    /* default */ enum Library {
        /** Resolved against {@code libssl} only. */
        SSL,
        /** Resolved against {@code libcrypto} only. */
        CRYPTO,
        /** Resolved against {@code libssl} first, then {@code libcrypto}. */
        EITHER;

        /**
         * Picks the scope this library names.
         *
         * @param merged the {@code libssl}-then-{@code libcrypto} lookup
         * @param ssl    the {@code libssl} lookup
         * @param crypto the {@code libcrypto} lookup
         * @return the lookup a symbol of this library is resolved against
         */
        /* default */ SymbolLookup select(SymbolLookup merged, SymbolLookup ssl, SymbolLookup crypto) {
            return switch (this) {
                case SSL -> ssl;
                case CRYPTO -> crypto;
                case EITHER -> merged;
            };
        }
    }

    /** Whether a missing symbol fails the bootstrap. */
    /* default */ enum Resolution {
        /** A missing symbol fails {@link CoreOpenSslLoader#load} with {@code EX-NET-2002}. */
        REQUIRED,
        /** A missing symbol leaves its handle {@code null}; the caller degrades the feature. */
        OPTIONAL
    }

    private final String symbol;
    private final FunctionDescriptor descriptor;
    private final Library library;
    private final Resolution resolution;

    OpenSslDowncall(String symbol, FunctionDescriptor descriptor, Library library, Resolution resolution) {
        this.symbol = symbol;
        this.descriptor = descriptor;
        this.library = library;
        this.resolution = resolution;
    }

    /* default */ String symbol() {
        return symbol;
    }

    /* default */ FunctionDescriptor descriptor() {
        return descriptor;
    }

    /* default */ Library library() {
        return library;
    }

    /* default */ Resolution resolution() {
        return resolution;
    }

    private static ValueLayout cLong() {
        return (ValueLayout) Linker.nativeLinker().canonicalLayouts().get("long");
    }
}
