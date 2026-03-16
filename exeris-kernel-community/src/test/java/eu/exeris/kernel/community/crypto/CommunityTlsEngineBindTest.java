/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("L2: CommunityTlsEngine FD bind")
class CommunityTlsEngineBindTest {

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

        Path certPath = Path.of("..", "native-libs", "certs", "server.crt").normalize();
        Path keyPath = Path.of("..", "native-libs", "certs", "server.key").normalize();
        assumeTrue(Files.isRegularFile(certPath) && Files.isRegularFile(keyPath),
                "TLS test cert/key not found — skipping server bind test");

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
