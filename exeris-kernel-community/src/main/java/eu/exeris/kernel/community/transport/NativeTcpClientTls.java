/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.crypto.CommunityTlsClientTrust;
import eu.exeris.kernel.community.crypto.CommunityTlsEngine;
import eu.exeris.kernel.core.crypto.tls.TlsPeerIdentity;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;

import java.util.Objects;

/**
 * What a carrier does with its outbound connections: dial TLS and verify the server against a
 * trust store, dial plaintext, or refuse to dial.
 *
 * <p>Owns the {@link CommunityTlsClientTrust} it verifies against, which {@link #close()} releases;
 * engines already built keep their own reference to the store.
 */
final class NativeTcpClientTls implements AutoCloseable {

    /** The outcome of the client TLS decision for one carrier. */
    /* default */ enum Posture {
        /** Outbound connections speak TLS and verify the server. */
        VERIFIED,
        /** {@code exeris.transport.tls=false}: outbound connections are plaintext. */
        PLAINTEXT_DECLINED,
        /** No crypto provider was bound where the carrier was built: outbound connections are plaintext. */
        PLAINTEXT_NO_CRYPTO_PROVIDER,
        /** A DUAL carrier whose listener holds no certificate: it serves and dials plaintext. */
        PLAINTEXT_NO_LISTENER_MATERIAL,
        /** The bound crypto provider cannot verify an outbound peer: outbound connections are refused. */
        REFUSED_FOREIGN_PROVIDER
    }

    /** Where the trust a verifying carrier uses was named. */
    /* default */ enum TrustOrigin {
        /** {@code crypto.tls.client.trustFile} in the kernel configuration. */
        CONFIG_KEY,
        /** The {@code exeris.crypto.tls.client.trustFile} system property. */
        SYSTEM_PROPERTY,
        /** Nowhere: OpenSSL's default locations. */
        SYSTEM_DEFAULT,
        /** The carrier verifies nothing, so it has no trust. */
        NONE
    }

    private static final NativeTcpClientTls NONE =
            new NativeTcpClientTls(null, null, null, null, TrustOrigin.NONE);

    private final Posture posture;
    private final CommunityKernelCryptoProvider provider;
    private final CommunityTlsClientTrust trust;
    private final CryptoProviderConfig engineConfig;
    private final TrustOrigin trustOrigin;

    private NativeTcpClientTls(Posture posture,
                               CommunityKernelCryptoProvider provider,
                               CommunityTlsClientTrust trust,
                               CryptoProviderConfig engineConfig,
                               TrustOrigin trustOrigin) {
        this.posture = posture;
        this.provider = provider;
        this.trust = trust;
        this.engineConfig = engineConfig;
        this.trustOrigin = trustOrigin;
    }

    /**
     * A carrier that never dials: a {@code SERVER}.
     *
     * @return the shared instance, with no posture
     */
    /* default */ static NativeTcpClientTls none() {
        return NONE;
    }

    /**
     * Outbound connections are plaintext, or refused, for the stated reason.
     *
     * @param posture one of the {@code PLAINTEXT_*} postures, or {@link Posture#REFUSED_FOREIGN_PROVIDER}
     * @return the decision
     */
    /* default */ static NativeTcpClientTls unarmed(Posture posture) {
        if (posture == Posture.VERIFIED) {
            throw new IllegalArgumentException("a verifying carrier needs its provider and trust");
        }
        return new NativeTcpClientTls(posture, null, null, null, TrustOrigin.NONE);
    }

    /**
     * Outbound connections speak TLS and verify the server against {@code trust}.
     *
     * @param provider    the provider that builds the engines
     * @param trust       the store to verify against; owned by the result from here on
     * @param trustOrigin where {@code trust} was named
     * @return the decision
     */
    /* default */ static NativeTcpClientTls verified(CommunityKernelCryptoProvider provider,
                                       CommunityTlsClientTrust trust,
                                       TrustOrigin trustOrigin) {
        return new NativeTcpClientTls(Posture.VERIFIED,
                Objects.requireNonNull(provider, "provider must not be null"),
                Objects.requireNonNull(trust, "trust must not be null"),
                CryptoProviderConfig.tcpClient(),
                trustOrigin);
    }

    /**
     * The decision, or {@code null} for a carrier that never dials.
     *
     * @return the posture
     */
    /* default */ Posture posture() {
        return posture;
    }

    /**
     * Where the trust was named.
     *
     * @return the origin; {@link TrustOrigin#NONE} unless {@link #armed()}
     */
    /* default */ TrustOrigin trustOrigin() {
        return trustOrigin;
    }

    /**
     * The trust a verifying carrier uses.
     *
     * @return the trust, or {@code null} unless {@link #armed()}
     */
    /* default */ CommunityTlsClientTrust trust() {
        return trust;
    }

    /**
     * Whether outbound connections speak TLS.
     *
     * @return {@code true} for {@link Posture#VERIFIED}
     */
    /* default */ boolean armed() {
        return posture == Posture.VERIFIED;
    }

    /**
     * Whether outbound connections are refused.
     *
     * @return {@code true} for {@link Posture#REFUSED_FOREIGN_PROVIDER}
     */
    /* default */ boolean refusesOutbound() {
        return posture == Posture.REFUSED_FOREIGN_PROVIDER;
    }

    /**
     * A client engine that verifies the server against this carrier's trust and {@code peer}.
     *
     * @param peer the identity the server must present
     * @return a new engine the caller owns
     * @throws IllegalStateException if this carrier's trust has been closed, or it is not armed
     */
    /* default */ CommunityTlsEngine newEngine(TlsPeerIdentity peer) {
        if (!armed()) {
            throw new IllegalStateException("client TLS is not armed on this carrier");
        }
        return provider.createClientTlsEngine(engineConfig, trust, peer);
    }

    /**
     * Releases the trust store, if there is one. Idempotent.
     */
    @Override
    public void close() {
        if (trust != null) {
            trust.close();
        }
    }
}
