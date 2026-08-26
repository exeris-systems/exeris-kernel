/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.identity.ClaimsMapper;
import eu.exeris.kernel.spi.security.identity.VerifiedClaims;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Default Community {@link ClaimsMapper}: maps a verified subject to a {@link PrincipalContext}.
 *
 * <p>{@code sub} parses to a UUIDv7 that becomes both {@code principalId} and {@code tenantId};
 * roles and scopes come from the token.
 *
 * <h2>The token grants what it says, and nothing else (since 0.11)</h2>
 * <p>Until 0.11 every authenticated principal received a single hardcoded {@code security:read} scope
 * and no roles, whatever the token claimed — the deferral ADR-040 §"Implementation" left open, since
 * that ADR already specifies this mapper as "sub → principalId, roles/scopes claims →
 * PrincipalContext". Two consequences made the deferral worth closing rather than carrying: no route
 * could distinguish one caller's permissions from another's, and {@code security:write} was a scope
 * <em>nothing could ever grant</em>, so any policy requiring it denied everyone.
 *
 * <p>There is deliberately <b>no fallback</b>. A token carrying no scope claim yields an empty scope
 * set, and a route requiring any scope denies it. Granting a default would reinstate exactly the
 * invisible grant this replaces: a route asking for {@code security:read} would admit a caller who
 * never asked for it, which is indistinguishable from the route having no requirement at all.
 *
 * <h2>Claim shapes</h2>
 * <ul>
 *   <li>{@code scope} — the OAuth 2.0 form (RFC 6749 §3.3): one string, space-delimited.</li>
 *   <li>{@code scp} — the array form several identity providers emit instead. Read as well, and
 *       unioned, because a token carrying one of the two is not a token carrying nothing.</li>
 *   <li>{@code roles} — multi-valued. Feeds {@code PrincipalContext.roles()}, which
 *       {@code SecurityInterceptor} resolves into the {@code roleMask} used by {@code @RequiresRole}.
 *       Before this change roles were always empty, so that mask was always {@code 0L}.</li>
 * </ul>
 *
 * @since 0.10.0
 */
final class CommunityClaimsMapper implements ClaimsMapper {

    private static final String JWT_TYPE = "JWT";
    private static final String SCOPE_CLAIM = "scope";
    private static final String SCOPE_ARRAY_CLAIM = "scp";
    private static final String ROLES_CLAIM = "roles";
    private static final Pattern SCOPE_SEPARATOR = Pattern.compile("\\s+");

    @Override
    public PrincipalContext map(VerifiedClaims claims) {
        String subject = claims.subject();
        if (subject == null || subject.isBlank()) {
            throw new SecurityAuthenticationException(JWT_TYPE, "claims-missing");
        }

        UUID subjectUuid;
        try {
            subjectUuid = UUID.fromString(subject);
        } catch (IllegalArgumentException _) {
            throw new SecurityAuthenticationException(JWT_TYPE, "invalid-subject");
        }

        return ImmutablePrincipal.ofTenant(
                subjectUuid, subjectUuid, claims.stringSetClaim(ROLES_CLAIM), scopesOf(claims));
    }

    /**
     * Unions the two shapes an identity provider may use to express scopes.
     *
     * @param claims the verified claims
     * @return the granted scopes; empty when the token declares none
     */
    private static Set<String> scopesOf(VerifiedClaims claims) {
        Set<String> scopes = new HashSet<>(claims.stringSetClaim(SCOPE_ARRAY_CLAIM));
        claims.claim(SCOPE_CLAIM)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .ifPresent(value -> Collections.addAll(scopes, SCOPE_SEPARATOR.split(value)));
        return Set.copyOf(scopes);
    }
}
