/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Checks that a binding's {@link TlsPeerFixtures} have the shapes the record documents, with the
 * JDK's certificate parser. A refusal case proves something only if its leaf differs from an
 * accepted one in the single respect the case names; a fixture that differs in two, or in none,
 * would let an implementation pass for the wrong reason.
 */
final class TlsPeerFixtureShapes {

    private static final String LOCALHOST = "localhost";
    private static final String LOOPBACK = "127.0.0.1";
    private static final int SAN_DNS = 2;
    private static final int SAN_IP = 7;

    private TlsPeerFixtureShapes() {
    }

    /**
     * Asserts every shape {@link TlsPeerFixtures} documents.
     *
     * @param material the binding's fixtures
     * @throws AssertionError naming the first leaf that does not have its shape
     */
    static void assertShapes(TlsPeerFixtures material) {
        X509Certificate root = certificate(material.trustedRoot());
        assertThat(root.getBasicConstraints()).as("the trusted root is a CA certificate").isNotNegative();

        assertIssuedBy(material.localhostLeaf(), root, true);
        assertIssuedBy(material.loopbackAddressLeaf(), root, true);
        assertIssuedBy(material.loopbackAsDnsNameLeaf(), root, true);
        assertIssuedBy(material.otherNameLeaf(), root, true);
        assertIssuedBy(material.commonNameOnlyLeaf(), root, true);
        assertIssuedBy(material.untrustedIssuerLeaf(), root, false);

        assertSans(material.localhostLeaf(), Set.of(LOCALHOST), Set.of());
        X509Certificate addressLeaf = certificate(material.loopbackAddressLeaf().certificate());
        assertThat(sans(addressLeaf, SAN_DNS)).as("loopbackAddressLeaf carries no DNS entry").isEmpty();
        assertThat(sans(addressLeaf, SAN_IP))
                .as("loopbackAddressLeaf names 127.0.0.1 and ::1")
                .contains(LOOPBACK)
                .containsAnyOf("0:0:0:0:0:0:0:1", "::1");
        assertSans(material.loopbackAsDnsNameLeaf(), Set.of(LOOPBACK), Set.of());
        assertSans(material.otherNameLeaf(), Set.of("other.invalid"), Set.of());
        assertSans(material.untrustedIssuerLeaf(), Set.of(LOCALHOST), Set.of(LOOPBACK));

        X509Certificate commonNameOnly = certificate(material.commonNameOnlyLeaf().certificate());
        assertThat(catchSans(commonNameOnly))
                .as("commonNameOnlyLeaf has no subject alternative name extension").isNull();
        assertThat(commonName(commonNameOnly)).as("commonNameOnlyLeaf's common name").isEqualTo(LOCALHOST);

        for (TlsPeerFixtures.Leaf leaf : List.of(material.localhostLeaf(), material.loopbackAddressLeaf(),
                material.loopbackAsDnsNameLeaf(), material.otherNameLeaf(), material.untrustedIssuerLeaf())) {
            assertThat(commonName(certificate(leaf.certificate())))
                    .as("the common name of %s is no host the suite dials", leaf.certificate().getFileName())
                    .isNotIn(LOCALHOST, LOOPBACK, "::1");
        }
    }

    private static X509Certificate certificate(Path pem) {
        try (InputStream in = Files.newInputStream(pem)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (CertificateException e) {
            throw new AssertionError("not a PEM certificate: " + pem, e);
        }
    }

    private static void assertIssuedBy(TlsPeerFixtures.Leaf leaf, X509Certificate root, boolean expected) {
        X509Certificate certificate = certificate(leaf.certificate());
        Throwable failure = catchThrowable(() -> certificate.verify(root.getPublicKey()));
        assertThat(failure == null)
                .as("%s is %s by the trusted root", leaf.certificate().getFileName(),
                        expected ? "signed" : "not signed")
                .isEqualTo(expected);
    }

    private static void assertSans(TlsPeerFixtures.Leaf leaf, Set<String> dns, Set<String> ip) {
        X509Certificate certificate = certificate(leaf.certificate());
        assertThat(sans(certificate, SAN_DNS)).as("DNS entries of %s", leaf.certificate().getFileName())
                .containsExactlyInAnyOrderElementsOf(dns);
        assertThat(sans(certificate, SAN_IP)).as("IP entries of %s", leaf.certificate().getFileName())
                .containsExactlyInAnyOrderElementsOf(ip);
    }

    private static Set<String> sans(X509Certificate certificate, int type) {
        Set<String> values = new TreeSet<>();
        Collection<List<?>> names = catchSans(certificate);
        if (names != null) {
            for (List<?> name : names) {
                if ((Integer) name.get(0) == type) {
                    values.add((String) name.get(1));
                }
            }
        }
        return values;
    }

    private static Collection<List<?>> catchSans(X509Certificate certificate) {
        try {
            return certificate.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            throw new AssertionError("unparseable subject alternative names", e);
        }
    }

    private static String commonName(X509Certificate certificate) {
        for (String part : certificate.getSubjectX500Principal().getName().split(",")) {
            if (part.startsWith("CN=")) {
                return part.substring("CN=".length());
            }
        }
        return "";
    }
}
