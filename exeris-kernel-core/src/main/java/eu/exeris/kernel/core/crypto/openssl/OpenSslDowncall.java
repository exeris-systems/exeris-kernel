/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
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
 * @since 0.13
 */
enum OpenSslDowncall {

    TLS_SERVER_METHOD("TLS_server_method", FunctionDescriptor.of(JAVA_LONG)),
    TLS_CLIENT_METHOD("TLS_client_method", FunctionDescriptor.of(JAVA_LONG)),
    SSL_CTX_NEW_EX("SSL_CTX_new_ex", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG)),
    SSL_CTX_FREE("SSL_CTX_free", FunctionDescriptor.ofVoid(JAVA_LONG)),
    SSL_CTX_USE_CERTIFICATE_FILE("SSL_CTX_use_certificate_file",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT)),
    SSL_CTX_USE_CERTIFICATE_CHAIN_FILE("SSL_CTX_use_certificate_chain_file",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG)),
    SSL_CTX_USE_PRIVATEKEY_FILE("SSL_CTX_use_PrivateKey_file",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT)),
    SSL_CTX_CHECK_PRIVATE_KEY("SSL_CTX_check_private_key", FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
    SSL_CTX_SET_VERIFY("SSL_CTX_set_verify", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, ADDRESS)),
    SSL_CTX_SET_ALPN_PROTOS("SSL_CTX_set_alpn_protos",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT)),
    SSL_CTX_SET_ALPN_SELECT_CB("SSL_CTX_set_alpn_select_cb",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG)),
    SSL_CTX_CTRL("SSL_CTX_ctrl",
            FunctionDescriptor.of(cLong(), JAVA_LONG, JAVA_INT, cLong(), JAVA_LONG)),

    SSL_NEW("SSL_new", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG)),
    SSL_FREE("SSL_free", FunctionDescriptor.ofVoid(JAVA_LONG)),
    SSL_ACCEPT("SSL_accept", FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
    SSL_CONNECT("SSL_connect", FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
    SSL_DO_HANDSHAKE("SSL_do_handshake", FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),

    SSL_READ("SSL_read", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT)),
    SSL_WRITE("SSL_write", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT)),
    SSL_SHUTDOWN("SSL_shutdown", FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
    SSL_GET_SHUTDOWN("SSL_get_shutdown", FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
    SSL_GET_ERROR("SSL_get_error", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT)),
    SSL_GET0_ALPN_SELECTED("SSL_get0_alpn_selected",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG)),
    SSL_GET_CURRENT_CIPHER("SSL_get_current_cipher", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG)),
    SSL_CIPHER_GET_NAME("SSL_CIPHER_get_name", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG)),

    ERR_CLEAR_ERROR("ERR_clear_error", FunctionDescriptor.ofVoid()),

    SSL_CTX_SET1_CERT_STORE("SSL_CTX_set1_cert_store", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG)),
    SSL_GET0_PARAM("SSL_get0_param", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG)),
    X509_VERIFY_PARAM_SET1_HOST("X509_VERIFY_PARAM_set1_host",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG)),
    X509_VERIFY_PARAM_SET_HOSTFLAGS("X509_VERIFY_PARAM_set_hostflags",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT)),
    X509_VERIFY_PARAM_SET1_IP("X509_VERIFY_PARAM_set1_ip",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG)),
    SSL_CTRL("SSL_ctrl",
            FunctionDescriptor.of(cLong(), JAVA_LONG, JAVA_INT, cLong(), JAVA_LONG)),
    SSL_GET_VERIFY_RESULT("SSL_get_verify_result", FunctionDescriptor.of(cLong(), JAVA_LONG)),
    X509_VERIFY_CERT_ERROR_STRING("X509_verify_cert_error_string", FunctionDescriptor.of(JAVA_LONG, cLong())),

    X509_STORE_NEW("X509_STORE_new", FunctionDescriptor.of(JAVA_LONG)),
    X509_STORE_FREE("X509_STORE_free", FunctionDescriptor.ofVoid(JAVA_LONG)),
    X509_STORE_SET_DEFAULT_PATHS("X509_STORE_set_default_paths", FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
    X509_STORE_LOAD_FILE("X509_STORE_load_file", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG)),
    X509_GET_DEFAULT_CERT_FILE("X509_get_default_cert_file", FunctionDescriptor.of(JAVA_LONG)),
    X509_GET_DEFAULT_CERT_DIR("X509_get_default_cert_dir", FunctionDescriptor.of(JAVA_LONG)),
    X509_GET_DEFAULT_CERT_FILE_ENV("X509_get_default_cert_file_env", FunctionDescriptor.of(JAVA_LONG)),
    X509_GET_DEFAULT_CERT_DIR_ENV("X509_get_default_cert_dir_env", FunctionDescriptor.of(JAVA_LONG)),

    OPENSSL_VERSION_NUM("OpenSSL_version_num", FunctionDescriptor.of(JAVA_LONG)),
    OPENSSL_VERSION_MAJOR("OPENSSL_version_major", FunctionDescriptor.of(JAVA_INT)),
    OPENSSL_VERSION_MINOR("OPENSSL_version_minor", FunctionDescriptor.of(JAVA_INT)),
    OPENSSL_VERSION("OpenSSL_version", FunctionDescriptor.of(ADDRESS, JAVA_INT));

    private final String symbol;
    private final FunctionDescriptor descriptor;

    OpenSslDowncall(String symbol, FunctionDescriptor descriptor) {
        this.symbol = symbol;
        this.descriptor = descriptor;
    }

    /* default */ String symbol() {
        return symbol;
    }

    /* default */ FunctionDescriptor descriptor() {
        return descriptor;
    }

    private static ValueLayout cLong() {
        return (ValueLayout) Linker.nativeLinker().canonicalLayouts().get("long");
    }
}
