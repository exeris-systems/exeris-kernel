/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.crypto.CommunityTlsClientTrust;
import eu.exeris.kernel.core.crypto.tls.TlsFailureDetail;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportMode;

import java.nio.file.Path;

/**
 * The client TLS decision {@link NativeTcpTransportProvider} makes for each carrier: whether its
 * outbound connections verify TLS, dial plaintext or are refused, and what a verifying carrier
 * trusts. The carrier's owner may require the answer ({@link CommunityOutboundTls}); otherwise it
 * follows what is bound where the carrier is built. Each {@code CLIENT} or {@code DUAL} decision is
 * recorded as {@link TransportTlsClientPostureEvent} and an INFO log line.
 */
final class NativeTcpClientTlsResolver {

    /** Why a carrier whose owner requires verified TLS is not built under the opt-out. */
    /* default */ static final String VERIFIED_BUT_DECLINED =
            "the peer requires TLS, and exeris.transport.tls=false declines it";

    /** Why a carrier whose owner requires verified TLS is not built with no crypto provider bound. */
    /* default */ static final String VERIFIED_BUT_NO_CRYPTO_PROVIDER =
            "the peer requires TLS, and no crypto provider is bound";

    /** The system property naming the client trust file when the kernel configuration does not. */
    private static final String CLIENT_TRUST_FILE_PROPERTY =
            "exeris." + NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY;

    private static final String PROVIDER_NAME = NativeTcpTransportProvider.PROVIDER_NAME;
    private static final String CLIENT_TRUST_FILE_KEY = NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY;

    private NativeTcpClientTlsResolver() {
    }

    /**
     * What a carrier does with its outbound connections, recorded for {@code CLIENT} and
     * {@code DUAL}. A configured trust file is checked first, whatever the requirement and even when
     * outbound TLS is not armed, the same rule as listener material checked before the opt-out; the
     * trust is opened last, once nothing else can refuse the carrier.
     *
     * @param requirement what the carrier's owner requires; anything but
     *                    {@link CommunityOutboundTls#AMBIENT} reaches here for a {@code CLIENT} only
     * @throws TransportException ({@code EX-NET-4004}) if the trust file cannot be used, a
     *         {@code CLIENT} carrier's bound provider cannot verify an outbound peer, or a
     *         {@link CommunityOutboundTls#VERIFIED} requirement cannot be met
     */
    /* default */ static NativeTcpClientTls resolve(TransportConfig config,
                                                  KernelCryptoProvider cryptoProvider,
                                                  CryptoProviderConfig listenerConfig,
                                                  CommunityOutboundTls requirement) {
        if (config == null || config.mode() == TransportMode.SERVER || config.mode() == TransportMode.DISABLED) {
            return NativeTcpClientTls.none();
        }
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.isBound()
                ? KernelProviders.CURRENT_CONFIG.get()
                : null;
        ClientTrustSetting trustSetting = ClientTrustSetting.resolve(configProvider);
        Decision decision = new Decision(config.mode(), requirement, configProvider != null);

        return switch (requirement) {
            case AMBIENT -> ambient(decision, cryptoProvider, listenerConfig, trustSetting);
            case PLAINTEXT -> recorded(decision,
                    NativeTcpClientTls.unarmed(NativeTcpClientTls.Posture.PLAINTEXT_REQUIRED));
            case VERIFIED -> verifiedOrRefused(decision, cryptoProvider, trustSetting);
        };
    }

    /** The decision where the owner stated no requirement: what is bound where the carrier is built. */
    private static NativeTcpClientTls ambient(Decision decision,
                                              KernelCryptoProvider cryptoProvider,
                                              CryptoProviderConfig listenerConfig,
                                              ClientTrustSetting trustSetting) {
        NativeTcpClientTls.Posture plaintext = plaintextPosture(decision.mode(), cryptoProvider, listenerConfig);
        if (plaintext != null) {
            return recorded(decision, NativeTcpClientTls.unarmed(plaintext));
        }
        if (!(cryptoProvider instanceof CommunityKernelCryptoProvider community)) {
            NativeTcpClientTls refused = recorded(decision,
                    NativeTcpClientTls.unarmed(NativeTcpClientTls.Posture.REFUSED_FOREIGN_PROVIDER));
            if (decision.mode() == TransportMode.CLIENT) {
                throw TransportException.bootstrapFailure(PROVIDER_NAME, TlsFailureDetail.NO_PEER_VERIFIER, null);
            }
            return refused;
        }
        CommunityTlsClientTrust trust = openTrust(community, trustSetting);
        return recorded(decision, NativeTcpClientTls.verified(community, trust, trustSetting.origin()));
    }

    /**
     * The decision where the owner requires verified TLS: a verifying carrier, or a recorded refusal
     * thrown before any carrier exists. It never falls back to plaintext.
     */
    private static NativeTcpClientTls verifiedOrRefused(Decision decision,
                                                        KernelCryptoProvider cryptoProvider,
                                                        ClientTrustSetting trustSetting) {
        if (NativeTcpTransportProvider.tlsWanted()
                && cryptoProvider instanceof CommunityKernelCryptoProvider community) {
            CommunityTlsClientTrust trust = openTrust(community, trustSetting);
            return recorded(decision, NativeTcpClientTls.verified(community, trust, trustSetting.origin()));
        }
        VerifiedRefusal refusal = VerifiedRefusal.applying(cryptoProvider);
        recorded(decision, NativeTcpClientTls.unarmed(refusal.posture()));
        throw TransportException.bootstrapFailure(PROVIDER_NAME, refusal.reason(), null);
    }

