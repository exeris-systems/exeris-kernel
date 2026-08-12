/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.community.http.CommunityHttpProvider;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.storage.blob.BlobAccess;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;
import eu.exeris.kernel.spi.storage.blob.BlobRef;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The wire half of the S3-compatible driver: one signed request, one response.
 *
 * <p>Split out of {@link CommunityS3BlobStore} so the store reads as the blob contract and this reads
 * as HTTP. The store never builds a header, and this never decides what a status means for a
 * {@code BlobRef}.
 *
 * <h2>Engine ownership</h2>
 * <p>This owns a {@code CLIENT}-mode engine of its own rather than borrowing the ambient
 * {@code HTTP_CLIENT_ENGINE}. Two reasons, and the second is the load-bearing one: an engine is bound
 * to a single host by {@code HttpConfig}, so a shared application client cannot address the storage
 * endpoint at all; and object transfers are large and long, so putting them through the pool that
 * serves application traffic would let one upload sit in front of every other request.
 *
 * @since 0.11.0
 */
final class CommunityS3Client implements AutoCloseable {

    private static final CommunityBlobFailures FAILURES =
            CommunityBlobFailures.forProvider(CommunityBlobFailures.S3_PROVIDER);

    /**
     * Both are positional arguments {@link HttpConfig} requires and neither reaches anything on this
     * path, so they are named here rather than read as tuning. {@code maxConnections} bounds the
     * listener backlog and the accept-time slot reservation, and a {@code CLIENT}-mode carrier never
     * listens or accepts; {@code idleTimeoutMillis} is carried into {@code TransportConfig} and no
     * carrier reads it at all. Values kept plausible so a future enforcement point inherits a sane
     * default instead of a placeholder.
     */
    private static final int MAX_CONNECTIONS = 64;
    private static final long IDLE_TIMEOUT_MS = 30_000L;
    private static final int MAX_HEADER_COUNT = 64;
    private static final int MAX_HEADER_SIZE = 8_192;
    private static final char PATH_SEPARATOR = '/';

    private final CommunityS3Settings settings;
    private final Duration maxSignedUrlTtl;
    private final CommunityS3Signer signer;
    private final HttpClientEngine engine;

    /* default */ CommunityS3Client(CommunityS3Settings settings, Duration maxSignedUrlTtl,
                                    Clock clock) {
        this.settings = settings;
        this.maxSignedUrlTtl = maxSignedUrlTtl;
        this.signer = new CommunityS3Signer(settings, clock);
        this.engine = new CommunityHttpProvider().createClientEngine(new HttpConfig(
                HttpMode.CLIENT,
                settings.host(),
                settings.port(),
                MAX_CONNECTIONS,
                IDLE_TIMEOUT_MS,
                MAX_HEADER_COUNT,
                MAX_HEADER_SIZE,
                settings.engineBodyCeiling(),
                false,
                HttpVersion.HTTP_1_1));
        this.engine.start();
    }

    /**
     * Sends a bodyless signed request.
     *
     * @param method    the HTTP method
     * @param objectKey the bucket-relative object key
     * @param extra     headers beyond the signed set; may be empty
     * @return the response, whose body buffer the caller owns
     */
    /* default */ HttpResponse send(HttpMethod method, String objectKey, List<HttpHeader> extra) {
        return dispatch(method, objectKey, extra, null, CommunityS3Signer.emptyPayloadHash());
    }

    /**
     * Sends a signed request carrying {@code length} bytes from {@code body}.
     *
     * <p>The payload hash is computed over the segment in place, so the body is never copied to the
     * heap on the way to being signed.
     *
     * @param method    the HTTP method
     * @param objectKey the bucket-relative object key
     * @param extra     headers beyond the signed set; may be empty
     * @param body      the buffer holding the body; the caller keeps ownership
     * @param length    number of bytes of {@code body} to send
     * @return the response, whose body buffer the caller owns
     */
    /* default */ HttpResponse sendWithBody(HttpMethod method, String objectKey,
                                            List<HttpHeader> extra, LoanedBuffer body, long length) {
        return dispatch(method, objectKey, extra, body,
                CommunityS3Signer.hashPayload(body.segment(), length));
    }

