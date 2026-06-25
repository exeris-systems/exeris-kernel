/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.identity.ClaimsMapper;
import eu.exeris.kernel.spi.security.identity.VerifiedClaims;

import java.util.Set;
import java.util.UUID;

/**
 * Default Community {@link ClaimsMapper}: maps a verified subject to a {@link PrincipalContext}.
 *
 * <p>Preserves the historical Community mapping verbatim (the static-map path is byte-for-byte):
 * {@code sub} parses to a UUIDv7 that becomes both {@code principalId} and {@code tenantId}, with
 * no roles and a single {@code security:read} scope.
 *
 * <p><b>Claim-driven role/scope mapping</b> (reading {@code roles}/{@code scope} claims) is the
 * documented next iteration of this seam (ADR-040 — {@code ClaimsMapper} is the customisation
 * point); it lands with the matching TCK/fixture changes so as not to alter observable behaviour
 * under this refactor.
 *
 * @since 0.10.0
 */
final class CommunityClaimsMapper implements ClaimsMapper {

    private static final String JWT_TYPE = "JWT";

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

        return ImmutablePrincipal.ofTenant(subjectUuid, subjectUuid, Set.of(), Set.of("security:read"));
    }
}
