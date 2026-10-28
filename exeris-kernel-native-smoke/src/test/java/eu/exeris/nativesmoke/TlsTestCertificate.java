/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

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
 * Generates an ephemeral self-signed certificate and private key for TLS smoke tests.
 */
public final class TlsTestCertificate {

    private static final int KEY_BITS = 2_048;
    private static final Duration VALIDITY = Duration.ofDays(1);
    private static final String SUBJECT = "CN=smoke-leaf";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final Path certificatePath;
    private final Path privateKeyPath;

    private TlsTestCertificate(Path certificatePath, Path privateKeyPath) {
        this.certificatePath = certificatePath;
        this.privateKeyPath = privateKeyPath;
    }

    /**
     * PEM certificate file path.
     *
     * @return certificate path
     */
    public Path certificate() {
        return certificatePath;
    }

    /**
     * PEM private key file path.
     *
     * @return private key path
     */
    public Path privateKey() {
        return privateKeyPath;
    }

    /**
     * Generates a new self-signed certificate and key into the given directory.
     *
     * @param directory target directory
     * @return generated certificate pair
     */
    public static TlsTestCertificate generateInto(Path directory) {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(KEY_BITS, SecureRandom.getInstanceStrong());
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            Instant now = Instant.now();
            X500Name subjectName = new X500Name(SUBJECT);
            JcaX509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
                    subjectName,
                    BigInteger.valueOf(now.toEpochMilli()),
                    Date.from(now.minus(Duration.ofHours(1))),
                    Date.from(now.plus(VALIDITY)),
                    subjectName,
                    keyPair.getPublic());
            certificateBuilder.addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));

            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    certificateBuilder.build(new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                            .build(keyPair.getPrivate())));

            Path certPath = directory.resolve("smoke-server.crt");
            Path keyPath = directory.resolve("smoke-server.key");
            Files.writeString(certPath, encodePem("CERTIFICATE", certificate.getEncoded()));
            Files.writeString(keyPath, encodePem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
            return new TlsTestCertificate(certPath, keyPath);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to generate smoke TLS test certificate", failure);
        }
    }

    private static String encodePem(String type, byte[] derBytes) throws java.io.IOException {
        StringWriter stringWriter = new StringWriter();
        try (PemWriter pemWriter = new PemWriter(stringWriter)) {
            pemWriter.writeObject(new PemObject(type, derBytes));
        }
        return stringWriter.toString();
    }
}
