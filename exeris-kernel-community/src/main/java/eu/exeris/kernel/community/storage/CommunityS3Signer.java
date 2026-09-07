/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * AWS Signature Version 4 for the S3-compatible driver — the subset this driver actually needs.
 *
 * <h2>The subset, stated</h2>
 * <p>Header signing over a fixed three-header set ({@code host}, {@code x-amz-content-sha256},
 * {@code x-amz-date}) and query signing for presigned URLs. Not implemented: chunked
 * ({@code STREAMING-*}) payload signing, session tokens, and signing arbitrary caller-supplied
 * {@code x-amz-*} headers. Each of those exists to serve a capability this driver does not have —
 * multipart upload, temporary credentials, server-side encryption — so implementing them would be
 * signing for requests it cannot make.
 *
 * <h2>Payload hash</h2>
 * <p>Header-signed requests carry the real SHA-256 of the body, computed over the off-heap segment
 * without copying it to the heap. Presigned URLs use {@code UNSIGNED-PAYLOAD}, which is the only
 * option: the bytes are not known when the URL is minted, and the holder is the one who supplies them.
 *
 * <h2>Secret safety</h2>
 * <p>The secret key is used to derive a signing key and never appears in a header, an exception, a JFR
 * event, or a {@code toString()}. This class deliberately has no {@code toString()} override, so the
 * record-like temptation to print it never arises.
 *
 * @since 0.11
 */
final class CommunityS3Signer {

    /** Payload hash a presigned URL carries: the bytes are the holder's, not ours. */
    /* default */ static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    /** Shortest grant {@code X-Amz-Expires} can express: the field is whole seconds. */
    private static final long MIN_EXPIRES_SECONDS = BlobStorageConfig.MIN_SIGNED_URL_TTL.toSeconds();

    /** The algorithm token, and the prefix of the derived signing key. */
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SECRET_PREFIX = "AWS4";
    private static final String SERVICE = "s3";
    private static final String TERMINATOR = "aws4_request";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SHA_256 = "SHA-256";

    /** The three headers this driver signs, in the lowercase, sorted form SigV4 requires. */
    private static final String SIGNED_HEADERS = "host;x-amz-content-sha256;x-amz-date";

    private static final String HEADER_HOST = "Host";
    private static final String HEADER_CONTENT_SHA256 = "x-amz-content-sha256";
    private static final String HEADER_DATE = "x-amz-date";
    private static final String HEADER_AUTHORIZATION = "Authorization";

    private static final DateTimeFormatter AMZ_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private static final HexFormat HEX = HexFormat.of();
    private static final String NEWLINE = "\n";

    private final CommunityS3Settings settings;
    private final Clock clock;
    private final String hostHeader;

    /* default */ CommunityS3Signer(CommunityS3Settings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.hostHeader = settings.host() + ":" + settings.port();
    }

    /**
     * Returns the {@code Host} header value this signer signs.
     *
     * <p>Sent explicitly rather than left to the request encoder's default, because the signature
     * covers this exact string: a header the encoder synthesised differently would verify against
     * nothing.
     *
     * @return the host header value
     */
    /* default */ String hostHeader() {
        return hostHeader;
    }

    /**
     * Signs a request and returns the headers that carry the signature.
     *
     * @param method      the HTTP method
     * @param encodedPath the percent-encoded request target, which is also the canonical URI
     * @param payloadHash lowercase hex SHA-256 of the body, or the empty-body hash
     * @return {@code Host}, {@code x-amz-date}, {@code x-amz-content-sha256} and {@code Authorization}
     */
    /* default */ List<HttpHeader> sign(HttpMethod method, String encodedPath, String payloadHash) {
        Instant now = clock.instant();
        String amzDateTime = AMZ_DATE_TIME.format(now);
        String amzDate = AMZ_DATE.format(now);
        String scope = amzDate + "/" + settings.region() + "/" + SERVICE + "/" + TERMINATOR;

        String canonicalHeaders = "host:" + hostHeader + NEWLINE
                + HEADER_CONTENT_SHA256 + ":" + payloadHash + NEWLINE
                + HEADER_DATE + ":" + amzDateTime + NEWLINE;
        // The doubled terminator is the canonical query line, which is empty for a header-signed
        // request: SigV4 requires the field to be present and blank, not omitted.
        String canonicalRequest = method.name() + NEWLINE
                + encodedPath + NEWLINE
                + NEWLINE
                + canonicalHeaders + NEWLINE
                + SIGNED_HEADERS + NEWLINE
                + payloadHash;
        String signature = signatureOf(canonicalRequest, amzDateTime, amzDate, scope);

        List<HttpHeader> headers = new ArrayList<>(4);
        headers.add(new HttpHeader(HEADER_HOST, hostHeader));
        headers.add(new HttpHeader(HEADER_DATE, amzDateTime));
        headers.add(new HttpHeader(HEADER_CONTENT_SHA256, payloadHash));
        headers.add(new HttpHeader(HEADER_AUTHORIZATION, ALGORITHM
                + " Credential=" + settings.accessKey() + "/" + scope
                + ", SignedHeaders=" + SIGNED_HEADERS
                + ", Signature=" + signature));
        return List.copyOf(headers);
    }

