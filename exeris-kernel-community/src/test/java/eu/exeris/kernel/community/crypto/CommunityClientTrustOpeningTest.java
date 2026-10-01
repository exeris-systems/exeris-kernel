/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.community.transport.TlsTestAuthority;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CommunityKernelCryptoProvider#openClientTrust} against real OpenSSL: which files it refuses,
 * with which reason, and that a refusal leaves the thread's OpenSSL error queue empty. Fails rather
 * than skips when OpenSSL cannot be loaded.
 */
@DisplayName("CommunityKernelCryptoProvider#openClientTrust — refusals and sources")
class CommunityClientTrustOpeningTest {

    private static CommunityKernelCryptoProvider provider;
    private static MethodHandle errPeekError;

    @TempDir
    static Path material;

    @BeforeAll
    static void loadOpenSsl() {
        provider = new CommunityKernelCryptoProvider();
        errPeekError = CoreOpenSslLoader.load(Arena.global())
                .requiredCryptoHandle("ERR_peek_error", FunctionDescriptor.of(JAVA_LONG));
    }

    @Test
    @DisplayName("a missing file is refused before any native call")
    void missingFileIsRefused() {
        assertRefused(material.resolve("absent.pem"), "trust file is not a readable regular file");
    }

    @Test
    @DisplayName("a directory is refused before any native call")
    void directoryIsRefused() {
        assertRefused(material, "trust file is not a readable regular file");
    }

    @Test
    @DisplayName("a PEM holding only a key is refused by OpenSSL, and the error queue is left empty")
    void keyOnlyFileIsRefused() throws Throwable {
        TlsTestAuthority authority = TlsTestAuthority.root(material, "key-only-ca");
        Path keyOnly = authority.issue(TlsTestAuthority.dns("localhost")).privateKey();

        assertRefused(keyOnly, "trust file holds no certificate OpenSSL can load");
        assertThat((long) errPeekError.invokeExact())
                .as("the refused load's entries are cleared from this thread's queue")
                .isZero();
    }

    @Test
    @DisplayName("a CA file is the trust's only source")
    void configuredFileIsTheOnlySource() {
        TlsTestAuthority authority = TlsTestAuthority.root(material, "configured-ca");

        try (CommunityTlsClientTrust trust = provider.openClientTrust(authority.certificate())) {
            assertThat(trust.source()).isEqualTo(CommunityTlsClientTrust.TrustSource.CONFIGURED_FILE);
            assertThat(trust.defaultCertFile()).isNull();
            assertThat(trust.defaultCertDir()).isNull();
            assertThat(trust.defaultTrustPresent()).isFalse();
        }
    }

    @Test
    @DisplayName("no file: OpenSSL's default locations, reported by path")
    void noFileMeansTheDefaultLocations() {
        try (CommunityTlsClientTrust trust = provider.openClientTrust(null)) {
            assertThat(trust.source()).isEqualTo(CommunityTlsClientTrust.TrustSource.SYSTEM_DEFAULT);
            assertThat(trust.defaultCertFile()).isNotBlank();
            assertThat(trust.defaultCertDir()).isNotBlank();
            assertThat(trust.defaultTrustPresent())
                    .isEqualTo(Files.exists(Path.of(trust.defaultCertFile()))
                            || Files.exists(Path.of(trust.defaultCertDir())));
        }
    }

    private static void assertRefused(Path trustFile, String reason) {
        assertThatThrownBy(() -> provider.openClientTrust(trustFile))
                .isInstanceOf(CryptoBootstrapException.class)
                .satisfies(e -> {
                    CryptoBootstrapException refusal = (CryptoBootstrapException) e;
                    assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_2002);
                    assertThat(refusal.rawArgs()).contains(reason, trustFile.toString());
                });
    }
}
