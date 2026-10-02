/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.community.http.CommunityEndpointScheme;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;

import java.net.URI;
import java.util.Map;

/**
 * What the S3-compatible driver needs, read once out of {@link BlobStorageConfig} (ADR-056 §10).
 *
 * <p>{@code location} carries the endpoint — {@code http://host[:port]} or
 * {@code https://host[:port]}, optionally with a single trailing {@code /} — and everything else
 * arrives through {@code properties}, because the SPI record deliberately has no field the kernel could
 * interpret as a storage topology. A location with a path, a query, a fragment or userinfo is refused:
 * the driver would drop each of them, and a dropped path prefix or ignored credential surfaces only as
 * a transfer failure. No refusal echoes the userinfo.
 *
 * <h2>The scheme decides</h2>
 * <p>The client engine requires of its transport what the scheme says ({@link CommunityEndpointScheme#outboundTls()}),
 * wherever the store is built. {@code http://} is plaintext, even where a crypto provider is bound.
 * {@code https://} is TLS that verifies the server against the endpoint host, or no store: without the
 * Community crypto provider bound where the store is built, or under
 * {@code -Dexeris.transport.tls=false}, the store is not created. It is never downgraded — sending SigV4
 * credentials in the clear because a scheme was ignored is exactly the failure that must never be
 * quiet. The default port follows the scheme.
 *
 * <p>The host is read once, lower-cased and without a trailing dot, and every authority the driver
 * uses is built from it: the dialled one ({@link #dialAuthority()}), the signed {@code Host}
 * ({@link #hostHeader()}) and a presigned URL's ({@link #origin()}). The name the transport verifies
 * and sends as the server name is the host of the dialled authority, so all four agree. Addressing is
 * path-style, so no bucket enters a host name.
 *
 * @param scheme         endpoint scheme, which decides the client engine's transport
 * @param host           endpoint host, lower-cased, without a trailing dot; an IPv6 literal keeps its
 *                       brackets
 * @param port           endpoint port
 * @param bucket         bucket every object lands in; tenants are separated by key prefix, not by bucket
 * @param accessKey      SigV4 access key id
 * @param secretKey      SigV4 secret access key
 * @param region         SigV4 credential-scope region
 * @param maxObjectBytes ceiling on a single object, in bytes
 * @since 0.11
 */