    /**
     * Mints a query-signed URL granting one method on one object for a bounded time.
     *
     * @param method      the single method the URL grants
     * @param encodedPath the percent-encoded object path
     * @param ttl         validity, already capped by the store
     * @return the absolute URL, signature included
     */
    /* default */ String presign(HttpMethod method, String encodedPath, Duration ttl) {
        long expiresSeconds = ttl.toSeconds();
        if (expiresSeconds < MIN_EXPIRES_SECONDS) {
            // X-Amz-Expires is whole seconds, so a sub-second grant has no representation here.
            // Truncating produces Expires=0, outside SigV4's accepted range: the caller would be
            // handed a URL that every endpoint rejects, by a method whose contract says it signs
            // uniformly or declines uniformly. Rounding up to one second is the other option and is
            // worse — the SPI promises a URL "valid no longer than ttl", and over-granting breaks
            // that promise silently where refusing states the limit.
            throw new IllegalArgumentException(
                    "ttl must be at least one second: signed-URL expiry has one-second granularity");
        }
        Instant now = clock.instant();
        String amzDateTime = AMZ_DATE_TIME.format(now);
        String scope = AMZ_DATE.format(now) + "/" + settings.region() + "/" + SERVICE + "/" + TERMINATOR;

        // Sorted by key, as SigV4 canonicalisation requires: Algorithm, Credential, Date, Expires,
        // SignedHeaders. The CHECKSTYLE pause covers the banned-`Date` rule, which fires on the
        // protocol's own parameter name; no java.util type is involved.
        //CHECKSTYLE:OFF
        String canonicalQuery = "X-Amz-Algorithm=" + ALGORITHM
                + "&X-Amz-Credential="
                + CommunityS3ObjectKey.encodeQueryComponent(settings.accessKey() + "/" + scope)
                + "&X-Amz-Date=" + amzDateTime
                + "&X-Amz-Expires=" + expiresSeconds
                + "&X-Amz-SignedHeaders=host";
        //CHECKSTYLE:ON
        String canonicalRequest = method.name() + NEWLINE
                + encodedPath + NEWLINE
                + canonicalQuery + NEWLINE
                + "host:" + hostHeader + NEWLINE + NEWLINE
                + "host" + NEWLINE
                + UNSIGNED_PAYLOAD;
        String signature = signatureOf(canonicalRequest, amzDateTime,
                AMZ_DATE.format(now), scope);

        return "http://" + hostHeader + encodedPath + "?" + canonicalQuery
                + "&X-Amz-Signature=" + signature;
    }

    /**
     * Computes the lowercase-hex SHA-256 of {@code length} bytes of an off-heap segment.
     *
     * <p>Fed to the digest through a {@link ByteBuffer} view of the segment. The scoped ban on
     * {@code ByteBuffer} covers zero-copy runtime paths, where it would replace a
     * {@code MemorySegment}; here it is the only adapter {@link MessageDigest} accepts, and using it
     * is what avoids the heap copy the ban exists to prevent.
     *
     * @param source the segment holding the body
     * @param length number of bytes to hash
     * @return lowercase hex digest
     */
    /* default */ static String hashPayload(java.lang.foreign.MemorySegment source, long length) {
        MessageDigest digest = newDigest();
        if (length > 0) {
            digest.update(source.asSlice(0, length).asByteBuffer());
        }
        return HEX.formatHex(digest.digest());
    }

    /** Returns the SHA-256 of the empty string, which every bodyless request carries. */
    /* default */ static String emptyPayloadHash() {
        return HEX.formatHex(newDigest().digest());
    }

    private String signatureOf(String canonicalRequest, String amzDateTime, String amzDate,
                               String scope) {
        String stringToSign = ALGORITHM + NEWLINE
                + amzDateTime + NEWLINE
                + scope + NEWLINE
                + HEX.formatHex(newDigest().digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        return HEX.formatHex(hmac(signingKey(amzDate), stringToSign));
    }

    private byte[] signingKey(String amzDate) {
        byte[] key = hmac((SECRET_PREFIX + settings.secretKey()).getBytes(StandardCharsets.UTF_8),
                amzDate);
        key = hmac(key, settings.region());
        key = hmac(key, SERVICE);
        return hmac(key, TERMINATOR);
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
