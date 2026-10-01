/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStorageProvider;
import eu.exeris.kernel.spi.storage.blob.BlobStore;

import java.time.Clock;

/**
 * Community {@link BlobStorageProvider} speaking the S3 HTTP API (ADR-056 §10).
 *
 * <p>{@link BlobStorageConfig#location()} is read as the endpoint — {@code http(s)://host[:port]} —
 * and the bucket and credentials arrive through {@link BlobStorageConfig#properties()}. Target is a
 * MinIO-compatible, path-style endpoint; see {@link CommunityS3Settings} for the property keys and for
 * how the endpoint's scheme decides the transport: plaintext for {@code http}, TLS that verifies the
 * endpoint host for {@code https}.
 *
 * <h2>Selection</h2>
 * <p>Registered alongside the filesystem provider, and at the same Community priority, so ranking cannot
 * choose between the two: {@code StorageBootstrap} selects by the configured {@code storage.blob.provider}
 * id, and this driver's is {@code blob-s3-community}. The storage subsystem calls {@link #createStore} in
 * its {@code start()}, inside the kernel scope, because the store takes the kernel's
 * {@code MEMORY_ALLOCATOR}.
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
     * @throws IllegalArgumentException if the endpoint is not an {@code http} or {@code https} URI with
     *                                  a host and nothing beyond its port, or a required property is
     *                                  missing or unusable
     * @throws TransportException ({@code EX-NET-4004}) for an {@code https} endpoint where no crypto
     *                            provider is bound, where the bound one is not the Community provider,
     *                            or under {@code -Dexeris.transport.tls=false}; never downgraded to
     *                            plaintext
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
