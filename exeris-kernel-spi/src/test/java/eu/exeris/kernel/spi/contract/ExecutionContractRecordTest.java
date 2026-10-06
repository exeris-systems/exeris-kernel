/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.contract;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the value types of {@code eu.exeris.kernel.spi.contract}: what each record guarantees on
 * its own, with no provider involved. The provider-facing suites are the {@code Abstract*Tck} classes.
 */
@DisplayName("spi.contract value types")
class ExecutionContractRecordTest {

    private static ExecutionContract contract(Set<String> capabilities, Set<String> environments,
                                              Map<String, EnforcementLevel> rules) {
        return new ExecutionContract("C-1", "STANDARD", "enterprise", "SUBSCRIPTION", "sku",
                capabilities, environments, 3, null, null, null, 7, rules);
    }

    @Nested
    @DisplayName("ExecutionContract")
    class Contract {

        @Test
        @DisplayName("Null collections and bounds become empty or unlimited; collections are copied")
        void normalizesComponents() {
            Set<String> capabilities = new HashSet<>(Set.of("gateway.core"));
            ExecutionContract contract = new ExecutionContract("C-1", "STANDARD", "enterprise", "SUBSCRIPTION",
                    "sku", capabilities, null, 0, null, null, null, 0, null);
            capabilities.add("late-addition");

            assertThat(contract.entitledCapabilities()).containsExactly("gateway.core");
            assertThat(contract.authorizedEnvironments()).isEmpty();
            assertThat(contract.enforcementRules()).isEmpty();
            assertThat(contract.envelope()).isEqualTo(WorkloadEnvelope.UNLIMITED);
            assertThat(contract.validFrom()).isEqualTo(Instant.EPOCH);
            assertThat(contract.validUntil()).isEqualTo(Instant.MAX);
        }

