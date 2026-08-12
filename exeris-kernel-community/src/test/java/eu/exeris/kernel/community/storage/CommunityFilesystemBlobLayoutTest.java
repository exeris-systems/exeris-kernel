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
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The isolation key is an unconstrained {@code String} — {@code ImmutableStorageContext.shared} does
 * not validate it — so a hostile value reaching the layout is representable, not hypothetical.
 * {@code AbstractBlobStorageTck} proves tenants get <em>different</em> directories using ordinary ids;
 * this proves a hostile id cannot get <em>outside</em> the root, which is the property
 * {@code docs/subsystems/storage.md} actually leans on.
 */
@DisplayName("CommunityFilesystemBlobLayout — hostile isolation keys cannot escape the store root")
class CommunityFilesystemBlobLayoutTest {

    private static final String CONTAINER = "docs";
    private static final String KEY = "report.pdf";
    private static final String OPERATION = "test";
    private static final String CONTAINED =
            "a hostile isolation key must still resolve inside the store root — Path.startsWith is "
            + "used directly because AssertJ canonicalises through toRealPath, which would need the "
            + "directory to exist and so would test the filesystem instead of the encoding";

    private static Path resolveAs(Path root, String isolationKey) {
        CommunityFilesystemBlobLayout layout = new CommunityFilesystemBlobLayout(root);
        return ScopedValue.where(KernelProviders.STORAGE_CONTEXT,
                        ImmutableStorageContext.shared(isolationKey))
                .call(() -> layout.resolve(new BlobRef(CONTAINER, KEY), OPERATION).object());
    }

    @Nested
    @DisplayName("traversal-shaped isolation keys")
    class TraversalKeys {

        @Test
        @DisplayName("a key of '..' still resolves under the root, not above it")
        void parentSegmentKeyStaysContained(@TempDir Path tmp) {
            Path root = tmp.resolve("store");
            Path resolved = resolveAs(root, "..");

            assertThat(resolved.startsWith(root)).as(CONTAINED).isTrue();
            assertThat(resolved.normalize()).isEqualTo(resolved);
        }

        @Test
        @DisplayName("a deep traversal key stays contained and never reaches a sibling of the root")
        void deepTraversalKeyStaysContained(@TempDir Path tmp) {
            Path root = tmp.resolve("store");
            Path sibling = tmp.resolve("etc");

            Path resolved = resolveAs(root, "../../etc");

            assertThat(resolved.startsWith(root)).as(CONTAINED).isTrue();
            assertThat(resolved.startsWith(sibling)).isFalse();
        }

        @Test
        @DisplayName("a separator-bearing key does not become a nested directory chain")
        void separatorKeyDoesNotNest(@TempDir Path tmp) {
            Path root = tmp.resolve("store");

            Path resolved = resolveAs(root, "a/b/c");

            // Encoded, the whole key is one directory name:
            // root -> tenant -> objects -> container -> object.
            assertThat(root.relativize(resolved).getNameCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("an absolute-path key does not escape to the filesystem root")
        void absoluteKeyDoesNotEscape(@TempDir Path tmp) {
            Path root = tmp.resolve("store");

            Path resolved = resolveAs(root, "/etc/passwd");

            assertThat(resolved.startsWith(root)).as(CONTAINED).isTrue();
        }
    }

    @Nested
    @DisplayName("encoding is injective")
    class Injectivity {

        @Test
        @DisplayName("distinct hostile keys resolve to distinct directories")
        void distinctKeysDoNotCollide(@TempDir Path tmp) {
            Path root = tmp.resolve("store");

            assertThat(resolveAs(root, "..")).isNotEqualTo(resolveAs(root, "../.."));
            assertThat(resolveAs(root, "a/b")).isNotEqualTo(resolveAs(root, "a-b"));
        }

        @Test
        @DisplayName("the same key resolves to the same directory")
        void sameKeyIsStable(@TempDir Path tmp) {
            Path root = tmp.resolve("store");

            assertThat(resolveAs(root, "tenant-alpha")).isEqualTo(resolveAs(root, "tenant-alpha"));
        }
    }

    @Nested
    @DisplayName("absent isolation key")
    class AbsentKey {

        @Test
        @DisplayName("a blank key denies rather than falling back to the root")
        void blankKeyDenies(@TempDir Path tmp) {
            Path root = tmp.resolve("store");
            CommunityFilesystemBlobLayout layout = new CommunityFilesystemBlobLayout(root);

            assertThatThrownBy(() -> ScopedValue.where(KernelProviders.STORAGE_CONTEXT,
                            ImmutableStorageContext.shared("   "))
                    .call(() -> layout.requireTenantDirectory(OPERATION)))
                    .isInstanceOfSatisfying(BlobStorageException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_BLOB_8002));
        }
    }
}
