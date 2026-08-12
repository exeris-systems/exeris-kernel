/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.storage;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.storage.blob.BlobAccess;
import eu.exeris.kernel.spi.storage.blob.BlobDownloadHandle;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;
import eu.exeris.kernel.spi.storage.blob.BlobRange;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import eu.exeris.kernel.spi.storage.blob.BlobUploadHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: executable contract for {@link BlobStore} bindings (ADR-056).
 *
 * <p>A binding supplies a configured store and a {@link MemoryProvider}. Every transfer in this suite
 * runs through a {@code PARANOID} allocator, so a store that retains or releases a caller's buffer is
 * caught here rather than in production.
 *
 * <h2>What this suite is really pinning</h2>
 * <p>Round-trip fidelity is the easy half. The load-bearing cases are the ones a driver passes only by
 * honouring the contract's fail-closed rules: a store that ignores the ambient
 * {@link StorageContext} passes every happy path and fails {@link Isolation}; a store that publishes
 * bytes before commit passes every happy path and fails {@link UploadVisibility}; a store that signs
 * for some inputs and not others passes a naive signed-URL test and fails {@link SignedUrlContract}.
 *
 * @since 0.11.0
 */
public abstract class AbstractBlobStorageTck {

    private static final String CONTAINER = "documents";
    private static final String TENANT_A = "tenant-alpha";
    private static final String TENANT_B = "tenant-beta";
    private static final int BUFFER_BYTES = 64 * 1024;

    // =========================================================================
    // Template methods
    // =========================================================================

    /** Creates the {@link BlobStore} under test. */
    protected abstract BlobStore createStore();

    /** Supplies the {@link MemoryProvider} whose PARANOID allocator drives every transfer here. */
    protected abstract MemoryProvider createMemoryProvider();

    /**
     * Whether this store is expected to produce signed URLs.
     *
     * <p>Default {@code false} — a store that cannot sign is a first-class outcome (ADR-056 §7), not a
     * degraded one. {@link SignedUrlContract} asserts the answer is <em>uniform</em> either way, which
     * is the property a caller actually needs.
     */
    protected boolean supportsSignedUrls() {
        return false;
    }

    private BlobStore store;
    private MemoryAllocator allocator;