    /**
     * Mints a query-signed URL granting one access on one object.
     *
     * <p>Both mappings live here rather than in the store: which HTTP method expresses an access, and
     * how long a grant may last, are facts about the protocol and the endpoint configuration.
     *
     * @param access    the single operation the URL grants
     * @param objectKey the bucket-relative object key
     * @param ttl       requested validity, capped here at the configured ceiling
     * @return the absolute URL
     */
    /* default */ String presign(BlobAccess access, String objectKey, Duration ttl) {
        HttpMethod method = access == BlobAccess.READ ? HttpMethod.GET : HttpMethod.PUT;
        Duration capped = ttl.compareTo(maxSignedUrlTtl) > 0 ? maxSignedUrlTtl : ttl;
        return signer.presign(method, requestTarget(objectKey), capped);
    }

    /**
     * Asks the store to describe an object without transferring it.
     *
     * @param objectKey the bucket-relative object key
     * @param ref       the reference the metadata is reported against
     * @param operation the {@code CommunityBlobFailures.OP_*} constant of the call in flight
     * @return the metadata, or empty when no such object exists
     */
    /* default */ Optional<BlobMetadata> head(String objectKey, BlobRef ref, String operation) {
        return head(objectKey, ref, operation, false);
    }

    /**
     * As {@link #head(String, BlobRef, String)}, but refuses a response that does not declare a size.
     *
     * @param requireDeclaredSize {@code true} for callers that turn the size into a byte count
     */
    /* default */ Optional<BlobMetadata> head(String objectKey, BlobRef ref, String operation,
                                              boolean requireDeclaredSize) {
        HttpResponse response = send(HttpMethod.HEAD, objectKey, List.of());
        try {
            int code = response.status().code();
            if (code == HttpStatus.NOT_FOUND.code()) {
                return Optional.empty();
            }
            if (code != HttpStatus.OK.code()) {
                throw FAILURES.remoteRefused(operation, ref.container(), code);
            }
            long size = CommunityS3Responses.sizeOf(response);
            if (size == CommunityS3Responses.SIZE_UNDECLARED) {
                if (requireDeclaredSize) {
                    // Only the download path refuses. It is the one that turns this number into
                    // "how many bytes to fetch", where an invented 0 becomes an empty object the
                    // caller cannot distinguish from a real one.
                    throw FAILURES.transferFailed(operation, ref.container(), null);
                }
                // stat() and delete() do not. stat() reporting an unknown size as 0 is a degraded
                // description of an object the caller still learns exists; delete() uses this call
                // only as an existence probe and never reads the size at all. Refusing here would
                // make an existing object undeletable on a store that omits Content-Length, and
                // undeletable identically on every retry — which is precisely the idempotent retry
                // BlobStore.delete promises is safe.
                size = 0L;
            }
            return Optional.of(new BlobMetadata(ref, size,
                    CommunityS3Responses.contentTypeOf(response)));
        } finally {
            CommunityS3Responses.closeBody(response);
        }
    }

    @Override
    public void close() {
        engine.close();
    }

    private HttpResponse dispatch(HttpMethod method, String objectKey, List<HttpHeader> extra,
                                  LoanedBuffer body, String payloadHash) {
        String target = requestTarget(objectKey);
        List<HttpHeader> signed = signer.sign(method, target, payloadHash);
        List<HttpHeader> headers = new ArrayList<>(signed.size() + extra.size());
        headers.addAll(signed);
        headers.addAll(extra);
        return engine.send(new HttpRequest(
                method, target, HttpVersion.HTTP_1_1, List.copyOf(headers), body));
    }

    /**
     * Builds the path-style request target, which is also the SigV4 canonical URI.
     *
     * <p>Path style rather than virtual-host style because the latter needs one DNS name per bucket,
     * which a MinIO-compatible endpoint on a fixed host cannot offer.
     */
    private String requestTarget(String objectKey) {
        return PATH_SEPARATOR + settings.bucket() + PATH_SEPARATOR
                + CommunityS3ObjectKey.encode(objectKey);
    }
}
