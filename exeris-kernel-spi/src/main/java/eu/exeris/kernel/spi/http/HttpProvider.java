/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import java.util.Optional;

/**
 * SPI: Pluggable HTTP engine factory — the single entry-point through which the
 * kernel bootstrapper creates {@link HttpServerEngine} and {@link HttpClientEngine}.
 *
 * <h2>Open-Core (The Wall)</h2>
 * <p>This interface is implementation-blind and defines only capability-level contracts.
 * Provider implementations may differ internally, but this SPI must not encode concrete
 * transport, crypto, or OS-driver details.
 *
 * <h2>Discovery</h2>
 * <p>Loaded via {@link java.util.ServiceLoader}. The kernel bootstrapper in Core selects the
 * highest-{@link #priority()} provider:
 * {@snippet lang="java" :
 * HttpProvider provider = java.util.ServiceLoader.load(HttpProvider.class)
 *         .stream()
 *         .map(java.util.ServiceLoader.Provider::get)
 *         .max(java.util.Comparator.comparingInt(HttpProvider::priority))
 *         .orElseThrow(() -> HttpException.providerBootstrapFailure("unknown", null));
 * HttpServerEngine server = provider.createServerEngine(HttpConfig.defaultServer());
 * ScopedValue.where(HttpKernelProviders.HTTP_SERVER_ENGINE, server).run(kernel::start);
 * }
 *
 * <h2>SPI Compliance</h2>
 * <p>This interface is <strong>implementation-blind</strong>: zero references to
 * TCP sockets, QUIC streams, QPACK tables, io_uring rings, or OpenSSL handles.
 *
 * @implSpec An implementation takes its {@link eu.exeris.kernel.spi.memory.MemoryAllocator} from
 *           the {@link eu.exeris.kernel.spi.context.KernelProviders} scoped slot rather than
 *           constructing one itself, so that one kernel scope has one allocator.
 * @implNote TLS/crypto is the underlying {@code TransportProvider}'s concern, handled one layer
 *           below; it is not a dependency {@code HttpProvider} sources directly.
 * @since 0.5
 * @see HttpServerEngine
 * @see HttpClientEngine
 * @see HttpConfig
 */
public interface HttpProvider {

    /**
     * Creates and initialises an {@link HttpServerEngine} from the given configuration.
     *
     * @param config HTTP server configuration; must not be {@code null}
     * @return a fully initialised, not-yet-started server engine
     * @implSpec The returned engine is in the CREATED state: it holds no bound port and accepts
     *           nothing until {@link HttpServerEngine#setHandler(HttpHandler)} and
     *           {@link HttpServerEngine#start()} have been called. Binding inside this factory
     *           would take the port before the caller has a handler to serve it with.
     * @apiNote Potentially blocking (socket setup, TLS context creation); do not call it from a
     *          virtual thread that is expected never to park. The caller owns the returned engine
     *          and releases it with {@link HttpServerEngine#close()}.
     */
    HttpServerEngine createServerEngine(HttpConfig config);

    /**
     * Creates and initialises an {@link HttpClientEngine} from the given configuration.
     *
     * @param config HTTP client configuration; must not be {@code null}
     * @return a fully initialised, not-yet-started client engine
     * @implSpec The returned engine is in the CREATED state: {@link HttpClientEngine#start()} must
     *           be called before it will send anything.
     * @apiNote The caller owns the returned engine and releases it with
     *          {@link HttpClientEngine#close()}.
     */
    HttpClientEngine createClientEngine(HttpConfig config);

    /**
     * Returns the stable, programmatic identifier for this provider.
     *
     * <p>Used for configuration routing and diagnostic JFR events. Must be a stable
     * string constant that does not change between releases.
     * Examples: {@code "community"}, {@code "enterprise"}.
     *
     * @return provider identifier; never {@code null}
     */
    String providerId();

    /**
     * Returns the display name of this provider.
     *
     * <p>Used in bootstrap JFR events and diagnostics.
     * Examples: {@code "CommunityHttpEngine"}, {@code "EnterpriseQuicHttpEngine"}.
     *
     * @return provider name; never {@code null}
     */
    String providerName();

    /**
     * Returns the priority of this provider. Higher value wins when multiple
     * providers are on the classpath.
     *
     * @return priority (higher wins)
     * @implSpec The default implementation returns {@code 0}, the Community tier's value. The
     *           open-core convention is {@code 0} for a Community provider and {@code 100} for an
     *           Enterprise one, so that adding the Enterprise jar to a classpath selects it without
     *           any configuration change.
     */
    default int priority() {
        return 0;
    }

    /**
     * Returns the registry this provider offers for encoding typed response payloads.
     *
     * @return response body encoder registry; never null
     * @implSpec The default implementation returns {@link HttpResponseBodyEncoderRegistry#empty()},
     *           which resolves nothing — a provider that ships no encoders declines typed response
     *           encoding rather than half-supporting it. Override to expose auto-binding.
     */
    default HttpResponseBodyEncoderRegistry responseBodyEncoderRegistry() {
        return HttpResponseBodyEncoderRegistry.empty();
    }

    /**
     * Returns the optional client-side typed request body encoder registry
     * exposed by this provider, if any.
     *
     * @return optional registry; empty when the provider does not contribute defaults
     * @implSpec The default implementation returns {@link Optional#empty()}. A provider shipping
     *           default body encoders overrides it, and bootstrap publishes what it returns through
     *           {@link HttpKernelProviders#HTTP_REQUEST_BODY_ENCODER_REGISTRY}.
     * @since 0.8
     */
    default Optional<HttpRequestBodyEncoderRegistry> requestBodyEncoderRegistry() {
        return Optional.empty();
    }

    /**
     * Returns the optional client-side typed response body decoder registry
     * exposed by this provider, if any.
     *
     * @return optional registry; empty when the provider does not contribute defaults
     * @implSpec The default implementation returns {@link Optional#empty()}. A provider shipping
     *           default body decoders overrides it, and bootstrap publishes what it returns through
     *           {@link HttpKernelProviders#HTTP_RESPONSE_BODY_DECODER_REGISTRY}.
     * @since 0.8
     */
    default Optional<HttpResponseBodyDecoderRegistry> responseBodyDecoderRegistry() {
        return Optional.empty();
    }

    /**
     * Returns the optional server-side typed request body decoder registry
     * exposed by this provider, if any.
     *
     * @return optional registry; empty when the provider does not contribute defaults
     * @implSpec The default implementation returns {@link Optional#empty()}. A provider shipping
     *           default body decoders overrides it, and bootstrap publishes what it returns through
     *           {@link HttpKernelProviders#HTTP_REQUEST_BODY_DECODER_REGISTRY}, which generated
     *           request handlers read to decode typed request bodies (ADR-036).
     * @since 0.8
     */
    default Optional<HttpRequestBodyDecoderRegistry> requestBodyDecoderRegistry() {
        return Optional.empty();
    }
}

