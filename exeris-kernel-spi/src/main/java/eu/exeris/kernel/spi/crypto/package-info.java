/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Exeris Kernel SPI – Crypto/TLS subsystem contracts (L0).
 *
 * <h2>Key Types</h2>
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.crypto.KernelCryptoProvider} — {@code ServiceLoader} factory;
 *       Community provides TLS 1.3 over TCP, Enterprise adds datagram-based QUIC transport</li>
 *   <li>{@link eu.exeris.kernel.spi.crypto.TlsEngine} — stateful TLS session facade:
 *       wrap/unwrap, handshake, and graceful shutdown operations</li>
 *   <li>{@link eu.exeris.kernel.spi.crypto.CryptoProviderConfig} — immutable configuration record
 *       (Valhalla-ready, candidate for {@code value record} once JEP 401 is mainline)</li>
 *   <li>{@link eu.exeris.kernel.spi.crypto.TlsHandshakeResult} — immutable single-step handshake
 *       outcome; pre-allocated singletons ({@code COMPLETE}, {@code NEED_UNWRAP}, {@code NEED_WRAP})
 *       ensure zero allocation on the hot handshake path</li>
 *   <li>{@link eu.exeris.kernel.spi.crypto.TlsShutdownResult} — immutable single-step shutdown
 *       outcome; pre-allocated singletons ({@code COMPLETE}, {@code ERROR}) avoid GC pressure</li>
 *   <li>{@link eu.exeris.kernel.spi.crypto.TlsPhase} — explicit session lifecycle state machine
 *       ({@code UNINITIALIZED} → {@code HANDSHAKE_IN_PROGRESS} → {@code ACTIVE} → {@code CLOSED})</li>
 *   <li>{@link eu.exeris.kernel.spi.crypto.TlsSessionState} — read-only view of the current
 *       {@link eu.exeris.kernel.spi.crypto.TlsPhase}; consumed by the Core orchestration layer</li>
 *   <li>{@link eu.exeris.kernel.spi.crypto.TlsStatus} — transport-layer outcome codes
 *       ({@code OK}, {@code NEED_WRAP}, {@code NEED_UNWRAP}, {@code NEED_HANDSHAKE},
 *       {@code FINISHED}, {@code CLOSED})</li>
 * </ul>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This package is <strong>implementation-blind</strong>: no references to native TLS libraries,
 * datagram transport internals, foreign memory APIs, or any community/enterprise driver class.
 * Provider-specific mapping of native return codes and bit-flags MUST remain inside the
 * implementation tier (Community or Enterprise module).
 *
 * <h2>Tier Implementations</h2>
 * <ul>
 *   <li><strong>Community</strong> — {@code CommunityKernelCryptoProvider}: TLS 1.3 over TCP;
 *       MUST throw {@link eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException}
 *       when {@link eu.exeris.kernel.spi.crypto.CryptoProviderConfig.Protocol#QUIC} is requested</li>
 *   <li><strong>Enterprise</strong> — high-density provider: TLS 1.3 over TCP
 *       <em>and</em> datagram-based QUIC transport;
 *       {@link eu.exeris.kernel.spi.crypto.KernelCryptoProvider#priority()} = 100
 *       to win {@code ServiceLoader} resolution over Community</li>
 * </ul>
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>{@link eu.exeris.kernel.spi.crypto.TlsHandshakeResult} and
 * {@link eu.exeris.kernel.spi.crypto.TlsShutdownResult} expose pre-allocated singleton instances
 * for all non-error outcomes. Error results are the only allocation permitted on the handshake/
 * shutdown path, and exceptions are never on the hot path.
 *
 * @see <a href="../../../../../../docs/subsystems/crypto.md">crypto.md</a>
 */
package eu.exeris.kernel.spi.crypto;


