/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.AccessFlag;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit: the module-wide registry of JEP 401 value carriers.
 *
 * <h2>Why this exists</h2>
 * <p>The {@code value} modifier is the only difference between a carrier on the two distribution
 * lines. Nothing else in the suite notices when it is lost to a merge, a reformat, or a conflict
 * resolution that takes the wrong side — structural equality cases pass for an identity record too.
 * Per-carrier {@code ValhallaReadiness} blocks assert it for a handful of types; this registry
 * covers the rest of the module without one nested block per carrier.
 *
 * <h2>Non-vacuity</h2>
 * <p>A reflective sweep that discovers nothing passes every assertion made over it — that is exactly
 * how {@code ExerisArchitectureTest} came to inspect zero Core and Community classes while reporting
 * green. The discovery floor below is the guard: a broken walk reports zero and reddens instead.
 *
 * <h2>Scope on this branch</h2>
 * <p>{@link #VALUE_CARRIERS} lists the carriers converted so far. The exhaustive form of this
 * check — every discovered record must be a value class unless explicitly excused — is added per
 * module as that module's sweep completes, because it cannot pass while the sweep is in flight.
 *
 * @since 0.11.0
 */
@DisplayName("L1 Unit: Valhalla value-carrier registry (SPI)")
class ValhallaValueCarrierRegistryTest {

    /** Only classes under this package are considered ours; vendored entries are ignored. */
    private static final String MODULE_PACKAGE = "eu.exeris.kernel.spi";

    /**
     * Floors, not exact counts: they exist to catch a discovery walk that silently finds nothing,
     * not to track module size. Set well below the current census so ordinary growth never
     * reddens them.
     */
    private static final int CLASS_FLOOR = 150;
    private static final int RECORD_FLOOR = 50;

    /** Carriers declared {@code value} in this module. Every entry must carry the modifier. */
    private static final List<String> VALUE_CARRIERS = List.of(
            "eu.exeris.kernel.spi.memory.MemoryStats",
            "eu.exeris.kernel.spi.memory.MemoryProviderConfig",
            "eu.exeris.kernel.spi.crypto.TlsHandshakeResult",
            "eu.exeris.kernel.spi.crypto.TlsShutdownResult",
            "eu.exeris.kernel.spi.events.EventEngineStats",
            "eu.exeris.kernel.spi.transport.TransportStats",
            "eu.exeris.kernel.spi.transport.TransportConfig",
            "eu.exeris.kernel.spi.telemetry.KernelEvent",
            "eu.exeris.kernel.spi.telemetry.TelemetryConfig",
            "eu.exeris.kernel.spi.config.ConfigProvider$KernelSettings",
            "eu.exeris.kernel.spi.config.ConfigProvider$NetworkSettings",
            "eu.exeris.kernel.spi.config.ConfigProvider$PersistenceSettings",
            "eu.exeris.kernel.spi.config.ConfigProvider$TelemetrySettings",
            "eu.exeris.kernel.spi.bootstrap.BootstrapSelector",
            "eu.exeris.kernel.spi.bootstrap.HealthProbe$ProbeSnapshot",
            "eu.exeris.kernel.spi.diagnostics.ProvidersSnapshot",
            "eu.exeris.kernel.spi.diagnostics.BootstrapDagSnapshot",
            "eu.exeris.kernel.spi.diagnostics.RuntimeErgonomicsSnapshot",
            "eu.exeris.kernel.spi.diagnostics.SubsystemSnapshot",
            "eu.exeris.kernel.spi.diagnostics.ProviderDescriptor",
            "eu.exeris.kernel.spi.diagnostics.SubsystemDescriptor",
            "eu.exeris.kernel.spi.diagnostics.DagNode");

    // =========================================================================
    // 1. The sweep itself must not be vacuous
    // =========================================================================

    @Nested
    @DisplayName("1. Discovery")
    class Discovery {

        @Test
        @DisplayName("The class-file walk finds this module's classes")
        void walkIsNotVacuous() throws Exception {
            assertThat(discoverClasses())
                    .as("class-file discovery found nothing — the walk is broken, "
                            + "and every assertion made over it would pass vacuously")
                    .hasSizeGreaterThan(CLASS_FLOOR);
        }

        @Test
        @DisplayName("The walk finds this module's records")
        void recordsAreDiscovered() throws Exception {
            assertThat(discoverClasses().stream().filter(Class::isRecord).toList())
                    .as("no records discovered — a carrier-scoped assertion would inspect nothing")
                    .hasSizeGreaterThan(RECORD_FLOOR);
        }
    }

    // =========================================================================
    // 2. Declared carriers carry the modifier
    // =========================================================================

    @Nested
    @DisplayName("2. Declared value carriers")
    class DeclaredCarriers {

        @Test
        @DisplayName("Every registered carrier is a value class")
        void registeredCarriersAreValueClasses() throws ClassNotFoundException {
            for (String fqn : VALUE_CARRIERS) {
                Class<?> carrier = Class.forName(fqn, false,
                        ValhallaValueCarrierRegistryTest.class.getClassLoader());
                assertThat(carrier.isValue())
                        .as("%s must carry the value modifier; ACC_IDENTITY must be clear", fqn)
                        .isTrue();
            }
        }

        /**
         * {@code isValue()} and the {@code ACC_IDENTITY} access flag are two reads of the same bit
         * through different APIs. Asserting both means a change in either reflective surface is
         * caught rather than silently trusted.
         */
        @Test
        @DisplayName("accessFlags() agrees with isValue() on every registered carrier")
        void accessFlagsAgreeWithIsValue() throws ClassNotFoundException {
            for (String fqn : VALUE_CARRIERS) {
                Class<?> carrier = Class.forName(fqn, false,
                        ValhallaValueCarrierRegistryTest.class.getClassLoader());
                assertThat(carrier.accessFlags())
                        .as("%s is a value class, so ACC_IDENTITY must be absent", fqn)
                        .doesNotContain(AccessFlag.IDENTITY);
            }
        }
    }

    // =========================================================================
    // 3. Controls — the assertion must be able to fail
    // =========================================================================

    @Nested
    @DisplayName("3. Controls")
    class Controls {

        /**
         * Both directions on the running JDK, so a green registry is evidence that
         * {@code isValue()} discriminates rather than evidence that it answers {@code true}
         * to everything. {@code Integer} is one of the migrated platform classes; {@code String}
         * deliberately is not.
         */
        @Test
        @DisplayName("isValue() discriminates on known JDK classes")
        void jdkControlsDiscriminate() {
            assertThat(Integer.class.isValue())
                    .as("Integer is a migrated value class on JDK 28")
                    .isTrue();
            assertThat(String.class.isValue())
                    .as("String is deliberately not a value class")
                    .isFalse();
            assertThat(String.class.accessFlags())
                    .as("an identity class must carry ACC_IDENTITY")
                    .contains(AccessFlag.IDENTITY);
        }
    }

    // =========================================================================
    // Discovery helper
    // =========================================================================

    /**
     * Loads every class this module compiled, from the module's own output directory.
     *
     * <p>Classes are loaded with {@code initialize=false} deliberately: running a static
     * initialiser here would load native libraries and open resources that a reflective census
     * has no business touching.
     */
    private static List<Class<?>> discoverClasses() throws Exception {
        Path root = moduleOutputRoot();
        ClassLoader loader = ValhallaValueCarrierRegistryTest.class.getClassLoader();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> toBinaryName(root, path))
                    .filter(name -> name.startsWith(MODULE_PACKAGE))
                    .filter(name -> !name.endsWith("package-info") && !name.endsWith("module-info"))
                    .map(name -> loadOrNull(name, loader))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    private static Path moduleOutputRoot() throws URISyntaxException {
        // Anchored on a class of this module rather than a relative path, so the test does not
        // depend on the working directory Surefire happens to run it from.
        return Path.of(eu.exeris.kernel.spi.memory.MemoryStats.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static String toBinaryName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString();
        return relative.substring(0, relative.length() - ".class".length())
                .replace(File.separatorChar, '.');
    }

    private static Class<?> loadOrNull(String binaryName, ClassLoader loader) {
        try {
            return Class.forName(binaryName, false, loader);
        } catch (ClassNotFoundException | NoClassDefFoundError _) {
            return null;
        }
    }
}
