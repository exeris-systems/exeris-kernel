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
 * <pre>{@code
 * HttpProvider provider = java.util.ServiceLoader.load(HttpProvider.class)
 *         .stream()
 *         .map(java.util.ServiceLoader.Provider::get)
 *         .max(java.util.Comparator.comparingInt(HttpProvider::priority))
 *         .orElseThrow(() -> HttpException.providerBootstrapFailure("unknown", null));
 * HttpServerEngine server = provider.createServerEngine(HttpConfig.defaultServer());
 * ScopedValue.where(HttpKernelProviders.HTTP_SERVER_ENGINE, server).run(kernel::start);
 * }</pre>
 *
 * <h2>SPI Compliance</h2>
 * <p>This interface is <strong>implementation-blind</strong>: zero references to
 * TCP sockets, QUIC streams, QPACK tables, io_uring rings, or OpenSSL handles.
 *
 * <h2>Dependency Injection</h2>
 * <p>Implementations obtain their {@link eu.exeris.kernel.spi.memory.MemoryAllocator}
 * and {@link eu.exeris.kernel.spi.crypto.KernelCryptoProvider} from
 * {@link eu.exeris.kernel.spi.context.KernelProviders} scoped slots — consistent with
 * {@code TransportProvider}, {@code PersistenceProvider}, and {@code GraphProvider}.
 *
 * @see HttpServerEngine
 * @see HttpClientEngine
 * @see HttpConfig
 * @since 0.5.0
 */
public interface HttpProvider {

    /**
     * Creates and initialises an {@link HttpServerEngine} from the given configuration.
     *
     * <p>This is a potentially blocking call (socket bind, TLS context creation).
     * MUST NOT be called on a virtual thread expected to be non-blocking.
     *
     * <p>The returned engine is in the CREATED state — {@link HttpServerEngine#setHandler(HttpHandler)}
     * and {@link HttpServerEngine#start()} must be called before it accepts connections.
     *
     * @param config HTTP server configuration; must not be {@code null}
     * @return a fully initialised, not-yet-started server engine
     */
    HttpServerEngine createServerEngine(HttpConfig config);

    /**
     * Creates and initialises an {@link HttpClientEngine} from the given configuration.
     *
     * <p>The returned engine is in the CREATED state — {@link HttpClientEngine#start()} must
     * be called before sending requests.
     *
     * @param config HTTP client configuration; must not be {@code null}
     * @return a fully initialised, not-yet-started client engine
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
     * <p>Convention:
     * <ul>
     *   <li>Community: {@code 0}</li>
     *   <li>Enterprise: {@code 100}</li>
     * </ul>
     *
     * @return priority (higher wins)
     */
    default int priority() {
        return 0;
    }

    /**
     * Returns typed-response encoder registry for this provider.
     *
     * <p>Default implementation returns an empty registry to preserve compatibility.
     * Providers may override this to expose auto-binding response encoding.
     *
     * @return response body encoder registry; never null
     */
    default HttpResponseBodyEncoderRegistry responseBodyEncoderRegistry() {
        return HttpResponseBodyEncoderRegistry.empty();
    }

    /**
     * Returns the optional client-side typed request body encoder registry
     * exposed by this provider, if any.
     *
     * <p>Default implementation returns {@link Optional#empty()} — providers that
     * ship default body encoders (e.g., Community Jackson JSON) override this to
     * expose them via the {@link HttpKernelProviders#HTTP_REQUEST_BODY_ENCODER_REGISTRY}
     * bootstrap channel.
     *
     * @return optional registry; empty when the provider does not contribute defaults
     * @since 0.8.0
     */
    default Optional<HttpRequestBodyEncoderRegistry> requestBodyEncoderRegistry() {
        return Optional.empty();
    }

    /**
     * Returns the optional client-side typed response body decoder registry
     * exposed by this provider, if any.
     *
     * <p>Default implementation returns {@link Optional#empty()} — providers that
     * ship default body decoders (e.g., Community Jackson JSON) override this to
     * expose them via the {@link HttpKernelProviders#HTTP_RESPONSE_BODY_DECODER_REGISTRY}
     * bootstrap channel.
     *
     * @return optional registry; empty when the provider does not contribute defaults
     * @since 0.8.0
     */
    default Optional<HttpResponseBodyDecoderRegistry> responseBodyDecoderRegistry() {
        return Optional.empty();
    }

    /**
     * Returns the optional server-side typed request body decoder registry
     * exposed by this provider, if any.
     *
     * <p>Default implementation returns {@link Optional#empty()} — providers that
     * ship default body decoders (e.g., Community Jackson JSON) override this to
     * expose them via the {@link HttpKernelProviders#HTTP_REQUEST_BODY_DECODER_REGISTRY}
     * bootstrap channel, which generated request handlers read to decode typed
     * request bodies (ADR-036).
     *
     * @return optional registry; empty when the provider does not contribute defaults
     * @since 0.8.0
     */
    default Optional<HttpRequestBodyDecoderRegistry> requestBodyDecoderRegistry() {
        return Optional.empty();
    }
}