/* default */ value record CommunityS3Settings(CommunityEndpointScheme scheme, String host, int port, String bucket,
                                         String accessKey, String secretKey, String region, long maxObjectBytes) {

    /** Property key: the bucket every object lands in. */
    /* default */ static final String BUCKET = "s3.bucket";

    /** Property key: SigV4 access key id. */
    /* default */ static final String ACCESS_KEY = "s3.accessKey";

    /** Property key: SigV4 secret access key. */
    /* default */ static final String SECRET_KEY = "s3.secretKey";

    /** Property key: SigV4 credential-scope region. */
    /* default */ static final String REGION = "s3.region";

    /**
     * Property key: ceiling on a single object, in bytes.
     *
     * <p>Configurable rather than fixed because it is a deployment's memory budget, not a property of
     * S3. The driver holds an object in one buffer for the length of a transfer (ADR-056 §10), so this
     * is the number that decides whether a transfer is attempted at all.
     *
     * <p>It is also a per-request cost, not only a limit. The Community HTTP client engine reads every
     * response into one buffer sized from its configured body ceiling, so a {@code HEAD} pays the same
     * allocation as the largest {@code GET} the driver is prepared to make. Raise this to the largest
     * object a deployment genuinely stores, and no further.
     *
     * <p>Bounded above by {@link #MAX_SUPPORTED_OBJECT_BYTES}: the single-buffer design cannot address
     * more than an {@code int} of bytes, so a larger ceiling is refused at construction rather than
     * discovered when a transfer narrows to a wrapped allocation size.
     */
    /* default */ static final String MAX_OBJECT_BYTES = "s3.maxObjectBytes";

    /** Region assumed when a deployment states none — the value MinIO defaults to. */
    /* default */ static final String DEFAULT_REGION = "us-east-1";

    /**
     * Ceiling assumed when a deployment states none: 8 MiB.
     *
     * <p>Deliberately modest. Because the ceiling is also the per-request buffer size, a generous
     * default would tax every {@code stat} in every deployment that never stores a large object.
     */
    /* default */ static final long DEFAULT_MAX_OBJECT_BYTES = 8L * 1024 * 1024;

    private static final long HEADER_HEADROOM_BYTES = 64L * 1024;

    /**
     * What the client engine adds to whatever ceiling it is handed, for the status line and headers it
     * reads into the same buffer. Named here because this driver's ceiling has to leave room for it.
     */
    private static final long ENGINE_FRAMING_HEADROOM_BYTES = 8L * 1024;

    /**
     * Largest ceiling this driver can honour: an object is held in one buffer, and both
     * {@code MemoryAllocator.allocateNetwork} and the client engine's aggregate sizing address it with
     * an {@code int}. The two headrooms above sit on top of the object, so the addressable maximum is
     * what is left of {@link Integer#MAX_VALUE} once both are subtracted.
     *
     * <p>Bounded here rather than at the transfer, because a ceiling that cannot be honoured is a
     * configuration error and belongs at construction. Left unbounded, a ceiling above 2 GiB would let
     * an oversized transfer pass its own limit check and then narrow to a wrapped {@code int} at
     * allocation — the exact failure the named ceiling exists to replace with a loud refusal.
     */
    /* default */ static final long MAX_SUPPORTED_OBJECT_BYTES =
            Integer.MAX_VALUE - HEADER_HEADROOM_BYTES - ENGINE_FRAMING_HEADROOM_BYTES;

    /**
     * Canonical constructor.
     *
     * @throws IllegalArgumentException if the ceiling is not positive, or exceeds
     *                                  {@link #MAX_SUPPORTED_OBJECT_BYTES}
     */
    /* default */ CommunityS3Settings {
        if (maxObjectBytes <= 0) {
            throw new IllegalArgumentException(MAX_OBJECT_BYTES + " must be positive");
        }
        if (maxObjectBytes > MAX_SUPPORTED_OBJECT_BYTES) {
            throw new IllegalArgumentException(MAX_OBJECT_BYTES + " must not exceed "
                    + MAX_SUPPORTED_OBJECT_BYTES + " — this driver holds an object in one buffer, and a "
                    + "larger ceiling could not be allocated; got: " + maxObjectBytes);
        }
    }

    /**
     * Reads settings out of a driver-agnostic configuration.
     *
     * @param config the configuration handed to the provider
     * @return the parsed settings; never {@code null}
     * @throws IllegalArgumentException if the endpoint is unusable or carries a path, query, fragment or
     *                                  userinfo, a required property is missing, or the ceiling is not a
     *                                  positive number
     */
    /* default */ static CommunityS3Settings from(BlobStorageConfig config) {
        URI endpoint = CommunityS3Endpoint.parse(config.location());
        CommunityEndpointScheme scheme =
                CommunityEndpointScheme.of(endpoint.getScheme(), CommunityS3Endpoint.LOCATION);
        Map<String, String> properties = config.properties();
        return new CommunityS3Settings(
                scheme,
                CommunityS3Endpoint.normalisedHost(endpoint, config.location()),
                endpoint.getPort() < 0 ? scheme.defaultPort() : endpoint.getPort(),
                required(properties, BUCKET),
                required(properties, ACCESS_KEY),
                required(properties, SECRET_KEY),
                optional(properties, REGION, DEFAULT_REGION),
                ceiling(properties));
    }

    /**
     * Returns the ceiling the HTTP client engine should be sized for.
     *
     * <p>Headroom covers the response status line and headers, which share the engine's single
     * aggregate buffer with the body. Undersizing it turns a legal object into a decode failure that
     * reads like a network fault.
     *
     * @return the engine's body ceiling in bytes
     */
    /* default */ long engineBodyCeiling() {
        return maxObjectBytes + HEADER_HEADROOM_BYTES;
    }

    /**
     * The authority every request dials: {@code host:port}, with the port always stated, since the
     * client engine requires one.
     *
     * @return the dialled authority
     */
    /* default */ String dialAuthority() {
        return host + ":" + port;
    }

    /**
     * The {@code Host} value the signer signs and sends: the host alone on the scheme's default port,
     * otherwise {@code host:port}.
     *
     * <p>RFC 9110 reads both as the same authority as {@link #dialAuthority()}. The port-less form is
     * the one a browser or curl sends for a presigned URL on the default port, so a signature over
     * {@code host:443} would verify against nothing it sends.
     *
     * @return the {@code Host} value
     */
    /* default */ String hostHeader() {
        return port == scheme.defaultPort() ? host : host + ":" + port;
    }

    /**
     * The scheme and authority a presigned URL starts with: {@code scheme://} and {@link #hostHeader()}.
     *
     * @return the origin, with no trailing slash
     */
    /* default */ String origin() {
        return scheme.token() + "://" + hostHeader();
    }

    private static String required(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("required blob-storage property is missing: " + key);
        }
        return value;
    }

    private static String optional(Map<String, String> properties, String key, String fallback) {
        String value = properties.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static long ceiling(Map<String, String> properties) {
        String value = properties.get(MAX_OBJECT_BYTES);
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_OBJECT_BYTES;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(MAX_OBJECT_BYTES + " must be a number, got: " + value, e);
        }
    }
}
