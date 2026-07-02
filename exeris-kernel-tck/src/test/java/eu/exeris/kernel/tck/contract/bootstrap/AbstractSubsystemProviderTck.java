/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract base for {@link SubsystemProvider} contract verification.
 *
 * <h2>Front 1 — Bootstrap &amp; Config</h2>
 * <p>Enforces the constraints that every {@link SubsystemProvider} must satisfy before the
 * {@code SubsystemOrchestrator} can safely use it during L0 bootstrap.
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>getSubsystems() is <b>pure</b> — no side effects, may be called multiple times.</li>
 *   <li>No two subsystems in the list share the same {@link Subsystem#name()}.</li>
 *   <li>Every subsystem declares a valid, non-null {@link BootstrapPhase}.</li>
 *   <li>Every subsystem's dependsOn() list contains no null or blank entries.</li>
 *   <li>No reflection-based DI (public no-arg constructor required by ServiceLoader).</li>
 *   <li>priority() follows Open-Core convention (Community = 0, Enterprise = 100).</li>
 *   <li>Returned list is non-null and does not itself contain null entries.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunitySubsystemProviderTest extends AbstractSubsystemProviderTck {
 *     \@Override protected SubsystemProvider createProvider() {
 *         return new CommunitySubsystemProvider();
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractSubsystemProviderTck {

    // =========================================================================
    // Template method
    // =========================================================================

    /** Creates the {@link SubsystemProvider} under test. */
    protected abstract SubsystemProvider createProvider();

    private SubsystemProvider provider;
    private ConfigProvider    stubConfig;
    private List<Subsystem>   subsystems;

    @BeforeEach
    final void setUp() {
        provider   = createProvider();
        stubConfig = Mockito.mock(ConfigProvider.class);
        subsystems = provider.getSubsystems(stubConfig);
    }

    // =========================================================================
    // Priority contract
    // =========================================================================

    @Test
    @DisplayName("priority() must be ≥ 0")
    void priorityIsNonNegative() {
        assertThat(provider.priority())
                .as("SubsystemProvider.priority() must be non-negative " +
                    "(convention: Community = 0, Enterprise = 100)")
                .isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // getSubsystems() — purity & non-nullness
    // =========================================================================

    @Nested
    @DisplayName("getSubsystems() — purity and non-null contract")
    class PurityContract {

        @Test
        @DisplayName("getSubsystems() returns a non-null list")
        void returnsNonNullList() {
            assertThat(subsystems)
                    .as("SubsystemProvider.getSubsystems() MUST return a non-null list")
                    .isNotNull();
        }

        @Test
        @DisplayName("getSubsystems() list contains no null entries")
        void noNullSubsystemsInList() {
            assertThat(subsystems)
                    .as("Subsystem list MUST NOT contain null entries")
                    .doesNotContainNull();
        }

        @Test
        @DisplayName("getSubsystems() is pure — repeated calls return equivalent lists")
        void pureMethodIsIdempotent() {
            List<Subsystem> second = provider.getSubsystems(stubConfig);
            assertThat(second).hasSameSizeAs(subsystems);
            List<String> firstNames  = subsystems.stream().map(Subsystem::name).sorted().toList();
            List<String> secondNames = second.stream().map(Subsystem::name).sorted().toList();
            assertThat(secondNames)
                    .as("getSubsystems() is declared pure — repeated calls must yield " +
                        "the same subsystem names (no side-effect mutations)")
                    .isEqualTo(firstNames);
        }
    }

    // =========================================================================
    // Subsystem name uniqueness
    // =========================================================================

    @Nested
    @DisplayName("Subsystem name uniqueness")
    class NameUniqueness {

        @Test
        @DisplayName("No two subsystems share the same name (within this provider)")
        void namesAreUniqueWithinProvider() {
            Map<String, Long> counts = subsystems.stream()
                    .map(Subsystem::name)
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

            List<String> duplicates = counts.entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .toList();

            assertThat(duplicates)
                    .as("SubsystemProvider MUST NOT return two subsystems with the same name. " +
                        "Duplicate names: %s", duplicates)
                    .isEmpty();
        }

        @Test
        @DisplayName("Every subsystem name is non-null and non-blank")
        void subsystemNamesAreNonBlank() {
            subsystems.forEach(sub ->
                    assertThat(sub.name())
                            .as("Subsystem.name() MUST be non-null and non-blank; " +
                                "got: '%s'", sub.name())
                            .isNotBlank());
        }

        @Test
        @DisplayName("Every subsystem name uses lowercase hyphen-separated format")
        void subsystemNamesAreLowercaseHyphenSeparated() {
            subsystems.forEach(sub ->
                    assertThat(sub.name())
                            .as("Subsystem name '%s' MUST be lowercase and hyphen-separated " +
                                "(e.g. 'memory', 'event-bus', 'transport-tcp')", sub.name())
                            .matches("[a-z][a-z0-9-]*"));
        }
    }

    // =========================================================================
    // BootstrapPhase contract
    // =========================================================================

    @Nested
    @DisplayName("BootstrapPhase declarations")
    class PhaseDeclarations {

        @Test
        @DisplayName("Every subsystem declares a non-null BootstrapPhase")
        void allSubsystemsDeclarePhase() {
            subsystems.forEach(sub ->
                    assertThat(sub.phase())
                            .as("Subsystem '%s' returned null from phase(). " +
                                "MUST return FOUNDATION, SERVICES, or RUNTIME.", sub.name())
                            .isNotNull());
        }

        @Test
        @DisplayName("FOUNDATION subsystems declare no optional flag " +
                     "(FOUNDATION is always mandatory)")
        void foundationSubsystemsAreMandatory() {
            subsystems.stream()
                    .filter(sub -> sub.phase() == BootstrapPhase.FOUNDATION)
                    .forEach(sub ->
                            assertThat(sub.isOptional())
                                    .as("Subsystem '%s' is in FOUNDATION phase but declares " +
                                        "isOptional()=true. FOUNDATION subsystems are " +
                                        "ALWAYS mandatory — FAIL_FAST on error.", sub.name())
                                    .isFalse());
        }
    }

    // =========================================================================
    // dependsOn() contract
    // =========================================================================

    @Nested
    @DisplayName("dependsOn() — dependency declaration contract")
    class DependsOnContract {

        @Test
        @DisplayName("dependsOn() never returns null")
        void dependsOnIsNeverNull() {
            subsystems.forEach(sub ->
                    assertThat(sub.dependsOn())
                            .as("Subsystem '%s' dependsOn() MUST return a non-null list " +
                                "(return empty list for no dependencies)", sub.name())
                            .isNotNull());
        }

        @Test
        @DisplayName("dependsOn() contains no null or blank entries")
        void dependsOnContainsNoBlankEntries() {
            subsystems.forEach(sub ->
                    sub.dependsOn().forEach(dep ->
                            assertThat(dep)
                                    .as("Subsystem '%s' has a null/blank dependency entry. " +
                                        "Kahn's BFS in SubsystemOrchestrator requires valid names.",
                                        sub.name())
                                    .isNotBlank()));
        }

        @Test
        @DisplayName("dependsOn() does not contain self-reference (trivial cycle)")
        void dependsOnDoesNotContainSelf() {
            subsystems.forEach(sub -> {
                boolean selfRef = sub.dependsOn().contains(sub.name());
                assertThat(selfRef)
                        .as("Subsystem '%s' lists itself in dependsOn(). " +
                            "Self-reference is a trivial cycle — SubsystemCircularDependencyException " +
                            "must be thrown by the orchestrator, not silently ignored.", sub.name())
                        .isFalse();
            });
        }
    }

    // =========================================================================
    // ServiceLoader contract
    // =========================================================================

    @Test
    @DisplayName("Provider must have a public no-arg constructor (ServiceLoader compliance)")
    void publicNoArgConstructorExists() {
        assertThatCode(() -> provider.getClass().getDeclaredConstructor())
                .as("SubsystemProvider MUST expose a public no-arg constructor for ServiceLoader. " +
                    "Banned: Spring DI, Guice, Jakarta Inject.")
                .doesNotThrowAnyException();
    }
}

