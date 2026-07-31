/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @since 0.11.0
 */
public final class CommunityFilesystemBlobStorageProvider implements BlobStorageProvider {

    private static final String PROVIDER_ID = "blob-fs-community";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String providerName() {
        // Shared with the telemetry choke point so the JFR events and the SPI identity cannot drift.
        return CommunityBlobFailures.PROVIDER_NAME;
    }

    @Override
    public BlobStore createStore(BlobStorageConfig config) {
        return new CommunityFilesystemBlobStore(config);
    }
}
