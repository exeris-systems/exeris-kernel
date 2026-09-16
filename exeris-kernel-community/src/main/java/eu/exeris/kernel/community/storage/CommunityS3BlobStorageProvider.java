/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStorageProvider;
import eu.exeris.kernel.spi.storage.blob.BlobStore;

import java.time.Clock;

/**
 * Community {@link BlobStorageProvider} speaking the S3 HTTP API (ADR-056 §10).
 *
 * <p>{@link BlobStorageConfig#location()} is read as the endpoint — {@code http://host:port} — and the
 * bucket and credentials arrive through {@link BlobStorageConfig#properties()}. Target is a
 * MinIO-compatible, path-style endpoint; see {@link CommunityS3Settings} for the property keys and for
 * why an {@code https} endpoint is refused rather than downgraded.
 *
 * <h2>Selection</h2>
 * <p>Registered alongside the filesystem provider, and at the same Community priority. Nothing in this
 * repository loads {@link BlobStorageProvider} through {@code ServiceLoader} yet, so which of the two a
 * deployment gets is settled by constructing the provider it wants. When a storage subsystem does
 * bootstrap the SPI, two providers at one priority will need a configured choice rather than a
 * discovery order — that is a gap this slice records rather than closes.
 *
 * @since 0.11
 */
public final class CommunityS3BlobStorageProvider implements BlobStorageProvider {

    private static final String PROVIDER_ID = "blob-s3-community";

    /**
     * Instantiated reflectively by {@code ServiceLoader} through this module's
     * {@code META-INF/services} registration of {@link BlobStorageProvider}, alongside
     * {@link CommunityFilesystemBlobStorageProvider}; not meant to be constructed directly.
     */
    public CommunityS3BlobStorageProvider() {
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
        return CommunityBlobFailures.S3_PROVIDER;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if no {@code MEMORY_ALLOCATOR} is bound. Transfers are staged
     *                               off-heap, and a store that quietly allocated its own pool would
     *                               hold memory the kernel's watermark accounting never sees.
     */
    @Override
    public BlobStore createStore(BlobStorageConfig config) {
        if (!KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            throw new IllegalStateException(
                    "KernelProviders.MEMORY_ALLOCATOR is not bound — the S3 blob store stages "
                            + "transfers off-heap and takes its buffers from the kernel's allocator");
        }
        return new CommunityS3BlobStore(config, KernelProviders.MEMORY_ALLOCATOR.get(),
                Clock.systemUTC());
    }
}
