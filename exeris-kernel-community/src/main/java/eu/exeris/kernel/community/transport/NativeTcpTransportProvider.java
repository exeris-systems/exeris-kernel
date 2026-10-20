/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportProvider;

import java.util.List;
import java.util.Objects;

/**
 * Community transport provider backed by {@link NativeTcpCarrier}.
 *
 * <p>Implementation is intentionally protocol-blind at the SPI level and uses only
 * provider slots from {@link KernelProviders}.
 *
 * <h2>Client TLS</h2>
 * <p>A {@code CLIENT} or {@code DUAL} carrier's outbound connections speak TLS when
 * {@code exeris.transport.tls} is not {@code false} and a crypto provider is bound where the carrier
 * is built — for {@code DUAL}, only when its listener holds certificate material too. They then
 * verify the server against {@code crypto.tls.client.trustFile} (the kernel configuration first, then
 * the {@code exeris.crypto.tls.client.trustFile} system property), which replaces OpenSSL's default
 * trust, or against that default when the key is unset. A bound provider that cannot verify an
 * outbound peer fails a {@code CLIENT} carrier's construction and every {@code DUAL} carrier's
 * connect. There is no setting that keeps TLS and skips verification. Each {@code CLIENT} or
 * {@code DUAL} carrier records its decision as {@link TransportTlsClientPostureEvent} and an INFO
 * log line.
 *
 * <p>An owner that knows the scheme of the peer its {@code CLIENT} carrier dials states it through
 * {@link #createEngine(TransportConfig, CommunityOutboundTls)} instead of taking the decision above.
 * {@link CommunityOutboundTls#PLAINTEXT} dials plaintext whatever crypto provider is bound.
 * {@link CommunityOutboundTls#VERIFIED} verifies the server as above, or fails the carrier's
 * construction when {@code exeris.transport.tls} is {@code false}, no crypto provider is bound, or
 * the bound one cannot verify an outbound peer; it never dials plaintext. The posture event records
 * the requirement beside the decision.
 *
 * @since 0.5
 */
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public final class NativeTcpTransportProvider implements TransportProvider {

    /** The kernel configuration key naming the client trust file. */
    public static final String CLIENT_TRUST_FILE_KEY = "crypto.tls.client.trustFile";

    /* default */ static final String PROVIDER_NAME = "ExerisCommunity/NativeTcpCarrier";

    /** Opt-out from TLS for any transport that would otherwise have it; anything but "false" leaves it on. */
    /* default */ static final String TLS_PROPERTY = "exeris.transport.tls";

    private static final String PROVIDER_ID = "community-transport";

    /**
     * Instantiated reflectively by {@code ServiceLoader} through this module's
     * {@code META-INF/services} registration of {@link TransportProvider}; not meant to be
     * constructed directly.
     */
    public NativeTcpTransportProvider() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Builds a {@link NativeTcpCarrier} for the given configuration, resolving the bound
     * {@link MemoryAllocator} and, if present, the bound {@link KernelCryptoProvider}, the TLS
     * configuration of its listener and what it does with outbound connections.
     *
     * <p>{@link #createEngine(TransportConfig, CommunityOutboundTls)} with
     * {@link CommunityOutboundTls#AMBIENT}.
     *
     * @param config the transport configuration to build an engine for
     * @return a new, unstarted {@link NativeTcpCarrier}
     * @throws TransportException ({@code EX-NET-4004}) if no {@link MemoryAllocator} is bound, the
     *                             TLS material is misconfigured, {@code crypto.tls.client.trustFile}
     *                             cannot be used as client trust, a {@code CLIENT} carrier's bound
     *                             crypto provider cannot verify an outbound peer, or carrier
     *                             construction fails
     */
    @Override
    public TransportEngine createEngine(TransportConfig config) {
        return createEngine(config, CommunityOutboundTls.AMBIENT);
    }

    /**
     * Builds a {@link NativeTcpCarrier} as {@link #createEngine(TransportConfig)} does, with its
     * outbound connections held to what its owner requires.
     *
     * <p>Community-internal, and not a {@link TransportProvider} method: for an owner whose
     * {@code CLIENT} carrier dials peers of one known scheme, such as the S3 blob client. A
     * requirement other than {@link CommunityOutboundTls#AMBIENT} applies to a {@code CLIENT}
     * configuration only, since a listener's TLS follows its certificate material.
     *
     * @param config      the transport configuration to build an engine for
     * @param requirement what the owner requires of outbound connections
     * @return a new, unstarted {@link NativeTcpCarrier}
     * @throws IllegalArgumentException if {@code requirement} is not {@code AMBIENT} and
     *                                  {@code config} is not a {@code CLIENT} configuration
     * @throws TransportException       ({@code EX-NET-4004}) as {@link #createEngine(TransportConfig)},
     *                                  and, for {@link CommunityOutboundTls#VERIFIED}, if
     *                                  {@code exeris.transport.tls} is {@code false}, no crypto
     *                                  provider is bound, or the bound one cannot verify an
     *                                  outbound peer
     * @since 0.12
     */
    public TransportEngine createEngine(TransportConfig config, CommunityOutboundTls requirement) {
        return createEngine(config, requirement, NativeTcpListenerMaterial.DEFAULT);
    }

    /**
     * Builds a {@link NativeTcpCarrier} as {@link #createEngine(TransportConfig, CommunityOutboundTls)}
     * does, with a listener's ALPN answer narrowed to {@code listenerAlpn}.
     *
     * <p>Community-internal, for an owner that derives the protocols its listener may select from its
     * own configuration, such as the HTTP engine's {@code http.maxVersion}. A protocol is
     * selected only when the list names it, and the list must name {@code http/1.1}, which every
     * listener serves. A {@code CLIENT} configuration has no listener, so the list does not apply to it.
     *
     * @param config       the transport configuration to build an engine for
     * @param requirement  what the owner requires of outbound connections
     * @param listenerAlpn the ALPN protocol names a listener offers, in preference order
     * @return a new, unstarted {@link NativeTcpCarrier}
     * @throws IllegalArgumentException if {@code listenerAlpn} does not name {@code http/1.1}, or as
     *                                  {@link #createEngine(TransportConfig, CommunityOutboundTls)}
     * @throws TransportException       ({@code EX-NET-4004}) as
     *                                  {@link #createEngine(TransportConfig, CommunityOutboundTls)}
     * @since 0.13
     */
    public TransportEngine createEngine(TransportConfig config, CommunityOutboundTls requirement,
                                        List<String> listenerAlpn) {
        NativeTcpListenerMaterial.requireHttp11(listenerAlpn);
        requireApplicable(config, requirement);
        if (!KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            throw TransportException.bootstrapFailure(
                    PROVIDER_NAME,
                    "KernelProviders.MEMORY_ALLOCATOR is not bound",
                    null);
        }

        MemoryAllocator allocator = KernelProviders.MEMORY_ALLOCATOR.get();
        KernelCryptoProvider cryptoProvider =
                KernelProviders.CRYPTO_PROVIDER.isBound() ? KernelProviders.CRYPTO_PROVIDER.get() : null;

        NativeTcpTlsFloor.require();
        CryptoProviderConfig listenerConfig = NativeTcpListenerMaterial.resolve(config, listenerAlpn);
        NativeTcpClientTls clientTls =
                NativeTcpClientTlsResolver.resolve(config, cryptoProvider, listenerConfig, requirement);
        try {
            return new NativeTcpCarrier(config, allocator, cryptoProvider, listenerConfig, clientTls);
        } catch (RuntimeException cause) {
            clientTls.close();
            throw TransportException.bootstrapFailure(PROVIDER_NAME, "Failed to create NativeTcpCarrier", cause);
        }
    }

    /**
     * Returns this provider's stable identifier.
     *
     * @return {@code "community-transport"}
     */
    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * Returns this provider's display name, used in telemetry and exception context.
     *
     * @return the fixed provider name
     */
    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /**
     * Returns this provider's selection priority.
     *
     * @return {@code 0} — the Community baseline priority
     */
    @Override
    public int priority() {
        return 0;
    }

    /**
     * NIO-backed transport is always available — no platform gate required.
     */
    @Override
    public boolean isAvailable() {
        return true;
    }

    /** A requirement other than {@code AMBIENT} is for a {@code CLIENT} transport only. */
    private static void requireApplicable(TransportConfig config, CommunityOutboundTls requirement) {
        Objects.requireNonNull(requirement, "requirement must not be null");
        if (requirement != CommunityOutboundTls.AMBIENT
                && (config == null || config.mode() != TransportMode.CLIENT)) {
            throw new IllegalArgumentException("an outbound TLS requirement applies to a CLIENT transport only; got "
                    + requirement + " for " + (config == null ? "no configuration" : config.mode()));
        }
    }

    /**
     * Whether TLS is wanted, given that the transport could have it.
     *
     * <p>A listener is gated by its material, which it holds or does not. A client holds no
     * material of its own, so its default is TLS wherever a crypto provider is bound, and this
     * property is the one way to decline it. It is read from the JVM's system properties only, so
     * it applies to every listener and client in the process at once.
     *
     * <p>Read at construction, never in a static initialiser: a field resolved at class load freezes
     * whatever was set when the class was first touched (ADR-071).
     */
    /* default */ static boolean tlsWanted() {
        return !"false".equalsIgnoreCase(System.getProperty(TLS_PROPERTY));
    }
}
