/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.crypto;

import eu.exeris.kernel.spi.contract.EntitlementClass;
import eu.exeris.kernel.spi.contract.ExecutionEnvironment;
import eu.exeris.kernel.spi.contract.WorkloadEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IssuerKeyScope and TrustedIssuerKey")
class IssuerKeyScopeTest {

    @Test
    @DisplayName("The three scopes hold the values of the key table: environments, validity, ceiling, classes")
    void scopeConstants() {
        assertThat(IssuerKeyScope.PROD.environments()).containsExactlyInAnyOrder(ExecutionEnvironment.values());
        assertThat(IssuerKeyScope.PROD.classes()).containsExactly(EntitlementClass.COMMERCIAL);

        assertThat(IssuerKeyScope.DEV.environments()).containsExactly(ExecutionEnvironment.DEVELOPMENT);
        assertThat(IssuerKeyScope.DEV.maxValidity()).isEqualTo(Duration.ofDays(30));
        assertThat(IssuerKeyScope.DEV.workloadCeiling()).isEqualTo(new WorkloadEnvelope(50, 10, 0));
        assertThat(IssuerKeyScope.DEV.classes()).containsExactly(EntitlementClass.COMMERCIAL);

        assertThat(IssuerKeyScope.ENT.environments()).containsExactlyInAnyOrder(ExecutionEnvironment.values());
        assertThat(IssuerKeyScope.ENT.classes()).containsExactlyInAnyOrder(EntitlementClass.values());
    }

    @Test
    @DisplayName("Only a scope with a workload ceiling is a developer scope")
    void developerScopeIsTheCeiling() {
        assertThat(IssuerKeyScope.DEV.isDeveloperScope()).isTrue();
        assertThat(IssuerKeyScope.PROD.isDeveloperScope()).isFalse();
        assertThat(IssuerKeyScope.ENT.isDeveloperScope()).isFalse();
        assertThat(new IssuerKeyScope(EnumSet.of(ExecutionEnvironment.STAGING), Duration.ofDays(1), null,
                EnumSet.of(EntitlementClass.COMMERCIAL)).isDeveloperScope())
                .as("a maximum validity alone does not make a developer scope").isFalse();
    }

    @Test
    @DisplayName("A scope keeps an immutable copy of the sets it was given")
    void scopeIsImmutable() {
        Set<ExecutionEnvironment> environments = EnumSet.of(ExecutionEnvironment.STAGING);
        IssuerKeyScope scope = new IssuerKeyScope(environments, null, null, Set.of(EntitlementClass.COMMERCIAL));
        environments.add(ExecutionEnvironment.PRODUCTION);

        assertThat(scope.environments()).containsExactly(ExecutionEnvironment.STAGING);
        assertThatThrownBy(() -> scope.environments().add(ExecutionEnvironment.PRODUCTION))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.classes().add(EntitlementClass.ENTERPRISE_PRIVATE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("A scope with no environment, no class or a non-positive validity is rejected")
    void scopeRejectsDegenerateValues() {
        assertThatThrownBy(() -> new IssuerKeyScope(new HashSet<>(), null, null, Set.of(EntitlementClass.COMMERCIAL)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssuerKeyScope(Set.of(ExecutionEnvironment.STAGING), null, null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssuerKeyScope(Set.of(ExecutionEnvironment.STAGING), Duration.ZERO, null,
                Set.of(EntitlementClass.COMMERCIAL))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A trusted key needs an identifier, a key and a scope")
    void trustedKeyRejectsNullAndBlank() throws Exception {
        var publicKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();

        assertThat(new TrustedIssuerKey("k1", publicKey, IssuerKeyScope.PROD).keyId()).isEqualTo("k1");
        assertThatThrownBy(() -> new TrustedIssuerKey(" ", publicKey, IssuerKeyScope.PROD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrustedIssuerKey(null, publicKey, IssuerKeyScope.PROD))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TrustedIssuerKey("k1", null, IssuerKeyScope.PROD))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TrustedIssuerKey("k1", publicKey, null))
                .isInstanceOf(NullPointerException.class);
    }
}
