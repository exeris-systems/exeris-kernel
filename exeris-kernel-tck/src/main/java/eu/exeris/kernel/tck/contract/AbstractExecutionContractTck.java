/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract;

import eu.exeris.kernel.spi.contract.ContractViolation;
import eu.exeris.kernel.spi.contract.EnforcementLevel;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.contract.ExecutionEnvironment;
import eu.exeris.kernel.spi.contract.WorkloadEnvelope;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK contract verification for {@link ExecutionContract} per ADR-088 and ADR-089.
 *
 * <p>Verifies immutability, capability entitlement evaluation, environment authorization, enforcement
 * dispatch — {@code HARD} throws, {@code SOFT} and {@code AUDIT} return a {@link ContractViolation} at
 * their own level, and an unmapped constraint is {@code AUDIT} — and the Community fallback defaults.
 * Each enforcement direction has its own case, so an implementation that inverts one of them fails.
 *
 * @since 0.13
 */
public abstract class AbstractExecutionContractTck {

    /** Constructor for subclasses. */
    protected AbstractExecutionContractTck() {
        // binding classes supply the contract under test
    }

    /**
     * Creates a test execution contract instance for evaluation.
     *
     * @return test contract
     */
    protected abstract ExecutionContract createContract();

    private static ExecutionContract contract(
            Set<String> capabilities, Set<String> environments, Map<String, EnforcementLevel> rules) {
        return new ExecutionContract(
                "TEST-CONTRACT",
                "STANDARD",
                "enterprise",
                "SUBSCRIPTION",
                "exeris-sku-core",
                capabilities,
                environments,
                10,
                WorkloadEnvelope.UNLIMITED,
                Instant.EPOCH,
                Instant.MAX,
                14,
                rules);
    }

