/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import java.time.Duration;

/**
 * A TLS server {@link AbstractHttpClientTlsPeerVerificationTck} points a client at: it presents one
 * leaf, answers every complete HTTP/1.1 request head with {@code 200}, and counts the requests it
 * read, so a case can show that a refused client sent none.
 *
 * @since 0.12
 */
public interface TlsPeerServer extends AutoCloseable {

    /**
     * The port the server accepts on.
     *
     * @return the bound port
     */
    int port();

    /**
     * The request heads this server has read in full since it started.
     *
     * @return the count, never negative
     */
    int requestsServed();

    /**
     * Waits until every connection this server has accepted is finished with, or until
     * {@code limit} passes with none accepted, so that an assertion about what the server read is
     * made after it read everything the client sent.
     *
     * @param limit how long to wait for a first connection before concluding there was none
     * @throws InterruptedException if the waiting thread is interrupted
     */
    void awaitQuiet(Duration limit) throws InterruptedException;

    /** Stops accepting and releases the listening socket. */
    @Override
    void close();
}
