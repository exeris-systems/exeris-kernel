/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import java.io.IOException;

/**
 * Package-private marker for a socket send that failed because the peer reset or closed the
 * connection (broken pipe, connection reset or aborted). It is an {@link IOException}, so a caller
 * that does not classify keeps treating it as a plain send failure; {@link NativeTcpStream} tells it
 * apart to report the closed-stream signal instead.
 *
 * <p><b>Allocation:</b> one instance per failed send, on the failure path only.
 * <p><b>Ownership:</b> owns nothing; the message carries the errno for diagnostics.
 */
final class NativeTcpPeerResetException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the failure message, naming the errno
     */
    /* default */ NativeTcpPeerResetException(String message) {
        super(message);
    }
}
