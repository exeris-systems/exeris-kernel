/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.transport;

import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

/**
 * SPI: Pluggable transport layer factory — the single entry-point through which
 * the kernel bootstrapper creates a {@link TransportEngine}.
 *
 * <h2>Open-Core (The Wall)</h2>
 * <ul>
 *   <li><b>Community binding</b> (free, priority 0): Java NIO.2 TCP/TLS engine.
 *       Cross-platform, 1 VT per connection, off-heap buffers via {@link MemoryAllocator},
 *       JDK {@code SSLEngine} for TLS.</li>
 *   <li><b>Enterprise binding</b> (secret sauce, priority 100): io_uring + QUIC + OpenSSL
 *       BIO engine. Linux-only, multishot recvmsg, provided buffer rings, raw-address
 *       zero-copy pipeline, deterministic slab pools. This binding lives in
 *       {@code exeris-kernel-enterprise} and must <em>never</em> be referenced from
 *       this SPI.</li>
 * </ul>
 *
 * <h2>Discovery</h2>
 * <p>Loaded via {@link java.util.ServiceLoader}. The kernel bootstrapper selects the
 * highest-{@link #priority()} provider:
 * <pre>{@code
 * TransportProvider provider = ServiceLoader.load(TransportProvider.class)
 *     .stream()
 *     .map(ServiceLoader.Provider::get)
 *     .max(Comparator.comparingInt(TransportProvider::priority))
 *     .orElseThrow(() -> TransportException.bootstrapFailure("No TransportProvider on classpath"));
 *
 * TransportEngine engine = provider.createEngine(config, allocator, cryptoProvider);
 * ScopedValue.where(KernelProviders.TRANSPORT_ENGINE, engine).run(kernel::start);
 * }</pre>
 *
 * <h2>SPI Compliance</h2>
 * <p>This interface is <strong>implementation-blind</strong>: zero references to io_uring,
 * Netty, OpenSSL, BIO, SocketChannel, or any native transport mechanism.
 *
 * @since 0.5.0
 * @see TransportEngine
 * @see TransportConfig
 */
public interface TransportProvider {

    /**
     * Creates and initialises a {@link TransportEngine} from the given configuration.
     *
     * <p>This is a potentially blocking call (socket bind, io_uring ring setup, TLS
     * context creation). It MUST NOT be called on a virtual thread that is expected
     * to be non-blocking.
     *
     * <p>The engine receives the already-initialised {@link MemoryAllocator} and
     * {@link KernelCryptoProvider} so that transport implementations can allocate
     * buffers and create TLS sessions without importing any concrete memory/crypto class.
     *
     * @param config         transport-layer configuration (mode, port, reactor count)
     * @param allocator      the kernel-wide memory allocator (already bootstrapped)
     * @param cryptoProvider the kernel-wide crypto provider (already bootstrapped);
     *                       may be {@code null} if TLS is not required
     * @return a fully initialised, but <em>not yet started</em>, transport engine
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException if the engine
     *         cannot be created (missing native lib, bind failure, etc.)
     */
    TransportEngine createEngine(TransportConfig config,
                                 MemoryAllocator allocator,
                                 KernelCryptoProvider cryptoProvider);

    /**
     * Returns the display name of this provider.
     *
     * <p>Used in bootstrap JFR events and diagnostics. Must be a stable string constant.
     * Examples: {@code "Community/NioTransport"}, {@code "Enterprise/IoUringTransport"}.
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
}

