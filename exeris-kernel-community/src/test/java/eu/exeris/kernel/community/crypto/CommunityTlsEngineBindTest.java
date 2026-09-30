/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.community.transport.TlsTestCertificate;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("L2: CommunityTlsEngine FD bind")
class CommunityTlsEngineBindTest {

    @TempDir
    private Path tlsMaterialDir;

    @Test
    @DisplayName("client mode: bindFileDescriptor(-1) fails with TlsHandshakeException")
    void clientBindInvalidFdFails() {
        CommunityKernelCryptoProvider provider = createProviderOrSkip();

        TlsEngine tlsEngine = provider.createTlsEngine(CryptoProviderConfig.tcpClient());
        assertThat(tlsEngine)
                .as("Provider must return CommunityTlsEngine implementation")
                .isInstanceOf(CommunityTlsEngine.class);

        try {
            CommunityTlsEngine engine = (CommunityTlsEngine) tlsEngine;
                assertThatThrownBy(() -> engine.bindFileDescriptor(-1))
                    .isInstanceOfSatisfying(TlsHandshakeException.class, ex ->
                        assertThat(ex.rawArgs()[1]).asString().contains("SSL_set_fd"));
        } finally {
            tlsEngine.close();
            provider.close();
        }
    }

    @Test
    @DisplayName("server mode: bindFileDescriptor(-1) fails with TlsHandshakeException")
    void serverBindInvalidFdFails() {
        CommunityKernelCryptoProvider provider = createProviderOrSkip();

        // Generated, not located. No TLS material is checked in or created by any script, so a test
        // that assumes a path such as ../native-libs/certs skips in every build while reporting as
        // passing.
        TlsTestCertificate certificate = TlsTestCertificate.generateInto(tlsMaterialDir);
        Path certPath = certificate.certificate();
        Path keyPath = certificate.privateKey();

        TlsEngine tlsEngine = provider.createTlsEngine(CryptoProviderConfig.httpsServer(certPath, keyPath));
        assertThat(tlsEngine)
                .as("Provider must return CommunityTlsEngine implementation")
                .isInstanceOf(CommunityTlsEngine.class);

        try {
            CommunityTlsEngine engine = (CommunityTlsEngine) tlsEngine;
                assertThatThrownBy(() -> engine.bindFileDescriptor(-1))
                    .isInstanceOfSatisfying(TlsHandshakeException.class, ex ->
                        assertThat(ex.rawArgs()[1]).asString().contains("SSL_set_fd"));
        } finally {
            tlsEngine.close();
            provider.close();
        }
    }

    private static CommunityKernelCryptoProvider createProviderOrSkip() {
        try {
            return new CommunityKernelCryptoProvider();
        } catch (CryptoBootstrapException exception) {
            assumeTrue(false, "OpenSSL 3.x not available on this host — skipping test");
            throw new IllegalStateException("unreachable", exception);
        }
    }
}
