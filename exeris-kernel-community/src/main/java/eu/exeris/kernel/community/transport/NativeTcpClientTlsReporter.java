/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityTlsClientTrust;
import eu.exeris.kernel.spi.transport.TransportMode;

/**
 * Reports one carrier's client TLS decision: {@link TransportTlsClientPostureEvent}, an INFO log line
 * and, for a default trust that has neither its file nor its directory, a WARNING.
 *
 * <p>Logged under {@link NativeTcpClientTlsResolver}'s name, since the decision is the resolver's.
 */
final class NativeTcpClientTlsReporter {

    private static final System.Logger LOG = System.getLogger(NativeTcpClientTlsResolver.class.getName());

    private NativeTcpClientTlsReporter() {
    }

    /**
     * Emits the posture event and the log lines for {@code outcome}.
     *
     * @param mode        {@code CLIENT} or {@code DUAL}
     * @param requirement what the carrier's owner required
     * @param outcome     the decision; its trust is read, not taken
     * @param configBound whether the kernel configuration was bound where the carrier is built
     */
    @SuppressWarnings("PMD.CloseResource") // reads the outcome's trust; the outcome closes it
    /* default */ static void report(TransportMode mode, CommunityOutboundTls requirement,
                                     NativeTcpClientTls outcome, boolean configBound) {
        CommunityTlsClientTrust trust = outcome.trust();
        String defaultFile = trust == null ? null : trust.defaultCertFile();
        String defaultDir = trust == null ? null : trust.defaultCertDir();
        boolean defaultPresent = trust != null && trust.defaultTrustPresent();
        TransportTlsClientPostureEvent.emit(mode.name(), requirement.name(), outcome.posture().name(),
                outcome.trustOrigin().name(), configBound, defaultFile, defaultDir, defaultPresent);
        LOG.log(System.Logger.Level.INFO, () -> "[NativeTcpTransportProvider] client TLS mode=" + mode
                + " requirement=" + requirement
                + " posture=" + outcome.posture()
                + " trust=" + outcome.trustOrigin()
                + " configBound=" + configBound
                + (trust == null ? "" : " defaultCertFile=" + defaultFile + " defaultCertDir=" + defaultDir));
        if (outcome.trustOrigin() == NativeTcpClientTls.TrustOrigin.SYSTEM_DEFAULT && !defaultPresent) {
            LOG.log(System.Logger.Level.WARNING, () -> "[NativeTcpTransportProvider] client TLS verifies against "
                    + "OpenSSL's default trust, and neither " + defaultFile + " nor " + defaultDir
                    + " exists, so no server will verify; set " + NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY
                    + " or SSL_CERT_FILE");
        }
    }
}
