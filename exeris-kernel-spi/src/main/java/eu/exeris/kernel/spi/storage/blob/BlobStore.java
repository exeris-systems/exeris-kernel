/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * <p>Implementations are thread-safe; the handles they return are not.
 *
 * @since 0.11.0
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
     * @throws BlobStorageException     if the ambient context carries no isolation key, or on I/O failure
     * @throws NullPointerException     if {@code ref} is {@code null}
     * @throws IllegalArgumentException if {@code contentLength} is negative
     */
    BlobUploadHandle beginUpload(BlobRef ref, long contentLength, String contentType);

    /**
     * Opens a read over the whole object.
     *
     * @param ref tenant-relative reference to read
     * @return an open download handle; never {@code null}
     * @throws BlobStorageException if the object does not exist, the ambient context carries no
     *                              isolation key, or on I/O failure
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
     * @throws BlobStorageException if the object does not exist, the ambient context carries no
     *                              isolation key, or on I/O failure
     * @throws NullPointerException if either argument is {@code null}
     */
    BlobDownloadHandle openDownload(BlobRef ref, BlobRange range);

    /**
     * Returns metadata for the object, if it exists in the resolved namespace.
     *
     * @param ref tenant-relative reference to describe
     * @return the metadata, or empty if no such object exists for this tenant
     * @throws BlobStorageException if the ambient context carries no isolation key, or on I/O failure
     * @throws NullPointerException if {@code ref} is {@code null}
     */
    Optional<BlobMetadata> stat(BlobRef ref);

    /**
     * Deletes the object if it exists.
     *
     * @param ref tenant-relative reference to delete
     * @return {@code true} if an object was deleted, {@code false} if none existed — deleting a
     *         non-existent object is not an error, so retrying a delete is safe
     * @throws BlobStorageException if the ambient context carries no isolation key, or on I/O failure
     * @throws NullPointerException if {@code ref} is {@code null}
     */
    boolean delete(BlobRef ref);

    /**
     * Produces a URL granting one operation on one object, if this store can sign one.
     *
     * <h2>What the SPI promises</h2>
     * <p>A returned URL grants exactly {@code access}, on exactly {@code ref}, and is valid no longer
     * than {@code ttl} (further capped by {@link BlobStorageConfig#maxSignedUrlTtl()}).
     *
     * <h2>What it does not promise</h2>
     * <p>The scheme, the structure, whether credentials are embedded, whether it can be revoked before
     * expiry — and whether one can be produced at all. A store backed by a filesystem has no meaningful
     * signed URL, so this returns {@link Optional#empty()}.
     *
     * <p><b>A store's answer must be uniform</b> (ADR-056 §7): it either signs for every input or
     * declines for every input. One that sometimes returns a URL cannot be programmed against, because
     * a caller could not tell an unsupported operation from a missing object.
     *
     * @param ref    tenant-relative reference the URL should address
     * @param access the single operation to grant
     * @param ttl    requested validity; capped by the configured ceiling. Must be positive
     * @return the signed URL, or empty if this store does not sign
     * @throws BlobStorageException     if the ambient context carries no isolation key
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code ttl} is not positive
     */
    Optional<URI> signedUrl(BlobRef ref, BlobAccess access, Duration ttl);

    /**
     * Releases this store's resources. Idempotent.
     */
    @Override
    void close();
}