    @Test
    @DisplayName("Contract record defensively copies and enforces unmodifiable collections")
    void contractCollectionsAreUnmodifiable() {
        ExecutionContract contract = createContract();
        assertThat(contract).isNotNull();

        Set<String> caps = contract.entitledCapabilities();
        assertThatThrownBy(() -> caps.add("illegal-mutation"))
                .isInstanceOf(UnsupportedOperationException.class);

        Set<String> envs = contract.authorizedEnvironments();
        assertThatThrownBy(() -> envs.add("illegal-env"))
                .isInstanceOf(UnsupportedOperationException.class);

        Map<String, EnforcementLevel> rules = contract.enforcementRules();
        assertThatThrownBy(() -> rules.put("illegal-rule", EnforcementLevel.HARD))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("allowsCapability returns true only for entitled capabilities")
    void allowsCapabilityEvaluatesCorrectly() {
        ExecutionContract contract = contract(
                Set.of("tls-offload", "ha-clustering"), Set.of("production"),
                Map.of("capability", EnforcementLevel.HARD));

        assertThat(contract.allowsCapability("tls-offload")).isTrue();
        assertThat(contract.allowsCapability("ha-clustering")).isTrue();
        assertThat(contract.allowsCapability("unlicensed-feature")).isFalse();
        assertThat(contract.allowsCapability(null)).isFalse();
    }

    @Test
    @DisplayName("isEnvironmentAuthorized returns true only for authorized environments")
    void environmentAuthorizationEvaluatesCorrectly() {
        ExecutionContract contract = contract(Set.of(), Set.of("staging", "production"), Map.of());

        assertThat(contract.isEnvironmentAuthorized("production")).isTrue();
        assertThat(contract.isEnvironmentAuthorized("staging")).isTrue();
        assertThat(contract.isEnvironmentAuthorized("development")).isFalse();
        assertThat(contract.isEnvironmentAuthorized(null)).isFalse();
    }

    @Test
    @DisplayName("A mapped constraint takes its rule; an omitted known one its ADR-089 level; an unknown one AUDIT")
    void enforcementDefaults() {
        ExecutionContract contract = contract(Set.of(), Set.of(), Map.of("capability", EnforcementLevel.SOFT));

        assertThat(contract.getEnforcement("capability")).isEqualTo(EnforcementLevel.SOFT);
        assertThat(contract.getEnforcement("environment")).isEqualTo(EnforcementLevel.HARD);
        assertThat(contract.getEnforcement("authorizedInstances")).isEqualTo(EnforcementLevel.SOFT);
        assertThat(contract.getEnforcement("workloadEnvelope")).isEqualTo(EnforcementLevel.AUDIT);
        assertThat(contract.getEnforcement("bandwidth")).isEqualTo(EnforcementLevel.AUDIT);
        assertThat(contract.getEnforcement(null)).isEqualTo(EnforcementLevel.AUDIT);
        assertThat(contract(Set.of(), Set.of(), Map.of()).getEnforcement("capability"))
                .isEqualTo(EnforcementLevel.HARD);
    }

    @Test
    @DisplayName("assertCapability throws EX-LIC-0004 when capability is unentitled and enforcement is HARD")
    void assertCapabilityEnforcesHardPolicy() {
        ExecutionContract contract = contract(
                Set.of("entitled-feature"), Set.of("production"), Map.of("capability", EnforcementLevel.HARD));

        assertThat(contract.assertCapability("entitled-feature")).isEmpty();
        assertThatThrownBy(() -> contract.assertCapability("unentitled-feature"))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> {
                    ContractBreachException breach = (ContractBreachException) e;
                    assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0004);
                    assertThat(breach.rawArgs()).containsExactly("unentitled-feature");
                });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EnforcementLevel.class, names = {"SOFT", "AUDIT"})
    @DisplayName("Below HARD, assertCapability returns the violation at the contract's level instead of throwing")
    void assertCapabilityPermissiveBelowHard(EnforcementLevel level) {
        ExecutionContract contract = contract(Set.of(), Set.of("production"), Map.of("capability", level));

        assertThat(contract.assertCapability("unentitled-feature"))
                .contains(new ContractViolation("TEST-CONTRACT", "capability", level, "unentitled-feature"));
    }

    @Test
    @DisplayName("assertCapability with no capability rule is HARD: omitting the rule never weakens it")
    void assertCapabilityWithoutRuleIsHard() {
        ExecutionContract contract = contract(Set.of(), Set.of("production"), Map.of());

        assertThatThrownBy(() -> contract.assertCapability("unentitled-feature"))
                .isInstanceOf(ContractBreachException.class);
    }

    @Test
    @DisplayName("The record rejects a negative instance count or grace period")
    void negativeLimitsAreRejected() {
        assertThatThrownBy(() -> new ExecutionContract("C", "STANDARD", "enterprise", "SUBSCRIPTION", "sku",
                Set.of(), Set.of(), -1, null, null, null, 0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionContract("C", "STANDARD", "enterprise", "SUBSCRIPTION", "sku",
                Set.of(), Set.of(), 0, null, null, null, -1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Capability identifiers are lowercase kebab-case segments, optionally dot-separated")
    void capabilityIdShape() {
        assertThat(ExecutionContract.isCapabilityId("gateway.rate-limit")).isTrue();
        assertThat(ExecutionContract.isCapabilityId("security.bot-fingerprinting")).isTrue();
        assertThat(ExecutionContract.isCapabilityId("ha-clustering")).isTrue();
        for (String malformed : new String[] {"Gateway", "gateway_core", "gateway..core", "-gateway", "gateway.",
                "gateway core", "", null}) {
            assertThat(ExecutionContract.isCapabilityId(malformed)).as("%s", malformed).isFalse();
        }
    }

    @Test
    @DisplayName("The six environment ids are ADR-088's, and exactly production, production-load-sim and dr-hot need an entitlement")
    void environmentClasses() {
        assertThat(Arrays.stream(ExecutionEnvironment.values()).map(ExecutionEnvironment::id))
                .containsExactlyInAnyOrder(
                        "development", "staging", "production", "production-load-sim", "dr-cold", "dr-hot");
        assertThat(Arrays.stream(ExecutionEnvironment.values())
                .filter(ExecutionEnvironment::requiresEntitlement)
                .map(ExecutionEnvironment::id))
                .containsExactlyInAnyOrder("production", "production-load-sim", "dr-hot");
        for (ExecutionEnvironment environment : ExecutionEnvironment.values()) {
            assertThat(ExecutionEnvironment.fromId(environment.id())).contains(environment);
            assertThat(ExecutionEnvironment.fromId(" " + environment.id().toUpperCase(java.util.Locale.ROOT) + " "))
                    .contains(environment);
        }
        assertThat(ExecutionEnvironment.fromId("test")).isEmpty();
        assertThat(ExecutionEnvironment.fromId(null)).isEmpty();
    }

    @Test
    @DisplayName("assertEnvironment throws EX-LIC-0007 when environment is unauthorized and enforcement is HARD")
    void assertEnvironmentEnforcesHardPolicy() {
        ExecutionContract contract = contract(Set.of(), Set.of("staging"), Map.of("environment", EnforcementLevel.HARD));

        assertThat(contract.assertEnvironment("staging")).isEmpty();
        assertThatThrownBy(() -> contract.assertEnvironment("production"))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> {
                    ContractBreachException breach = (ContractBreachException) e;
                    assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0007);
                    assertThat(breach.rawArgs()).containsExactly("production");
                });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EnforcementLevel.class, names = {"SOFT", "AUDIT"})
    @DisplayName("Below HARD, assertEnvironment returns the violation at the contract's level instead of throwing")
    void assertEnvironmentPermissiveBelowHard(EnforcementLevel level) {
        ExecutionContract contract = contract(Set.of(), Set.of("staging"), Map.of("environment", level));

        assertThat(contract.assertEnvironment("production"))
                .contains(new ContractViolation("TEST-CONTRACT", "environment", level, "production"));
    }

    @Test
    @DisplayName("Community fallback authorizes every environment, entitles nothing, and keeps environment HARD")
    void communityFallbackConformsToContract() {
        ExecutionContract fallback = ExecutionContract.communityFallback();

        assertThat(fallback.contractId()).isEqualTo("COMMUNITY");
        assertThat(fallback.edition()).isEqualTo("community");
        assertThat(fallback.envelope()).isEqualTo(WorkloadEnvelope.UNLIMITED);
        for (ExecutionEnvironment environment : ExecutionEnvironment.values()) {
            assertThat(fallback.isEnvironmentAuthorized(environment.id()))
                    .as("Community is unrestricted, so the fallback authorizes %s", environment.id())
                    .isTrue();
        }
        assertThat(fallback.isEnvironmentAuthorized("test")).isFalse();
        assertThat(fallback.entitledCapabilities()).isEmpty();
        assertThat(fallback.getEnforcement("capability")).isEqualTo(EnforcementLevel.SOFT);
        assertThat(fallback.getEnforcement("environment")).isEqualTo(EnforcementLevel.HARD);
    }
}
