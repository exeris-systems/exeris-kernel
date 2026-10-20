/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A TCP listener's server material: whether it has any, and the ALPN offer it carries, which is the
 * protocol list its owner names and around which the listener's configuration is rebuilt.
 */
final class NativeTcpListenerMaterial {

    /** The offer when the owner names none: HTTP/2 first, then HTTP/1.1. */
    /* default */ static final List<String> DEFAULT = List.of("h2", "http/1.1");

    /** The fallback every TCP listener serves, which every offer therefore names. */
    /* default */ static final String HTTP_1_1 = "http/1.1";

    private NativeTcpListenerMaterial() {
    }

    /**
     * Refuses an offer that does not name {@code http/1.1}, the protocol every TCP listener serves
     * once its handshake completes: a list without it would have an HTTP/1.1 client complete the
     * handshake with no ALPN protocol, which the list claims the listener does not speak.
     *
     * @param alpn the owner's offer
     * @throws IllegalArgumentException if {@code alpn} does not name {@code http/1.1}
     */
    /* default */ static void requireHttp11(List<String> alpn) {
        Objects.requireNonNull(alpn, "listenerAlpn must not be null");
        if (!alpn.contains(HTTP_1_1)) {
            throw new IllegalArgumentException("a listener's ALPN list must name " + HTTP_1_1
                    + ", the fallback every TCP listener serves; got " + alpn);
        }
    }

    /**
     * The listener's TLS configuration: {@code null} for a {@code CLIENT}, which listens on nothing,
     * and for a listener with no material or whose TLS was declined.
     *
     * <p>The server and dual answer, which material gates and the opt-out can then decline.
     * Validation runs before the opt-out on purpose: half-configured material is a deployment
     * mistake and stays a boot failure even when TLS was declined, because the next deployment that
     * drops the decline would otherwise start with a broken pair and no warning.
     *
     * @param config the transport configuration
     * @param alpn   the ALPN offer the listener makes
     * @return the listener's TLS configuration, or {@code null} when it serves plaintext
     */
    /* default */ static CryptoProviderConfig resolve(TransportConfig config, List<String> alpn) {
        if (config == null || config.mode() == TransportMode.CLIENT) {
            return null;
        }
        String certPath = config.certPath();
        String keyPath = config.keyPath();
        if (certPath == null && keyPath == null) {
            return null;
        }
        if (certPath == null || keyPath == null) {
            throw TransportException.bootstrapFailure(
                    NativeTcpTransportProvider.PROVIDER_NAME,
                    "TLS is misconfigured: both certPath and keyPath must be set together or both be null",
                    null);
        }
        if (!NativeTcpTransportProvider.tlsWanted()) {
            // A listener that holds valid material and serves plaintext anyway is the one case here
            // that is invisible from the outside and security-relevant: the socket looks like every
            // other plaintext socket. The knob is legitimate — TLS terminated at a sidecar — but it
            // must not be silent, so the decision leaves a trail the way every other bind does.
            TransportTlsDeclinedEvent.emit(config.mode().name(), NativeTcpTransportProvider.TLS_PROPERTY);
            return null;
        }
        CryptoProviderConfig material = CryptoProviderConfig.httpsServer(Path.of(certPath), Path.of(keyPath));
        return NativeTcpTlsFloor.apply(offering(material, alpn));
    }

    /**
     * {@code base} offering {@code alpn} instead of its own list, rebuilt through the canonical constructor.
     *
     * @param base the configuration carrying the SPI default offer
     * @param alpn the offer the listener makes
     * @return the configuration with {@code alpn} as its offer
     */
    /* default */ static CryptoProviderConfig offering(CryptoProviderConfig base, List<String> alpn) {
        return new CryptoProviderConfig(base.protocol(), base.certChainPath(), base.privateKeyPath(),
                alpn, base.sessionCacheSize(), base.jfrEnabled(), base.minimumTlsVersion());
    }
}
