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
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.KernelIsolationClaims;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the five isolation strategy resolution cases in
 * {@link CommunityJwksValidator}/{@link CommunitySecurityProvider}.
 *
 * <table>
 *   <caption>Cases under test</caption>
 *   <tr><th>#</th><th>Strategy claim</th><th>Secondary claim</th><th>Expected strategy</th></tr>
 *   <tr><td>1</td><td>absent</td><td>—</td><td>SHARED (fail-closed default)</td></tr>
 *   <tr><td>2</td><td>SEPARATED_SCHEMA</td><td>schema present</td><td>SEPARATED_SCHEMA</td></tr>
 *   <tr><td>3</td><td>SEPARATED_SCHEMA</td><td>schema absent</td><td>SHARED (fail-closed)</td></tr>
 *   <tr><td>4</td><td>DEDICATED</td><td>datasource key present</td><td>DEDICATED</td></tr>
 *   <tr><td>5</td><td>DEDICATED</td><td>datasource key absent</td><td>SHARED (fail-closed)</td></tr>
 * </table>
 *
 * @since 0.5.0
 */
@DisplayName("Community: isolation strategy resolution — 5 cases")
class CommunityIsolationStrategyResolutionTest {

    private static AuthenticationResult authenticate(TestJwt.Builder builder) {
        CommunitySecurityProvider provider = new CommunitySecurityProvider(
                TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);
        return provider.authenticate(builder.toBuffer());
    }

    // =========================================================================
    // Case 1: strategy claim absent → SHARED (fail-closed default)
    // =========================================================================

    @Test
    @DisplayName("Case 1: absent strategy claim → SHARED (fail-closed default)")
    void absentStrategyClaim_defaultsToShared() {
        AuthenticationResult result = authenticate(TestJwt.builder());

        assertThat(result.storage().strategy())
                .isEqualTo(StorageContext.IsolationStrategy.SHARED);
    }

    // =========================================================================
    // Case 2: SEPARATED_SCHEMA + schema present → SEPARATED_SCHEMA
    // =========================================================================

    @Test
    @DisplayName("Case 2: SEPARATED_SCHEMA strategy + schema claim present → SEPARATED_SCHEMA context")
    void separatedSchema_schemaPresent_yieldsSeparatedSchemaContext() {
        AuthenticationResult result = authenticate(TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "SEPARATED_SCHEMA")
                .claim(KernelIsolationClaims.SCHEMA_NAME, "tenant_acme"));

        assertThat(result.storage().strategy())
                .isEqualTo(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
        assertThat(result.storage().schemaName()).isPresent().contains("tenant_acme");
    }

    // =========================================================================
    // Case 3: SEPARATED_SCHEMA + schema absent → SHARED (fail-closed)
    // =========================================================================

    @Test
    @DisplayName("Case 3: SEPARATED_SCHEMA strategy + schema claim absent → SHARED (fail-closed)")
    void separatedSchema_schemaAbsent_failClosedToShared() {
        AuthenticationResult result = authenticate(TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "SEPARATED_SCHEMA"));
        // No SCHEMA_NAME claim

        assertThat(result.storage().strategy())
                .isEqualTo(StorageContext.IsolationStrategy.SHARED);
    }

    // =========================================================================
    // Case 4: DEDICATED + datasource key present → DEDICATED
    // =========================================================================

    @Test
    @DisplayName("Case 4: DEDICATED strategy + datasource key claim present → DEDICATED context")
    void dedicated_datasourceKeyPresent_yieldsDedicatedContext() {
        AuthenticationResult result = authenticate(TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "DEDICATED")
                .claim(KernelIsolationClaims.DATASOURCE_KEY, "ds-primary"));

        assertThat(result.storage().strategy())
                .isEqualTo(StorageContext.IsolationStrategy.DEDICATED);
        assertThat(result.storage().dataSourceKey()).isPresent().contains("ds-primary");
    }

    // =========================================================================
    // Case 5: DEDICATED + datasource key absent → SHARED (fail-closed)
    // =========================================================================

    @Test
    @DisplayName("Case 5: DEDICATED strategy + datasource key claim absent → SHARED (fail-closed)")
    void dedicated_datasourceKeyAbsent_failClosedToShared() {
        AuthenticationResult result = authenticate(TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "DEDICATED"));
        // No DATASOURCE_KEY claim

        assertThat(result.storage().strategy())
                .isEqualTo(StorageContext.IsolationStrategy.SHARED);
    }

    // =========================================================================
    // Bonus: unrecognised strategy value → SHARED (fail-closed)
    // =========================================================================

    @Test
    @DisplayName("Unrecognised strategy value → SHARED (fail-closed)")
    void unrecognisedStrategyValue_failClosedToShared() {
        AuthenticationResult result = authenticate(TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "UNKNOWN_STRATEGY_XYZ"));

        assertThat(result.storage().strategy())
                .isEqualTo(StorageContext.IsolationStrategy.SHARED);
    }
}
