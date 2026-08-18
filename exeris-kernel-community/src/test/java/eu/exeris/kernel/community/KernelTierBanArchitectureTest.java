/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four scoped-ban API rules, checked across the three tiers this module can see.
 *
 * <h2>Why this did not exist</h2>
 * <p>{@code ExerisArchitectureTest} declares these same four bans against
 * {@code eu.exeris.kernel..} — the whole repository — and has since they were written. It lives in
 * {@code exeris-kernel-tck}, whose only production dependency is the SPI, so no Core or Community
 * class has ever been on its analysis classpath. Every one of those four rules has therefore been
 * evaluated against the SPI alone and passed on the empty remainder, while reading like a
 * repository-wide guarantee. That is the same diagnosis {@code KernelTierDirectionArchitectureTest}
 * records for the direction rules, and the same remedy: this module is the first point in the
 * reactor where SPI, Core and Community are all visible at once.
 *
 * <p>The bans are not new and neither is the discipline — {@code CLAUDE.md} lists them as scoped
 * bans and CONTRIBUTING documents the replacements. What is new is that a violation in Core now
 * fails a build instead of waiting for a reviewer to notice.
 *
 * <h2>What this found when it was switched on</h2>
 * <p>Nothing, which is the outcome worth recording rather than glossing. Core and Community carry
 * no {@code Executors}, {@code CompletableFuture}, {@code java.lang.ThreadLocal} or
 * {@code sun.misc.Unsafe} dependency today. Every textual match in those tiers is a Javadoc or
 * comment naming the ban, with one exception that is not a violation:
 * {@code CommunityHttpRetryPolicy} uses {@code java.util.concurrent.ThreadLocalRandom}, a distinct
 * type that carries none of {@code ThreadLocal}'s lifetime problem. The rules below name fully
 * qualified types precisely so that distinction is drawn by the checker rather than by a reader.
 *
 * <p>So this class converts "clean, as far as anyone has looked" into "clean, and checked" — and
 * that is its whole value until the first time it fails.
 *
 * <h2>Reach, stated because overclaiming it is the defect this class exists to fix</h2>
 * <p>Three tiers, not the repository: {@code spi}, {@code core} and {@code community}. Two production
 * modules are outside it, and for a structural reason rather than an oversight —
 * {@code exeris-kernel-community-kafka} and {@code exeris-kernel-diagnostics-cli} are both leaves
 * that nothing depends on, so neither is on any other module's analysis classpath and no suite
 * anywhere in the reactor sees them. Covering them needs a suite per leaf, or a shared rules holder
 * on a classpath all of them have; both are their own slice, recorded in ROADMAP rather than implied
 * away here. An earlier revision of this class was called {@code KernelWideBanArchitectureTest},
 * which claimed the reach it does not have — the same sentence-versus-classpath gap it was written
 * to close.
 *
 * @see KernelTierDirectionArchitectureTest for the placement rationale in full
 */
@AnalyzeClasses(packages = "eu.exeris.kernel")
class KernelTierBanArchitectureTest {

    @ArchTest
    static void allThreeTiersAreOnTheAnalysisClasspath(JavaClasses classes) {
        // Every rule below is "no classes ... should ...", which passes trivially on an absent
        // subject. Without this check the rules would keep passing if the classpath narrowed —
        // which is precisely how they came to mean less than they said in the first place.
        assertThat(countIn(classes, "eu.exeris.kernel.spi"))
                .as("SPI classes must be analysable").isGreaterThan(0);
        assertThat(countIn(classes, "eu.exeris.kernel.core"))
                .as("Core classes must be analysable — otherwise these bans are the SPI-only ones "
                        + "in ExerisArchitectureTest wearing a wider name")
                .isGreaterThan(0);
        assertThat(countIn(classes, "eu.exeris.kernel.community"))
                .as("Community classes must be analysable").isGreaterThan(0);
    }

    @ArchTest
    static final ArchRule noExecutors = noClasses()
            .that().resideInAPackage("eu.exeris.kernel..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.Executors")
            .because("All concurrency must run inside a structured scope that owns the task's "
                    + "lifetime: StructuredScope (GA virtual threads + ScopedValue) on the default "
                    + "line, StructuredTaskScope on the preview branch. See docs/modules/02-core.md "
                    + "rule 3 and ADR-066 — the ban is on the unstructured escape hatch, not on "
                    + "either sanctioned mechanism.");

    @ArchTest
    static final ArchRule noCompletableFuture = noClasses()
            .that().resideInAPackage("eu.exeris.kernel..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.CompletableFuture")
            .because("CompletableFuture is unstructured concurrency: nothing owns the task's "
                    + "lifetime and a failure has no scope to cancel (docs/modules/02-core.md rule 3).");

    @ArchTest
    static final ArchRule noThreadLocal = noClasses()
            .that().resideInAPackage("eu.exeris.kernel..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal")
            .because("Runtime context propagates through ScopedValue. A ThreadLocal outlives the "
                    + "request on a pooled carrier and leaks on a virtual thread that never dies.");

    @ArchTest
    static final ArchRule noUnsafe = noClasses()
            .that().resideInAPackage("eu.exeris.kernel..")
            .should().dependOnClassesThat().haveFullyQualifiedName("sun.misc.Unsafe")
            .because("Off-heap access goes through the FFM API, which carries bounds and lifetime; "
                    + "sun.misc.Unsafe carries neither and is on its way out of the platform.");

    private static long countIn(JavaClasses classes, String packagePrefix) {
        return classes.stream()
                .filter(c -> c.getPackageName().startsWith(packagePrefix))
                .count();
    }
}
