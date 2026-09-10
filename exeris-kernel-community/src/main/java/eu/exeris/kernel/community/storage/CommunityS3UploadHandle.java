/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobUploadHandle;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * An in-progress S3 upload: bytes accumulate off-heap, and one {@code PUT} publishes them.
 *
 * <h2>Why the object is staged whole</h2>
 * <p>A single {@code PUT} must declare {@code Content-Length} before its first body byte, and the
 * Community HTTP client engine takes a request body as one buffer. Multipart upload — the mechanism
 * that would let a transfer stream — is explicitly out of this driver's scope (ADR-056 §10), so the
 * object is held in one buffer sized from the length the caller declared. The declared length is what
 * makes that possible without spooling to disk first, which is the reason
 * {@link eu.exeris.kernel.spi.storage.blob.BlobStore#beginUpload} demands it.
 *
 * <h2>Visibility</h2>
 * <p>Nothing is sent until {@link #commit()}, so aborting is not a compensating action — it is the
 * absence of one. Closing an uncommitted handle releases the buffer and the store never hears about it,
 * which is the strongest form of "no partial object is left visible" (ADR-056 §3).
 *
 * <p><b>Allocation:</b> none — the staging buffer is allocated by the store before this handle exists
 * ({@code MemoryAllocator.allocateNetwork}, sized from the declared content length); {@link #write}
 * only copies into it.
 * <p><b>Thread confinement:</b> owner thread — matches {@link BlobUploadHandle}'s contract that one
 * upload is driven by one thread; the handle keeps no internal synchronization.
 * <p><b>Ownership:</b> this handle owns the staging buffer and releases it exactly once, in
 * {@link #close()}, whether that follows a {@link #commit()} or an abort; a zero-length upload has no
 * buffer to release.
 *
 * @since 0.11
 */
final class CommunityS3UploadHandle implements BlobUploadHandle {

    private static final CommunityBlobFailures FAILURES =
            CommunityBlobFailures.forProvider(CommunityBlobFailures.S3_PROVIDER);

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CLOSED_MSG = "Upload handle is committed or closed";

    private final CommunityS3Client client;
    private final BlobRef ref;
    private final String objectKey;
    private final long declaredLength;
    private final String contentType;
    private final LoanedBuffer staging;

    private long written;
    private boolean committed;
    private boolean closed;

    /* default */ CommunityS3UploadHandle(CommunityS3Client client, BlobRef ref, String objectKey,
                                          long declaredLength, String contentType,
                                          LoanedBuffer staging) {
        this.client = client;
        this.ref = ref;
        this.objectKey = objectKey;
        this.declaredLength = declaredLength;
        this.contentType = contentType;
        this.staging = staging;
    }

    @Override
    public void write(MemorySegment source, int length) {
        requireWritable(source, length);
        if (length == 0) {
            return;
        }
        MemorySegment.copy(source, 0L, staging.segment(), written, length);
        written += length;
    }

    /**
     * Rejects everything that must not reach the staging buffer.
     *
     * <p>The overrun check is here rather than at {@link #commit()} because the declared length is what
     * sized the buffer: by the time an overlong write reached the copy it would be writing past the
     * allocation.
     */
    private void requireWritable(MemorySegment source, int length) {
        if (committed || closed) {
            throw new IllegalStateException(CLOSED_MSG);
        }
        if (length < 0 || length > source.byteSize()) {
            throw new IllegalArgumentException(
                    "length must be within [0, segment size], got: " + length);
        }
        if (written + length > declaredLength) {
            throw BlobStorageException.uploadLengthMismatch(
                    FAILURES.providerName(), declaredLength, written + length);
        }
    }

    @Override
    public BlobMetadata commit() {
        if (committed || closed) {
            throw new IllegalStateException(CLOSED_MSG);
        }
        if (written != declaredLength) {
            throw BlobStorageException.uploadLengthMismatch(
                    FAILURES.providerName(), declaredLength, written);
        }
        put();
        committed = true;
        return new BlobMetadata(ref, declaredLength, contentType);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (staging != null) {
            staging.close();
        }
    }

    private void put() {
        List<HttpHeader> extra = List.of(new HttpHeader(HEADER_CONTENT_TYPE, contentType));
        HttpResponse response = declaredLength == 0
                ? client.send(HttpMethod.PUT, objectKey, extra)
                : sendBody(extra);
        try {
            // isSuccess, not a literal 200. MinIO and Ceph RGW answer a PUT with 204, and this was
            // the one place in the driver that decided success on its own: the object is durably
            // stored, the caller is handed a BlobStorageException, and its retry overwrites what was
            // already there.
            if (!CommunityS3Responses.isSuccess(response)) {
                throw FAILURES.remoteRefused(
                        CommunityBlobFailures.OP_UPLOAD, ref.container(), response.status().code());
            }
        } finally {
            closeBody(response);
        }
    }

    private HttpResponse sendBody(List<HttpHeader> extra) {
        // The engine derives Content-Length from the buffer's logical size, which the pool may have
        // rounded up at allocation. Pinning it to the declared length is what keeps the wire framing
        // and the signed payload hash describing the same bytes.
        staging.setSize(declaredLength);
        return client.sendWithBody(HttpMethod.PUT, objectKey, extra, staging, declaredLength);
    }

    private static void closeBody(HttpResponse response) {
        if (response.hasBody()) {
            response.body().close();
        }
    }
}
