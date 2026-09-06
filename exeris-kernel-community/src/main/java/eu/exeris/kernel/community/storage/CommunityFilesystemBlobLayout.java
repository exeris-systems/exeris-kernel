/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import java.util.UUID;

/**
 * Where a {@link BlobRef} lands on disk, and nothing else.
 *
 * <p>Separated from {@link CommunityFilesystemBlobStore} because this is where the security-relevant
 * decisions live — tenant resolution, the containment check, and the encoding that keeps a hostile
 * isolation key from becoming a directory name. The store's job is transfer mechanics; keeping the two
 * apart means a change to one is not read as a change to the other.
 *
 * <p>Layout: {@code <root>/t-<hex(isolationKey)>/<tree>/…}, where {@code <tree>} is one of three
 * fixed names — {@code objects}, {@code sidecars}, {@code staging}.
 *
 * <h2>Why three trees rather than suffixes</h2>
 * <p>Content type and in-flight-upload state live in their own trees rather than as suffixes on the
 * object's own path, because {@link BlobRef} rejects traversal, not extensions: {@code
 * "report.uploading"} and {@code "photo.ctype"} are keys a tenant may legitimately store. A suffix
 * would therefore not name a private file — it would name <em>another object of the same tenant</em>,
 * so every operation on the shorter key would silently reach the longer one: an upload to
 * {@code "report"} would truncate and then move away whatever was stored at {@code "report.uploading"},
 * and deleting {@code "photo"} would delete {@code "photo.ctype"} with it.
 *
 * <p>A driver may restrict keys further than the contract does, so refusing the two endings is
 * permitted — but it would make one key work on one blob driver and fail on another for a reason the
 * caller cannot see. Separating the namespaces removes the collision instead of forbidding the keys
 * that expose it, and costs one path segment.
 *
 * <p>Disjointness is structural, not conventional: a container is always a child of {@code objects},
 * so a container named {@code sidecars} lands at {@code objects/sidecars} and collides with nothing.
 * Nothing the caller controls is ever a direct child of the tenant directory.
 *
 * @since 0.11
 */
final class CommunityFilesystemBlobLayout {

    private static final CommunityBlobFailures FAILURES =
            CommunityBlobFailures.forProvider(CommunityBlobFailures.FILESYSTEM_PROVIDER);

    private static final String TENANT_PREFIX = "t-";
    private static final String OBJECT_TREE = "objects";
    private static final String SIDECAR_TREE = "sidecars";
    private static final String STAGING_TREE = "staging";

    private final Path root;

    /* default */ CommunityFilesystemBlobLayout(Path root) {
        this.root = root;
    }

    /**
     * The two paths one reference occupies: its bytes, and the sidecar recording its content type.
     *
     * <p>Resolved together because they share a tenant directory and a relative path, and because a
     * caller that has one almost always needs the other — {@code stat} reads both, {@code delete}
     * removes both.
     */
    /* default */ record BlobPaths(Path object, Path sidecar) { }

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
    /* default */ BlobPaths resolve(BlobRef ref, String operation) {
        StorageContext context = KernelProviders.storageContextOrSystem();
        Path tenantDir = tenantDirectoryOf(context, operation);
        return new BlobPaths(
                contain(tenantDir.resolve(OBJECT_TREE), ref, operation, context),
                contain(tenantDir.resolve(SIDECAR_TREE), ref, operation, context));
    }

    private Path contain(Path treeRoot, BlobRef ref, String operation, StorageContext context) {
        Path resolved = treeRoot.resolve(ref.container()).resolve(ref.key()).normalize();
        if (!resolved.startsWith(treeRoot)) {
            throw FAILURES.isolationDenied(
                    operation, CommunityBlobFailures.REASON_PATH_ESCAPE, context.strategy().name());
        }
        return resolved;
    }

    /**
     * Returns a fresh staging path for one upload, inside the caller's tenant directory.
     *
     * <p>Named by a random id rather than by the target key. A random id cannot collide with a real
     * object under the tenant's {@code objects} tree, and it keeps two concurrent uploads to the same
     * key from sharing one staging file, so one upload's {@code TRUNCATE_EXISTING} cannot cut the
     * other's transfer out from under it. Uploads to one key remain last-commit-wins — the contract —
     * but each writes to its own file until it commits or aborts.
     */
    /* default */ Path stagingFile(String operation) throws IOException {
        Path stagingDir =
                tenantDirectoryOf(KernelProviders.storageContextOrSystem(), operation)
                        .resolve(STAGING_TREE);
        Files.createDirectories(stagingDir);
        return stagingDir.resolve(UUID.randomUUID().toString());
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
     * Records a content type, keeping the sidecar tree in step with an overwrite.
     *
     * <p>The default type is represented by <em>no</em> sidecar, so recording it means removing any
     * the previous object left behind: writing without deleting would let an overwrite keep the old
     * type — re-uploading a {@code text/csv} object as the default would still report {@code text/csv},
     * because absence is only ever written here, never restored on its own.
     *
     * <p>A filesystem has nowhere tidier to keep this. Extended attributes would avoid the second file
     * but are not portable across the filesystems a Community driver has to run on.
     */
    /* default */ static void writeContentType(Path sidecar, String contentType) throws IOException {
        if (BlobMetadata.DEFAULT_CONTENT_TYPE.equals(contentType)) {
            Files.deleteIfExists(sidecar);
            return;
        }
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, contentType, StandardCharsets.UTF_8);
    }

    /** Reads a recorded content type, falling back to the default when none was recorded. */
    /* default */ static String contentTypeOf(Path sidecar) {
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
