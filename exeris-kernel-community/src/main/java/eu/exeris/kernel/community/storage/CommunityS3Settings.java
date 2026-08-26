/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/**
 * What the S3-compatible driver needs, read once out of {@link BlobStorageConfig} (ADR-056 §10).
 *
 * <p>{@code location} carries the endpoint — {@code http://host:port} — and everything else arrives
 * through {@code properties}, because the SPI record deliberately has no field the kernel could
 * interpret as a storage topology.
 *
 * <h2>Cleartext only, and said out loud</h2>
 * <p>The Community HTTP client engine has no client-side TLS: {@code CommunityHttpTransportFactory}
 * wires certificate material for listeners only, so a {@code CLIENT}-mode engine speaks cleartext
 * whatever the endpoint scheme says. An {@code https://} endpoint is therefore rejected at
 * construction rather than silently downgraded — sending SigV4 credentials in the clear because a
 * scheme was ignored is exactly the failure that must never be quiet. This driver targets a
 * MinIO-compatible endpoint reached over a trusted network path; a public S3 endpoint needs the
 * Enterprise transport.
 *
 * @param host           endpoint host
 * @param port           endpoint port
 * @param bucket         bucket every object lands in; tenants are separated by key prefix, not by bucket
 * @param accessKey      SigV4 access key id
 * @param secretKey      SigV4 secret access key
 * @param region         SigV4 credential-scope region
 * @param maxObjectBytes ceiling on a single object, in bytes
 * @since 0.11.0
 */
/* default */ record CommunityS3Settings(String host, int port, String bucket, String accessKey,
                                         String secretKey, String region, long maxObjectBytes) {

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

    private static final String HTTP_SCHEME = "http";
    private static final int DEFAULT_HTTP_PORT = 80;
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
     * @throws IllegalArgumentException if the endpoint is unusable, a required property is missing, or
     *                                  the ceiling is not a positive number
     */
    /* default */ static CommunityS3Settings from(BlobStorageConfig config) {
        URI endpoint = parseEndpoint(config.location());
        Map<String, String> properties = config.properties();
        return new CommunityS3Settings(
                endpoint.getHost(),
                endpoint.getPort() < 0 ? DEFAULT_HTTP_PORT : endpoint.getPort(),
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

    private static URI parseEndpoint(String location) {
        URI endpoint;
        try {
            endpoint = new URI(location);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("location must be an endpoint URI, got: " + location, e);
        }
        if (!HTTP_SCHEME.equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException(
                    "location must use the http scheme — the Community HTTP client engine has no "
                            + "client-side TLS, so an https endpoint would be sent in the clear; got: "
                            + endpoint.getScheme());
        }
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalArgumentException("location must carry a host, got: " + location);
        }
        return endpoint;
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
