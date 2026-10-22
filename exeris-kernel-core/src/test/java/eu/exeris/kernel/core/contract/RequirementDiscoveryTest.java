/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.spi.contract.EntitlementClass;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RequirementDiscovery — EntitlementRequirement providers held to their contract")
class RequirementDiscoveryTest {

    @Test
    @DisplayName("Requirements are discovered through ServiceLoader and merged, each identifier with its class")
    void requirementsAreDiscovered(@TempDir Path root) throws Exception {
        try (URLClassLoader loader = loaderDeclaring(root, GatewayRequirement.class, BotRequirement.class)) {
            RequirementDiscovery.DeclaredRequirements declared = RequirementDiscovery.declared(loader);

            assertThat(declared.byId()).containsExactly(
                    Map.entry("bot-fingerprinting", EntitlementClass.COMMERCIAL),
                    Map.entry("gateway-core", EntitlementClass.COMMERCIAL),
                    Map.entry("gateway-routing", EntitlementClass.COMMERCIAL));
            assertThat(declared.providerById()).containsEntry("gateway-core", GatewayRequirement.class.getName());
            assertThat(declared.isEmpty()).isFalse();
        }
    }

    @Test
    @DisplayName("An enterprise-private provider keeps its class")
    void enterprisePrivateClassIsKept(@TempDir Path root) throws Exception {
        try (URLClassLoader loader = loaderDeclaring(root, EnterpriseRequirement.class)) {
            assertThat(RequirementDiscovery.declared(loader).byId())
                    .containsEntry("bot-fingerprinting", EntitlementClass.ENTERPRISE_PRIVATE);
        }
    }

    @Test
    @DisplayName("One identifier declared twice under the same class is accepted once")
    void sameIdentifierSameClassIsIdempotent(@TempDir Path root) throws Exception {
        try (URLClassLoader loader = loaderDeclaring(root, BotRequirement.class, GatewayRequirement.class)) {
            RequirementDiscovery.DeclaredRequirements declared = RequirementDiscovery.declared(loader);

            assertThat(declared.byId()).containsKey("gateway-core").hasSize(3);
        }
    }

    @Test
    @DisplayName("One identifier declared under two classes stops the boot with EX-LIC-0008, naming the second provider")
    void sameIdentifierTwoClassesIsRefused(@TempDir Path root) throws Exception {
        try (URLClassLoader loader = loaderDeclaring(root, BotRequirement.class, EnterpriseRequirement.class)) {
            assertThatThrownBy(() -> RequirementDiscovery.declared(loader))
                    .isInstanceOf(ContractBreachException.class)
                    .hasMessageContaining("bot-fingerprinting")
                    .satisfies(e -> {
                        ContractBreachException breach = (ContractBreachException) e;
                        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0008);
                        assertThat(breach.rawArgs()).containsExactly(EnterpriseRequirement.class.getName());
                    });
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {
            ThrowingRequirement.class, NullRequirement.class, EmptyRequirement.class, MalformedRequirement.class,
            NullClassRequirement.class, ThrowingClassRequirement.class})
    @DisplayName("A requirement that throws, or declares null, nothing, a malformed id or no class, stops the boot with EX-LIC-0008")
    void invalidRequirementIsRefused(Class<? extends EntitlementRequirement> provider, @TempDir Path root) throws Exception {
        try (URLClassLoader loader = loaderDeclaring(root, provider)) {
            assertThatThrownBy(() -> RequirementDiscovery.declared(loader))
                    .isInstanceOf(ContractBreachException.class)
                    .satisfies(e -> {
                        ContractBreachException breach = (ContractBreachException) e;
                        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0008);
                        assertThat(breach.rawArgs()).containsExactly(provider.getName());
                    });
        }
    }

    @Test
    @DisplayName("A requirement class that cannot be loaded stops the boot with EX-LIC-0008, not a raw Error")
    void unloadableRequirementIsRefused(@TempDir Path root) throws Exception {
        Path services = Files.createDirectories(root.resolve("META-INF/services"));
        Files.writeString(services.resolve(EntitlementRequirement.class.getName()),
                "eu.exeris.example.MissingRequirement\n");
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {root.toUri().toURL()}, RequirementDiscoveryTest.class.getClassLoader())) {
            assertThatThrownBy(() -> RequirementDiscovery.declared(loader))
                    .isInstanceOf(ContractBreachException.class)
                    .satisfies(e -> {
                        ContractBreachException breach = (ContractBreachException) e;
                        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0008);
                        assertThat(breach.rawArgs()).containsExactly(EntitlementRequirement.class.getName());
                    });
        }
    }

    @Test
    @DisplayName("A classpath with no requirement declares nothing")
    void noRequirementOnPlainClasspath() {
        assertThat(RequirementDiscovery.declared(getClass().getClassLoader()).isEmpty()).isTrue();
    }

    @SafeVarargs
    private static URLClassLoader loaderDeclaring(Path root, Class<? extends EntitlementRequirement>... providers)
            throws Exception {
        Path services = Files.createDirectories(root.resolve("META-INF/services"));
        StringBuilder names = new StringBuilder();
        for (Class<? extends EntitlementRequirement> provider : providers) {
            names.append(provider.getName()).append('\n');
        }
        Files.writeString(services.resolve(EntitlementRequirement.class.getName()), names.toString());
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, RequirementDiscoveryTest.class.getClassLoader());
    }

    /** Test requirement that fails to declare. */
    public static final class ThrowingRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            throw new IllegalStateException("not ready");
        }

        @Override
        public EntitlementClass entitlementClass() {
            return EntitlementClass.COMMERCIAL;
        }
    }

    /** Test requirement that declares null. */
    public static final class NullRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return null;
        }

        @Override
        public EntitlementClass entitlementClass() {
            return EntitlementClass.COMMERCIAL;
        }
    }

    /** Test requirement that declares nothing. */
    public static final class EmptyRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of();
        }

        @Override
        public EntitlementClass entitlementClass() {
            return EntitlementClass.COMMERCIAL;
        }
    }

    /** Test requirement that declares an identifier no manifest could entitle. */
    public static final class MalformedRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of("Gateway Core");
        }

        @Override
        public EntitlementClass entitlementClass() {
            return EntitlementClass.COMMERCIAL;
        }
    }

    /** Test requirement that states no class. */
    public static final class NullClassRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of("gateway-core");
        }

        @Override
        public EntitlementClass entitlementClass() {
            return null;
        }
    }

    /** Test requirement whose class cannot be determined. */
    public static final class ThrowingClassRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of("gateway-core");
        }

        @Override
        public EntitlementClass entitlementClass() {
            throw new IllegalStateException("not ready");
        }
    }

    /** Test requirement: two gateway capabilities. */
    public static final class GatewayRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of("gateway-core", "gateway-routing");
        }

        @Override
        public EntitlementClass entitlementClass() {
            return EntitlementClass.COMMERCIAL;
        }
    }

    /** Test requirement: one overlapping and one distinct capability. */
    public static final class BotRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of("gateway-core", "bot-fingerprinting");
        }

        @Override
        public EntitlementClass entitlementClass() {
            return EntitlementClass.COMMERCIAL;
        }
    }

    /** Test requirement: the distinct capability, declared enterprise-private. */
    public static final class EnterpriseRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of("bot-fingerprinting");
        }

        @Override
        public EntitlementClass entitlementClass() {
            return EntitlementClass.ENTERPRISE_PRIVATE;
        }
    }
}
