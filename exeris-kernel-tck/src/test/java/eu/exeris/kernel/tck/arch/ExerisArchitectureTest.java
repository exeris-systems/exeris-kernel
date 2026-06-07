/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArchUnit — The Static Judge.
 */

@AnalyzeClasses(packages = "eu.exeris.kernel")
public class ExerisArchitectureTest {

    @ArchTest
    static void verifyClassesArePresent(JavaClasses classes) {
        long spiClassCount = classes.stream()
                .filter(c -> c.getPackageName().startsWith("eu.exeris.kernel.spi"))
                .count();

        assertThat(spiClassCount)
                .as("No Class Loaded")
                .isGreaterThan(0);
    }

    @ArchTest
    static final ArchRule noJavaIoInSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi..")
            .should().dependOnClassesThat().resideInAPackage("java.io..")
            .allowEmptyShould(true)
            .because("SPI must use java.nio or Panama FFM, never legacy java.io");

    @ArchTest
    static final ArchRule noExecutorsAnywhere = noClasses()
            .that().resideInAPackage("eu.exeris.kernel..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.Executors")
            .allowEmptyShould(true)
            .because("All concurrency must use StructuredTaskScope (JEP 525).");

    @ArchTest
    static final ArchRule noCompletableFuture = noClasses()
            .that().resideInAPackage("eu.exeris.kernel..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.CompletableFuture")
            .allowEmptyShould(true)
            .because("CompletableFuture is unstructured concurrency. Use StructuredTaskScope.");

    @ArchTest
    static final ArchRule noThreadLocal = noClasses()
            .that().resideInAPackage("eu.exeris.kernel..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal")
            .allowEmptyShould(true)
            .because("ThreadLocal causes memory leaks. Use ScopedValue.");

    @ArchTest
    static final ArchRule noImplLeaksInSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.netty..", "io.uring..", "org.openssl..", "com.zaxxer.hikari..", "java.sql.."
            )
            .allowEmptyShould(true)
            .because("SPI must be implementation-blind (The Wall).");

    @ArchTest
    static final ArchRule noDiFrameworksInSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "com.google.inject..", "jakarta.inject.."
            )
            .allowEmptyShould(true)
            .because("Zero-Magic DI: use pure constructors and ServiceLoader.");

    @ArchTest
    static final ArchRule noDirectArenaInSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.foreign.Arena")
            .allowEmptyShould(true)
            .because("All allocations must go through MemoryAllocator.");

    @ArchTest
    static final ArchRule noUnsafe = noClasses()
            .that().resideInAPackage("eu.exeris.kernel..")
            .should().dependOnClassesThat().haveFullyQualifiedName("sun.misc.Unsafe")
            .allowEmptyShould(true)
            .because("sun.misc.Unsafe is banned. Use FFM API.");

    @ArchTest
    static final ArchRule diagnosticsSpiIsEventFree = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi.diagnostics..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "eu.exeris.telemetry.spec..", "jdk.jfr..")
            .allowEmptyShould(true)
            .because("ADR-033 Obligation 10 / ADR-039: the diagnostics SPI carries state, not events;"
                    + " event surfaces stay on the JFR / Glass-Box wire side.");
}