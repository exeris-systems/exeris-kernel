/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

import eu.exeris.kernel.core.crypto.openssl.OpenSslDowncall.Library;
import eu.exeris.kernel.core.crypto.openssl.OpenSslDowncall.Resolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static eu.exeris.kernel.core.crypto.openssl.OpenSslDowncall.Library.CRYPTO;
import static eu.exeris.kernel.core.crypto.openssl.OpenSslDowncall.Library.EITHER;
import static eu.exeris.kernel.core.crypto.openssl.OpenSslDowncall.Library.SSL;
import static eu.exeris.kernel.core.crypto.openssl.OpenSslDowncall.Resolution.OPTIONAL;
import static eu.exeris.kernel.core.crypto.openssl.OpenSslDowncall.Resolution.REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins, per {@link OpenSslDowncall} constant, the library it is looked up in and whether its absence
 * is fatal. The loader binds from these attributes, so a change here changes which libraries a symbol
 * may come from and which symbols a load can survive without.
 *
 * <p>The version probes are bound by the version gate, not by {@code bind}: it fails the load with a
 * probe-specific message when {@code OpenSSL_version_num}, {@code OPENSSL_version_major} or
 * {@code OPENSSL_version_minor} is absent, so they are {@code REQUIRED}; {@code OpenSSL_version}
 * only feeds a diagnostic string, so it is {@code OPTIONAL}. The ssl-side {@code OPENSSL_version_major}
 * probe of the cross-major check is a second, tolerant lookup: it is skipped when {@code libssl} does
 * not expose the symbol.
 */
@DisplayName("L0: OpenSslDowncall library and resolution pins")
class CoreOpenSslLoaderResolutionPinTest {

    private record Pin(Library library, Resolution resolution) {
    }

    private static final Map<String, Pin> PINS = Map.ofEntries(
            pin("TLS_SERVER_METHOD", EITHER, REQUIRED),
            pin("TLS_CLIENT_METHOD", EITHER, REQUIRED),
            pin("SSL_CTX_NEW_EX", EITHER, REQUIRED),
            pin("SSL_CTX_FREE", EITHER, REQUIRED),
            pin("SSL_CTX_USE_CERTIFICATE_FILE", EITHER, REQUIRED),
            pin("SSL_CTX_USE_CERTIFICATE_CHAIN_FILE", EITHER, REQUIRED),
            pin("SSL_CTX_USE_PRIVATEKEY_FILE", EITHER, REQUIRED),
            pin("SSL_CTX_CHECK_PRIVATE_KEY", EITHER, REQUIRED),
            pin("SSL_CTX_SET_VERIFY", EITHER, REQUIRED),
            pin("SSL_CTX_SET_ALPN_PROTOS", EITHER, OPTIONAL),
            pin("SSL_CTX_SET_ALPN_SELECT_CB", EITHER, OPTIONAL),
            pin("SSL_CTX_CTRL", EITHER, REQUIRED),
            pin("SSL_NEW", EITHER, REQUIRED),
            pin("SSL_FREE", EITHER, REQUIRED),
            pin("SSL_ACCEPT", EITHER, REQUIRED),
            pin("SSL_CONNECT", EITHER, REQUIRED),
            pin("SSL_DO_HANDSHAKE", EITHER, REQUIRED),
            pin("SSL_READ", EITHER, REQUIRED),
            pin("SSL_WRITE", EITHER, REQUIRED),
            pin("SSL_SHUTDOWN", EITHER, REQUIRED),
            pin("SSL_GET_SHUTDOWN", EITHER, REQUIRED),
            pin("SSL_GET_ERROR", EITHER, REQUIRED),
            pin("SSL_GET0_ALPN_SELECTED", EITHER, OPTIONAL),
            pin("SSL_GET_CURRENT_CIPHER", EITHER, OPTIONAL),
            pin("SSL_CIPHER_GET_NAME", EITHER, OPTIONAL),
            pin("ERR_CLEAR_ERROR", CRYPTO, REQUIRED),
            pin("OPENSSL_CLEANSE", CRYPTO, REQUIRED),
            pin("SSL_CTX_SET1_CERT_STORE", SSL, REQUIRED),
            pin("SSL_GET0_PARAM", SSL, REQUIRED),
            pin("X509_VERIFY_PARAM_SET1_HOST", CRYPTO, REQUIRED),
            pin("X509_VERIFY_PARAM_SET_HOSTFLAGS", CRYPTO, REQUIRED),
            pin("X509_VERIFY_PARAM_SET1_IP", CRYPTO, REQUIRED),
            pin("SSL_CTRL", SSL, REQUIRED),
            pin("SSL_GET_VERIFY_RESULT", SSL, REQUIRED),
            pin("X509_VERIFY_CERT_ERROR_STRING", CRYPTO, REQUIRED),
            pin("X509_STORE_NEW", CRYPTO, REQUIRED),
            pin("X509_STORE_FREE", CRYPTO, REQUIRED),
            pin("X509_STORE_SET_DEFAULT_PATHS", CRYPTO, REQUIRED),
            pin("X509_STORE_LOAD_FILE", CRYPTO, REQUIRED),
            pin("X509_GET_DEFAULT_CERT_FILE", CRYPTO, REQUIRED),
            pin("X509_GET_DEFAULT_CERT_DIR", CRYPTO, REQUIRED),
            pin("X509_GET_DEFAULT_CERT_FILE_ENV", CRYPTO, REQUIRED),
            pin("X509_GET_DEFAULT_CERT_DIR_ENV", CRYPTO, REQUIRED),
            pin("OPENSSL_VERSION_NUM", EITHER, REQUIRED),
            pin("OPENSSL_VERSION_MAJOR", EITHER, REQUIRED),
            pin("OPENSSL_VERSION_MINOR", EITHER, REQUIRED),
            pin("OPENSSL_VERSION", EITHER, OPTIONAL));

    private static Map.Entry<String, Pin> pin(String constant, Library library, Resolution resolution) {
        return Map.entry(constant, new Pin(library, resolution));
    }

    @Test
    @DisplayName("every OpenSslDowncall row is pinned, so a new row cannot join unreviewed")
    void everyRowIsPinned() {
        assertThat(Arrays.stream(OpenSslDowncall.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(PINS.keySet());
    }

    @Test
    @DisplayName("every pinned constant keeps its library and resolution")
    void pinnedConstantsKeepTheirAttributes() {
        List<String> drift = new ArrayList<>();
        for (Map.Entry<String, Pin> entry : PINS.entrySet()) {
            OpenSslDowncall downcall = OpenSslDowncall.valueOf(entry.getKey());
            Pin actual = new Pin(downcall.library(), downcall.resolution());
            if (!actual.equals(entry.getValue())) {
                drift.add(entry.getKey() + " expected " + entry.getValue() + " but was " + actual);
            }
        }
        assertThat(drift).as("OpenSslDowncall rows whose lookup scope or resolution changed").isEmpty();
    }
}
