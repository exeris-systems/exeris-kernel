/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Package-private record of the errno a fatal {@code SSL_write} left, for {@link OffHeapTlsEngine}.
 * Errno is the only place the {@code send} failure under {@code SSL_ERROR_SYSCALL} shows, so it is
 * read once, on the failure branch, right after {@code SSL_get_error}.
 *
 * <p><b>Allocation:</b> none per call.
 * <p><b>Thread confinement:</b> written by the thread that ran the failing write and read by that
 * thread right after; the atomic keeps a read from another thread well defined, not meaningful.
 */
final class WriteErrno {

    private final IntSupplier reader;
    private final AtomicInteger last = new AtomicInteger();

    /**
     * Creates the record.
     *
     * @param reader reads the current errno, or {@code -1} when it is unavailable
     */
    /* default */ WriteErrno(IntSupplier reader) {
        this.reader = reader;
    }

    /**
     * Records the outcome of a fatal write: the errno for {@code SSL_ERROR_SYSCALL}, {@code 0} for any
     * other cause, so a later reader never sees the errno of an earlier failure.
     *
     * @param sslError the {@code SSL_get_error} code of the failed write
     */
    /* default */ void record(int sslError) {
        last.set(sslError == CoreOpenSslLoader.SSL_ERROR_SYSCALL ? reader.getAsInt() : 0);
    }

    /**
     * The recorded value.
     *
     * @return the errno, {@code 0} or {@code -1}
     */
    /* default */ int last() {
        return last.get();
    }
}
