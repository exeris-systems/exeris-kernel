/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.core.storage.StorageBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The storage subsystem boots by name through {@link KernelBootstrap} (ADR-056).
 *
 * <p>An application that boots subsystems by name writes {@code BootstrapSelector.forNames("storage")}
 * and nothing more. Three things then have to hold that no subsystem-direct test can see: a
 * {@code SubsystemProvider} on the classpath supplies a subsystem called {@code storage}, the
 * selector's dependency closure pulls in {@code memory} without it being named, and the operator's
 * {@code storage.blob.*} keys reach the subsystem through the discovered {@code ConfigProvider}.
 * {@link CommunityStorageSubsystemTest} drives the subsystem with a hand-bound config and cannot fail
 * on any of them; these cases boot the kernel the way a generated application does.
 *
 * <p>Configuration is set as {@code exeris.}-prefixed system properties because that is what
 * {@code CommunityConfigProvider} reads — the key spelling under test is the one an operator writes.
 * Each case restores the properties it touched.
 */
@DisplayName("Community: storage boots by name through KernelBootstrap")
class CommunityStorageBootstrapIntegrationTest {

    private static final String FS_PROVIDER = "blob-fs-community";
    private static final String PROVIDER_PROPERTY = "exeris." + StorageBootstrap.PROVIDER_KEY;
    private static final String LOCATION_PROPERTY = "exeris.storage.blob.location";

    @Test
    @DisplayName("unconfigured: the closure brings memory up with storage, and nothing is bound")
    void unconfiguredStorageBootsWithItsDependencyAndBindsNothing() throws Exception {
        AtomicBoolean allocatorBound = new AtomicBoolean();
        AtomicReference<List<String>> active = new AtomicReference<>(List.of());
        AtomicBoolean storageRunning = new AtomicBoolean();
        AtomicBoolean blobStoreBound = new AtomicBoolean(true);
        AtomicBoolean blobProviderBound = new AtomicBoolean(true);

        withProperties(properties(null, null), () -> bootStorage(() -> {
            allocatorBound.set(KernelProviders.MEMORY_ALLOCATOR.isBound());
            // SUBSYSTEMS travels in the carrier that holds the provider bindings, and in this boot
            // only memory binds anything, so the slot is bound exactly when memory is. Read
            // unguarded, a missing memory would surface as an unbound-slot error rather than as
            // the assertion below that names it.
            if (KernelProviders.SUBSYSTEMS.isBound()) {
                List<Subsystem> subsystems = KernelProviders.SUBSYSTEMS.get();
                active.set(subsystems.stream().map(Subsystem::name).toList());
                storageRunning.set(subsystems.stream().anyMatch(
                        subsystem -> "storage".equals(subsystem.name()) && subsystem.isRunning()));
            }
            blobStoreBound.set(KernelProviders.BLOB_STORE.isBound());
            blobProviderBound.set(KernelProviders.BLOB_STORAGE_PROVIDER.isBound());
        }));

        assertThat(allocatorBound.get())
                .as("forNames(\"storage\") does not name memory; the dependency closure has to bring "
                        + "it up, and MEMORY_ALLOCATOR is what it binds")
                .isTrue();
        assertThat(active.get())
                .as("memory arrives through the closure and initializes first, and nothing else is "
                        + "pulled in")
                .containsExactly("memory", "storage");
        assertThat(storageRunning.get())
                .as("an unset storage.blob.provider has nothing to run, which is not a failure")
                .isTrue();
        assertThat(blobStoreBound.get())
                .as("an unset storage.blob.provider means blob storage is off")
                .isFalse();
        assertThat(blobProviderBound.get())
                .as("an unset storage.blob.provider selects no driver")
                .isFalse();
    }

    @Test
    @DisplayName("configured: the named driver is bound inside boot, and it is the one named")
    void configuredStorageBindsTheNamedDriverInsideBoot(@TempDir Path root) throws Exception {
        AtomicBoolean blobStoreBound = new AtomicBoolean();
        AtomicReference<String> providerId = new AtomicReference<>();

        withProperties(properties(FS_PROVIDER, root.toString()), () -> bootStorage(() -> {
            blobStoreBound.set(KernelProviders.BLOB_STORE.isBound());
            providerId.set(KernelProviders.BLOB_STORAGE_PROVIDER.get().providerId());
        }));

        assertThat(blobStoreBound.get())
                .as("storage.blob.provider set through the kernel's own config provider binds "
                        + "BLOB_STORE for the application")
                .isTrue();
        assertThat(providerId.get())
                .as("both Community drivers register at the same priority; only the key separates "
                        + "them")
                .isEqualTo(FS_PROVIDER);
        assertThat(KernelProviders.BLOB_STORE.isBound())
                .as("the store is bound inside boot() only")
                .isFalse();
    }

    @Test
    @DisplayName("misconfigured: an id naming no driver refuses the boot with EX-BLOB-8008")
    void unknownProviderIdRefusesTheBoot(@TempDir Path root) {
        AtomicBoolean mainRan = new AtomicBoolean();

        assertThatThrownBy(() -> withProperties(properties("blob-elsewhere", root.toString()),
                () -> bootStorage(() -> mainRan.set(true))))
                .isInstanceOf(KernelBootstrap.BootstrapException.class)
                .satisfies(thrown -> {
                    BlobStorageException refusal = causeOfType(thrown, BlobStorageException.class);
                    assertThat(refusal)
                            .as("a set key that names nothing is a refusal, not blob storage off; "
                                    + "the boot failed with: %s", thrown.getMessage())
                            .isNotNull();
                    assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_BLOB_8008);
                    assertThat(refusal.rawArgs())
                            .as("the key and the value the operator set, read through the kernel's "
                                    + "own config provider")
                            .startsWith(StorageBootstrap.PROVIDER_KEY, "blob-elsewhere");
                });
        assertThat(mainRan.get())
                .as("the application does not run on a kernel whose storage failed to boot")
                .isFalse();
    }

    private static void bootStorage(Runnable kernelMain) throws KernelBootstrap.BootstrapException {
        KernelBootstrap.builder()
                .selector(BootstrapSelector.forNames("storage"))
                .build()
                .boot(kernelMain);
    }

    /** The two storage keys a case sets; a {@code null} value leaves the key unset. */
    private static Map<String, String> properties(String providerId, String location) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(PROVIDER_PROPERTY, providerId);
        properties.put(LOCATION_PROPERTY, location);
        return properties;
    }

    private static void withProperties(Map<String, String> properties, BootAction action)
            throws KernelBootstrap.BootstrapException {
        Map<String, String> previous = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            setOrClear(key, value);
        });
        try {
            action.run();
        } finally {
            previous.forEach(CommunityStorageBootstrapIntegrationTest::setOrClear);
        }
    }

    private static void setOrClear(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static <T extends Throwable> T causeOfType(Throwable thrown, Class<T> type) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface BootAction {
        void run() throws KernelBootstrap.BootstrapException;
    }
}
