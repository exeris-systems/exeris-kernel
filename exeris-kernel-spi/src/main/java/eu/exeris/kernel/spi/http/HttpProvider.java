/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.exceptions.http.HttpException;

import java.util.Comparator;
import java.util.ServiceLoader;

/**
 * SPI: Pluggable HTTP engine factory — the single entry-point through which the
 * kernel bootstrapper creates {@link HttpServerEngine} and {@link HttpClientEngine}.
 *
 * <h2>Open-Core (The Wall)</h2>
 * <ul>
 *   <li><b>Community binding</b> (free, priority 0): HTTP/1.1 + HTTP/2 engine backed by
 *       NIO TCP sockets, off-heap TLS (OpenSSL 3.x), HPACK-compressed HTTP/2 frames.
 *       Lives in {@code exeris-kernel-community}.</li>
 *   <li><b>Enterprise binding</b> (secret sauce, priority 100): HTTP/3 + QPACK engine backed by
 *       QUIC (io_uring UDP, {@code QuicBioMultiplexer}), with HTTP/2 fallback.
 *       Lives in {@code exeris-kernel-enterprise}. MUST NOT be referenced from this SPI.</li>
 * </ul>
 *
 * <h2>Discovery</h2>
 * <p>Loaded via {@link ServiceLoader}. The kernel bootstrapper selects the
 * highest-{@link #priority()} provider:
 * <pre>{@code
 * HttpProvider provider = HttpProvider.selectHighestPriority();
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
     * @throws HttpException
     *         if the engine cannot be created (missing native lib, TLS context failure)
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
     * @throws HttpException
     *         if the engine cannot be created
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
     * Selects the highest-priority {@link HttpProvider} available on the classpath.
     *
     * <p>This is a convenience bootstrap helper. The kernel bootstrapper may use it
     * directly or perform its own {@link ServiceLoader} resolution.
     *
     * @return the selected provider
     * @throws HttpException
     *         if no {@code HttpProvider} is found on the classpath
     */
    static HttpProvider selectHighestPriority() {
        return ServiceLoader.load(HttpProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(HttpProvider::priority))
                .orElseThrow(() -> HttpException.providerBootstrapFailure("unknown", null));
    }
}

