/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * A self-signed loopback server certificate and its private key, written as PEM files.
 *
 * <p>Generated with the JDK's own {@code keytool}, so the TCK needs no cryptography library beyond
 * the JDK. The certificate carries {@code IP:127.0.0.1} as its subject alternative name and is
 * valid for one day. A failure to generate it is an environment fault and is thrown, never turned
 * into a skipped test.
 *
 * @param certificate PEM certificate chain file
 * @param privateKey  PEM PKCS#8 private key file
 * @param x509        the same certificate, for a peer that must trust it
 */
public record TckTlsMaterial(Path certificate, Path privateKey, X509Certificate x509) {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final String ALIAS = "leaf";
    private static final int KEYTOOL_TIMEOUT_SECONDS = 60;

    /**
     * Generates a fresh certificate and key into {@code directory}.
     *
     * @param directory an existing, writable directory
     * @return the generated material
     * @throws IllegalStateException if generation fails
     */
    public static TckTlsMaterial generateInto(Path directory) {
        try {
            Path keystore = directory.resolve("leaf.p12");
            Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
            Process process = new ProcessBuilder(keytool.toString(),
                    "-genkeypair", "-alias", ALIAS,
                    "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                    "-dname", "CN=exeris-tck-leaf", "-ext", "san=ip:127.0.0.1", "-validity", "1",
                    "-keystore", keystore.toString(), "-storetype", "PKCS12",
                    "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(KEYTOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IllegalStateException("keytool could not generate the TLS test certificate");
            }
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(keystore)) {
                store.load(in, PASSWORD);
            }
            X509Certificate x509 = (X509Certificate) store.getCertificate(ALIAS);
            PrivateKey key = (PrivateKey) store.getKey(ALIAS, PASSWORD);
            Path certificate = directory.resolve("server.crt");
            Path privateKey = directory.resolve("server.key");
            Files.writeString(certificate, pem("CERTIFICATE", x509.getEncoded()), StandardCharsets.US_ASCII);
            Files.writeString(privateKey, pem("PRIVATE KEY", key.getEncoded()), StandardCharsets.US_ASCII);
            return new TckTlsMaterial(certificate, privateKey, x509);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TLS test certificate generation was interrupted", interrupted);
        } catch (IOException | java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("TLS test certificate generation failed", failure);
        }
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
