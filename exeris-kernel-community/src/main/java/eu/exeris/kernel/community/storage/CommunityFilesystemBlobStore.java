/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.storage.blob.BlobAccess;
import eu.exeris.kernel.spi.storage.blob.BlobDownloadHandle;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;
import eu.exeris.kernel.spi.storage.blob.BlobRange;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import eu.exeris.kernel.spi.storage.blob.BlobUploadHandle;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Community filesystem {@link BlobStore} (ADR-056) — objects are files under a per-tenant directory.
 *
 * <p>Transfer mechanics only. Where a reference lands — tenant resolution, the containment check, and
 * the encoding that keeps a hostile isolation key from becoming a directory name — belongs to
 * {@link CommunityFilesystemBlobLayout}, so a change to placement is not read as a change to I/O.
 * Failures are raised through {@link CommunityBlobFailures}, which records them to JFR on the way out.
 *
 * <p>Uploads land in a temporary file and are moved into place on commit, so an interrupted transfer
 * leaves nothing visible (ADR-056 §3).
 *
 * @since 0.11.0
 */
public final class CommunityFilesystemBlobStore implements BlobStore {

    private static final CommunityBlobFailures FAILURES =
            CommunityBlobFailures.forProvider(CommunityBlobFailures.FILESYSTEM_PROVIDER);

    private static final String REF_REQUIRED = "ref must not be null";
    private static final String ROOT_CONTAINER = "[root]";

    private final Path root;
    private final CommunityFilesystemBlobLayout layout;

    /* default */ CommunityFilesystemBlobStore(BlobStorageConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        this.root = Path.of(config.location()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw FAILURES.transferFailed(
                    CommunityBlobFailures.OP_INIT, ROOT_CONTAINER, e);
        }
        this.layout = new CommunityFilesystemBlobLayout(root);
    }

    @Override
    public BlobUploadHandle beginUpload(BlobRef ref, long contentLength, String contentType) {
        Objects.requireNonNull(ref, REF_REQUIRED);
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength must not be negative");
        }
        CommunityFilesystemBlobLayout.BlobPaths paths =
                layout.resolve(ref, CommunityBlobFailures.OP_UPLOAD);
        try {
            Files.createDirectories(paths.object().getParent());
            Path staging = layout.stagingFile(CommunityBlobFailures.OP_UPLOAD);
            return new CommunityFilesystemBlobUploadHandle(
                    staging, paths, ref, contentLength,
                    contentType == null ? BlobMetadata.DEFAULT_CONTENT_TYPE : contentType);
        } catch (IOException e) {
            throw FAILURES.transferFailed(
                    CommunityBlobFailures.OP_UPLOAD, ref.container(), e);
        }
    }

    @Override
    public BlobDownloadHandle openDownload(BlobRef ref) {
        return openDownload(ref, null);
    }

    @Override
    public BlobDownloadHandle openDownload(BlobRef ref, BlobRange range) {
        Objects.requireNonNull(ref, REF_REQUIRED);
        CommunityFilesystemBlobLayout.BlobPaths paths =
                layout.resolve(ref, CommunityBlobFailures.OP_DOWNLOAD);
        Path target = paths.object();
        if (!Files.isRegularFile(target)) {
            throw FAILURES.notFound(ref.container());
        }
        try {
            long size = Files.size(target);
            return new CommunityFilesystemBlobDownloadHandle(
                    target,
                    new BlobMetadata(ref, size,
                            CommunityFilesystemBlobLayout.contentTypeOf(paths.sidecar())),
                    range);
        } catch (IOException e) {
            throw FAILURES.transferFailed(
                    CommunityBlobFailures.OP_DOWNLOAD, ref.container(), e);
        }
    }

    @Override
    public Optional<BlobMetadata> stat(BlobRef ref) {
        Objects.requireNonNull(ref, REF_REQUIRED);
        CommunityFilesystemBlobLayout.BlobPaths paths =
                layout.resolve(ref, CommunityBlobFailures.OP_STAT);
        if (!Files.isRegularFile(paths.object())) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BlobMetadata(ref, Files.size(paths.object()),
                    CommunityFilesystemBlobLayout.contentTypeOf(paths.sidecar())));
        } catch (IOException e) {
            throw FAILURES.transferFailed(
                    CommunityBlobFailures.OP_STAT, ref.container(), e);
        }
    }

    @Override
    public boolean delete(BlobRef ref) {
        Objects.requireNonNull(ref, REF_REQUIRED);
        CommunityFilesystemBlobLayout.BlobPaths paths =
                layout.resolve(ref, CommunityBlobFailures.OP_DELETE);
        try {
            boolean removed = Files.deleteIfExists(paths.object());
            Files.deleteIfExists(paths.sidecar());
            return removed;
        } catch (IOException e) {
            throw FAILURES.transferFailed(
                    CommunityBlobFailures.OP_DELETE, ref.container(), e);
        }
    }

    /**
     * Always empty: a filesystem has no signed URL, and this store declines uniformly rather than
     * signing for some inputs and not others (ADR-056 §7). The isolation check still runs, so an
     * unscoped caller is denied here exactly as it would be on a transfer.
     */
    @Override
    public Optional<URI> signedUrl(BlobRef ref, BlobAccess access, Duration ttl) {
        Objects.requireNonNull(ref, REF_REQUIRED);
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        layout.requireTenantDirectory(CommunityBlobFailures.OP_SIGNED_URL);
        return Optional.empty();
    }

    @Override
    public void close() {
        // No pooled resource: handles own their channels and are closed by their callers.
    }
}
