/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.community.transport.TlsTestCertificate;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslRuntime;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * A server context that cannot load its certificate or key leaves the calling thread's OpenSSL
 * error queue empty.
 *
 * <p>OpenSSL keeps its error queue per OS thread, and on the 3.x line {@code SSL_get_error} reports
 * {@code SSL_ERROR_SSL} whenever that queue holds an entry. A refused certificate or key pushes
 * entries there; left behind, they would be read as a fatal error of the next TLS call this thread
 * makes. The queue is read with {@code ERR_peek_error} on the thread that built the context, so the
 * check holds on every OpenSSL major. Fails rather than skips when OpenSSL cannot be loaded.
 */
@DisplayName("CommunityKernelCryptoProvider — a refused server context leaves the error queue empty")
class CommunityServerContextErrorQueueTest {

    @TempDir
    static Path material;

    private static CommunityKernelCryptoProvider provider;
    private static MethodHandle errPeekError;
    private static MethodHandle errClearError;

    @BeforeAll
    static void loadOpenSsl() {
        provider = new CommunityKernelCryptoProvider();
        CoreOpenSslRuntime runtime = CoreOpenSslLoader.load(Arena.global());
        errPeekError = runtime.requiredCryptoHandle("ERR_peek_error", FunctionDescriptor.of(JAVA_LONG));
        errClearError = runtime.requiredCryptoHandle("ERR_clear_error", FunctionDescriptor.ofVoid());
    }

    @BeforeEach
    void startFromAnEmptyQueue() throws Throwable {
        errClearError.invokeExact();
    }

    @Test
    @DisplayName("a certificate file that holds no certificate is refused, and the queue is left empty")
    void unparsableCertificateLeavesTheQueueEmpty() throws Throwable {
        Path notACertificate = Files.writeString(material.resolve("not-a-certificate.crt"), "no PEM block here\n");
        TlsTestCertificate pair = TlsTestCertificate.generateInto(
                Files.createDirectories(material.resolve("unparsable")));

        assertRefusedWithAnEmptyQueue(notACertificate, pair.privateKey());
    }

    @Test
    @DisplayName("a key that does not belong to the certificate is refused, and the queue is left empty")
    void mismatchedKeyLeavesTheQueueEmpty() throws Throwable {
        TlsTestCertificate first = TlsTestCertificate.generateInto(
                Files.createDirectories(material.resolve("first")));
        TlsTestCertificate second = TlsTestCertificate.generateInto(
                Files.createDirectories(material.resolve("second")));

        assertRefusedWithAnEmptyQueue(first.certificate(), second.privateKey());
    }

    private static void assertRefusedWithAnEmptyQueue(Path certificate, Path privateKey) throws Throwable {
        assertThatThrownBy(() -> provider.createTlsEngine(CryptoProviderConfig.httpsServer(certificate, privateKey)))
                .isInstanceOf(CryptoBootstrapException.class)
                .satisfies(refusal -> assertThat(((CryptoBootstrapException) refusal).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_NET_2002));
        assertThat((long) errPeekError.invokeExact())
                .as("the refused load's entries are cleared from this thread's queue")
                .isZero();
    }
}
