/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the direction of the tier dependency: SPI ← Core ← Community.
 *
 * <h2>Why this did not exist</h2>
 * <p>The Wall is the repository's most load-bearing structural invariant, and until this class its
 * <em>direction</em> was enforced by nothing. {@code ExerisArchitectureTest} carries thirteen rules,
 * every one of them about what the SPI may reference — none about Core reaching down into a driver.
 * That is not an oversight in the rules so much as a consequence of where they live:
 * {@code exeris-kernel-tck} depends only on the SPI, so no Core or Community class is ever on its
 * analysis classpath, and a rule written there naming {@code eu.exeris.kernel.core..} would match
 * zero classes and pass on the empty set forever.
 *
 * <p>This module is the first point in the reactor where SPI, Core and Community are all visible at
 * once, so it is the only place the direction can actually be checked. Same reasoning, and same
 * placement, as {@code CommunitySchedulingArchitectureTest}.
 *
 * <h2>What "direction" means here</h2>
 * <p>Community depending on Core is intended, not tolerated — the drivers are built on the
 * driver-agnostic engine, and {@code exeris-kernel-community/pom.xml} declares that edge. What must
 * never happen is the reverse: Core reaching into a concrete driver would make the engine
 * driver-specific, which is the whole thing the SPI exists to prevent.
 */
@AnalyzeClasses(packages = "eu.exeris.kernel")
class KernelTierDirectionArchitectureTest {

    @ArchTest
    static void allThreeTiersAreOnTheAnalysisClasspath(JavaClasses classes) {
        // Both rules below are "no classes ... should ...", which pass trivially when the subject
        // package is absent. Without this check a classpath change could silence them and every
        // build would stay green — the exact failure mode that kept the direction unguarded.
        assertThat(countIn(classes, "eu.exeris.kernel.spi"))
                .as("SPI classes must be analysable").isGreaterThan(0);
        assertThat(countIn(classes, "eu.exeris.kernel.core"))
                .as("Core classes must be analysable — otherwise coreDoesNotDependOnCommunity is vacuous")
                .isGreaterThan(0);
        assertThat(countIn(classes, "eu.exeris.kernel.community"))
                .as("Community classes must be analysable — otherwise there is nothing to depend upon")
                .isGreaterThan(0);
    }

    @ArchTest
    static final ArchRule coreDoesNotDependOnCommunity = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.core..")
            .should().dependOnClassesThat().resideInAPackage("eu.exeris.kernel.community..")
            .because("The Wall: Core is the driver-agnostic engine. A single edge into a concrete "
                    + "driver makes it driver-specific, and every provider after that one inherits "
                    + "an assumption nobody declared.");

    @ArchTest
    static final ArchRule spiDependsOnNeitherCoreNorCommunity = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "eu.exeris.kernel.core..", "eu.exeris.kernel.community..")
            .because("The Wall: the SPI is the constitution both tiers are written against, so it "
                    + "cannot know either of them.");

    /**
     * The three packages of the licence contract in Core: the gate in the package itself, the verifier and
     * its trust roots in {@code crypto}, the events and the violation reporter in {@code jfr}. A class
     * outside them belongs to no slice.
     */
    private static final SliceAssignment CONTRACT_SLICES = new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            return switch (javaClass.getPackageName()) {
                case "eu.exeris.kernel.core.contract" -> SliceIdentifier.of("gate");
                case "eu.exeris.kernel.core.contract.crypto" -> SliceIdentifier.of("crypto");
                case "eu.exeris.kernel.core.contract.jfr" -> SliceIdentifier.of("jfr");
                default -> SliceIdentifier.ignore();
            };
        }

        @Override
        public String getDescription() {
            return "the licence contract packages of Core";
        }
    };

    @ArchTest
    static void contractSlicesAreAnalysable(JavaClasses classes) {
        // The rules below constrain slices; with a slice absent they would hold over an empty set.
        for (String contractPackage : new String[] {
                "eu.exeris.kernel.core.contract",
                "eu.exeris.kernel.core.contract.crypto",
                "eu.exeris.kernel.core.contract.jfr"}) {
            assertThat(classes.stream().filter(c -> c.getPackageName().equals(contractPackage)).count())
                    .as("%s must be analysable", contractPackage).isGreaterThan(0);
        }
    }

    @ArchTest
    static final ArchRule contractPackagesAreFreeOfCycles = slices()
            .assignedFrom(CONTRACT_SLICES)
            .should().beFreeOfCycles()
            .because("the gate depends on the verifier and the verifier on the reporter, never the other "
                    + "way round: that acyclic chain is what lets the verifier report a grace-period "
                    + "violation without depending on the gate.");

    @ArchTest
    static final ArchRule cryptoDoesNotDependOnTheGate = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.core.contract.crypto..")
            .should().dependOnClassesThat().resideInAPackage("eu.exeris.kernel.core.contract")
            .because("the verifier and the trust roots are usable without the bootstrap gate.");

    @ArchTest
    static final ArchRule jfrDependsOnNeitherTheGateNorTheVerifier = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.core.contract.jfr..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "eu.exeris.kernel.core.contract", "eu.exeris.kernel.core.contract.crypto..")
            .because("events and the violation reporter are the leaf of the contract packages.");

    private static long countIn(JavaClasses classes, String packagePrefix) {
        return classes.stream()
                .filter(c -> c.getPackageName().startsWith(packagePrefix))
                .count();
    }
}
