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
import java.util.LinkedHashMap;
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
 * <p><strong>Depends on {@code memory}, because one of the two drivers does.</strong>
 * {@code CommunityS3BlobStorageProvider.createStore} refuses outright unless
 * {@code KernelProviders.MEMORY_ALLOCATOR} is bound — it stages transfers off-heap through the
 * kernel's allocator. The filesystem driver needs nothing of the sort, but a subsystem declares what
 * the drivers it <em>may select</em> require, not what the one it happened to select does: the
 * dependency cannot be conditional on a config value read after the boot graph is built. Not
 * {@code http}: the S3 driver builds its own client rather than taking the HTTP subsystem's.
 *
 * @since 0.12.0
 */
final class CommunityStorageSubsystem extends AbstractCommunitySubsystem {

    private static final String LOCATION_KEY = "storage.blob.location";
    private static final String SIGNED_URL_TTL_KEY = "storage.blob.maxSignedUrlTtlSeconds";
    private static final String PROPERTY_PREFIX = "storage.blob.";

    /**
     * Driver property names read from {@code storage.blob.<name>}, in the driver's own spelling so
     * the two cannot drift apart. Only the S3 store reads any today; the filesystem store takes its
     * whole configuration from the location.
     */
    private static final List<String> FORWARDED_PROPERTIES = List.of(
            "s3.bucket", "s3.accessKey", "s3.secretKey", "s3.region", "s3.maxObjectBytes");

    private BlobStorageProvider storageProvider;
    private BlobStore store;

    @Override
    public String name() {
        return "storage";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("memory");
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
     * an operator reading a boot failure needs the key. What a location <em>is</em> belongs to the
     * driver — a directory for the filesystem store, the endpoint {@code http://host:port} for S3,
     * which takes its bucket and credentials as properties instead.
     */
    private static BlobStorageConfig buildConfig(ConfigProvider configProvider) {
        String location = configProvider.getString(LOCATION_KEY)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> BlobStorageException.missingConfiguration(
                        LOCATION_KEY, "a directory for the filesystem driver, http://host:port for S3"));
        long ttlSeconds = configProvider.getLong(SIGNED_URL_TTL_KEY)
                .orElse(BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL.toSeconds());
        return new BlobStorageConfig(location, Duration.ofSeconds(ttlSeconds),
                driverProperties(configProvider));
    }

    /**
     * Forwards the driver-interpreted keys from {@code storage.blob.} into
     * {@link BlobStorageConfig#properties()}.
     *
     * <p><strong>Enumerated rather than swept, and that is a limitation of the config surface rather
     * than a choice.</strong> {@link ConfigProvider} can answer {@code getString(key)} and nothing
     * else — there is no way to ask it for every key under a prefix — so a pass-through has to name
     * what it passes. The consequence is real: a driver that grows a property gets it read only once
     * this list grows too, which is why the list lives next to the keys it mirrors and why the
     * absent-property case is the driver's own refusal rather than silence.
     *
     * <p>The alternative is a provider declaring its own keys through the SPI, which is a
     * {@code BlobStorageProvider} change with a TCK obligation behind it. Recorded in the ROADMAP
     * rather than taken here.
     */
    private static Map<String, String> driverProperties(ConfigProvider configProvider) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (String property : FORWARDED_PROPERTIES) {
            configProvider.getString(PROPERTY_PREFIX + property)
                    .filter(value -> !value.isBlank())
                    .ifPresent(value -> properties.put(property, value));
        }
        return Map.copyOf(properties);
    }
}