    /**
     * The plaintext posture that applies, or {@code null} when outbound TLS is wanted and a provider
     * is bound. A {@code DUAL} carrier arms outbound TLS only when its listener is armed.
     */
    private static NativeTcpClientTls.Posture plaintextPosture(TransportMode mode,
                                                               KernelCryptoProvider cryptoProvider,
                                                               CryptoProviderConfig listenerConfig) {
        if (!NativeTcpTransportProvider.tlsWanted()) {
            return NativeTcpClientTls.Posture.PLAINTEXT_DECLINED;
        }
        if (mode == TransportMode.DUAL && listenerConfig == null) {
            return NativeTcpClientTls.Posture.PLAINTEXT_NO_LISTENER_MATERIAL;
        }
        if (cryptoProvider == null) {
            return NativeTcpClientTls.Posture.PLAINTEXT_NO_CRYPTO_PROVIDER;
        }
        return null;
    }

    private static CommunityTlsClientTrust openTrust(CommunityKernelCryptoProvider provider,
                                                     ClientTrustSetting trustSetting) {
        try {
            return provider.openClientTrust(trustSetting.file());
        } catch (CryptoBootstrapException cause) {
            throw trustSetting.origin() == NativeTcpClientTls.TrustOrigin.SYSTEM_DEFAULT
                    ? TransportException.bootstrapFailure(PROVIDER_NAME,
                            "the default client trust cannot be opened", cause)
                    : unusableTrustFile(cause);
        }
    }

    private static TransportException unusableTrustFile(CryptoBootstrapException cause) {
        return TransportException.bootstrapFailure(PROVIDER_NAME,
                CLIENT_TRUST_FILE_KEY + " cannot be used as client trust", cause);
    }

    /** Reports {@code outcome} and returns it; it owns its trust. */
    private static NativeTcpClientTls recorded(Decision decision, NativeTcpClientTls outcome) {
        NativeTcpClientTlsReporter.report(decision.mode(), decision.requirement(), outcome, decision.configBound());
        return outcome;
    }

    /**
     * What is fixed about one carrier's decision before its outcome is known.
     *
     * @param mode        {@code CLIENT} or {@code DUAL}
     * @param requirement what the carrier's owner requires
     * @param configBound whether the kernel configuration was bound where the carrier is built
     */
    private record Decision(TransportMode mode, CommunityOutboundTls requirement, boolean configBound) {
    }

    /** Why a {@link CommunityOutboundTls#VERIFIED} requirement cannot be met, in the order checked. */
    private enum VerifiedRefusal {
        DECLINED(NativeTcpClientTls.Posture.REFUSED_DECLINED, VERIFIED_BUT_DECLINED),
        NO_CRYPTO_PROVIDER(NativeTcpClientTls.Posture.REFUSED_NO_CRYPTO_PROVIDER, VERIFIED_BUT_NO_CRYPTO_PROVIDER),
        FOREIGN_PROVIDER(NativeTcpClientTls.Posture.REFUSED_FOREIGN_PROVIDER, TlsFailureDetail.NO_PEER_VERIFIER);

        private final NativeTcpClientTls.Posture posture;
        private final String reason;

        VerifiedRefusal(NativeTcpClientTls.Posture posture, String reason) {
            this.posture = posture;
            this.reason = reason;
        }

        /** The refusal that applies when the Community provider cannot verify under TLS: the opt-out first. */
        private static VerifiedRefusal applying(KernelCryptoProvider cryptoProvider) {
            if (!NativeTcpTransportProvider.tlsWanted()) {
                return DECLINED;
            }
            return cryptoProvider == null ? NO_CRYPTO_PROVIDER : FOREIGN_PROVIDER;
        }

        private NativeTcpClientTls.Posture posture() {
            return posture;
        }

        private String reason() {
            return reason;
        }
    }

    /**
     * The client trust file a carrier was configured with, and where that was said.
     *
     * @param file   the file, or {@code null} for OpenSSL's default trust
     * @param origin where it was named
     */
    private record ClientTrustSetting(Path file, NativeTcpClientTls.TrustOrigin origin) {

        /**
         * Reads the key, then the system property, and checks a named file with Java before any
         * native call.
         *
         * @throws TransportException ({@code EX-NET-4004}, cause {@code EX-NET-2002}) if a named
         *         file is not a readable regular file
         */
        /* default */ static ClientTrustSetting resolve(ConfigProvider configProvider) {
            String configured = configProvider == null
                    ? null
                    : configProvider.getString(CLIENT_TRUST_FILE_KEY).orElse(null);
            if (configured != null && !configured.isBlank()) {
                return named(configured, NativeTcpClientTls.TrustOrigin.CONFIG_KEY);
            }
            String property = System.getProperty(CLIENT_TRUST_FILE_PROPERTY);
            if (property != null && !property.isBlank()) {
                return named(property, NativeTcpClientTls.TrustOrigin.SYSTEM_PROPERTY);
            }
            return new ClientTrustSetting(null, NativeTcpClientTls.TrustOrigin.SYSTEM_DEFAULT);
        }

        private static ClientTrustSetting named(String location, NativeTcpClientTls.TrustOrigin origin) {
            try {
                return new ClientTrustSetting(CommunityTlsClientTrust.readableTrustFile(location.strip()), origin);
            } catch (CryptoBootstrapException cause) {
                throw unusableTrustFile(cause);
            }
        }
    }
}
