/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.security.identity;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.security.KernelIsolationClaims;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct contract tests for the single kernel-owned claims → {@link StorageContext} mapping
 * (ADR-012 §4a/§4b, ADR-040 §2.4).
 *
 * <p>The mapping is SPI-owned logic that every {@link IdentityProvider} routes through, but until now it
 * was exercised only indirectly, through the Community binding. These tests pin it at its own level, so a
 * regression is attributed here rather than surfacing as a driver failure.
 */
@DisplayName("IdentityStorageMapping — fail-closed claims → StorageContext")
class IdentityStorageMappingTest {

    private static final UUID SUBJECT_ID = UUID.fromString("00000000-0000-4000-8000-00000000beef");
    private static final String SUBJECT = "tenant-acme";
    private static final String JWT = "JWT";

    private static StorageContext map(Map<String, String> claims) {
        return IdentityStorageMapping.fromClaims(new FakeClaims(claims), SUBJECT_ID, JWT);
    }

    private static void assertDenied(Map<String, String> claims, String because) {
        assertThatThrownBy(() -> map(claims))
                .as(because)
                .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
    }

    @Nested
    @DisplayName("Physical isolation strategy (ADR-012 §4a)")
    class Strategy {

        @Test
        @DisplayName("absent strategy → SHARED, the only permissive fall-through")
        void absentStrategyIsShared() {
            assertThat(map(Map.of()).strategy()).isEqualTo(StorageContext.IsolationStrategy.SHARED);
        }

        @Test
        @DisplayName("SEPARATED_SCHEMA without a schema name → deny")
        void separatedSchemaWithoutSchemaDenies() {
            assertDenied(
                    Map.of(KernelIsolationClaims.ISOLATION_STRATEGY, "SEPARATED_SCHEMA"),
                    "a declared strategy missing its required sub-claim cannot be honoured, and "
                            + "downgrading to SHARED would weaken the provisioned tier (S-P0-07)");
        }

        @Test
        @DisplayName("unrecognised strategy value → deny")
        void unrecognisedStrategyDenies() {
            assertDenied(
                    Map.of(KernelIsolationClaims.ISOLATION_STRATEGY, "BYPASS_RLS"),
                    "an unhonourable strategy must be rejected, not downgraded — downgrading grants a "
                            + "session on an injection probe");
        }
    }

    @Nested
    @DisplayName("Shared scope (ADR-012 §4b.5)")
    class SharedScope {

        @Test
        @DisplayName("absent shared-scope claim → tenant-private")
        void absentSharedScopeIsTenantPrivate() {
            assertThat(map(Map.of()).sharedScopeKey())
                    .as("absence must mean the behaviour of every deployment predating the accessor")
                    .isEmpty();
        }

        @Test
        @DisplayName("declared shared scope → deny while no binding can enforce it")
        void declaredSharedScopeDenies() {
            assertDenied(
                    Map.of(KernelIsolationClaims.SHARED_SCOPE_KEY, "world-alpha"),
                    "no binding implements read-widen / owner-scoped-write, so the claim is "
                            + "unenforceable. Narrowing it to tenant-private would silently give the "
                            + "caller less than it asked for; honouring it would hand back a context "
                            + "claiming visibility nothing enforces");
        }

        @Test
        @DisplayName("declared shared scope denies regardless of the physical strategy")
        void sharedScopeDeniesUnderEveryStrategy() {
            assertDenied(
                    Map.of(KernelIsolationClaims.SHARED_SCOPE_KEY, "world-alpha",
                            KernelIsolationClaims.ISOLATION_STRATEGY, "SEPARATED_SCHEMA",
                            KernelIsolationClaims.SCHEMA_NAME, "tenant_acme"),
                    "the two axes are orthogonal — an otherwise-valid SEPARATED_SCHEMA declaration "
                            + "must not smuggle an unenforceable shared scope through");
            assertDenied(
                    Map.of(KernelIsolationClaims.SHARED_SCOPE_KEY, "world-alpha",
                            KernelIsolationClaims.ISOLATION_STRATEGY, "DEDICATED",
                            KernelIsolationClaims.DATASOURCE_KEY, "ds-primary-eu"),
                    "likewise for an otherwise-valid DEDICATED declaration");
        }

        @Test
        @DisplayName("blank shared-scope claim is treated as absent, not as a declaration")
        void blankSharedScopeIsAbsent() {
            assertThat(map(Map.of(KernelIsolationClaims.SHARED_SCOPE_KEY, "   ")).sharedScopeKey())
                    .as("a blank value expresses no shared-scope intent, matching how a blank "
                            + "ISOLATION_STRATEGY is read")
                    .isEmpty();
        }
    }

    /** Minimal {@link VerifiedClaims} whose only interesting behaviour is the claim map. */
    private record FakeClaims(Map<String, String> claims) implements VerifiedClaims {

        @Override
        public String subject() {
            return SUBJECT;
        }

        @Override
        public String issuer() {
            return "https://issuer.test";
        }

        @Override
        public Set<String> audience() {
            return Set.of("exeris-test");
        }

        @Override
        public Optional<Instant> expiresAt() {
            return Optional.empty();
        }

        @Override
        public Optional<String> claim(String name) {
            return Optional.ofNullable(claims.get(name));
        }

        @Override
        public Set<String> stringSetClaim(String name) {
            return Set.of();
        }
    }
}
