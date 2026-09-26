/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.crypto.CommunityTlsClientTrust;
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
 * trusts. Each {@code CLIENT} or {@code DUAL} decision is recorded as
 * {@link TransportTlsClientPostureEvent} and an INFO log line.
 */
final class NativeTcpClientTlsResolver {

    private static final System.Logger LOG = System.getLogger(NativeTcpClientTlsResolver.class.getName());

    /** The system property naming the client trust file when the kernel configuration does not. */
    private static final String CLIENT_TRUST_FILE_PROPERTY =
            "exeris." + NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY;

    private static final String PROVIDER_NAME = NativeTcpTransportProvider.PROVIDER_NAME;
    private static final String CLIENT_TRUST_FILE_KEY = NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY;

    private NativeTcpClientTlsResolver() {
    }

    /**
     * What a carrier does with its outbound connections, recorded for {@code CLIENT} and
     * {@code DUAL}. A configured trust file is checked even when outbound TLS is not armed, the same
     * rule as listener material checked before the opt-out; the trust is opened last, once nothing
     * else can refuse the carrier.
     */
    /* default */ static NativeTcpClientTls resolve(TransportConfig config,
                                                  KernelCryptoProvider cryptoProvider,
                                                  CryptoProviderConfig listenerConfig) {
        if (config == null || config.mode() == TransportMode.SERVER || config.mode() == TransportMode.DISABLED) {
            return NativeTcpClientTls.none();
        }
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.isBound()
                ? KernelProviders.CURRENT_CONFIG.get()
                : null;
        ClientTrustSetting trustSetting = ClientTrustSetting.resolve(configProvider);

        NativeTcpClientTls.Posture plaintext = plaintextPosture(config.mode(), cryptoProvider, listenerConfig);
        if (plaintext != null) {
            return recorded(config.mode(), NativeTcpClientTls.unarmed(plaintext), configProvider != null);
        }
        if (!(cryptoProvider instanceof CommunityKernelCryptoProvider community)) {
            NativeTcpClientTls refused = recorded(config.mode(),
                    NativeTcpClientTls.unarmed(NativeTcpClientTls.Posture.REFUSED_FOREIGN_PROVIDER),
                    configProvider != null);
            if (config.mode() == TransportMode.CLIENT) {
                throw TransportException.bootstrapFailure(PROVIDER_NAME,
                        "bound crypto provider cannot verify an outbound peer", null);
            }
            return refused;
        }
        CommunityTlsClientTrust trust = openTrust(community, trustSetting);
        return recorded(config.mode(),
                NativeTcpClientTls.verified(community, trust, trustSetting.origin()),
                configProvider != null);
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

    /** Emits the posture event and log line, then returns {@code decision}, which owns its trust. */
    @SuppressWarnings("PMD.CloseResource") // reads the decision's trust; the decision closes it
    private static NativeTcpClientTls recorded(TransportMode mode, NativeTcpClientTls decision, boolean configBound) {
        CommunityTlsClientTrust trust = decision.trust();
        String defaultFile = trust == null ? null : trust.defaultCertFile();
        String defaultDir = trust == null ? null : trust.defaultCertDir();
        boolean defaultPresent = trust != null && trust.defaultTrustPresent();
        TransportTlsClientPostureEvent.emit(mode.name(), decision.posture().name(), decision.trustOrigin().name(),
                configBound, defaultFile, defaultDir, defaultPresent);
        LOG.log(System.Logger.Level.INFO, () -> "[NativeTcpTransportProvider] client TLS mode=" + mode
                + " posture=" + decision.posture()
                + " trust=" + decision.trustOrigin()
                + " configBound=" + configBound
                + (trust == null ? "" : " defaultCertFile=" + defaultFile + " defaultCertDir=" + defaultDir));
        if (decision.trustOrigin() == NativeTcpClientTls.TrustOrigin.SYSTEM_DEFAULT && !defaultPresent) {
            LOG.log(System.Logger.Level.WARNING, () -> "[NativeTcpTransportProvider] client TLS verifies against "
                    + "OpenSSL's default trust, and neither " + defaultFile + " nor " + defaultDir
                    + " exists, so no server will verify; set " + CLIENT_TRUST_FILE_KEY + " or SSL_CERT_FILE");
        }
        return decision;
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
