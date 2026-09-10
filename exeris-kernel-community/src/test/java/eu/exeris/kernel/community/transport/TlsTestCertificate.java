/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Generates the self-signed server certificate the TLS carrier suites need, in-process.
 *
 * <h2>Why this exists</h2>
 * <p>Those suites used to read {@code ../native-libs/certs/server.{crt,key}} behind an
 * {@code assumeTrue}. That directory is <b>not in the repository, not in {@code .gitignore}, not
 * created by any script, and not mentioned in any document or workflow</b> — so the path never
 * resolved for anyone, the assumption always failed, and four carrier-level TLS tests skipped in
 * every build while reporting success. A skip is the one test outcome that looks like a pass in a
 * summary line.
 *
 * <p>Generating the material here rather than checking a key into the tree is deliberate on two
 * counts: a committed {@code PRIVATE KEY} block trips secret scanners for no benefit, and a
 * committed certificate expires, turning a passing suite into a dated time bomb. This one is
 * created per run, so it cannot rot.
 *
 * <p>RSA-2048 with SHA-256, ~1 s to generate on a laptop, done once per test class rather than per
 * test. The SAN carries {@code IP:127.0.0.1} because every one of these suites binds the loopback.
 */
public final class TlsTestCertificate {

    private static final int KEY_BITS = 2_048;
    private static final Duration VALIDITY = Duration.ofDays(1);
    private static final String SUBJECT = "CN=127.0.0.1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final Path certificatePath;
    private final Path privateKeyPath;

    private TlsTestCertificate(Path certificatePath, Path privateKeyPath) {
        this.certificatePath = certificatePath;
        this.privateKeyPath = privateKeyPath;
    }

    /** PEM certificate path, for callers taking a {@link Path} (e.g. {@code CryptoProviderConfig}). */
    public Path certificate() {
        return certificatePath;
    }

    /** PEM private-key path, for callers taking a {@link Path}. */
    public Path privateKey() {
        return privateKeyPath;
    }

    /** PEM certificate path, for {@code TransportConfig.certPath}. */
    /* default */ String certPath() {
        return certificatePath.toString();
    }

    /** PEM private-key path, for {@code TransportConfig.keyPath}. */
    /* default */ String keyPath() {
        return privateKeyPath.toString();
    }

    /**
     * Writes a fresh self-signed certificate and key into {@code directory}.
     *
     * @param directory a per-class temporary directory (JUnit {@code @TempDir})
     * @return the generated pair's on-disk locations
     * @throws IllegalStateException if generation fails, which is never an expected condition —
     *         the algorithms used are JDK-mandatory, so a failure here is an environment fault
     *         worth surfacing rather than a reason to skip. That distinction is the whole point:
     *         the previous shape turned every fault into a silent skip.
     */
    public static TlsTestCertificate generateInto(Path directory) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_BITS, SecureRandom.getInstanceStrong());
            KeyPair keyPair = generator.generateKeyPair();

            Instant now = Instant.now();
            X500Name subject = new X500Name(SUBJECT);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject,
                    BigInteger.valueOf(now.toEpochMilli()),
                    Date.from(now.minus(Duration.ofHours(1))),
                    Date.from(now.plus(VALIDITY)),
                    subject,
                    keyPair.getPublic());
            builder.addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));

            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                            .build(keyPair.getPrivate())));

            Path certificatePath = directory.resolve("server.crt");
            Path privateKeyPath = directory.resolve("server.key");
            Files.writeString(certificatePath, toPem("CERTIFICATE", certificate.getEncoded()));
            Files.writeString(privateKeyPath, toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
            return new TlsTestCertificate(certificatePath, privateKeyPath);
        } catch (Exception failure) {
            throw new IllegalStateException("TLS test certificate generation failed", failure);
        }
    }

    private static String toPem(String type, byte[] der) throws java.io.IOException {
        StringWriter out = new StringWriter();
        try (PemWriter writer = new PemWriter(out)) {
            writer.writeObject(new PemObject(type, der));
        }
        return out.toString();
    }
}
