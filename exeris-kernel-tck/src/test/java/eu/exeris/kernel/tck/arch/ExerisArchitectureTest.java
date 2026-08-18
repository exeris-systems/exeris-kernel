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
 * ArchUnit — The Static Judge, over the SPI.
 *
 * <h2>Reach, stated because it used to be assumed</h2>
 * <p>This suite runs in {@code exeris-kernel-tck}, whose only production dependency is the SPI, so
 * the SPI is all it can see. Four rules here used to name {@code eu.exeris.kernel..} — the whole
 * repository — and were evaluated against the SPI alone, passing on the empty remainder while
 * reading as a repository-wide guarantee. Proven rather than assumed: a {@code ThreadLocal} field
 * added to {@code core.memory.LeakTracker} left this suite 13/13 green. They are now scoped and
 * named for what they check, and the repository-wide versions live in
 * {@code KernelWideBanArchitectureTest} in {@code exeris-kernel-community}, the first module where
 * all three tiers are on one classpath.
 *
 * <p>{@code allowEmptyShould(true)} is deliberately absent throughout. Every subject below is a
 * package or class set that provably has members, so the flag could only ever hide a future
 * narrowing of the classpath — which is exactly the failure this file just had.
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
            .because("SPI must use java.nio or Panama FFM, never legacy java.io");

    @ArchTest
    static final ArchRule noFilesystemTypesInStorageSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi.storage..")
            .should().dependOnClassesThat().resideInAPackage("java.nio.file..")
            .because("ADR-056 §9 — a filesystem type in the blob contract would encode one driver's "
                    + "addressing model into the seam. Scoped to the storage package rather than the "
                    + "whole SPI because CryptoProviderConfig legitimately carries a Path, and scoped "
                    + "to java.nio.file only because noJavaIoInSpi already covers java.io repo-wide.");

    @ArchTest
    static final ArchRule noStructuredTaskScopeInSchedulingSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi.scheduling..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("java.util.concurrent.StructuredTaskScope")
            .because("ADR-057 §2 — StructuredTaskScope is the kernel's last preview dependency and "
                    + "the Platform Baseline commits the default artifact to being preview-clean for "
                    + "1.0 GA. Scoped to the SPI half only: this suite runs in exeris-kernel-tck, "
                    + "whose only production dependency is the SPI, so no Community class is ever on "
                    + "its analysis classpath and a rule naming one here could never fire. The "
                    + "driver half is held by CommunitySchedulingArchitectureTest, in the module "
                    + "that can see it.");

    @ArchTest
    static final ArchRule noExecutorsInSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.Executors")
            .because("All concurrency must run inside a structured scope that owns the task's "
                    + "lifetime: StructuredScope (GA virtual threads + ScopedValue) on the default "
                    + "line, StructuredTaskScope on the preview branch. See docs/modules/02-core.md "
                    + "rule 3 and ADR-066 — the rule bans the unstructured escape hatch, not one of "
                    + "the two sanctioned mechanisms.");

    @ArchTest
    static final ArchRule noCompletableFutureInSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.CompletableFuture")
            .because("CompletableFuture is unstructured concurrency. Use the structured scope for "
                    + "the distribution line — StructuredScope on the default line, "
                    + "StructuredTaskScope on the preview branch (docs/modules/02-core.md rule 3).");

    @ArchTest
    static final ArchRule noThreadLocalInSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal")
            .because("ThreadLocal causes memory leaks. Use ScopedValue.");

    @ArchTest
    static final ArchRule noImplLeaksInSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.netty..", "io.uring..", "org.openssl..", "com.zaxxer.hikari..", "java.sql..",
                    "com.nimbusds..", "org.apache.kafka.."
            )
            .because("SPI must be implementation-blind (The Wall) — no JWT/Nimbus (ADR-040) "
                    + "or Kafka client (ADR-049/050) vocabulary may cross into the SPI.");

    @ArchTest
    static final ArchRule noDiFrameworksInSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "com.google.inject..", "jakarta.inject.."
            )
            .because("Zero-Magic DI: use pure constructors and ServiceLoader.");

    @ArchTest
    static final ArchRule noDirectArenaInSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.foreign.Arena")
            .because("All allocations must go through MemoryAllocator.");

    @ArchTest
    static final ArchRule noUnsafeInSpi = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi..")
            .should().dependOnClassesThat().haveFullyQualifiedName("sun.misc.Unsafe")
            .because("sun.misc.Unsafe is banned. Use FFM API.");

    @ArchTest
    static final ArchRule streamingSpiCarriesNoWireOrTransportTypes = noClasses()
            .that().haveSimpleName("HttpStreamExchange")
            .or().haveSimpleName("HttpStreamHandler")
            .or().haveSimpleName("StreamEvent")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "eu.exeris.kernel.core.http..",
                    "eu.exeris.kernel.community..",
                    "eu.exeris.kernel.spi.transport..",
                    "jdk.jfr..")
            .because("ADR-043 / The Wall: the streaming SPI carriers carry no wire-format or transport "
                    + "types; SSE framing is Core and the held-open transport is tier-specific.");

    @ArchTest
    static final ArchRule diagnosticsSpiIsEventFree = noClasses()
            .that().resideInAPackage("eu.exeris.kernel.spi.diagnostics..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "eu.exeris.telemetry.spec..", "jdk.jfr..")
            .because("ADR-033 Obligation 10 / ADR-039: the diagnostics SPI carries state, not events;"
                    + " event surfaces stay on the JFR / Glass-Box wire side.");
}
