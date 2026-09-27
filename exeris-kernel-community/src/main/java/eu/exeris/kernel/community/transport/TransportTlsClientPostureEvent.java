/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * What a {@code CLIENT} or {@code DUAL} carrier does with its outbound connections, recorded once
 * when the carrier is built.
 *
 * <p>Outbound TLS is armed only where a crypto provider is bound when the carrier is built and
 * {@code exeris.transport.tls} is not {@code false}. An engine built anywhere else dials plaintext,
 * unless its owner required plaintext or verified TLS ({@code requirement}), and from outside the
 * process a plaintext dial looks like any other. This event makes the decision, what it was held to,
 * and the trust a verifying carrier uses, visible.
 *
 * @since 0.12
 */
@Name("eu.exeris.kernel.transport.TransportTlsClientPosture")
@Label("Transport TLS Client Posture")
@Category({"Exeris Kernel", "Transport"})
@Description("Whether a client or dual carrier's outbound connections verify TLS, dial plaintext or are "
        + "refused, and what they trust")
@StackTrace(false)
final class TransportTlsClientPostureEvent extends Event {

    @Label("Transport Mode")
    @Description("CLIENT or DUAL")
    /* default */ String transportMode;

    @Label("Requirement")
    @Description("What the carrier's owner required: AMBIENT when it stated nothing and the decision follows "
            + "what is bound where the carrier is built, PLAINTEXT, or VERIFIED")
    /* default */ String requirement;

    @Label("Posture")
    @Description("VERIFIED, PLAINTEXT_DECLINED, PLAINTEXT_NO_CRYPTO_PROVIDER, PLAINTEXT_NO_LISTENER_MATERIAL, "
            + "PLAINTEXT_REQUIRED, REFUSED_FOREIGN_PROVIDER, REFUSED_DECLINED or REFUSED_NO_CRYPTO_PROVIDER")
    /* default */ String posture;

    @Label("Trust Source")
    @Description("CONFIG_KEY, SYSTEM_PROPERTY, SYSTEM_DEFAULT, or NONE when nothing is verified")
    /* default */ String trustSource;

    @Label("Configuration Bound")
    @Description("Whether the kernel configuration was bound where the carrier was built")
    /* default */ boolean configBound;

    @Label("Default Certificate File")
    @Description("The CA file OpenSSL's default trust reads, after SSL_CERT_FILE; empty unless the trust "
            + "is the default")
    /* default */ String defaultCertFile;

    @Label("Default Certificate Directory")
    @Description("The CA directory OpenSSL's default trust consults, after SSL_CERT_DIR; empty unless the "
            + "trust is the default")
    /* default */ String defaultCertDir;

    @Label("Default Trust Present")
    @Description("Whether the default file or directory exists; a default trust without either verifies no server")
    /* default */ boolean defaultTrustPresent;

    /* default */ static void emit(String transportMode, String requirement, String posture, String trustSource,
                                   boolean configBound, String defaultCertFile, String defaultCertDir,
                                   boolean defaultTrustPresent) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TransportTlsClientPostureEvent event = new TransportTlsClientPostureEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.transportMode = transportMode;
        event.requirement = requirement;
        event.posture = posture;
        event.trustSource = trustSource;
        event.configBound = configBound;
        event.defaultCertFile = defaultCertFile;
        event.defaultCertDir = defaultCertDir;
        event.defaultTrustPresent = defaultTrustPresent;
        event.commit();
    }
}
