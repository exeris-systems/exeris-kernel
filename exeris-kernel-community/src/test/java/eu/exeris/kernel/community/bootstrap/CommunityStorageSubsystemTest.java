/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.core.storage.StorageBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
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
        @DisplayName("declares no dependencies — neither driver takes a subsystem as input")
        void declaresPlacement() {
            CommunityStorageSubsystem subsystem = new CommunityStorageSubsystem();

            assertThat(subsystem.name()).isEqualTo("storage");
            assertThat(subsystem.phase()).isEqualTo(BootstrapPhase.SERVICES);
            assertThat(subsystem.dependsOn())
                    .as("the filesystem driver needs a directory and the S3 driver an endpoint it "
                            + "reaches over a client it builds itself; declaring http for the S3 "
                            + "case would constrain the boot graph for a driver that may not be the "
                            + "one selected")
                    .isEmpty();
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
                            .extracting(thrown -> ((BlobStorageException) thrown).rawArgs()[0])
                            .as("BlobStorageConfig would refuse this too, naming a constructor "
                                    + "parameter — which is not what an operator has to write")
                            .isEqualTo(LOCATION_KEY));
        }
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
