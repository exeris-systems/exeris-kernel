/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.community.transport.NativeTcpTransportProvider;
import eu.exeris.kernel.community.transport.TlsTestAuthority;
import eu.exeris.kernel.core.crypto.tls.TlsFailureDetail;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.tck.contract.http.AbstractHttpClientTlsPeerVerificationTck;
import eu.exeris.kernel.tck.contract.http.TlsPeerFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@link AbstractHttpClientTlsPeerVerificationTck} to the Community HTTP client.
 *
 * <p>Each client is built the way a kernel builds one: with a {@link CommunityKernelCryptoProvider}
 * bound, so its transport arms outbound TLS, and with {@code crypto.tls.client.trustFile} in the
 * bound kernel configuration, or absent for OpenSSL's default trust. A refusal's {@code rawArgs} is
 * the {@code X509_V_*} code and the detail {@code peer certificate verification failed}. Fails rather
 * than skips when OpenSSL cannot be loaded.
 */
@DisplayName("Community: HttpClientEngine TLS peer verification TCK")
class CommunityHttpClientTlsPeerVerificationTckTest extends AbstractHttpClientTlsPeerVerificationTck {

    private static final int X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY = 20;
    private static final int X509_V_ERR_HOSTNAME_MISMATCH = 62;
    private static final int X509_V_ERR_IP_ADDRESS_MISMATCH = 64;
    private static final String TRUST_FILE_PROPERTY = "exeris." + NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY;

    private static CommunityKernelCryptoProvider crypto;

    @BeforeAll
    static void loadOpenSsl() {
        crypto = new CommunityKernelCryptoProvider();
    }

    @Override
    protected TlsPeerFixtures fixtures(Path directory) {
        TlsTestAuthority trusted = TlsTestAuthority.root(directory, "tck-trusted-ca");
        TlsTestAuthority untrusted = TlsTestAuthority.root(directory, "tck-untrusted-ca");
        return new TlsPeerFixtures(
                trusted.certificate(),
                leaf(trusted.issue(TlsTestAuthority.dns("localhost"))),
                leaf(trusted.issue(TlsTestAuthority.ip("127.0.0.1"), TlsTestAuthority.ip("::1"))),
                leaf(trusted.issue(TlsTestAuthority.dns("127.0.0.1"))),
                leaf(trusted.issue(TlsTestAuthority.dns("other.invalid"))),
                leaf(trusted.issueWithCommonName("localhost")),
                leaf(untrusted.issue(TlsTestAuthority.dns("localhost"), TlsTestAuthority.ip("127.0.0.1"))));
    }

    @Override
    protected HttpClientEngine startClient(Path trustAnchor, String defaultAuthority) {
        assertThat(System.getProperty("exeris.transport.tls"))
                .as("-Dexeris.transport.tls=false would make this client dial plaintext")
                .isNotEqualToIgnoringCase("false");
        ConfigProvider kernelConfig;
        if (trustAnchor == null) {
            assertThat(System.getProperty(TRUST_FILE_PROPERTY))
                    .as("-D%s would replace the default trust this client is meant to use", TRUST_FILE_PROPERTY)
                    .isNull();
            kernelConfig = new MapConfigProvider(Map.of(), Map.of());
        } else {
            kernelConfig = new MapConfigProvider(
                    Map.of(NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY, trustAnchor.toString()), Map.of());
        }
        HttpClientEngine engine = ScopedValue.where(KernelProviders.CRYPTO_PROVIDER, crypto)
                .where(KernelProviders.CURRENT_CONFIG, kernelConfig)
                .call(() -> new CommunityHttpClientEngine(clientConfig(defaultAuthority)));
        engine.start();
        return engine;
    }

    @Override
    protected void assertRefusalDetail(TlsHandshakeException refusal, PeerRefusal reason) {
        int x509Code = switch (reason) {
            case UNTRUSTED_ISSUER -> X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY;
            case NAME_MISMATCH -> X509_V_ERR_HOSTNAME_MISMATCH;
            case ADDRESS_MISMATCH -> X509_V_ERR_IP_ADDRESS_MISMATCH;
        };
        assertThat(refusal.rawArgs())
                .as("the X509_V_* code for %s", reason)
                .containsExactly(x509Code, TlsFailureDetail.PEER_VERIFICATION_FAILED);
    }

    private static TlsPeerFixtures.Leaf leaf(TlsTestAuthority.Issued issued) {
        return new TlsPeerFixtures.Leaf(issued.certificate(), issued.privateKey());
    }

    private static HttpConfig clientConfig(String defaultAuthority) {
        HttpConfig defaults = HttpConfig.defaultClient();
        return new HttpConfig(HttpMode.CLIENT, null, -1,
                defaults.maxConnections(),
                defaults.idleTimeoutMillis(),
                defaults.maxRequestHeaderCount(),
                defaults.maxRequestHeaderSize(),
                defaults.maxRequestBodyBytes(),
                false,
                defaults.maxVersion(),
                defaultAuthority,
                defaults.maxHeaderBlockSize(),
                defaults.maxHeaderListSize(),
                defaults.maxStringLiteralSize(),
                defaults.maxResponseBodyBytes());
    }
}
