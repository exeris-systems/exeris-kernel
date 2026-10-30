/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.exceptions.SubsystemException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * The token verification settings the security subsystem reads: the JWKS endpoint, and the issuer and
 * audience a token must present.
 *
 * <p>All or none. With none of the three keys set there is nothing to read; with all three there is a
 * key set to verify against; anything in between is refused, naming the first key that is missing,
 * because a kernel that boots and then denies every token is the harder failure to find.
 *
 * @param jwksUri  the absolute {@code http} or {@code https} URI the JWKS document is served from
 * @param issuer   the {@code iss} claim value a token must present
 * @param audience the {@code aud} claim value a token must present
 */
/* default */ record CommunityJwksSettings(URI jwksUri, String issuer, String audience) {

    /* default */ static final String JWKS_URI_KEY = "security.jwks.uri";
    /* default */ static final String ISSUER_KEY = "security.jwt.issuer";
    /* default */ static final String AUDIENCE_KEY = "security.jwt.audience";

    private static final String SUBSYSTEM = "security";
    // SubsystemException carries a static detail, so each refusal has its own constant.
    private static final String ALL_OR_NONE = " is not set, but another token verification key is; set "
            + JWKS_URI_KEY + ", " + ISSUER_KEY + " and " + AUDIENCE_KEY + " together, or none of them";
    private static final String MISSING_JWKS_URI = JWKS_URI_KEY + ALL_OR_NONE;
    private static final String MISSING_ISSUER = ISSUER_KEY + ALL_OR_NONE;
    private static final String MISSING_AUDIENCE = AUDIENCE_KEY + ALL_OR_NONE;
    private static final String NOT_A_URI = JWKS_URI_KEY + " is not a URI";
    private static final String NOT_HTTP_URI = JWKS_URI_KEY + " must be an absolute http or https URI with a host";

    /**
     * Reads the settings, or nothing when none of the three keys is set. A blank value counts as unset.
     *
     * @param config the kernel configuration
     * @return the settings, or empty when token verification is not configured
     * @throws SubsystemException ({@code INITIALIZE}) naming the first key that is missing when some
     *         are set and others are not, or if {@value #JWKS_URI_KEY} is not an absolute
     *         {@code http} or {@code https} URI with a host
     */
    /* default */ static Optional<CommunityJwksSettings> read(ConfigProvider config) {
        Optional<String> uri = nonBlank(config, JWKS_URI_KEY);
        Optional<String> issuer = nonBlank(config, ISSUER_KEY);
        Optional<String> audience = nonBlank(config, AUDIENCE_KEY);
        if (uri.isEmpty() && issuer.isEmpty() && audience.isEmpty()) {
            return Optional.empty();
        }
        String present = uri.orElseThrow(() -> refusal(MISSING_JWKS_URI, null));
        return Optional.of(new CommunityJwksSettings(jwksUri(present),
                issuer.orElseThrow(() -> refusal(MISSING_ISSUER, null)),
                audience.orElseThrow(() -> refusal(MISSING_AUDIENCE, null))));
    }

    private static URI jwksUri(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw refusal(NOT_A_URI, ex);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
            throw refusal(NOT_HTTP_URI, null);
        }
        return uri;
    }

    private static Optional<String> nonBlank(ConfigProvider config, String key) {
        return config.getString(key).map(String::strip).filter(value -> !value.isEmpty());
    }

    private static SubsystemException refusal(String detail, Throwable cause) {
        return new SubsystemException(SUBSYSTEM, SubsystemException.Phase.INITIALIZE, detail, cause);
    }
}
