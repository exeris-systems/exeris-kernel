/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.core.storage.StorageBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bootstrap wiring for the storage subsystem (ADR-056).
 *
 * <p>Two Community blob drivers register at the same priority, so nothing can rank them — before
 * this subsystem existed, a deployment got whichever the discovery order produced. What these cases
 * pin is the pair of rules that replaced that: the configured id decides, and an <em>absent</em>
 * configuration is not an ambiguous one.
 *
 * <p>The unconfigured case is the one most at risk of being "fixed" into a startup failure by a
 * later reader following the ROADMAP entry's wording literally. It is asserted here for that reason:
 * both drivers are on every Community classpath, so failing an unset key would stop every existing
 * deployment from booting, which is not the defect the selection rule exists to prevent.
 */
@DisplayName("Community: storage subsystem bootstrap")
class CommunityStorageSubsystemTest {

    private static final String FS_PROVIDER = "blob-fs-community";
    private static final String S3_PROVIDER = "blob-s3-community";
    private static final String LOCATION_KEY = "storage.blob.location";
    private static final String SELECTED = "eu.exeris.kernel.storage.StorageBootstrapSelected";

    @Nested
    @DisplayName("Subsystem contract")
    class Contract {

        @Test
        @DisplayName("depends on memory, because one of the two drivers it may select does")
        void declaresPlacement() {
            CommunityStorageSubsystem subsystem = new CommunityStorageSubsystem();

            assertThat(subsystem.name()).isEqualTo("storage");
            assertThat(subsystem.phase()).isEqualTo(BootstrapPhase.SERVICES);
            assertThat(subsystem.dependsOn())
                    .as("CommunityS3BlobStorageProvider.createStore refuses outright unless "
                            + "MEMORY_ALLOCATOR is bound — it stages transfers off-heap. The "
                            + "filesystem driver needs nothing of the sort, but the dependency "
                            + "cannot be conditional on a config value read after the boot graph is "
                            + "built, so a subsystem declares what the drivers it MAY select need. "
                            + "Not http: the S3 driver builds its own client.")
                    .containsExactly("memory");
        }
    }

    @Nested
    @DisplayName("Unconfigured — the normal case, and it must stay bootable")
    class Unconfigured {

        @Test
        @DisplayName("an unset key binds nothing and still boots")
        void unsetKeyBindsNothing() {
            CommunityStorageSubsystem subsystem = new CommunityStorageSubsystem();

            ScopedValue.where(KernelProviders.CURRENT_CONFIG, config(Map.of())).run(() -> {
                assertThatCode(subsystem::initialize)
                        .as("both drivers are on this classpath; refusing to boot without the key "
                                + "would stop every deployment that never wanted blob storage")
                        .doesNotThrowAnyException();
                subsystem.start();

                subsystem.providerBindings().apply(ScopedValue.where(
                        KernelProviders.CURRENT_CONFIG, config(Map.of()))).run(() -> {
                            assertThat(KernelProviders.BLOB_STORE.isBound()).isFalse();
                            assertThat(KernelProviders.BLOB_STORAGE_PROVIDER.isBound()).isFalse();
                        });
            });

            assertThat(subsystem.isRunning())
                    .as("nothing to run is not the same as having failed")
                    .isTrue();
            subsystem.stop();
        }
    }

    @Nested
    @DisplayName("Configured — the id chooses, with both drivers present")
    class Configured {

        @Test
        @DisplayName("the named driver is bound, and it is the one named")
        void namedDriverIsBound(@TempDir Path root) {
            CommunityStorageSubsystem subsystem = new CommunityStorageSubsystem();

            ScopedValue.where(KernelProviders.CURRENT_CONFIG, filesystemConfig(root)).run(() -> {
                subsystem.initialize();
                subsystem.start();
                subsystem.providerBindings()
                        .apply(ScopedValue.where(KernelProviders.CURRENT_CONFIG, filesystemConfig(root)))
                        .run(() -> {
                            assertThat(KernelProviders.BLOB_STORE.isBound()).isTrue();
                            assertThat(KernelProviders.BLOB_STORAGE_PROVIDER.get().providerId())
                                    .as("the S3 driver is on the same classpath at the same "
                                            + "priority — only the key separates them")
                                    .isEqualTo(FS_PROVIDER);
                        });
                subsystem.stop();
            });
        }

        @Test
        @DisplayName("the S3 driver binds too — the half a filesystem-only suite cannot see")
        void s3DriverIsBound() {
            // The whole point of this subsystem is a tie between two drivers, so proving one of them
            // boots proves half of it. This case was absent on the first pass and the S3 path was
            // broken end-to-end underneath it: the subsystem forwarded no properties, and
            // CommunityS3Settings requires s3.bucket, s3.accessKey and s3.secretKey out of them, so
            // naming the S3 driver failed at boot every time. No live endpoint is needed — the store
            // parses settings and constructs a client without connecting.
            CommunityStorageSubsystem subsystem = new CommunityStorageSubsystem();

            withAllocator(() -> ScopedValue.where(KernelProviders.CURRENT_CONFIG, s3Config()).run(() -> {
                subsystem.initialize();
                subsystem.start();
                subsystem.providerBindings()
                        .apply(ScopedValue.where(KernelProviders.CURRENT_CONFIG, s3Config()))
                        .run(() -> assertThat(KernelProviders.BLOB_STORAGE_PROVIDER.get().providerId())
                                .isEqualTo(S3_PROVIDER));
                subsystem.stop();
            }));
        }

