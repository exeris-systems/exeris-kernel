/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.persistence;

/**
 * SPI: Factory for creating {@link PersistenceTransport} instances.
 *
 * <h2>ServiceLoader Discovery</h2>
 * <p>Discovered via {@link java.util.ServiceLoader}. The {@link PersistenceEngine}
 * implementation selects the highest-priority transport provider for connection creation.
 *
 * <h2>The Wall (Open-Core)</h2>
 * <ul>
 *   <li><b>Community</b> (priority=0): Blocking TCP — {@code NativeSocket} backed,
 *       Virtual Thread friendly.</li>
 *   <li><b>Enterprise</b> (priority=100): io_uring — multishot recvmsg, provided buffers,
 *       SQ polling, kernel-bypass DMA for registered buffer groups.</li>
 * </ul>
 *
 * @since 0.5.0
 * @see PersistenceTransport
 */
public interface TransportProvider {

    /**
     * Creates a transport instance for the given host and port.
     *
     * <p>The transport is in a disconnected state. Call
     * {@link PersistenceTransport#connect()} to establish the TCP connection.
     *
     * @param host database server hostname or IP
     * @param port database server port
     * @return disconnected transport; caller must connect and eventually close
     */
    PersistenceTransport createTransport(String host, int port);

    /**
     * Display name for JFR events and diagnostics
     * (e.g., {@code "BlockingTCP"}, {@code "io_uring/multishot"}).
     *
     * @return stable transport name
     */
    String transportName();

    /**
     * Selection priority. Higher value wins.
     * Community: 0. Enterprise: 100.
     *
     * @return priority
     */
    default int priority() {
        return 0;
    }
}

