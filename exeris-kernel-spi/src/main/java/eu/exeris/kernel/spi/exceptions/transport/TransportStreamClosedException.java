/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.transport;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown by {@link eu.exeris.kernel.spi.transport.TransportStream#read},
 * {@link eu.exeris.kernel.spi.transport.TransportStream#write} and
 * {@link eu.exeris.kernel.spi.transport.TransportStream#queueWrite} when the stream is closed.
 *
 * <h2>Cause discrimination</h2>
 * <p>The carried code says who ended the stream, so a caller can tell a routine disconnect from its
 * own use of a stream it closed:
 * <ul>
 *   <li>{@link #peerClosed()} → {@value KernelErrorCodes#EX_NET_4008}: the remote endpoint ended the
 *       stream, or the transport tore it down without a local close request;</li>
 *   <li>{@link #locallyClosed()} → {@value KernelErrorCodes#EX_NET_4009}: the stream was closed through
 *       this endpoint.</li>
 * </ul>
 *
 * <h2>Why an {@code IllegalStateException}</h2>
 * <p>The stream contract names {@link IllegalStateException} for a closed stream, and callers catch
 * it; refining that type keeps every such caller working while the code adds the distinction. For the
 * same reason this is not an {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException} and carries
 * no {@code rawArgs}.
 *
 * <p><b>Allocation:</b> allocates one instance per throw, with its stack trace. No instance is shared:
 * suppression cannot be disabled on this type, and a shared instance would collect suppressed
 * exceptions from unrelated streams.
 *
 * @since 0.13
 */
public final class TransportStreamClosedException extends IllegalStateException {

    private static final String PEER_CLOSED_MESSAGE = "Stream closed by peer";
    private static final String LOCALLY_CLOSED_MESSAGE = "Stream is closed";

    /** The {@code EX-NET} code that says who ended the stream; never null. */
    private final String errorCode;

    /**
     * Creates the exception with a fixed code and message.
     *
     * @param errorCode the {@code EX-NET} code this instance carries
     * @param message   the fixed message for that code
     */
    private TransportStreamClosedException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Creates the signal for a stream whose remote end was observed, or that the transport tore down
     * without a local close request.
     *
     * @return a new instance carrying {@value KernelErrorCodes#EX_NET_4008}
     */
    public static TransportStreamClosedException peerClosed() {
        return new TransportStreamClosedException(KernelErrorCodes.EX_NET_4008, PEER_CLOSED_MESSAGE);
    }

    /**
     * Creates the signal for a stream that was closed through this endpoint with no remote end
     * observed first.
     *
     * @return a new instance carrying {@value KernelErrorCodes#EX_NET_4009}
     */
    public static TransportStreamClosedException locallyClosed() {
        return new TransportStreamClosedException(KernelErrorCodes.EX_NET_4009, LOCALLY_CLOSED_MESSAGE);
    }

    /**
     * Returns the {@code EX-NET} code of this signal.
     *
     * @return the EX-NET code; never null
     */
    public String errorCode() {
        return errorCode;
    }

    /**
     * Whether the remote end of the stream was observed (or the transport tore the stream down)
     * before the failing call.
     *
     * @return {@code true} iff the code is {@value KernelErrorCodes#EX_NET_4008}
     */
    public boolean isPeerClosed() {
        return KernelErrorCodes.EX_NET_4008.equals(errorCode);
    }
}