    @BeforeEach
    final void setUpStoreAndAllocator() {
        store = createStore();
        allocator = createMemoryProvider().createAllocator(
                MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.PARANOID));
    }

    @AfterEach
    final void tearDownStoreAndAllocator() {
        closeQuietly(store);
        closeQuietly(allocator);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception _) {
            // Post-test cleanup: a failure here must not mask the assertion that already ran.
        }
    }

    // =========================================================================
    // Round trip
    // =========================================================================

    @Nested
    @DisplayName("Upload and download round-trip")
    class RoundTrip {

        @Test
        @DisplayName("bytes written are the bytes read back")
        void roundTripPreservesContent() {
            byte[] payload = payloadOf(4096);
            BlobRef ref = new BlobRef(CONTAINER, "round-trip.bin");

            asTenant(TENANT_A, () -> {
                upload(ref, payload, "application/octet-stream");
                assertThat(download(ref)).isEqualTo(payload);
            });
        }

        @Test
        @DisplayName("a transfer larger than one buffer streams across reads")
        void multiChunkTransfer() {
            // Larger than the buffer, so both write and read loop — a driver that silently handles only
            // the single-chunk case fails here and nowhere else.
            byte[] payload = payloadOf(BUFFER_BYTES * 2 + 1234);
            BlobRef ref = new BlobRef(CONTAINER, "large.bin");

            asTenant(TENANT_A, () -> {
                upload(ref, payload, null);
                assertThat(download(ref)).isEqualTo(payload);
            });
        }

        @Test
        @DisplayName("stat reports the stored size and declared content type")
        void statReportsMetadata() {
            byte[] payload = payloadOf(777);
            BlobRef ref = new BlobRef(CONTAINER, "described.bin");

            asTenant(TENANT_A, () -> {
                upload(ref, payload, "image/png");
                Optional<BlobMetadata> found = store.stat(ref);
                assertThat(found).isPresent();
                assertThat(found.get().sizeBytes()).isEqualTo(payload.length);
                assertThat(found.get().contentType()).isEqualTo("image/png");
            });
        }

        @Test
        @DisplayName("stat is empty and download denies for an object that does not exist")
        void missingObject() {
            BlobRef ref = new BlobRef(CONTAINER, "absent.bin");

            asTenant(TENANT_A, () -> {
                assertThat(store.stat(ref)).isEmpty();
                assertThatThrownBy(() -> store.openDownload(ref))
                        .isInstanceOfSatisfying(BlobStorageException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_BLOB_8001));
            });
        }

        @Test
        @DisplayName("delete reports whether anything was removed, and is safe to repeat")
        void deleteIsIdempotent() {
            BlobRef ref = new BlobRef(CONTAINER, "transient.bin");

            asTenant(TENANT_A, () -> {
                upload(ref, payloadOf(16), null);
                assertThat(store.delete(ref)).isTrue();
                assertThat(store.delete(ref))
                        .as("deleting an absent object is not an error, so a retried delete is safe")
                        .isFalse();
                assertThat(store.stat(ref)).isEmpty();
            });
        }
    }

    // =========================================================================
    // Ranged reads
    // =========================================================================

    @Nested
    @DisplayName("Ranged download")
    class RangedRead {

        @Test
        @DisplayName("a range returns exactly its bytes")
        void rangeReturnsSlice() {
            byte[] payload = payloadOf(1000);
            BlobRef ref = new BlobRef(CONTAINER, "ranged.bin");

            asTenant(TENANT_A, () -> {
                upload(ref, payload, null);
                byte[] slice = downloadRange(ref, new BlobRange(100, 250));
                assertThat(slice).hasSize(250);
                assertThat(slice).isEqualTo(java.util.Arrays.copyOfRange(payload, 100, 350));
            });
        }

        @Test
        @DisplayName("metadata reports the whole object size, not the range length")
        void metadataReportsWholeObject() {
            byte[] payload = payloadOf(1000);
            BlobRef ref = new BlobRef(CONTAINER, "ranged-meta.bin");

            asTenant(TENANT_A, () -> {
                upload(ref, payload, null);
                try (BlobDownloadHandle handle = store.openDownload(ref, new BlobRange(10, 20))) {
                    assertThat(handle.metadata().sizeBytes())
                            .as("a caller tracking progress needs the total; it already knows the "
                                    + "range it asked for")
                            .isEqualTo(payload.length);
                }
            });
        }

        @Test
        @DisplayName("a range past the end truncates, and one at the end is an immediate EOF")
        void rangeBeyondEndDoesNotThrow() {
            byte[] payload = payloadOf(100);
            BlobRef ref = new BlobRef(CONTAINER, "ranged-tail.bin");

            asTenant(TENANT_A, () -> {
                upload(ref, payload, null);
                assertThat(downloadRange(ref, new BlobRange(80, 500)))
                        .as("a caller walking an object with a fixed window must not need the size to "
                                + "avoid an exception on its final window")
                        .hasSize(20);
                assertThat(downloadRange(ref, new BlobRange(100, 10)))
                        .as("a range starting at the end yields no bytes rather than failing")
                        .isEmpty();
            });
        }
    }

    // =========================================================================
    // Upload visibility
    // =========================================================================

    @Nested
    @DisplayName("Upload visibility (ADR-056 §3)")
    class UploadVisibility {

        @Test
        @DisplayName("nothing is visible until commit")
        void notVisibleBeforeCommit() {
            BlobRef ref = new BlobRef(CONTAINER, "staged.bin");

            asTenant(TENANT_A, () -> {
                try (BlobUploadHandle upload = store.beginUpload(ref, 32, null);
                     LoanedBuffer buffer = allocator.allocateInfrastructure(32)) {
                    fill(buffer.segment(), payloadOf(32));
                    upload.write(buffer.segment(), 32);

                    assertThat(store.stat(ref))
                            .as("an interrupted transfer must never be mistakable for a complete "
                                    + "object, so written-but-uncommitted bytes are not visible")
                            .isEmpty();

                    upload.commit();
                }
                assertThat(store.stat(ref)).isPresent();
            });
        }

        @Test
        @DisplayName("closing without commit aborts and leaves nothing behind")
        void abortLeavesNothing() {
            BlobRef ref = new BlobRef(CONTAINER, "aborted.bin");

            asTenant(TENANT_A, () -> {
                try (BlobUploadHandle upload = store.beginUpload(ref, 32, null);
                     LoanedBuffer buffer = allocator.allocateInfrastructure(32)) {
                    fill(buffer.segment(), payloadOf(32));
                    upload.write(buffer.segment(), 32);
                }
                assertThat(store.stat(ref)).isEmpty();
            });
        }

        @Test
        @DisplayName("committing fewer bytes than declared is a contract violation")
        void shortUploadIsRejected() {
            BlobRef ref = new BlobRef(CONTAINER, "short.bin");

            asTenant(TENANT_A, () -> {
                try (BlobUploadHandle upload = store.beginUpload(ref, 64, null);
                     LoanedBuffer buffer = allocator.allocateInfrastructure(32)) {
                    fill(buffer.segment(), payloadOf(32));
                    upload.write(buffer.segment(), 32);

                    assertThatThrownBy(upload::commit)
                            .as("a truncated transfer that commits silently is indistinguishable "
                                    + "from a complete one for every later reader")
                            .isInstanceOfSatisfying(BlobStorageException.class, ex ->
                                    assertThat(ex.errorCode())
                                            .isEqualTo(KernelErrorCodes.EX_BLOB_8004));
                }
                assertThat(store.stat(ref)).isEmpty();
            });
        }

        @Test
        @DisplayName("writing more than declared is a contract violation")
        void overlongUploadIsRejected() {
            BlobRef ref = new BlobRef(CONTAINER, "overlong.bin");

            asTenant(TENANT_A, () -> {
                try (BlobUploadHandle upload = store.beginUpload(ref, 16, null);
                     LoanedBuffer buffer = allocator.allocateInfrastructure(32)) {
                    fill(buffer.segment(), payloadOf(32));
                    assertThatThrownBy(() -> upload.write(buffer.segment(), 32))
                            .isInstanceOfSatisfying(BlobStorageException.class, ex ->
                                    assertThat(ex.errorCode())
                                            .isEqualTo(KernelErrorCodes.EX_BLOB_8004));
                }
            });
        }

        @Test
        @DisplayName("a zero-length write is a no-op, not an error")
        void zeroLengthWriteIsNoOp() {
            BlobRef ref = new BlobRef(CONTAINER, "empty-write.bin");

            asTenant(TENANT_A, () -> {
                try (BlobUploadHandle upload = store.beginUpload(ref, 0, null);
                     LoanedBuffer buffer = allocator.allocateInfrastructure(32)) {
                    upload.write(buffer.segment(), 0);
                    assertThat(upload.commit().sizeBytes())
                            .as("a drained producer loop must not need a special case")
                            .isZero();
                }
            });
        }
    }

    // =========================================================================
    // Isolation — the reason this is a kernel seam
    // =========================================================================

    @Nested
    @DisplayName("Tenant isolation (ADR-056 §4/§5)")
    class Isolation {

        @Test
        @DisplayName("the identical reference addresses different objects for different tenants")
        void sameRefIsDistinctPerTenant() {
            BlobRef ref = new BlobRef(CONTAINER, "shared-name.bin");
            byte[] alpha = payloadOf(64);
            byte[] beta = payloadOf(128);

            asTenant(TENANT_A, () -> upload(ref, alpha, null));
            asTenant(TENANT_B, () -> upload(ref, beta, null));

            asTenant(TENANT_A, () -> assertThat(download(ref))
                    .as("BlobRef is tenant-relative — resolution happens against the ambient "
                            + "StorageContext, so neither tenant can name the other's object")
                    .isEqualTo(alpha));
            asTenant(TENANT_B, () -> assertThat(download(ref)).isEqualTo(beta));
        }

        @Test
        @DisplayName("one tenant's delete does not touch another's object of the same name")
        void deleteIsScoped() {
            BlobRef ref = new BlobRef(CONTAINER, "collide.bin");

            asTenant(TENANT_A, () -> upload(ref, payloadOf(16), null));
            asTenant(TENANT_B, () -> upload(ref, payloadOf(16), null));
            asTenant(TENANT_B, () -> assertThat(store.delete(ref)).isTrue());

            asTenant(TENANT_A, () -> assertThat(store.stat(ref))
                    .as("a scoped delete must not reach across the namespace boundary")
                    .isPresent());
        }

        @Test
        @DisplayName("an unscoped context denies every operation rather than resolving unscoped")
        void systemScopeDenies() {
            BlobRef ref = new BlobRef(CONTAINER, "system.bin");

            asSystem(() -> {
                assertDeniedForIsolation(() -> store.stat(ref));
                assertDeniedForIsolation(() -> store.delete(ref));
                assertDeniedForIsolation(() -> store.openDownload(ref));
                assertDeniedForIsolation(() -> store.beginUpload(ref, 4, null));
                assertDeniedForIsolation(() -> store.signedUrl(ref, BlobAccess.READ, Duration.ofMinutes(5)));
            });
        }

        private void assertDeniedForIsolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
            assertThatThrownBy(call)
                    .as("ADR-056 §5 — with no isolation key there is no namespace to resolve into, and "
                            + "falling back to the store root would place a tenant's object where "
                            + "every tenant can reach it")
                    .isInstanceOfSatisfying(BlobStorageException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_BLOB_8002));
        }
    }

    // =========================================================================
    // Signed URLs — a capability, asserted as a disjunction
    // =========================================================================

    @Nested
    @DisplayName("Signed-URL contract (ADR-056 §7)")
    class SignedUrlContract {

        @Test
        @DisplayName("the store's answer is uniform — it signs for every input or for none")
        void answerIsUniform() {
            BlobRef existing = new BlobRef(CONTAINER, "signed-existing.bin");
            BlobRef absent = new BlobRef(CONTAINER, "signed-absent.bin");

            asTenant(TENANT_A, () -> {
                upload(existing, payloadOf(8), null);

                Optional<URI> forExisting =
                        store.signedUrl(existing, BlobAccess.READ, Duration.ofMinutes(5));
                Optional<URI> forAbsent =
                        store.signedUrl(absent, BlobAccess.READ, Duration.ofMinutes(5));
                Optional<URI> forWrite =
                        store.signedUrl(existing, BlobAccess.WRITE, Duration.ofMinutes(5));

                assertThat(forExisting.isPresent())
                        .as("a store that sometimes returns a URL cannot be programmed against — the "
                                + "caller could not tell an unsupported operation from a missing "
                                + "object")
                        .isEqualTo(supportsSignedUrls());
                assertThat(forAbsent.isPresent()).isEqualTo(supportsSignedUrls());
                assertThat(forWrite.isPresent()).isEqualTo(supportsSignedUrls());
            });
        }

        @Test
        @DisplayName("a non-positive time-to-live is rejected")
        void nonPositiveTtlRejected() {
            BlobRef ref = new BlobRef(CONTAINER, "signed-ttl.bin");

            asTenant(TENANT_A, () -> assertThatThrownBy(
                    () -> store.signedUrl(ref, BlobAccess.READ, Duration.ZERO))
                    .as("an already-expired grant is a caller error, not a valid request")
                    .isInstanceOf(IllegalArgumentException.class));
        }

        @Test
        @DisplayName("a sub-second time-to-live is rejected, signing store or not")
        void subSecondTtlRejected() {
            BlobRef ref = new BlobRef(CONTAINER, "signed-subsecond.bin");

            // Expiry is expressed in whole seconds by the signing schemes, so a sub-second request
            // has two possible outcomes and both break the promise above: truncating to zero returns
            // an already-expired URL, rounding up grants longer than asked. Anchored here rather than
            // in one driver because the harm from divergence is silent — the same call that a strict
            // store rejects would be quietly over-granted by a lax one, and a deployment moving
            // between them would never see an error.
            asTenant(TENANT_A, () -> assertThatThrownBy(
                    () -> store.signedUrl(ref, BlobAccess.READ, Duration.ofMillis(500)))
                    .as("the floor is BlobStorageConfig.MIN_SIGNED_URL_TTL, and it binds every store "
                            + "— a store that cannot sign still validates its arguments")
                    .isInstanceOf(IllegalArgumentException.class));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void upload(BlobRef ref, byte[] payload, String contentType) {
        try (BlobUploadHandle handle = store.beginUpload(ref, payload.length, contentType);
             LoanedBuffer buffer = allocator.allocateInfrastructure(BUFFER_BYTES)) {
            int offset = 0;
            while (offset < payload.length) {
                int chunk = Math.min(BUFFER_BYTES, payload.length - offset);
                MemorySegment.copy(payload, offset, buffer.segment(), ValueLayout.JAVA_BYTE, 0, chunk);
                handle.write(buffer.segment(), chunk);
                offset += chunk;
            }
            handle.commit();
        }
    }

    private byte[] download(BlobRef ref) {
        try (BlobDownloadHandle handle = store.openDownload(ref)) {
            return drain(handle);
        }
    }

    private byte[] downloadRange(BlobRef ref, BlobRange range) {
        try (BlobDownloadHandle handle = store.openDownload(ref, range)) {
            return drain(handle);
        }
    }

    private byte[] drain(BlobDownloadHandle handle) {
        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        try (LoanedBuffer buffer = allocator.allocateInfrastructure(BUFFER_BYTES)) {
            int read;
            while ((read = handle.read(buffer.segment(), BUFFER_BYTES)) > 0) {
                byte[] chunk = new byte[read];
                MemorySegment.copy(buffer.segment(), ValueLayout.JAVA_BYTE, 0, chunk, 0, read);
                sink.write(chunk, 0, read);
            }
        }
        return sink.toByteArray();
    }

    private static void fill(MemorySegment segment, byte[] payload) {
        MemorySegment.copy(payload, 0, segment, ValueLayout.JAVA_BYTE, 0, payload.length);
    }

    /** Deterministic, non-uniform bytes — a repeated constant would hide an offset error. */
    private static byte[] payloadOf(int size) {
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return payload;
    }

    private static void asTenant(String tenantId, Runnable body) {
        run(ImmutableStorageContext.shared(tenantId), body);
    }

    private static void asSystem(Runnable body) {
        run(ImmutableStorageContext.GLOBAL, body);
    }

    private static void run(StorageContext context, Runnable body) {
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, context).run(body);
    }
}
