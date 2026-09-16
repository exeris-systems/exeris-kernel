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
import eu.exeris.kernel.spi.transport.TransportProvider;

/**
 * Community transport provider backed by {@link NativeTcpCarrier}.
 *
 * <p>Implementation is intentionally protocol-blind at the SPI level and uses only
 * provider slots from {@link KernelProviders}.
 *
 * @since 0.5
 */
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public final class NativeTcpTransportProvider implements TransportProvider {

    private static final String PROVIDER_ID = "community-transport";
    /** Opt-out from TLS for any transport that would otherwise have it; anything but "false" leaves it on. */
    private static final String TLS_PROPERTY = "exeris.transport.tls";

    private static final String PROVIDER_NAME = "ExerisCommunity/NativeTcpCarrier";

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
     * {@link MemoryAllocator} and, if present, the bound {@link KernelCryptoProvider} and its TLS
     * configuration for this transport.
     *
     * @param config the transport configuration to build an engine for
     * @return a new, unstarted {@link NativeTcpCarrier}
     * @throws TransportException if no {@link MemoryAllocator} is bound, the TLS material is
     *                             misconfigured, or carrier construction fails ({@code EX-NET-4004})
     */
    @Override
    public TransportEngine createEngine(TransportConfig config) {
        if (!KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            throw TransportException.bootstrapFailure(
                    PROVIDER_NAME,
                    "KernelProviders.MEMORY_ALLOCATOR is not bound",
                    null);
        }

        MemoryAllocator allocator = KernelProviders.MEMORY_ALLOCATOR.get();
        KernelCryptoProvider cryptoProvider =
                KernelProviders.CRYPTO_PROVIDER.isBound() ? KernelProviders.CRYPTO_PROVIDER.get() : null;

        CryptoProviderConfig cryptoConfig = resolveCryptoConfig(config);
        try {
            return new NativeTcpCarrier(config, allocator, cryptoProvider, cryptoConfig);
        } catch (RuntimeException cause) {
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

    /**
     * Whether TLS is wanted, given that the transport could have it.
     *
     * <p>The two sides cannot key on the same signal, and the codebase demonstrates why: the TLS
     * end-to-end tests build the server with a certificate and the client with {@code null, null},
     * and both speak TLS. A client holds no server material — that is normal, not a gap — so
     * material can gate the server and cannot gate the client. Keying the client on it was tried and
     * broke those tests, which is the clearest statement of the rule there is.
     *
     * <p>So each side keeps the only signal it has, and this knob is the opt-out both were missing.
     * TLS stays on wherever the transport can do it: material present for a server or dual, a bound
     * crypto provider for a client. What changes is that the client's answer is now a stated default
     * with a way out, instead of a consequence of another subsystem starting that nothing could
     * override — a kernel booting crypto to serve HTTPS previously could not make a plaintext
     * outbound call at all.
     *
     * <p>Read at construction, never in a static initialiser: a field resolved at class load freezes
     * whatever was set when the class was first touched (ADR-071).
     */
    private static boolean tlsWanted() {
        return !"false".equalsIgnoreCase(System.getProperty(TLS_PROPERTY));
    }

    private static CryptoProviderConfig resolveCryptoConfig(TransportConfig config) {
        if (config == null) {
            return null;
        }
        if (config.mode() == eu.exeris.kernel.spi.transport.TransportMode.CLIENT) {
            // A client holds no material of its own, so the decision is all there is.
            return tlsWanted() ? CryptoProviderConfig.tcpClient() : null;
        }
        return resolveListenerCryptoConfig(config);
    }

    /**
     * The server and dual answer, which material gates and the opt-out can then decline.
     *
     * <p>Validation runs before the opt-out on purpose: half-configured material is a deployment
     * mistake and stays a boot failure even when TLS was declined, because the next deployment that
     * drops the decline would otherwise start with a broken pair and no warning.
     */
    private static CryptoProviderConfig resolveListenerCryptoConfig(TransportConfig config) {
        String certPath = config.certPath();
        String keyPath = config.keyPath();
        if (certPath == null && keyPath == null) {
            return null;
        }
        if (certPath == null || keyPath == null) {
            throw TransportException.bootstrapFailure(
                    PROVIDER_NAME,
                    "TLS is misconfigured: both certPath and keyPath must be set together or both be null",
                    null);
        }
        if (!tlsWanted()) {
            // A listener that holds valid material and serves plaintext anyway is the one case here
            // that is invisible from the outside and security-relevant: the socket looks like every
            // other plaintext socket. The knob is legitimate — TLS terminated at a sidecar — but it
            // must not be silent, so the decision leaves a trail the way every other bind does.
            TransportTlsDeclinedEvent.emit(config.mode().name(), TLS_PROPERTY);
            return null;
        }
        return CryptoProviderConfig.httpsServer(
                java.nio.file.Path.of(certPath),
                java.nio.file.Path.of(keyPath));
    }
}
