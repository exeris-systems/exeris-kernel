/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */

/**
 * Exeris Kernel SPI – Transport layer contracts (L2 Native I/O).
 *
 * <h2>Protocol Blindness</h2>
 * <p>This package defines protocol-agnostic abstractions for the transport subsystem.
 * Business logic operates on {@link eu.exeris.kernel.spi.transport.TransportStream Stream}
 * and {@link eu.exeris.kernel.spi.transport.TransportConnection Connection} objects.
 * It does <em>not</em> know whether data flows over TCP (Community) or QUIC/io_uring
 * (Enterprise). Protocol-specific implementations reside in Community or Enterprise
 * modules and MUST NOT be referenced here.
 *
 * <h2>Key Contracts</h2>
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.transport.TransportProvider} – ServiceLoader entry point</li>
 *   <li>{@link eu.exeris.kernel.spi.transport.TransportEngine} – Lifecycle + accept/connect</li>
 *   <li>{@link eu.exeris.kernel.spi.transport.TransportConnection} – Protocol-blind connection</li>
 *   <li>{@link eu.exeris.kernel.spi.transport.TransportStream} – Protocol-blind bidirectional stream</li>
 *   <li>{@link eu.exeris.kernel.spi.transport.StreamHandler} – Incoming stream callback</li>
 *   <li>{@link eu.exeris.kernel.spi.transport.ConnectionHandler} – Connection established callback</li>
 *   <li>{@link eu.exeris.kernel.spi.transport.TransportConfig} – Configuration record (protocol-blind)</li>
 *   <li>{@link eu.exeris.kernel.spi.transport.TransportMode} – SERVER / CLIENT / DUAL / DISABLED</li>
 *   <li>{@link eu.exeris.kernel.spi.transport.TransportStats} – Diagnostics snapshot</li>
 * </ul>
 *
 * <h2>The Wall</h2>
 * <p>Implementation-blind: no references to Netty, io_uring, SocketChannel, TLS cipher
 * suites, QUIC frame types, BIO, or any native transport mechanism.
 */
package eu.exeris.kernel.spi.transport;

