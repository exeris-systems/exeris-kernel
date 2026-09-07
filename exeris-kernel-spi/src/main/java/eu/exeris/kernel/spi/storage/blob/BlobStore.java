/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.storage.blob;

import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.security.StorageContext;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * SPI: The operational blob-storage contract (ADR-056).
 *
 * <h2>Every operation is tenant-scoped</h2>
 * <p>A {@link BlobRef} is tenant-relative. Each operation resolves it against
 * {@link StorageContext#isolationKey()} from the ambient scope, so two tenants passing the identical
 * reference address different objects and neither can name the other's.
 *
 * <p>An ambient context with <b>no</b> isolation key is a terminal deny, never an unscoped fallback
 * (ADR-056 §5): there is no namespace to resolve into, and the weakest possible placement must not be
 * reached by silence.
 *
 * @implSpec Implementations must be thread-safe. The handles returned from {@link #beginUpload},
 *           {@link #openDownload(BlobRef)} and {@link #openDownload(BlobRef, BlobRange)} need not
 *           be — each documents its own thread confinement.
 * @since 0.11
 */
public interface BlobStore extends AutoCloseable {

    /**
     * Begins an upload of exactly one object.
     *
     * <p>{@code contentLength} is declared up front rather than discovered at commit, because a store
     * speaking a request-oriented protocol must send it before the first byte. Declaring it makes that
     * driver possible without a spooling copy, and lets every driver reject a truncated transfer.
     *
     * @param ref           tenant-relative reference to write
     * @param contentLength exact number of bytes that will be written; never negative
     * @param contentType   media type to record, or {@code null} for
     *                      {@link BlobMetadata#DEFAULT_CONTENT_TYPE}
     * @return an open upload handle; never {@code null}
     * @throws BlobStorageException     if the ambient context carries no isolation key
     *                                  ({@code EX-BLOB-8002}), {@code contentLength} exceeds the
     *                                  driver's configured ceiling ({@code EX-BLOB-8005}), or on I/O
     *                                  failure ({@code EX-BLOB-8003})
     * @throws NullPointerException     if {@code ref} is {@code null}
     * @throws IllegalArgumentException if {@code contentLength} is negative
     */
    BlobUploadHandle beginUpload(BlobRef ref, long contentLength, String contentType);

    /**
     * Opens a read over the whole object.
     *
     * @param ref tenant-relative reference to read
     * @return an open download handle; never {@code null}
     * @throws BlobStorageException if the object does not exist ({@code EX-BLOB-8001}), the ambient
     *                              context carries no isolation key ({@code EX-BLOB-8002}), or on
     *                              I/O failure ({@code EX-BLOB-8003})
     * @throws NullPointerException if {@code ref} is {@code null}
     */
    BlobDownloadHandle openDownload(BlobRef ref);

    /**
     * Opens a read over a byte range of the object.
     *
     * <p>A range starting at or beyond the end of the object is not an error: the handle reports end of
     * stream on first read. A range extending past the end is truncated to what exists. Both follow the
     * principle that a caller streaming a large object with a fixed window should not have to know the
     * size to avoid an exception on the final window.
     *
     * @param ref   tenant-relative reference to read
     * @param range the byte range to read
     * @return an open download handle limited to {@code range}; never {@code null}
     * @throws BlobStorageException if the object does not exist ({@code EX-BLOB-8001}), the ambient
     *                              context carries no isolation key ({@code EX-BLOB-8002}), or on
     *                              I/O failure ({@code EX-BLOB-8003})
     * @throws NullPointerException if either argument is {@code null}
     */
    BlobDownloadHandle openDownload(BlobRef ref, BlobRange range);

    /**
     * Returns metadata for the object, if it exists in the resolved namespace.
     *
     * @param ref tenant-relative reference to describe
     * @return the metadata, or empty if no such object exists for this tenant
     * @throws BlobStorageException if the ambient context carries no isolation key
     *                              ({@code EX-BLOB-8002}), or on I/O failure ({@code EX-BLOB-8003})
     * @throws NullPointerException if {@code ref} is {@code null}
     */
    Optional<BlobMetadata> stat(BlobRef ref);

    /**
     * Deletes the object if it exists.
     *
     * @param ref tenant-relative reference to delete
     * @return {@code true} if an object was deleted, {@code false} if none existed — deleting a
     *         non-existent object is not an error, so retrying a delete is safe
     * @throws BlobStorageException if the ambient context carries no isolation key
     *                              ({@code EX-BLOB-8002}), or on I/O failure ({@code EX-BLOB-8003})
     * @throws NullPointerException if {@code ref} is {@code null}
     */
    boolean delete(BlobRef ref);

    /**
     * Produces a URL granting one operation on one object, if this store can sign one.
     *
     * <p>A store backed by a filesystem has no meaningful signed URL, so this returns
     * {@link Optional#empty()}.
     *
     * @param ref    tenant-relative reference the URL should address
     * @param access the single operation to grant
     * @param ttl    requested validity; capped by the configured ceiling; must be at least one second
     * @return the signed URL, or empty if this store does not sign
     * @throws BlobStorageException     if the ambient context carries no isolation key
     *                                  ({@code EX-BLOB-8002})
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code ttl} is shorter than one second
     * @implSpec A returned URL must grant exactly {@code access}, on exactly {@code ref}, and be
     *           valid no longer than {@code ttl} (further capped by
     *           {@link BlobStorageConfig#maxSignedUrlTtl()}). A store's answer must be uniform
     *           (ADR-056 §7) — it must either sign for every input or decline for every input, never
     *           some of each, so a caller can never mistake an unsupported operation for a missing
     *           object. A store must reject a {@code ttl} shorter than one second whether or not it
     *           signs: the signing schemes in use express expiry in whole seconds, so a sub-second
     *           request has only two possible outcomes and both break a promise — truncating to zero
     *           produces an already-expired URL, and rounding up grants longer than asked.
     * @apiNote  What a returned URL does not promise: its scheme, its structure, whether credentials
     *           are embedded in it, or whether it can be revoked before expiry — and not every store
     *           can produce one at all.
     */
    Optional<URI> signedUrl(BlobRef ref, BlobAccess access, Duration ttl);

    /**
     * Releases this store's resources. Idempotent.
     */
    @Override
    void close();
}
