/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

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
import org.bouncycastle.operator.OperatorCreationException;
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
 * A certificate authority and the server certificates it issues, written as PEM files per run, for
 * the suites that verify a TLS client against its server.
 *
 * <p>P-256 keys keep generation in the millisecond range, so a suite mints every shape it needs. No
 * leaf's common name is a host any suite dials ({@code exeris-test-leaf-<n>}), except where a case
 * sets one to prove the common name is never read.
 */
public final class TlsTestAuthority {

    private static final Duration VALIDITY = Duration.ofDays(1);
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final AtomicLong SERIALS = new AtomicLong(System.currentTimeMillis());
    private static final AtomicLong LEAVES = new AtomicLong();

    private final Path directory;
    private final String name;
    private final X500Name subject;
    private final KeyPair keys;
    private final Path certificate;

    private TlsTestAuthority(Path directory, String name, X500Name subject, KeyPair keys, Path certificate) {
        this.directory = directory;
        this.name = name;
        this.subject = subject;
        this.keys = keys;
        this.certificate = certificate;
    }

    /**
     * A certificate and its key on disk.
     *
     * @param certificate PEM certificate path
     * @param privateKey  PEM PKCS#8 private-key path
     */
    public record Issued(Path certificate, Path privateKey) {
    }

    /**
     * A self-signed root, written into {@code directory}.
     *
     * @param directory where the PEM files go
     * @param name      the file-name stem and common name
     * @return the authority
     */
    public static TlsTestAuthority root(Path directory, String name) {
        KeyPair keys = newKeyPair();
        X500Name subject = new X500Name("CN=" + name);
        try {
            X509v3CertificateBuilder builder = builder(subject, subject, keys);
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            Issued written = write(directory, name, keys, builder, keys);
            return new TlsTestAuthority(directory, name, subject, keys, written.certificate());
        } catch (IOException | GeneralSecurityException | OperatorCreationException e) {
            throw new IllegalStateException("root generation failed", e);
        }
    }

    /**
     * This authority's certificate, the file a client trusts.
     *
     * @return the PEM path
     */
    public Path certificate() {
        return certificate;
    }

    /**
     * A server certificate for {@code sans}, issued by this authority, with a common name no suite
     * dials.
     *
     * @param sans the subject alternative names, e.g. {@link #dns} and {@link #ip}
     * @return the leaf
     */
    public Issued issue(GeneralName... sans) {
        return issueWithCommonName("exeris-test-leaf-" + LEAVES.incrementAndGet(), sans);
    }

    /**
     * A server certificate with the given common name and {@code sans}, issued by this authority.
     *
     * @param commonName the subject common name
     * @param sans       the subject alternative names; none means no SAN extension
     * @return the leaf
     */
    public Issued issueWithCommonName(String commonName, GeneralName... sans) {
        KeyPair leafKeys = newKeyPair();
        try {
            X509v3CertificateBuilder builder = builder(subject, new X500Name("CN=" + commonName), leafKeys);
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            if (sans.length > 0) {
                builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans));
            }
            return write(directory, name + "-leaf-" + LEAVES.incrementAndGet(), leafKeys, builder, keys);
        } catch (IOException | GeneralSecurityException | OperatorCreationException e) {
            throw new IllegalStateException("leaf generation failed", e);
        }
    }

    /**
     * A DNS subject alternative name.
     *
     * @param name the host name
     * @return the SAN entry
     */
    public static GeneralName dns(String name) {
        return new GeneralName(GeneralName.dNSName, name);
    }

    /**
     * An IP subject alternative name.
     *
     * @param address the address literal
     * @return the SAN entry
     */
    public static GeneralName ip(String address) {
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

    private static Issued write(Path directory, String stem, KeyPair keys, X509v3CertificateBuilder builder,
                                KeyPair signer)
            throws IOException, GeneralSecurityException, OperatorCreationException {
        java.security.cert.X509Certificate x509 = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(signer.getPrivate())));
        Path certificatePath = directory.resolve(stem + ".crt");
        Path keyPath = directory.resolve(stem + ".key");
        Files.writeString(certificatePath, pem("CERTIFICATE", x509.getEncoded()));
        Files.writeString(keyPath, pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        return new Issued(certificatePath, keyPath);
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
