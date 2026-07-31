/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.scheduling;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture guards for the scheduling driver.
 *
 * <p>Lives here rather than in {@code ExerisArchitectureTest} because that suite runs in
 * {@code exeris-kernel-tck}, whose only production dependency is the SPI — so no Core or Community
 * class is ever on its analysis classpath, and a rule written there naming a Community package can
 * never fire. This module is where a rule about a Community driver can actually hold.
 *
 * @since 0.11.0
 */
@AnalyzeClasses(packages = "eu.exeris.kernel.community.scheduling")
class CommunitySchedulingArchitectureTest {

    @ArchTest
    static void schedulingClassesAreAnalyzed(JavaClasses classes) {
        // Without this the rules below would pass on an empty set, which is how a guard becomes
        // decorative without anyone noticing.
        assertThat(classes.stream()
                .filter(c -> c.getPackageName().equals("eu.exeris.kernel.community.scheduling"))
                .count())
                .as("the scheduling driver must be on the analysis classpath")
                .isGreaterThan(0);
    }

    @ArchTest
    static final ArchRule noStructuredTaskScope = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.community.scheduling..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("java.util.concurrent.StructuredTaskScope")
            .because("ADR-057 §2 — StructuredTaskScope is the kernel's last preview dependency, and "
                    + "the Platform Baseline commits the default artifact to being preview-clean for "
                    + "1.0 GA. Four sites exist on the default line; a subsystem written in the "
                    + "milestone that removes them must not become a fifth.");

    @ArchTest
    static final ArchRule noThreadLocal = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.community.scheduling..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal")
            .because("context propagation is ScopedValue-only; a scheduler that reached for a "
                    + "ThreadLocal would leak one dispatch's identity into the next.");

    @ArchTest
    static final ArchRule noExecutors = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.community.scheduling..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("java.util.concurrent.ScheduledExecutorService")
            .because("ADR-057 §3 — a scheduled executor computes deadlines from System.nanoTime() "
                    + "with no seam to displace, which would put the deterministic trigger TCK out "
                    + "of reach and reintroduce the scoped-ban exception this ADR removed.");
}
