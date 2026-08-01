/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;
import eu.exeris.kernel.spi.storage.blob.BlobRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * Where a {@link BlobRef} lands on disk, and nothing else.
 *
 * <p>Separated from {@link CommunityFilesystemBlobStore} because this is where the security-relevant
 * decisions live — tenant resolution, the containment check, and the encoding that keeps a hostile
 * isolation key from becoming a directory name. The store's job is transfer mechanics; keeping the two
 * apart means a change to one is not read as a change to the other.
 *
 * <p>Layout: {@code <root>/t-<hex(isolationKey)>/<container>/<key>}.
 *
 * @since 0.11.0
 */
final class CommunityFilesystemBlobLayout {

    private static final CommunityBlobFailures FAILURES =
            CommunityBlobFailures.forProvider(CommunityBlobFailures.FILESYSTEM_PROVIDER);

    private static final String TENANT_PREFIX = "t-";
    private static final String SIDECAR_SUFFIX = ".ctype";

    private final Path root;

    /* default */ CommunityFilesystemBlobLayout(Path root) {
        this.root = root;
    }

    /**
     * Resolves a tenant-relative reference to an absolute path inside the caller's tenant directory.
     *
     * <p>The containment check is not redundant with {@link BlobRef}'s validation. It is the backstop
     * that still holds if the carrier is ever relaxed, and it costs one comparison on a path that is
     * about to touch a filesystem anyway.
     *
     * @param ref       the tenant-relative reference
     * @param operation the {@code CommunityBlobFailures.OP_*} constant for the call in flight, so a
     *                  denial is attributed to the operation the caller made rather than to resolution
     */
    /* default */ Path resolve(BlobRef ref, String operation) {
        StorageContext context = KernelProviders.storageContextOrSystem();
        Path tenantDir = tenantDirectoryOf(context, operation);
        Path resolved = tenantDir.resolve(ref.container()).resolve(ref.key()).normalize();
        if (!resolved.startsWith(tenantDir)) {
            throw FAILURES.isolationDenied(
                    operation, CommunityBlobFailures.REASON_PATH_ESCAPE, context.strategy().name());
        }
        return resolved;
    }

    /**
     * Returns the caller's tenant directory, denying when the ambient context carries no isolation key.
     *
     * <p>ADR-056 §5: an absent key means there is no namespace to resolve into, and falling back to the
     * store root would place one tenant's object where every tenant can reach it.
     *
     * <p>The directory name is hex-encoded rather than used verbatim. The isolation key is an
     * unconstrained {@code String} that arrives from a verified token claim, so what it may contain is
     * a policy question the store should not have to answer. Encoding makes the whole key one opaque
     * segment, which buys two things a bare {@code ".."} check would not:
     * <ul>
     *   <li>a key carrying separators — {@code "../../etc"}, or merely {@code "a/b"} — cannot become a
     *       nested directory chain, and so cannot climb out of the root;</li>
     *   <li>the mapping is injective, so two distinct tenants cannot land in one directory through
     *       any case-folding or normalisation the filesystem applies.</li>
     * </ul>
     *
     * <p>The {@code t-} prefix independently defeats a <em>bare</em> {@code ".."} key, since
     * {@code "t-.."} is an ordinary directory name. Encoding is what covers the separator-bearing and
     * colliding cases, which is where the real exposure is.
     */
    /* default */ Path requireTenantDirectory(String operation) {
        return tenantDirectoryOf(KernelProviders.storageContextOrSystem(), operation);
    }

    private Path tenantDirectoryOf(StorageContext context, String operation) {
        String isolationKey = context.isolationKey().orElse(null);
        if (isolationKey == null || isolationKey.isBlank()) {
            throw FAILURES.isolationDenied(operation,
                    CommunityBlobFailures.REASON_NO_ISOLATION_KEY, context.strategy().name());
        }
        String encoded = HexFormat.of().formatHex(isolationKey.getBytes(StandardCharsets.UTF_8));
        return root.resolve(TENANT_PREFIX + encoded);
    }

    /**
     * Returns the sidecar path holding an object's declared content type.
     *
     * <p>A filesystem has nowhere else to keep it. Extended attributes would be tidier but are not
     * portable across the filesystems a Community driver has to run on.
     */
    /* default */ static Path sidecar(Path objectPath) {
        return objectPath.resolveSibling(objectPath.getFileName() + SIDECAR_SUFFIX);
    }

    /** Records a content type, skipping the sidecar entirely for the default. */
    /* default */ static void writeContentType(Path objectPath, String contentType) throws IOException {
        if (!BlobMetadata.DEFAULT_CONTENT_TYPE.equals(contentType)) {
            Files.writeString(sidecar(objectPath), contentType, StandardCharsets.UTF_8);
        }
    }

    /** Reads a recorded content type, falling back to the default when none was recorded. */
    /* default */ static String contentTypeOf(Path objectPath) {
        Path sidecar = sidecar(objectPath);
        if (!Files.isRegularFile(sidecar)) {
            return BlobMetadata.DEFAULT_CONTENT_TYPE;
        }
        try {
            String declared = Files.readString(sidecar, StandardCharsets.UTF_8).strip();
            return declared.isBlank() ? BlobMetadata.DEFAULT_CONTENT_TYPE : declared;
        } catch (IOException e) {
            return BlobMetadata.DEFAULT_CONTENT_TYPE;
        }
    }
}
