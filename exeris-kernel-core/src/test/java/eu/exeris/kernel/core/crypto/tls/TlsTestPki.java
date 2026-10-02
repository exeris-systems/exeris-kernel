/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Certificate material for the OpenSSL integration tests, authored per run.
 *
 * <p>P-256 keys keep generation in the millisecond range, so a test class can mint every
 * certificate shape it needs rather than share one. Nothing is committed: a checked-in key trips
 * secret scanners and a checked-in certificate expires.
 */
final class TlsTestPki {

    private static final Duration VALIDITY = Duration.ofDays(1);
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final AtomicLong SERIALS = new AtomicLong(System.currentTimeMillis());

    private TlsTestPki() {
    }

    /**
     * A certificate and its key, in memory and as PEM files.
     *
     * @param certificate PEM certificate path
     * @param privateKey  PEM PKCS#8 private-key path
     * @param subject     the subject name, which issues the next certificate down
     * @param keys        the key pair
     */
    record Issued(Path certificate, Path privateKey, X500Name subject, KeyPair keys) {
    }

    /**
     * A self-signed CA certificate.
     *
     * @param dir  directory the PEM files are written to
     * @param name file-name stem and common name
     * @return the root
     */
    static Issued root(Path dir, String name) {
        KeyPair keys = newKeyPair();
        X500Name subject = new X500Name("CN=" + name);
        try {
            X509v3CertificateBuilder builder = builder(subject, subject, keys);
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            return write(dir, name, subject, keys, builder, keys);
        } catch (IOException | GeneralSecurityException | org.bouncycastle.operator.OperatorCreationException e) {
            throw new IllegalStateException("root generation failed", e);
        }
    }

    /**
     * A server certificate issued by {@code issuer}, or self-signed when {@code issuer} is
     * {@code null}.
     *
     * @param dir    directory the PEM files are written to
     * @param name   file-name stem
     * @param issuer the issuing CA, or {@code null} for a self-signed certificate
     * @param cn     the subject common name
     * @param sans   subject alternative names; none means the certificate carries no SAN extension
     * @return the leaf
     */
    static Issued leaf(Path dir, String name, Issued issuer, String cn, GeneralName... sans) {
        KeyPair keys = newKeyPair();
        X500Name subject = new X500Name("CN=" + cn);
        X500Name issuerName = issuer == null ? subject : issuer.subject();
        KeyPair signer = issuer == null ? keys : issuer.keys();
        try {
            X509v3CertificateBuilder builder = builder(issuerName, subject, keys);
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            if (sans.length > 0) {
                builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans));
            }
            return write(dir, name, subject, keys, builder, signer);
        } catch (IOException | GeneralSecurityException | org.bouncycastle.operator.OperatorCreationException e) {
            throw new IllegalStateException("leaf generation failed", e);
        }
    }

    static GeneralName dns(String name) {
        return new GeneralName(GeneralName.dNSName, name);
    }

    static GeneralName ip(String address) {
        return new GeneralName(GeneralName.iPAddress, address);
    }

    private static X509v3CertificateBuilder builder(X500Name issuer, X500Name subject, KeyPair keys) {
        Instant now = Instant.now();
        return new JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(SERIALS.incrementAndGet()),
                Date.from(now.minus(Duration.ofHours(1))),
                Date.from(now.plus(VALIDITY)),
                subject,
                keys.getPublic());
    }

    private static Issued write(Path dir, String name, X500Name subject, KeyPair keys,
                                X509v3CertificateBuilder builder, KeyPair signer)
            throws IOException, GeneralSecurityException, org.bouncycastle.operator.OperatorCreationException {
        java.security.cert.X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(signer.getPrivate())));
        Path certificatePath = dir.resolve(name + ".crt");
        Path keyPath = dir.resolve(name + ".key");
        Files.writeString(certificatePath, pem("CERTIFICATE", certificate.getEncoded()));
        Files.writeString(keyPath, pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        return new Issued(certificatePath, keyPath, subject, keys);
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC key generation failed", e);
        }
    }

    private static String pem(String type, byte[] der) throws IOException {
        StringWriter out = new StringWriter();
        try (PemWriter writer = new PemWriter(out)) {
            writer.writeObject(new PemObject(type, der));
        }
        return out.toString();
    }
}
