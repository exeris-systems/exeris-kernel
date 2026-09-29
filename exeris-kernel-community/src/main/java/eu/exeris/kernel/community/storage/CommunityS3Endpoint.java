/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.community.http.CommunityEndpointScheme;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Reads the S3 driver's endpoint out of a {@code location}, refusing every part of it the driver
 * would otherwise drop.
 *
 * <p>The endpoint is {@code http://host[:port]} or {@code https://host[:port]}; a single trailing
 * {@code /} is still the bare endpoint. Requests are path-style and built from the bucket and key
 * alone, so a path prefix would be missing from every request and every presigned URL, and a query or
 * a fragment would be dropped the same way. Credentials come only from
 * {@link CommunityS3Settings#ACCESS_KEY} and {@link CommunityS3Settings#SECRET_KEY}, so userinfo would be
 * ignored. Each is a configuration error, refused here rather than surfacing as a transfer failure.
 *
 * <p>No refusal carries the userinfo, in its message or in its cause: a location may hold credentials
 * even when it is refused for another reason.
 *
 * @since 0.12
 */
final class CommunityS3Endpoint {

    /** What the endpoint is called in a refusal: the {@code BlobStorageConfig} field that carries it. */
    /* default */ static final String LOCATION = "location";

    /** What stands in for the userinfo of a location echoed in a refusal. */
    private static final String USERINFO_MARKER = "<userinfo>";

    private static final String AUTHORITY_PREFIX = "://";

    private CommunityS3Endpoint() {
        // Utility holder — not instantiable.
    }

    /**
     * Parses the endpoint.
     *
     * @param location the configured location
     * @return the endpoint, with a scheme, a host and nothing beyond its port
     * @throws IllegalArgumentException if the location is not an {@code http} or {@code https} URI with
     *                                  a host, or carries userinfo, a path, a query or a fragment
     */
    /* default */ static URI parse(String location) {
        URI endpoint;
        try {
            endpoint = new URI(location);
        } catch (URISyntaxException cause) {
            throw new IllegalArgumentException("location must be an endpoint URI, got: " + redacted(location),
                    redactedCause(location, cause));
        }
        CommunityEndpointScheme.of(endpoint.getScheme(), LOCATION);
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalArgumentException("location must carry a host, got: " + redacted(location));
        }
        refuseBeyondTheAuthority(endpoint, location);
        return endpoint;
    }

    /**
     * The endpoint host in the form the transport verifies a name in: lower-cased, one trailing dot
     * removed.
     *
     * @param endpoint an endpoint {@link #parse(String)} returned
     * @param location the location it was parsed from, for the refusal
     * @return the normalised host
     * @throws IllegalArgumentException if nothing is left of the host once its trailing dot is removed
     */
    /* default */ static String normalisedHost(URI endpoint, String location) {
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        String withoutDot = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (withoutDot.isEmpty()) {
            throw new IllegalArgumentException("location must carry a host, got: " + redacted(location));
        }
        return withoutDot;
    }

    private static void refuseBeyondTheAuthority(URI endpoint, String location) {
        String path = endpoint.getRawPath();
        refuseIf(endpoint.getRawUserInfo() != null, "userinfo", "credentials are "
                + CommunityS3Settings.ACCESS_KEY + " and " + CommunityS3Settings.SECRET_KEY, location);
        refuseIf(!path.isEmpty() && !"/".equals(path), "a path",
                "requests are path-style from the bucket and key alone", location);
        refuseIf(endpoint.getRawQuery() != null, "a query", "the driver sends none", location);
        refuseIf(endpoint.getRawFragment() != null, "a fragment", "the driver sends none", location);
    }

    private static void refuseIf(boolean present, String part, String reason, String location) {
        if (present) {
            throw new IllegalArgumentException("location must not carry " + part + " — " + reason + "; got: "
                    + redacted(location));
        }
    }

    /**
     * The location with any userinfo replaced by {@link #USERINFO_MARKER}.
     *
     * <p>Textual rather than through {@link URI}, because it has to work on a location {@link URI}
     * cannot parse, and on a registry-based authority where {@link URI#getUserInfo()} is {@code null}.
     */
    private static String redacted(String location) {
        int userInfoEnd = userInfoEnd(location);
        if (userInfoEnd < 0) {
            return location;
        }
        int authorityStart = location.indexOf(AUTHORITY_PREFIX) + AUTHORITY_PREFIX.length();
        return location.substring(0, authorityStart) + USERINFO_MARKER + location.substring(userInfoEnd);
    }

    /**
     * Index of the {@code @} ending the userinfo, or {@code -1} if the location carries none. The
     * authority runs from {@code ://} to the first {@code /}, {@code ?} or {@code #}, and the userinfo
     * ends at its last {@code @}.
     */
    private static int userInfoEnd(String location) {
        int schemeEnd = location.indexOf(AUTHORITY_PREFIX);
        if (schemeEnd < 0) {
            return -1;
        }
        int authorityStart = schemeEnd + AUTHORITY_PREFIX.length();
        int authorityEnd = location.length();
        for (int i = authorityStart; i < location.length(); i++) {
            char delimiter = location.charAt(i);
            if (delimiter == '/' || delimiter == '?' || delimiter == '#') {
                authorityEnd = i;
                break;
            }
        }
        int atSign = location.lastIndexOf('@', authorityEnd - 1);
        return atSign >= authorityStart ? atSign : -1;
    }

    /**
     * The parse failure restated over the redacted location, with its index moved to match; an index
     * inside the userinfo has no counterpart and is dropped.
     */
    private static URISyntaxException redactedCause(String location, URISyntaxException cause) {
        int userInfoEnd = userInfoEnd(location);
        if (userInfoEnd < 0) {
            return cause;
        }
        String redacted = redacted(location);
        int shift = location.length() - redacted.length();
        int index = cause.getIndex() >= userInfoEnd ? cause.getIndex() - shift : -1;
        return new URISyntaxException(redacted, cause.getReason(), index);
    }
}
