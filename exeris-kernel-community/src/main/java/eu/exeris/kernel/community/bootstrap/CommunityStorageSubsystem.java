/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.storage.StorageBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStorageProvider;
import eu.exeris.kernel.spi.storage.blob.BlobStore;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Bootstraps the storage subsystem (ADR-056).
 *
 * <p><strong>Opt-in, and that is the load-bearing part.</strong> Two Community blob drivers register
 * at the same priority, so ranking cannot choose between them and the operator has to. But a
 * deployment that never wanted blob storage must not be made to answer the question: with
 * {@code storage.blob.provider} unset this subsystem binds nothing and reports running, exactly as a
 * kernel with no storage behaves today. Set the key and the choice is stated; leave it and there is
 * no choice to make. Failing an unconfigured boot would turn "two drivers exist" into "nothing
 * starts", which is not the defect the selection rule exists to prevent.
 *
 * <p><strong>Depends on nothing.</strong> The filesystem driver needs a directory and the S3 driver
 * needs an endpoint it reaches over the client engine it builds itself; neither takes a kernel
 * subsystem as input. Declaring {@code http} for the S3 case would constrain the boot graph for a
 * driver that may not be the one selected.
 *
 * @since 0.12.0
 */
final class CommunityStorageSubsystem extends AbstractCommunitySubsystem {

    private static final String LOCATION_KEY = "storage.blob.location";
    private static final String SIGNED_URL_TTL_KEY = "storage.blob.maxSignedUrlTtlSeconds";

    private BlobStorageProvider storageProvider;
    private BlobStore store;

    @Override
    public String name() {
        return "storage";
    }

    @Override
    public List<String> dependsOn() {
        return List.of();
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.SERVICES;
    }

    @Override
    public void initialize() {
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.get();
        Optional<String> providerId = configProvider.getString(StorageBootstrap.PROVIDER_KEY)
                .filter(value -> !value.isBlank());
        if (providerId.isEmpty()) {
            return;
        }

        StorageBootstrap.BootstrapResult bootstrap =
                StorageBootstrap.loadWithProvider(providerId.get(), buildConfig(configProvider));
        storageProvider = bootstrap.provider();
        store = bootstrap.store();
    }

    @Override
    public void start() {
        // A store is usable as soon as it is created — the filesystem driver holds a path and the S3
        // driver a client — so there is no second start step. An unconfigured subsystem is running
        // too: it has nothing to run, which is not the same as having failed.
        markRunning(true);
    }

    @Override
    public void stop() {
        markRunning(false);
        if (store != null) {
            store.close();
        }
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (storageProvider == null || store == null) {
            return defaultProviderBindings();
        }
        return CommunityCarrierBindings.operator(
                CommunityCarrierBindings.binding(
                        KernelProviders.BLOB_STORAGE_PROVIDER, storageProvider),
                CommunityCarrierBindings.binding(KernelProviders.BLOB_STORE, store)
        );
    }

    /**
     * Reads the store configuration for the driver the operator named.
     *
     * <p>{@code storage.blob.location} is required once storage is on, and refused here rather than
     * inside {@code BlobStorageConfig}: the record's own message names a constructor parameter, and
     * an operator reading a boot failure needs the key.
     */
    private static BlobStorageConfig buildConfig(ConfigProvider configProvider) {
        String location = configProvider.getString(LOCATION_KEY)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> BlobStorageException.selectionUnresolved(
                        LOCATION_KEY, "", "a directory for the filesystem driver, a bucket for S3"));
        long ttlSeconds = configProvider.getLong(SIGNED_URL_TTL_KEY)
                .orElse(BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL.toSeconds());
        return new BlobStorageConfig(location, Duration.ofSeconds(ttlSeconds), Map.of());
    }
}
