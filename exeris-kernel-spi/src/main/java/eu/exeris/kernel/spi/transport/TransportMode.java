/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

/**
 * SPI: Operational mode for the Transport subsystem.
 *
 * <h2>Resource Impact</h2>
 * <p>The selected mode determines which kernel resources are allocated at bootstrap
 * (UDP sockets, carrier threads, TLS contexts, slab pool partitions).
 * Choosing a narrower mode (e.g., {@link #CLIENT} instead of {@link #DUAL}) avoids
 * allocating server-side structures and conserves memory.
 *
 * <h2>Protocol Blindness (The Wall)</h2>
 * <p>This enum does not encode <em>which</em> protocol is used (TCP vs QUIC vs native async I/O).
 * It only declares <em>directionality</em>. The Community tier may implement {@link #SERVER}
 * with a standard listener socket; the Enterprise tier may use native asynchronous I/O
 * mechanisms. Business logic sees only {@code TransportMode}.
 *
 * @see TransportConfig
 * @see TransportEngine
 * @since 0.5.0
 */
public enum TransportMode {

    /**
     * Accept incoming connections only.
     *
     * <p>Allocates a listener socket, N carrier reactor threads, and a TLS server context.
     * No outbound connection pool is created.
     */
    SERVER,

    /**
     * Initiate outgoing connections only.
     *
     * <p>Allocates per-connection sockets (ephemeral ports) and a TLS client context.
     * No listener socket is bound.
     */
    CLIENT,

    /**
     * Both server and client functionality.
     *
     * <p>Allocates all resources from both {@link #SERVER} and {@link #CLIENT}.
     * Typical for service-mesh nodes, proxies, and dual-role microservices.
     */
    DUAL,

    /**
     * Transport layer completely disabled.
     *
     * <p>No sockets, no carrier threads, no TLS contexts. Useful for batch jobs,
     * testing, or pure-compute workloads that use only Persistence/Graph subsystems.
     */
    DISABLED
}