        @Test
        @DisplayName("Required identity fields reject null")
        void rejectsNullIdentity() {
            assertThatThrownBy(() -> new ExecutionContract(null, "STANDARD", "enterprise", "SUBSCRIPTION", "sku",
                    Set.of(), Set.of(), 0, null, null, null, 0, Map.of())).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ExecutionContract("C", null, "enterprise", "SUBSCRIPTION", "sku",
                    Set.of(), Set.of(), 0, null, null, null, 0, Map.of())).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ExecutionContract("C", "STANDARD", null, "SUBSCRIPTION", "sku",
                    Set.of(), Set.of(), 0, null, null, null, 0, Map.of())).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ExecutionContract("C", "STANDARD", "enterprise", null, "sku",
                    Set.of(), Set.of(), 0, null, null, null, 0, Map.of())).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ExecutionContract("C", "STANDARD", "enterprise", "SUBSCRIPTION", null,
                    Set.of(), Set.of(), 0, null, null, null, 0, Map.of())).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Negative instance count or grace period is rejected")
        void rejectsNegativeLimits() {
            assertThatThrownBy(() -> new ExecutionContract("C", "STANDARD", "enterprise", "SUBSCRIPTION", "sku",
                    Set.of(), Set.of(), -1, null, null, null, 0, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ExecutionContract("C", "STANDARD", "enterprise", "SUBSCRIPTION", "sku",
                    Set.of(), Set.of(), 0, null, null, null, -1, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Omitted known constraints take ADR-089's levels; unknown or null constraints are AUDIT")
        void enforcementDefaults() {
            ExecutionContract contract = contract(Set.of(), Set.of(), Map.of("workloadEnvelope", EnforcementLevel.HARD));

            assertThat(contract.getEnforcement("capability")).isEqualTo(EnforcementLevel.HARD);
            assertThat(contract.getEnforcement("environment")).isEqualTo(EnforcementLevel.HARD);
            assertThat(contract.getEnforcement("authorizedInstances")).isEqualTo(EnforcementLevel.SOFT);
            assertThat(contract.getEnforcement("workloadEnvelope")).isEqualTo(EnforcementLevel.HARD);
            assertThat(contract.getEnforcement("anything-else")).isEqualTo(EnforcementLevel.AUDIT);
            assertThat(contract.getEnforcement(null)).isEqualTo(EnforcementLevel.AUDIT);
        }

        @Test
        @DisplayName("An entitled capability and an authorized environment yield no violation")
        void entitledYieldsNothing() {
            ExecutionContract contract = contract(Set.of("gateway.core"), Set.of("production"), Map.of());

            assertThat(contract.allowsCapability("gateway.core")).isTrue();
            assertThat(contract.allowsCapability(null)).isFalse();
            assertThat(contract.isEnvironmentAuthorized("production")).isTrue();
            assertThat(contract.isEnvironmentAuthorized(null)).isFalse();
            assertThat(contract.assertCapability("gateway.core")).isEmpty();
            assertThat(contract.assertEnvironment("production")).isEmpty();
        }

        @Test
        @DisplayName("HARD throws EX-LIC-0004 / EX-LIC-0007 with the refused subject")
        void hardThrows() {
            ExecutionContract contract = contract(Set.of(), Set.of(), Map.of());

            assertThatThrownBy(() -> contract.assertCapability("gateway.core"))
                    .isInstanceOf(ContractBreachException.class)
                    .satisfies(e -> {
                        ContractBreachException breach = (ContractBreachException) e;
                        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0004);
                        assertThat(breach.rawArgs()).containsExactly("gateway.core");
                    });
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
        @DisplayName("Below HARD the violation is returned at the contract's level")
        void belowHardReturnsViolation(EnforcementLevel level) {
            ExecutionContract contract = contract(Set.of(), Set.of(),
                    Map.of("capability", level, "environment", level));

            assertThat(contract.assertCapability("gateway.core"))
                    .contains(new ContractViolation("C-1", "capability", level, "gateway.core"));
            assertThat(contract.assertEnvironment("staging"))
                    .contains(new ContractViolation("C-1", "environment", level, "staging"));
        }

        @Test
        @DisplayName("Capability identifiers: kebab-case segments, optionally dot-separated, nothing else")
        void capabilityIdShape() {
            assertThat(ExecutionContract.isCapabilityId("gateway.rate-limit")).isTrue();
            assertThat(ExecutionContract.isCapabilityId("a1.b2-c3.d")).isTrue();
            assertThat(ExecutionContract.isCapabilityId("Gateway")).isFalse();
            assertThat(ExecutionContract.isCapabilityId("gateway..core")).isFalse();
            assertThat(ExecutionContract.isCapabilityId("gateway-")).isFalse();
            assertThat(ExecutionContract.isCapabilityId(null)).isFalse();
            assertThat(ExecutionContract.isCapabilityId("a".repeat(100_000) + "-b")).isTrue();
        }

        @Test
        @DisplayName("The Community fallback authorizes every environment and entitles nothing")
        void communityFallback() {
            ExecutionContract fallback = ExecutionContract.communityFallback();

            for (ExecutionEnvironment environment : ExecutionEnvironment.values()) {
                assertThat(fallback.isEnvironmentAuthorized(environment.id())).isTrue();
            }
            assertThat(fallback.entitledCapabilities()).isEmpty();
            assertThat(fallback.edition()).isEqualTo("community");
        }
    }

    @Nested
    @DisplayName("ExecutionEnvironment")
    class Environment {

        @Test
        @DisplayName("fromId matches ids case-insensitively and ignores surrounding whitespace")
        void fromId() {
            assertThat(ExecutionEnvironment.fromId(" DR-Hot ")).contains(ExecutionEnvironment.DR_HOT);
            assertThat(ExecutionEnvironment.fromId("production-load-sim"))
                    .contains(ExecutionEnvironment.PRODUCTION_LOAD_SIM);
            assertThat(ExecutionEnvironment.fromId("test")).isEmpty();
            assertThat(ExecutionEnvironment.fromId(null)).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ExecutionEnvironment.class)
        @DisplayName("Exactly the production classes require an entitlement")
        void productionClasses(ExecutionEnvironment environment) {
            boolean production = Set.of("production", "production-load-sim", "dr-hot").contains(environment.id());

            assertThat(environment.requiresEntitlement()).isEqualTo(production);
            assertThat(ExecutionEnvironment.fromId(environment.id())).contains(environment);
        }
    }

    @Nested
    @DisplayName("WorkloadEnvelope, ContractViolation, ContractBreachException")
    class Carriers {

        @Test
        @DisplayName("WorkloadEnvelope rejects negative bounds")
        void envelopeBounds() {
            assertThat(new WorkloadEnvelope(1, 2, 3).maxConnections()).isEqualTo(2);
            assertThatThrownBy(() -> new WorkloadEnvelope(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new WorkloadEnvelope(0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new WorkloadEnvelope(0, 0, -1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("ContractViolation rejects null components")
        void violationNulls() {
            assertThatThrownBy(() -> new ContractViolation(null, "k", EnforcementLevel.SOFT, "s"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ContractViolation("c", null, EnforcementLevel.SOFT, "s"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ContractViolation("c", "k", null, "s"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ContractViolation("c", "k", EnforcementLevel.SOFT, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ContractBreachException carries code, message, cause and rawArgs in every constructor")
        void breachConstructors() {
            IllegalStateException cause = new IllegalStateException("cause");

            ContractBreachException plain = new ContractBreachException(KernelErrorCodes.EX_LIC_0001, "plain");
            ContractBreachException withCause = new ContractBreachException(KernelErrorCodes.EX_LIC_0001, "c", cause);
            ContractBreachException withArgs = new ContractBreachException(KernelErrorCodes.EX_LIC_0002, "a", "k1");
            ContractBreachException withBoth =
                    new ContractBreachException(KernelErrorCodes.EX_LIC_0001, "b", cause, "src");

            assertThat(plain.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0001);
            assertThat(plain.rawArgs()).isEmpty();
            assertThat(withCause.getCause()).isSameAs(cause);
            assertThat(withArgs.rawArgs()).containsExactly("k1");
            assertThat(withBoth.getCause()).isSameAs(cause);
            assertThat(withBoth.rawArgs()).containsExactly("src");
        }
    }
}
