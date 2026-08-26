/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

/**
 * SPI: Pluggable transport layer factory — the single entry-point through which
 * the kernel bootstrapper creates a {@link TransportEngine}.
 *
 * <h2>Open-Core (The Wall)</h2>
 * <ul>
 *   <li><b>Community binding</b> (free, priority 0): Standard cross-platform transport
 *       engine. 1 VT per connection, off-heap buffers, JDK-native TLS for encryption.</li>
 *   <li><b>Enterprise binding</b> (secret sauce, priority 100): Multiplexed zero-copy
 *       transport with native asynchronous I/O, provided buffer rings, and a
 *       deterministic slab pool pipeline. This binding lives in
 *       {@code exeris-kernel-enterprise} and must <em>never</em> be referenced from
 *       this SPI.</li>
 * </ul>
 *
 * <h2>Discovery</h2>
 * <p>Loaded via {@link java.util.ServiceLoader}. The kernel bootstrapper iterates providers in
 * descending priority order and selects the first one for which {@link #isAvailable()} returns
 * {@code true}:
 * <pre>{@code
 * TransportProvider provider = ServiceLoader.load(TransportProvider.class)
 *     .stream()
 *     .map(ServiceLoader.Provider::get)
 *     .sorted(Comparator.comparingInt(TransportProvider::priority).reversed())
 *     .filter(TransportProvider::isAvailable)
 *     .findFirst()
 *     .orElseThrow(() -> TransportException.bootstrapFailure(
 *             "unknown", "No available TransportProvider on classpath", null));
 *
 * TransportEngine engine = provider.createEngine(config);
 * ScopedValue.where(KernelProviders.TRANSPORT_ENGINE, engine).run(kernel::start);
 * }</pre>
 *
 * <h2>SPI Compliance</h2>
 * <p>This interface is <strong>implementation-blind</strong>: zero references to specific
 * transport APIs, native I/O mechanisms, or TLS library implementations.
 *
 * <h2>SPI Dependency Injection</h2>
 * <p>Implementations obtain their
 * {@link eu.exeris.kernel.spi.memory.MemoryAllocator} and
 * {@link eu.exeris.kernel.spi.crypto.KernelCryptoProvider} from
 * {@link eu.exeris.kernel.spi.context.KernelProviders} scoped slots — they do NOT
 * receive them as method parameters. This ensures clean SPI isolation and is
 * consistent with {@code PersistenceProvider} and {@code GraphProvider}.
 *
 * @see TransportEngine
 * @see TransportConfig
 * @since 0.5.0
 */
public interface TransportProvider {

    /**
     * Creates and initialises a {@link TransportEngine} from the given configuration.
     *
     * <p>This is a potentially blocking call (socket bind, native transport setup, TLS
     * context creation). It MUST NOT be called on a virtual thread that is expected
     * to be non-blocking.
     *
     * <p>Implementations read upstream providers from {@code KernelProviders} scoped slots:
     * <ul>
     *   <li>{@code KernelProviders.MEMORY_ALLOCATOR} — for off-heap buffer allocation</li>
     *   <li>{@code KernelProviders.CRYPTO_PROVIDER} — for TLS session creation;
     *       the slot may be unbound if TLS is not required, implementations must
     *       guard with {@code KernelProviders.CRYPTO_PROVIDER.isBound()}</li>
     * </ul>
     *
     * @param config transport-layer configuration (mode, port, reactor count)
     * @return a fully initialised, but <em>not yet started</em>, transport engine
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException
     * if the engine cannot be created (missing native lib, bind failure, etc.)
     */
    TransportEngine createEngine(TransportConfig config);

    /**
     * Returns the stable, programmatic identifier for this provider.
     *
     * <p>Used for configuration routing and diagnostic JFR events. Must be a stable
     * string constant that does not change between releases.
     * Examples: {@code "community"}, {@code "enterprise"}.
     *
     * <p>This differs from {@link #providerName()} which is intended as a
     * human-readable display name.
     *
     * @return provider identifier; never {@code null}
     */
    String providerId();

    /**
     * Returns the display name of this provider.
     *
     * <p>Used in bootstrap JFR events and diagnostics. Must be a stable string constant.
     * Examples: {@code "StandardTransport"}, {@code "NativeMultiplexedTransport"}.
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
     * Returns {@code true} if this provider can create a working engine on the current platform.
     *
     * <p>{@code KernelBootstrap} (or the active transport subsystem) calls this before
     * selecting a provider. The selection loop iterates providers in descending priority
     * order and picks the first one for which {@code isAvailable()} returns {@code true}:
     * <pre>{@code
     * TransportProvider selected = ServiceLoader.load(TransportProvider.class)
     *     .stream()
     *     .map(ServiceLoader.Provider::get)
     *     .sorted(Comparator.comparingInt(TransportProvider::priority).reversed())
     *     .filter(TransportProvider::isAvailable)
     *     .findFirst()
     *     .orElseThrow(() -> TransportException.bootstrapFailure("unknown", "No available TransportProvider", null));
     * }</pre>
     *
     * <p>Platform-conditional providers (e.g., {@code io_uring} on Linux only, IOCP on
     * Windows only) <strong>MUST</strong> override this method and gate on their platform
     * check. The default returns {@code true} — always-available providers
     * (e.g., NIO-based Community tier) do not need to override.
     *
     * @return {@code true} if this provider can operate on the current platform
     */
    default boolean isAvailable() {
        return true;
    }
}
