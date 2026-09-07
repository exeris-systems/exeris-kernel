/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStorageProvider;
import eu.exeris.kernel.spi.storage.blob.BlobStore;

/**
 * Community {@link BlobStorageProvider} backed by the local filesystem (ADR-056).
 *
 * <p>{@link BlobStorageConfig#location()} is read as the store root directory, created if absent.
 *
 * @since 0.11
 */
public final class CommunityFilesystemBlobStorageProvider implements BlobStorageProvider {

    private static final String PROVIDER_ID = "blob-fs-community";

    /**
     * Instantiated reflectively by {@code ServiceLoader} through this module's
     * {@code META-INF/services} registration of {@link BlobStorageProvider}; not meant to be
     * constructed directly.
     */
    public CommunityFilesystemBlobStorageProvider() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String providerName() {
        // Shared with the telemetry choke point so the JFR events and the SPI identity cannot drift.
        return CommunityBlobFailures.FILESYSTEM_PROVIDER;
    }

    @Override
    public BlobStore createStore(BlobStorageConfig config) {
        return new CommunityFilesystemBlobStore(config);
    }
}
