/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.storage.blob.BlobAccess;
import eu.exeris.kernel.spi.storage.blob.BlobDownloadHandle;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;
import eu.exeris.kernel.spi.storage.blob.BlobRange;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStorageProvider;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import eu.exeris.kernel.spi.storage.blob.BlobUploadHandle;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * A {@link BlobStore} that exists at {@code initialize()} and is created at {@code start()} — the
 * storage counterpart of {@link DeferredHttpClientEngine}, for the same reason.
 *
 * <p>Bootstrap runs every subsystem's {@code initialize()} before it composes {@code
 * providerBindings()}, so {@code KernelProviders.MEMORY_ALLOCATOR} is not bound while
 * {@link CommunityStorageSubsystem} is initialising: {@code dependsOn("memory")} orders the phases, it
 * does not make the binding visible earlier. The S3 driver's {@code createStore} refuses without that
 * binding, and the store's HTTP client engine takes its buffers from the same allocator. The reference
 * must exist by then anyway, because {@code providerBindings()} publishes it as {@code BLOB_STORE};
 * holding the provider and the configuration, and creating the real store in {@link #start()} — which
 * bootstrap runs inside the kernel scope — satisfies both. A driver that rejects its configuration
 * therefore rejects it in {@code start()}, which still fails the boot before the application runs.
 *
 * <p>Every operation before {@link #start()} or after {@link #close()} is refused with
 * {@link IllegalStateException}: there is no store to answer it, and an empty answer would read as a
 * missing object.
 */
final class DeferredBlobStore implements BlobStore {

    private final BlobStorageProvider provider;
    private final BlobStorageConfig config;

    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile BlobStore delegate;
    private volatile boolean closed;

    /* default */ DeferredBlobStore(BlobStorageProvider provider, BlobStorageConfig config) {
        this.provider = provider;
        this.config = config;
    }

    /**
     * Creates the store through the selected provider. Must run where the provider's own
     * dependencies are bound.
     *
     * @throws IllegalStateException if this store is closed or already started
     */
    /* default */ synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Blob store is closed");
        }
        if (delegate != null) {
            throw new IllegalStateException("Blob store is already started");
        }
        delegate = provider.createStore(config);
    }

    @Override
    public BlobUploadHandle beginUpload(BlobRef ref, long contentLength, String contentType) {
        return started().beginUpload(ref, contentLength, contentType);
    }

    @Override
    public BlobDownloadHandle openDownload(BlobRef ref) {
        return started().openDownload(ref);
    }

    @Override
    public BlobDownloadHandle openDownload(BlobRef ref, BlobRange range) {
        return started().openDownload(ref, range);
    }

    @Override
    public Optional<BlobMetadata> stat(BlobRef ref) {
        return started().stat(ref);
    }

    @Override
    public boolean delete(BlobRef ref) {
        return started().delete(ref);
    }

    @Override
    public Optional<URI> signedUrl(BlobRef ref, BlobAccess access, Duration ttl) {
        return started().signedUrl(ref, access, ttl);
    }

    /**
     * Closes the created store, if one was created. Idempotent; synchronised with {@link #start()}
     * so a store created concurrently with a close cannot outlive it.
     */
    @Override
    @SuppressWarnings("PMD.CloseResource") // the local aliases the owned delegate, not a new resource
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        BlobStore local = delegate;
        if (local != null) {
            local.close();
        }
    }

    private BlobStore started() {
        if (closed) {
            throw new IllegalStateException("Blob store is closed");
        }
        BlobStore local = delegate;
        if (local == null) {
            throw new IllegalStateException("Blob store is not started");
        }
        return local;
    }
}