        @Test
        @DisplayName("a driver property that is not forwarded is the driver's own refusal, not silence")
        void unforwardedPropertyIsRefusedByTheDriver() {
            // Bucket omitted. The subsystem cannot validate what a driver needs — it does not know —
            // so the contract is that the driver refuses loudly rather than starting half-configured.
            CommunityStorageSubsystem subsystem = new CommunityStorageSubsystem();
            MapConfigProvider config = config(Map.of(
                    StorageBootstrap.PROVIDER_KEY, S3_PROVIDER,
                    LOCATION_KEY, "http://127.0.0.1:9000",
                    "storage.blob.s3.accessKey", "key",
                    "storage.blob.s3.secretKey", "secret"));

            withAllocator(() -> ScopedValue.where(KernelProviders.CURRENT_CONFIG, config).run(() ->
                    assertThatThrownBy(subsystem::initialize)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("s3.bucket")));
        }

        @Test
        @DisplayName("the selection is recorded, so which driver won is not reconstructed later")
        void selectionIsRecorded(@TempDir Path root, @TempDir Path recordingDir) throws Exception {
            Path dump = recordingDir.resolve("storage-bootstrap.jfr");
            CommunityStorageSubsystem subsystem = new CommunityStorageSubsystem();

            try (Recording recording = new Recording()) {
                recording.enable(SELECTED);
                recording.start();
                ScopedValue.where(KernelProviders.CURRENT_CONFIG, filesystemConfig(root)).run(() -> {
                    subsystem.initialize();
                    subsystem.stop();
                });
                recording.dump(dump);
            }

            List<RecordedEvent> events = RecordingFile.readAllEvents(dump).stream()
                    .filter(event -> SELECTED.equals(event.getEventType().getName()))
                    .toList();

            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getString("providerId")).isEqualTo(FS_PROVIDER);
        }
    }

    @Nested
    @DisplayName("Refusals name the key, because one that does not cannot be acted on")
    class Refusals {

        @Test
        @DisplayName("an unknown id is refused, and the message lists what could have been chosen")
        void unknownIdIsRefused(@TempDir Path root) {
            CommunityStorageSubsystem subsystem = new CommunityStorageSubsystem();

            ScopedValue.where(KernelProviders.CURRENT_CONFIG, config(Map.of(
                    StorageBootstrap.PROVIDER_KEY, "blob-elsewhere",
                    LOCATION_KEY, root.toString()))).run(() ->
                    assertThatThrownBy(subsystem::initialize)
                            .isInstanceOf(BlobStorageException.class)
                            .extracting(thrown -> ((BlobStorageException) thrown).rawArgs())
                            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.ARRAY)
                            .as("the two ids actually on the classpath, so the operator can pick "
                                    + "one without going to look for them")
                            .containsExactly(StorageBootstrap.PROVIDER_KEY, "blob-elsewhere",
                                    FS_PROVIDER + ", " + S3_PROVIDER));
        }

        @Test
        @DisplayName("a driver with nowhere to put objects is refused by location key, not by parameter name")
        void missingLocationIsRefused() {
            CommunityStorageSubsystem subsystem = new CommunityStorageSubsystem();

            ScopedValue.where(KernelProviders.CURRENT_CONFIG, config(Map.of(
                    StorageBootstrap.PROVIDER_KEY, FS_PROVIDER))).run(() ->
                    assertThatThrownBy(subsystem::initialize)
                            .isInstanceOf(BlobStorageException.class)
                            .extracting(thrown -> ((BlobStorageException) thrown).errorCode())
                            .as("EX-BLOB-8009, not 8008: 8008's last rawArg is the list of provider "
                                    + "ids, and Glass-Box tooling reads those positions — a hint "
                                    + "string in that slot would be parsed as ids")
                            .isEqualTo(KernelErrorCodes.EX_BLOB_8009));

            ScopedValue.where(KernelProviders.CURRENT_CONFIG, config(Map.of(
                    StorageBootstrap.PROVIDER_KEY, FS_PROVIDER))).run(() ->
                    assertThatThrownBy(subsystem::initialize)
                            .extracting(thrown -> ((BlobStorageException) thrown).rawArgs())
                            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.ARRAY)
                            .as("the key an operator has to write, and what a value looks like — "
                                    + "BlobStorageConfig would refuse this too, naming a constructor "
                                    + "parameter instead")
                            .containsExactly(LOCATION_KEY,
                                    "a directory for the filesystem driver, http://host:port for S3"));
        }
    }

    /**
     * Runs {@code action} with a real allocator bound: the S3 driver refuses to be created without
     * one, because it stages transfers off-heap.
     */
    private static void withAllocator(Runnable action) {
        try (MemoryAllocator allocator =
                     new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults())) {
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(action);
        }
    }

    private static MapConfigProvider s3Config() {
        return config(Map.of(
                StorageBootstrap.PROVIDER_KEY, S3_PROVIDER,
                LOCATION_KEY, "http://127.0.0.1:9000",
                "storage.blob.s3.bucket", "objects",
                "storage.blob.s3.accessKey", "key",
                "storage.blob.s3.secretKey", "secret"));
    }

    private static MapConfigProvider filesystemConfig(Path root) {
        return config(Map.of(
                StorageBootstrap.PROVIDER_KEY, FS_PROVIDER,
                LOCATION_KEY, root.toString()));
    }

    private static MapConfigProvider config(Map<String, String> strings) {
        return new MapConfigProvider(strings, Map.of());
    }
}
