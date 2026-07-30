/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.storage;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when the blob-storage subsystem cannot complete an operation (ADR-056).
 *
 * <h2>Zero-Allocation Telemetry Contract</h2>
 * <p>All domain context is passed as raw {@code Object[]} args to
 * {@link ExerisKernelException#rawArgs()} — no String formatting on the failure path.
 *
 * <h2>Secret safety</h2>
 * <p>Object keys can carry application data, so no factory here captures a key. Failures are described
 * by container and by a reason code, which is enough to locate the fault without putting caller data
 * into telemetry.
 *
 * <h2>Error Codes</h2>
 * <ul>
 *   <li>{@value KernelErrorCodes#EX_BLOB_8001} — object not found</li>
 *   <li>{@value KernelErrorCodes#EX_BLOB_8002} — isolation denied (no tenant scope to resolve against)</li>
 *   <li>{@value KernelErrorCodes#EX_BLOB_8003} — transfer failure</li>
 *   <li>{@value KernelErrorCodes#EX_BLOB_8004} — upload contract violation</li>
 * </ul>
 *
 * @since 0.11.0
 */
public final class BlobStorageException extends ExerisKernelException {

    private static final String NOT_FOUND_MSG = "Blob not found";
    private static final String ISOLATION_MSG = "Blob operation denied — no tenant scope";
    private static final String TRANSFER_MSG = "Blob transfer failure";
    private static final String UPLOAD_MSG = "Blob upload contract violation";

    private BlobStorageException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    /**
     * The referenced object does not exist in the resolved namespace.
     *
     * @param providerName provider display name
     * @param container    the logical container the lookup resolved into
     * @return exception with rawArgs: [providerName, container]
     */
    public static BlobStorageException notFound(String providerName, String container) {
        return new BlobStorageException(
                KernelErrorCodes.EX_BLOB_8001, NOT_FOUND_MSG, null, providerName, container);
    }

    /**
     * The ambient {@link eu.exeris.kernel.spi.security.StorageContext} carries no isolation key, so
     * there is no tenant namespace to resolve the reference against.
     *
     * <p>Terminal by design (ADR-056 §5): falling back to an unscoped location would place a tenant's
     * object where every tenant can reach it, which is the weakest possible placement reached silently.
     *
     * @param providerName provider display name
     * @param strategy     the {@code IsolationStrategy} name of the ambient context
     * @return exception with rawArgs: [providerName, strategy]
     */
    public static BlobStorageException isolationDenied(String providerName, String strategy) {
        return new BlobStorageException(
                KernelErrorCodes.EX_BLOB_8002, ISOLATION_MSG, null, providerName, strategy);
    }

    /**
     * An I/O failure during upload or download.
     *
     * @param providerName provider display name
     * @param container    the logical container being transferred to or from
     * @param cause        root cause
     * @return exception with rawArgs: [providerName, container]
     */
    public static BlobStorageException transferFailed(String providerName, String container,
                                                      Throwable cause) {
        return new BlobStorageException(
                KernelErrorCodes.EX_BLOB_8003, TRANSFER_MSG, cause, providerName, container);
    }

    /**
     * The upload violated its declared contract — more or fewer bytes than the declared content length.
     *
     * @param providerName    provider display name
     * @param declaredLength  content length declared at {@code beginUpload}
     * @param actualLength    bytes actually written
     * @return exception with rawArgs: [providerName, declaredLength, actualLength]
     */
    public static BlobStorageException uploadLengthMismatch(String providerName, long declaredLength,
                                                            long actualLength) {
        return new BlobStorageException(
                KernelErrorCodes.EX_BLOB_8004, UPLOAD_MSG, null,
                providerName, declaredLength, actualLength);
    }
}
