/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.storage.blob.BlobDownloadHandle;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import eu.exeris.kernel.spi.storage.blob.BlobUploadHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store's private files must not be reachable as objects, and vice versa.
 *
 * <p>{@code AbstractBlobStorageTck} exercises the contract with ordinary keys, which is where the
 * defect hid: staging and content-type files were named by appending {@code .uploading} and
 * {@code .ctype} to the object's own path, and both are endings {@link BlobRef} permits. The suffix
 * did not name a private file — it named another object of the same tenant. So these cases use
 * exactly the keys the old naming stole.
 *
 * <p>Nothing here is filesystem-specific except the driver under test: what is asserted is the
 * contract every {@code BlobStore} owes, that an operation on one key touches only that key.
 */
@DisplayName("CommunityFilesystemBlobStore — the store's own files share no namespace with objects")
class CommunityFilesystemBlobNamespaceTest {

    private static final String TENANT = "tenant-alpha";
    private static final String CONTAINER = "docs";
    private static final int BUFFER_BYTES = 4096;
    private static final byte[] NEIGHBOUR = "neighbour".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SUBJECT = "subject".getBytes(StandardCharsets.UTF_8);

    @TempDir
    private Path storeRoot;

    private BlobStore store;
    private MemoryAllocator allocator;

    @BeforeEach
    void setUp() {
        store = new CommunityFilesystemBlobStorageProvider()
                .createStore(BlobStorageConfig.atLocation(storeRoot.toString()));
        allocator = new CommunityMemoryProvider().createAllocator(
                MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.PARANOID));
    }

    @AfterEach
    void tearDown() {
        store.close();
        allocator.close();
    }

    @Nested
    @DisplayName("a key ending in the staging suffix is an ordinary object")
    class StagingSuffixKeys {

        @Test
        @DisplayName("uploading 'report' leaves 'report.uploading' untouched")
        void uploadDoesNotConsumeTheNeighbour() {
            asTenant(() -> {
                upload(ref("report.uploading"), NEIGHBOUR, null);
                upload(ref("report"), SUBJECT, null);

                assertThat(download(ref("report.uploading")))
                        .as("the staging file used to BE this object: opened with TRUNCATE_EXISTING, "
                            + "then moved away by the commit, so the neighbour was first emptied and "
                            + "then deleted by an upload that never named it")
                        .isEqualTo(NEIGHBOUR);
                assertThat(download(ref("report"))).isEqualTo(SUBJECT);
            });
        }

        @Test
        @DisplayName("an aborted upload to 'report' does not delete 'report.uploading'")
        void abortDoesNotDeleteTheNeighbour() {
            asTenant(() -> {
                upload(ref("report.uploading"), NEIGHBOUR, null);

                // Closed without commit: the abort path deletes the staging file, which used to be
                // the neighbour's own path.
                store.beginUpload(ref("report"), SUBJECT.length, null).close();

                assertThat(store.stat(ref("report.uploading"))).isPresent();
                assertThat(download(ref("report.uploading"))).isEqualTo(NEIGHBOUR);
            });
        }
    }

    @Nested
    @DisplayName("a key ending in the sidecar suffix is an ordinary object")
    class SidecarSuffixKeys {

        @Test
        @DisplayName("recording a content type for 'photo' does not overwrite 'photo.ctype'")
        void contentTypeDoesNotOverwriteTheNeighbour() {
            asTenant(() -> {
                upload(ref("photo.ctype"), NEIGHBOUR, null);
                upload(ref("photo"), SUBJECT, "image/png");

                assertThat(download(ref("photo.ctype"))).isEqualTo(NEIGHBOUR);
                assertThat(store.stat(ref("photo.ctype")).orElseThrow().sizeBytes())
                        .as("the sidecar's own bytes are shorter than the object's; a stat reading "
                            + "the sidecar reports its length instead")
                        .isEqualTo(NEIGHBOUR.length);
                assertThat(store.stat(ref("photo")).orElseThrow().contentType())
                        .isEqualTo("image/png");
            });
        }

        @Test
        @DisplayName("deleting 'photo' does not delete 'photo.ctype'")
        void deleteDoesNotRemoveTheNeighbour() {
            asTenant(() -> {
                upload(ref("photo.ctype"), NEIGHBOUR, null);
                upload(ref("photo"), SUBJECT, "image/png");

                assertThat(store.delete(ref("photo"))).isTrue();

                assertThat(store.stat(ref("photo.ctype")))
                        .as("delete removed the object and its sidecar, and the sidecar's path was "
                            + "the neighbour — one delete call, two objects gone")
                        .isPresent();
                assertThat(download(ref("photo.ctype"))).isEqualTo(NEIGHBOUR);
            });
        }
    }

    @Nested
    @DisplayName("the recorded content type follows the object it belongs to")
    class ContentTypeLifecycle {

        @Test
        @DisplayName("overwriting a typed object with the default type clears the recorded type")
        void defaultTypeOverwriteClearsTheRecord() {
            asTenant(() -> {
                upload(ref("sheet"), NEIGHBOUR, "text/csv");
                upload(ref("sheet"), SUBJECT, null);

                assertThat(store.stat(ref("sheet")).orElseThrow().contentType())
                        .as("the default is represented by the ABSENCE of a sidecar, so recording it "
                            + "means removing the previous one — skipping the write left the old "
                            + "type describing the new bytes")
                        .isEqualTo(BlobMetadata.DEFAULT_CONTENT_TYPE);
            });
        }

        @Test
        @DisplayName("a re-created object does not inherit the deleted one's type")
        void deleteClearsTheRecordedType() {
            asTenant(() -> {
                upload(ref("sheet"), NEIGHBOUR, "text/csv");
                store.delete(ref("sheet"));
                upload(ref("sheet"), SUBJECT, null);

                assertThat(store.stat(ref("sheet")).orElseThrow().contentType())
                        .isEqualTo(BlobMetadata.DEFAULT_CONTENT_TYPE);
            });
        }
    }

    @Nested
    @DisplayName("concurrent uploads to one key do not share a file")
    class ConcurrentUploads {

        @Test
        @DisplayName("a second upload opened mid-transfer does not truncate the first")
        void secondUploadDoesNotTruncateTheFirst() {
            asTenant(() -> {
                BlobRef ref = ref("contended");
                try (BlobUploadHandle first = store.beginUpload(ref, NEIGHBOUR.length, null)) {
                    writeAll(first, NEIGHBOUR);
                    // Opened while the first is still in flight. Both staging paths were derived
                    // from the key, so this open — CREATE + TRUNCATE_EXISTING — emptied the file the
                    // first had already written, and its commit then moved the truncated result into
                    // place. The length check passes either way: it counts bytes handed to write(),
                    // not bytes on disk.
                    store.beginUpload(ref, SUBJECT.length, null).close();
                    first.commit();
                }

                assertThat(download(ref)).isEqualTo(NEIGHBOUR);
            });
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static BlobRef ref(String key) {
        return new BlobRef(CONTAINER, key);
    }

    private void asTenant(Runnable body) {
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.shared(TENANT))
                .run(body);
    }

    private void upload(BlobRef ref, byte[] payload, String contentType) {
        try (BlobUploadHandle handle = store.beginUpload(ref, payload.length, contentType)) {
            writeAll(handle, payload);
            handle.commit();
        }
    }

    private void writeAll(BlobUploadHandle handle, byte[] payload) {
        try (LoanedBuffer buffer = allocator.allocateInfrastructure(BUFFER_BYTES)) {
            MemorySegment.copy(
                    payload, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, payload.length);
            handle.write(buffer.segment(), payload.length);
        }
    }

    private byte[] download(BlobRef ref) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (BlobDownloadHandle handle = store.openDownload(ref);
             LoanedBuffer buffer = allocator.allocateInfrastructure(BUFFER_BYTES)) {
            int read;
            while ((read = handle.read(buffer.segment(), BUFFER_BYTES)) > 0) {
                byte[] chunk = new byte[read];
                MemorySegment.copy(buffer.segment(), ValueLayout.JAVA_BYTE, 0, chunk, 0, read);
                sink.write(chunk, 0, read);
            }
        }
        return sink.toByteArray();
    }
}
