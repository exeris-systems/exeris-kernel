/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The certificate material {@link AbstractHttpClientTlsPeerVerificationTck} runs against, as PEM
 * files a binding writes for each case.
 *
 * <p>Each leaf differs from the one a verifying client accepts in exactly one respect, so that a
 * refusal has one possible reason. No leaf's subject common name is {@code localhost},
 * {@code 127.0.0.1} or {@code ::1} — the hosts the suite dials — except
 * {@link #commonNameOnlyLeaf()}, whose name is there to prove it is never read. The suite checks
 * these shapes before relying on them.
 *
 * @param trustedRoot           the certificate of the authority a verifying client is given as its
 *                              trust
 * @param localhostLeaf         issued by {@code trustedRoot}; subject alternative name
 *                              {@code DNS:localhost} and nothing else
 * @param loopbackAddressLeaf   issued by {@code trustedRoot}; subject alternative names
 *                              {@code IP:127.0.0.1} and {@code IP:::1}, and no DNS entry
 * @param loopbackAsDnsNameLeaf issued by {@code trustedRoot}; subject alternative name
 *                              {@code DNS:127.0.0.1} — a DNS entry spelling the address — and no
 *                              IP entry
 * @param otherNameLeaf         issued by {@code trustedRoot}; subject alternative name
 *                              {@code DNS:other.invalid} and nothing else
 * @param commonNameOnlyLeaf    issued by {@code trustedRoot}; subject {@code CN=localhost} and no
 *                              subject alternative name extension
 * @param untrustedIssuerLeaf   issued by a root that is not {@code trustedRoot}; subject
 *                              alternative names {@code DNS:localhost} and {@code IP:127.0.0.1}
 * @since 0.12
 */
public record TlsPeerFixtures(Path trustedRoot,
                              Leaf localhostLeaf,
                              Leaf loopbackAddressLeaf,
                              Leaf loopbackAsDnsNameLeaf,
                              Leaf otherNameLeaf,
                              Leaf commonNameOnlyLeaf,
                              Leaf untrustedIssuerLeaf) {

    /**
     * Requires every file.
     *
     * @throws NullPointerException if any component is {@code null}
     */
    public TlsPeerFixtures {
        Objects.requireNonNull(trustedRoot, "trustedRoot");
        Objects.requireNonNull(localhostLeaf, "localhostLeaf");
        Objects.requireNonNull(loopbackAddressLeaf, "loopbackAddressLeaf");
        Objects.requireNonNull(loopbackAsDnsNameLeaf, "loopbackAsDnsNameLeaf");
        Objects.requireNonNull(otherNameLeaf, "otherNameLeaf");
        Objects.requireNonNull(commonNameOnlyLeaf, "commonNameOnlyLeaf");
        Objects.requireNonNull(untrustedIssuerLeaf, "untrustedIssuerLeaf");
    }

    /**
     * A server certificate and its private key, as PEM files.
     *
     * @param certificate the PEM {@code CERTIFICATE}, the leaf alone
     * @param privateKey  the PEM {@code PRIVATE KEY} (PKCS#8) matching it
     * @since 0.12
     */
    public record Leaf(Path certificate, Path privateKey) {

        /**
         * Requires both files.
         *
         * @throws NullPointerException if either component is {@code null}
         */
        public Leaf {
            Objects.requireNonNull(certificate, "certificate");
            Objects.requireNonNull(privateKey, "privateKey");
        }
    }
}
