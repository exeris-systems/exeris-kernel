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
            UserInfo userInfo = UserInfo.withoutAuthority(location);
            throw new IllegalArgumentException("location must be an endpoint URI, got: "
                    + userInfo.redact(location), userInfo.redact(cause));
        }
        CommunityEndpointScheme.of(endpoint.getScheme(), LOCATION);
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalArgumentException("location must carry a host, got: "
                    + UserInfo.withoutAuthority(location).redact(location));
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
            throw new IllegalArgumentException("location must carry a host, got: "
                    + UserInfo.inServerAuthority(endpoint, location).redact(location));
        }
        return withoutDot;
    }

    private static void refuseBeyondTheAuthority(URI endpoint, String location) {
        String path = endpoint.getRawPath();
        refuseIf(endpoint.getRawUserInfo() != null, "userinfo", "credentials are "
                + CommunityS3Settings.ACCESS_KEY + " and " + CommunityS3Settings.SECRET_KEY, endpoint, location);
        refuseIf(!path.isEmpty() && !"/".equals(path), "a path",
                "requests are path-style from the bucket and key alone", endpoint, location);
        refuseIf(endpoint.getRawQuery() != null, "a query", "the driver sends none", endpoint, location);
        refuseIf(endpoint.getRawFragment() != null, "a fragment", "the driver sends none", endpoint, location);
    }

    private static void refuseIf(boolean present, String part, String reason, URI endpoint, String location) {
        if (present) {
            throw new IllegalArgumentException("location must not carry " + part + " — " + reason + "; got: "
                    + UserInfo.inServerAuthority(endpoint, location).redact(location));
        }
    }

    /**
     * Where a location's userinfo sits, as the half-open range {@code [start, end)} of its characters, so
     * a refusal can echo the location with that range replaced by {@link #USERINFO_MARKER}.
     *
     * <p>Two ways to find it. Where {@link URI} read a server authority, its raw userinfo is exact and
     * sits right after {@code //}; an {@code @} in a path stays in the echo. Where it did not (a syntax
     * failure, a registry-based authority, an opaque URI), nothing marks where the userinfo ends, since a
     * password may hold an unencoded {@code /}, {@code ?} or {@code #}: everything from the scheme's
     * {@code :} (and a following {@code //}) to the last {@code @} is withheld.
     */
    private value record UserInfo(int start, int end) {

        private static final UserInfo NONE = new UserInfo(-1, -1);

        /* default */ static UserInfo inServerAuthority(URI endpoint, String location) {
            String raw = endpoint.getRawUserInfo();
            if (raw == null) {
                return NONE;
            }
            int start = location.indexOf(AUTHORITY_PREFIX) + AUTHORITY_PREFIX.length();
            return new UserInfo(start, start + raw.length());
        }

        /* default */ static UserInfo withoutAuthority(String location) {
            int atSign = location.lastIndexOf('@');
            if (atSign < 0) {
                return NONE;
            }
            int colon = location.indexOf(':');
            if (colon < 0 || colon > atSign) {
                return new UserInfo(0, atSign);
            }
            int start = location.startsWith("//", colon + 1) ? colon + 3 : colon + 1;
            return new UserInfo(Math.min(start, atSign), atSign);
        }

        /* default */ String redact(String location) {
            return start < 0 ? location : location.substring(0, start) + USERINFO_MARKER + location.substring(end);
        }

        /**
         * The parse failure restated over the redacted location. An index before the userinfo stays, one
         * after it moves with the text, and one inside it has no counterpart and is dropped.
         */
        /* default */ URISyntaxException redact(URISyntaxException cause) {
            if (start < 0) {
                return cause;
            }
            int index = cause.getIndex();
            int moved = index < start ? index
                    : index >= end ? index - (end - start) + USERINFO_MARKER.length()
                    : -1;
            return new URISyntaxException(redact(cause.getInput()), cause.getReason(), moved);
        }
    }
}
