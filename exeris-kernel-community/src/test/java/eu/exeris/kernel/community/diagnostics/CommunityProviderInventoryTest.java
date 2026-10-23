/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.diagnostics;

import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnosticsProvider;
import eu.exeris.kernel.spi.diagnostics.ProviderDescriptor;
import eu.exeris.kernel.spi.diagnostics.ProvidersSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommunityProviderInventory — ServiceLoader discovery completeness")
class CommunityProviderInventoryTest {

    /**
     * SPIs registered in Community {@code META-INF/services} that are intentionally excluded from
     * runtime driver inventory snapshots.
     *
     * <p>{@link SubsystemProvider} is the L0 bootstrap entry point for discovering kernel subsystems,
     * not a runtime driver provider. {@link KernelDiagnosticsProvider} is the diagnostics service
     * entry point itself rather than a domain provider inspected by diagnostics.
     */
    private static final Set<Class<?>> EXCLUDED_SPIS = Set.of(
            SubsystemProvider.class,
            KernelDiagnosticsProvider.class
    );

    CommunityProviderInventoryTest() {
        super();
    }

    @Test
    @DisplayName("every SPI registered in META-INF/services is represented in the inventory snapshot")
    void everyRegisteredSpiIsRepresentedInSnapshot() throws Exception {
        Set<Class<?>> registeredSpis = loadRegisteredSpiInterfaces();
        registeredSpis.removeAll(EXCLUDED_SPIS);

        assertThat(registeredSpis).isNotEmpty();

        ProvidersSnapshot snapshot = CommunityProviderInventory.snapshot();
        Set<String> snapshotProviderNames = snapshot.providers().stream()
                .map(ProviderDescriptor::providerName)
                .collect(Collectors.toSet());

        for (Class<?> spiClass : registeredSpis) {
            String registeredProviderName = resolveHighestPriorityProviderName(spiClass);
            assertThat(snapshotProviderNames)
                    .as("Snapshot missing provider for SPI %s (expected %s)", spiClass.getName(), registeredProviderName)
                    .contains(registeredProviderName);
        }
    }

    @Test
    @DisplayName("listProviders reports every Community provider")
    void listProvidersReportsEveryCommunityProvider() {
        ProvidersSnapshot snapshot = new CommunityKernelDiagnosticsProvider().create().listProviders();
        assertThat(snapshot.providers()).isNotEmpty();
        Set<String> spiTypes = snapshot.providers().stream()
                .map(ProviderDescriptor::spiType)
                .collect(Collectors.toSet());
        assertThat(spiTypes).contains(
                "memory", "crypto", "telemetry", "persistence", "events",
                "flow", "transport", "graph", "security", "http",
                "websocket", "scheduling", "storage", "config"
        );
    }

    @Test
    @DisplayName("snapshot provider descriptors have valid fields")
    void descriptorsAreWellFormed() {
        ProvidersSnapshot snapshot = CommunityProviderInventory.snapshot();
        assertThat(snapshot.providers()).allSatisfy(descriptor -> {
            assertThat(descriptor.providerName()).isNotBlank();
            assertThat(descriptor.spiType()).isNotBlank();
            assertThat(descriptor.priority()).isGreaterThanOrEqualTo(0);
        });
    }

    private static Set<Class<?>> loadRegisteredSpiInterfaces() throws Exception {
        URL location = CommunityProviderInventory.class.getProtectionDomain().getCodeSource() != null
                ? CommunityProviderInventory.class.getProtectionDomain().getCodeSource().getLocation()
                : CommunityProviderInventory.class.getResource("/META-INF/services");
        if (location == null) {
            throw new IllegalStateException("Unable to resolve Community module location");
        }
        Path codePath = Path.of(location.toURI());
        Set<String> interfaceNames = new TreeSet<>();

        if (codePath.endsWith(Path.of("META-INF", "services"))) {
            try (Stream<Path> entries = Files.list(codePath)) {
                for (Path entry : entries.toList()) {
                    interfaceNames.add(entry.getFileName().toString());
                }
            }
        } else if (Files.isDirectory(codePath)) {
            Path servicesDir = codePath.resolve("META-INF/services");
            if (Files.isDirectory(servicesDir)) {
                try (Stream<Path> entries = Files.list(servicesDir)) {
                    for (Path entry : entries.toList()) {
                        interfaceNames.add(entry.getFileName().toString());
                    }
                }
            }
        } else if (Files.isRegularFile(codePath)) {
            try (FileSystem jarFs = FileSystems.newFileSystem(codePath, (ClassLoader) null)) {
                Path servicesDir = jarFs.getPath("META-INF/services");
                if (Files.isDirectory(servicesDir)) {
                    try (Stream<Path> entries = Files.list(servicesDir)) {
                        for (Path entry : entries.toList()) {
                            interfaceNames.add(entry.getFileName().toString());
                        }
                    }
                }
            }
        }

        Set<Class<?>> spiClasses = new HashSet<>();
        ClassLoader cl = CommunityProviderInventory.class.getClassLoader();
        for (String name : interfaceNames) {
            spiClasses.add(Class.forName(name, false, cl));
        }
        return spiClasses;
    }

    private static <T> String resolveHighestPriorityProviderName(Class<T> spiClass) throws Exception {
        Method nameMethod = spiClass.getMethod("providerName");
        Method priorityMethod = spiClass.getMethod("priority");

        T winner = ServiceLoader.load(spiClass).stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(p -> {
                    try {
                        return (int) priorityMethod.invoke(p);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(
                                "Failed to invoke priority() on " + p.getClass().getName(), e);
                    }
                }))
                .orElseThrow(() -> new AssertionError("No registered provider found for " + spiClass.getName()));

        return (String) nameMethod.invoke(winner);
    }
}
