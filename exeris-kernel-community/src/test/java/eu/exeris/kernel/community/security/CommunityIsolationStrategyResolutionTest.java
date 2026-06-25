/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.KernelIsolationClaims;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the isolation strategy resolution cases via
 * {@link CommunitySecurityProvider} (the pipeline now lives in
 * {@link CommunityOidcTokenValidator} +
 * {@link eu.exeris.kernel.spi.security.identity.IdentityStorageMapping}).
 *
 * <p>Fail-closed semantics after S-P0-07 / ADR-012 §4a (amended): only a genuinely <i>absent</i>
 * (or explicit {@code SHARED}) strategy resolves to SHARED. A <b>declared</b> strategy that the
 * kernel cannot honour — unrecognised value, or a strong strategy missing/blank/wrong-typed
 * required sub-claim — is a <b>terminal deny</b> ({@code EX-SEC-2002}), never a silent downgrade
 * to the weakest isolation tier (that downgrade was fail-OPEN).
 *
 * <table>
 *   <caption>Cases under test</caption>
 *   <tr><th>#</th><th>Strategy claim</th><th>Secondary claim</th><th>Expected</th></tr>
 *   <tr><td>1</td><td>absent</td><td>—</td><td>SHARED (legitimate default)</td></tr>
 *   <tr><td>2</td><td>SEPARATED_SCHEMA</td><td>schema present</td><td>SEPARATED_SCHEMA</td></tr>
 *   <tr><td>3</td><td>SEPARATED_SCHEMA</td><td>schema absent</td><td>DENY (EX-SEC-2002)</td></tr>
 *   <tr><td>4</td><td>DEDICATED</td><td>datasource key present</td><td>DEDICATED</td></tr>
 *   <tr><td>5</td><td>DEDICATED</td><td>datasource key absent</td><td>DENY (EX-SEC-2002)</td></tr>
 *   <tr><td>6</td><td>explicit SHARED</td><td>—</td><td>SHARED</td></tr>
 *   <tr><td>7</td><td>unrecognised</td><td>—</td><td>DENY (EX-SEC-2002)</td></tr>
 *   <tr><td>8</td><td>wrong-typed</td><td>—</td><td>DENY (EX-SEC-2002)</td></tr>
 * </table>
 *
 * @since 0.5.0
 */
@DisplayName("Community: isolation strategy resolution — fail-closed (ADR-012 §4a amended)")
class CommunityIsolationStrategyResolutionTest {

    private static CommunitySecurityProvider provider() {
        return new CommunitySecurityProvider(
                TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);
    }

    private static AuthenticationResult authenticate(TestJwt.Builder builder) {
        try (LoanedBuffer token = builder.toBuffer()) {
            return provider().authenticate(token);
        }
    }

    private static void assertDenied(TestJwt.Builder builder) {
        CommunitySecurityProvider provider = provider();
        try (LoanedBuffer token = builder.toBuffer()) {
            assertThatThrownBy(() -> provider.authenticate(token))
                    .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
        }
    }

    @Test
    @DisplayName("Case 1: absent strategy claim → SHARED (legitimate default)")
    void absentStrategyClaim_defaultsToShared() {
        assertThat(authenticate(TestJwt.builder()).storage().strategy())
                .isEqualTo(StorageContext.IsolationStrategy.SHARED);
    }

    @Test
    @DisplayName("Case 2: SEPARATED_SCHEMA + schema present → SEPARATED_SCHEMA context")
    void separatedSchema_schemaPresent_yieldsSeparatedSchemaContext() {
        AuthenticationResult result = authenticate(TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "SEPARATED_SCHEMA")
                .claim(KernelIsolationClaims.SCHEMA_NAME, "tenant_acme"));

        assertThat(result.storage().strategy())
                .isEqualTo(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
        assertThat(result.storage().schemaName()).isPresent().contains("tenant_acme");
    }

    @Test
    @DisplayName("Case 3: SEPARATED_SCHEMA + schema absent → DENY (EX-SEC-2002), not SHARED")
    void separatedSchema_schemaAbsent_denied() {
        assertDenied(TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "SEPARATED_SCHEMA"));
    }

    @Test
    @DisplayName("Case 4: DEDICATED + datasource key present → DEDICATED context")
    void dedicated_datasourceKeyPresent_yieldsDedicatedContext() {
        AuthenticationResult result = authenticate(TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "DEDICATED")
                .claim(KernelIsolationClaims.DATASOURCE_KEY, "ds-primary"));

        assertThat(result.storage().strategy())
                .isEqualTo(StorageContext.IsolationStrategy.DEDICATED);
        assertThat(result.storage().dataSourceKey()).isPresent().contains("ds-primary");
    }

    @Test
    @DisplayName("Case 5: DEDICATED + datasource key absent → DENY (EX-SEC-2002), not SHARED")
    void dedicated_datasourceKeyAbsent_denied() {
        assertDenied(TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "DEDICATED"));
    }

    @Test
    @DisplayName("Case 6: explicit SHARED strategy → SHARED")
    void explicitSharedStrategy_yieldsShared() {
        assertThat(authenticate(TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "SHARED"))
                .storage().strategy())
                .isEqualTo(StorageContext.IsolationStrategy.SHARED);
    }

    @Test
    @DisplayName("Case 7: unrecognised strategy value → DENY (EX-SEC-2002), not SHARED")
    void unrecognisedStrategyValue_denied() {
        assertDenied(TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "UNKNOWN_STRATEGY_XYZ"));
    }

    @Test
    @DisplayName("Case 8: wrong-typed strategy claim → DENY (EX-SEC-2002), not SHARED")
    void wrongTypedStrategyClaim_denied() {
        // A non-string ISOLATION_STRATEGY (here a JSON number) is malformed security input.
        assertDenied(TestJwt.builder()
                .claimRaw(KernelIsolationClaims.ISOLATION_STRATEGY, 42));
    }
}
