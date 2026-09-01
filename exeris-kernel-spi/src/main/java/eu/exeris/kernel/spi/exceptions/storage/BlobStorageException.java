/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 *   <li>{@value KernelErrorCodes#EX_BLOB_8005} — object exceeds the driver's configured ceiling</li>
 *   <li>{@value KernelErrorCodes#EX_BLOB_8006} — the remote store refused the request</li>
 * </ul>
 *
 * @since 0.11.0
 */
public final class BlobStorageException extends ExerisKernelException {

    private static final String NOT_FOUND_MSG = "Blob not found";
    private static final String ISOLATION_MSG = "Blob operation denied — no tenant scope";
    private static final String TRANSFER_MSG = "Blob transfer failure";
    private static final String UPLOAD_MSG = "Blob upload contract violation";
    private static final String TOO_LARGE_MSG = "Blob exceeds the driver's configured ceiling";
    private static final String REMOTE_MSG = "Remote blob store refused the request";
    private static final String NO_PROVIDER_MSG = "No BlobStorageProvider on the classpath";
    private static final String UNRESOLVED_MSG = "Blob provider selection did not resolve to one driver";
    private static final String MISSING_KEY_MSG = "Blob storage is configured and a key it needs is unset";

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
     * <p>The same code covers a resolved location that would fall outside the tenant namespace: both
     * are "this reference has no valid place to land", and splitting them would make a driver choose
     * between codes on a path where the safe answer is identical.
     *
     * @param providerName provider display name
     * @param reason       the {@code IsolationStrategy} name when the ambient context carries no
     *                     isolation key, or a driver-specific reason code (such as
     *                     {@code path-escape}) when resolution left the tenant namespace
     * @return exception with rawArgs: [providerName, reason]
     */
    public static BlobStorageException isolationDenied(String providerName, String reason) {
        return new BlobStorageException(
                KernelErrorCodes.EX_BLOB_8002, ISOLATION_MSG, null, providerName, reason);
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

    /**
     * The transfer is larger than the driver is configured to hold.
     *
     * <p>Raised before any allocation or request, so an oversized object costs nothing to refuse.
     *
     * @param providerName  provider display name
     * @param declaredBytes the object size the caller asked for
     * @param ceilingBytes  the configured ceiling
     * @return exception with rawArgs: [providerName, declaredBytes, ceilingBytes]
     */
    public static BlobStorageException exceedsCeiling(String providerName, long declaredBytes,
                                                      long ceilingBytes) {
        return new BlobStorageException(
                KernelErrorCodes.EX_BLOB_8005, TOO_LARGE_MSG, null,
                providerName, declaredBytes, ceilingBytes);
    }

    /**
     * A remote store answered with a status the driver cannot treat as success.
     *
     * @param providerName provider display name
     * @param container    the logical container the request addressed
     * @param statusCode   the HTTP status the store returned
     * @return exception with rawArgs: [providerName, container, statusCode]
     */
    public static BlobStorageException remoteRefused(String providerName, String container,
                                                     int statusCode) {
        return new BlobStorageException(
                KernelErrorCodes.EX_BLOB_8006, REMOTE_MSG, null,
                providerName, container, statusCode);
    }

    /**
     * Blob storage was asked for and no driver is present to serve it.
     *
     * @param component the bootstrap component that looked
     * @return exception with rawArgs: [component]
     */
    public static BlobStorageException noProvider(String component) {
        return new BlobStorageException(
                KernelErrorCodes.EX_BLOB_8007, NO_PROVIDER_MSG, null, component);
    }

    /**
     * The configured provider id does not name exactly one discovered driver.
     *
     * <p>Carries the key as well as the value on purpose: a refusal an operator cannot act on is a
     * refusal that costs them a search, and the key is the one thing they need and do not have.
     *
     * @param configKey    the configuration key that selects the driver
     * @param configuredId the value that was set, or the empty string when it was not
     * @param availableIds comma-joined provider ids actually discovered
     * @return exception with rawArgs: [configKey, configuredId, availableIds]
     */
    public static BlobStorageException selectionUnresolved(String configKey, String configuredId,
                                                           String availableIds) {
        return new BlobStorageException(
                KernelErrorCodes.EX_BLOB_8008, UNRESOLVED_MSG, null,
                configKey, configuredId, availableIds);
    }

    /**
     * Blob storage is on and a key it needs was not set.
     *
     * <p>Refused here rather than deeper in a driver, where the message would name a constructor
     * parameter or a property rather than the key an operator has to write.
     *
     * @param configKey the key that is unset
     * @param expected  what a value for it looks like
     * @return exception with rawArgs: [configKey, expected]
     */
    public static BlobStorageException missingConfiguration(String configKey, String expected) {
        return new BlobStorageException(
                KernelErrorCodes.EX_BLOB_8009, MISSING_KEY_MSG, null, configKey, expected);
    }
}
