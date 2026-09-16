/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.storage.blob.BlobAccess;
import eu.exeris.kernel.spi.storage.blob.BlobDownloadHandle;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;
import eu.exeris.kernel.spi.storage.blob.BlobRange;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import eu.exeris.kernel.spi.storage.blob.BlobUploadHandle;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Community S3-compatible {@link BlobStore} (ADR-056 §10) — objects are keys under one bucket.
 *
 * <p>The second in-repo binding of the blob contract, and the reason the contract has two: a
 * filesystem and an object store disagree about almost everything below {@link BlobStore}, so a rule
 * that both satisfy is a rule about blobs rather than about files.
 *
 * <h2>Scope</h2>
 * <p>Single-object {@code PUT}, {@code GET}, ranged {@code GET}, {@code HEAD}, {@code DELETE}, and
 * SigV4 presigned URLs. No multipart upload, no bucket lifecycle, no listing, no server-side copy —
 * each of those is a capability the SPI does not expose, so implementing it here would be building for
 * a caller that cannot ask.
 *
 * <h2>Two round trips per read</h2>
 * <p>Every download begins with a {@code HEAD}. The driver must know an object's size before it decides
 * whether to pull it into a single buffer, and a ranged read needs the total size for
 * {@link BlobMetadata#sizeBytes()} regardless. Skipping it would mean either discovering the ceiling
 * after the bytes had already arrived, or reporting a range length as the object size — the first
 * defeats the limit and the second is simply wrong. The filesystem driver pays the same cost as a
 * {@code stat}.
 *
 * @since 0.11
 */
public final class CommunityS3BlobStore implements BlobStore {

    private static final CommunityBlobFailures FAILURES =
            CommunityBlobFailures.forProvider(CommunityBlobFailures.S3_PROVIDER);

    private static final String REF_REQUIRED = "ref must not be null";
    private static final String HEADER_RANGE = "Range";

    private final CommunityS3Settings settings;
    private final CommunityS3Client client;
    private final MemoryAllocator allocator;

    /* default */ CommunityS3BlobStore(BlobStorageConfig config, MemoryAllocator allocator,
                                       Clock clock) {
        Objects.requireNonNull(config, "config must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.settings = CommunityS3Settings.from(config);
        this.client = new CommunityS3Client(settings, config.maxSignedUrlTtl(), clock);
    }

    @Override
    public BlobUploadHandle beginUpload(BlobRef ref, long contentLength, String contentType) {
        Objects.requireNonNull(ref, REF_REQUIRED);
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength must not be negative");
        }
        String objectKey = CommunityS3ObjectKey.resolve(ref, CommunityBlobFailures.OP_UPLOAD);
        if (contentLength > settings.maxObjectBytes()) {
            throw FAILURES.exceedsCeiling(CommunityBlobFailures.OP_UPLOAD, ref.container(),
                    contentLength, settings.maxObjectBytes());
        }
        LoanedBuffer staging = contentLength == 0
                ? null
                : allocator.allocateNetwork((int) contentLength);
        return new CommunityS3UploadHandle(client, ref, objectKey, contentLength,
                Objects.requireNonNullElse(contentType, BlobMetadata.DEFAULT_CONTENT_TYPE), staging);
    }

    @Override
    public BlobDownloadHandle openDownload(BlobRef ref) {
        return download(ref, null);
    }

    @Override
    public BlobDownloadHandle openDownload(BlobRef ref, BlobRange range) {
        Objects.requireNonNull(range, "range must not be null");
        return download(ref, range);
    }

    @Override
    public Optional<BlobMetadata> stat(BlobRef ref) {
        Objects.requireNonNull(ref, REF_REQUIRED);
        return client.head(CommunityS3ObjectKey.resolve(ref, CommunityBlobFailures.OP_STAT), ref,
                CommunityBlobFailures.OP_STAT);
    }

    @Override
    public boolean delete(BlobRef ref) {
        Objects.requireNonNull(ref, REF_REQUIRED);
        String objectKey = CommunityS3ObjectKey.resolve(ref, CommunityBlobFailures.OP_DELETE);
        // S3 answers 204 whether or not anything was there, so the store is asked first. The contract
        // promises the caller can tell a removal from a no-op, and only this ordering can.
        boolean existed = client.head(objectKey, ref, CommunityBlobFailures.OP_DELETE).isPresent();
        HttpResponse response = client.send(HttpMethod.DELETE, objectKey, List.of());
        try {
            if (!CommunityS3Responses.isSuccess(response)) {
                throw FAILURES.remoteRefused(CommunityBlobFailures.OP_DELETE, ref.container(),
                        response.status().code());
            }
        } finally {
            CommunityS3Responses.closeBody(response);
        }
        return existed;
    }

    /**
     * Mints a presigned URL. Uniformly available (ADR-056 §7): an object store can always sign, so this
     * never returns empty — the disjunction the TCK pins is answered "yes" for every input.
     */
    @Override
    public Optional<URI> signedUrl(BlobRef ref, BlobAccess access, Duration ttl) {
        Objects.requireNonNull(ref, REF_REQUIRED);
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.compareTo(BlobStorageConfig.MIN_SIGNED_URL_TTL) < 0) {
            throw new IllegalArgumentException(
                    "ttl must be at least one second: signed-URL expiry has one-second granularity");
        }
        String objectKey = CommunityS3ObjectKey.resolve(ref, CommunityBlobFailures.OP_SIGNED_URL);
        return Optional.of(URI.create(client.presign(access, objectKey, ttl)));
    }

    @Override
    public void close() {
        client.close();
    }

    private BlobDownloadHandle download(BlobRef ref, BlobRange range) {
        Objects.requireNonNull(ref, REF_REQUIRED);
        String objectKey = CommunityS3ObjectKey.resolve(ref, CommunityBlobFailures.OP_DOWNLOAD);
        BlobMetadata metadata = client.head(objectKey, ref, CommunityBlobFailures.OP_DOWNLOAD, true)
                .orElseThrow(() -> FAILURES.notFound(ref.container()));

        CommunityS3Slice slice = CommunityS3Slice.covering(range, metadata.sizeBytes());
        if (slice.isEmpty()) {
            // A range at or past the end is an immediate end-of-stream, not a failure (ADR-056 §3).
            return new CommunityS3DownloadHandle(metadata, null, 0L, 0L);
        }
        if (slice.length() > settings.maxObjectBytes()) {
            throw FAILURES.exceedsCeiling(CommunityBlobFailures.OP_DOWNLOAD, ref.container(),
                    slice.length(), settings.maxObjectBytes());
        }
        return fetch(objectKey, ref, metadata, slice, range != null);
    }

    private BlobDownloadHandle fetch(String objectKey, BlobRef ref, BlobMetadata metadata,
                                     CommunityS3Slice slice, boolean ranged) {
        List<HttpHeader> extra = ranged
                ? List.of(new HttpHeader(HEADER_RANGE, slice.rangeHeaderValue()))
                : List.of();
        HttpResponse response = client.send(HttpMethod.GET, objectKey, extra);
        if (!CommunityS3Responses.isSuccess(response)) {
            CommunityS3Responses.closeBody(response);
            throw FAILURES.remoteRefused(CommunityBlobFailures.OP_DOWNLOAD, ref.container(),
                    response.status().code());
        }
        // A store is permitted to ignore Range and answer 200 with the whole object; then the slice the
        // caller asked for starts at its own offset rather than at zero.
        long start = response.status().code() == HttpStatus.PARTIAL_CONTENT.code()
                ? 0L
                : slice.offset();
        return new CommunityS3DownloadHandle(metadata, response.body(), start, slice.length());
    }

}
