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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deferral itself, against a recording provider rather than a driver.
 *
 * <p>What matters here is <em>when</em> the store is created, and a real driver cannot show it: the
 * class exists because {@code BLOB_STORE} is published before {@code MEMORY_ALLOCATOR} is bound, so
 * creating the store at construction would fail for the S3 driver. A counting provider is what makes
 * "not yet" observable.
 */
@DisplayName("DeferredBlobStore")
class DeferredBlobStoreTest {

    private static final BlobStorageConfig CONFIG = BlobStorageConfig.atLocation("/unused");
    private static final BlobRef REF = new BlobRef("reports", "q3.pdf");
    private static final BlobRange RANGE = new BlobRange(4, 8);
    private static final Duration TTL = Duration.ofMinutes(5);

    private final AtomicInteger created = new AtomicInteger();
    private final RecordingStore recording = new RecordingStore();
    private final BlobStorageProvider provider = new RecordingProvider(created, recording);

    @Nested
    @DisplayName("Before start")
    class BeforeStart {

        @Test
        @DisplayName("no store is created, which is the entire point of the class")
        void creationIsDeferred() {
            new DeferredBlobStore(provider, CONFIG);

            assertThat(created.get())
                    .as("creating at construction is what reads MEMORY_ALLOCATOR before it is bound")
                    .isZero();
        }

        @Test
        @DisplayName("every operation is refused, and none creates a store on the way")
        void operationsAreRefused() {
            DeferredBlobStore store = new DeferredBlobStore(provider, CONFIG);

            for (Consumer<BlobStore> operation : operations()) {
                assertThatThrownBy(() -> operation.accept(store))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("not started");
            }
            assertThat(created.get())
                    .as("an operation must not create the store lazily: a driver refusing its "
                            + "configuration would then refuse on first use instead of at boot")
                    .isZero();
        }

        @Test
        @DisplayName("close on a store that never started is quiet, and start after it is refused")
        void closeBeforeStart() {
            DeferredBlobStore store = new DeferredBlobStore(provider, CONFIG);

            assertThatCode(store::close).doesNotThrowAnyException();
            assertThatThrownBy(store::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
            assertThat(created.get()).isZero();
        }
    }

    @Nested
    @DisplayName("Across start")
    class AcrossStart {

        @Test
        @DisplayName("start creates the store through the provider, with the configuration it was given")
        void startCreatesTheStore() {
            DeferredBlobStore store = new DeferredBlobStore(provider, CONFIG);

            store.start();

            assertThat(created.get()).isEqualTo(1);
            assertThat(recording.config).isSameAs(CONFIG);
        }

        @Test
        @DisplayName("starting twice is refused, and creates one store, not two")
        void secondStartIsRefused() {
            DeferredBlobStore store = new DeferredBlobStore(provider, CONFIG);
            store.start();

            assertThatThrownBy(store::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already started");
            assertThat(created.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("a provider's refusal propagates out of start, and the store stays unstarted")
        void creationFailurePropagates() {
            IllegalArgumentException refusal = new IllegalArgumentException("required property missing");
            DeferredBlobStore store = new DeferredBlobStore(new RefusingProvider(refusal), CONFIG);

            assertThatThrownBy(store::start).isSameAs(refusal);
            assertThatThrownBy(() -> store.stat(REF))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not started");
        }

        @Test
        @DisplayName("each operation reaches the created store as itself, with its own arguments")
        void operationsDelegate() {
            DeferredBlobStore store = new DeferredBlobStore(provider, CONFIG);
            store.start();

            for (Consumer<BlobStore> operation : operations()) {
                operation.accept(store);
            }

            assertThat(recording.calls)
                    .as("openDownload(ref) and openDownload(ref, range) are separate methods; routing "
                            + "one to the other would turn a ranged read into a whole-object read")
                    .containsExactly(
                            "beginUpload " + REF + " 12 text/plain",
                            "openDownload " + REF,
                            "openDownload " + REF + " " + RANGE,
                            "stat " + REF,
                            "delete " + REF,
                            "signedUrl " + REF + " WRITE " + TTL);
        }

        @Test
        @DisplayName("results come back from the created store unchanged")
        void resultsAreTheStores() {
            DeferredBlobStore store = new DeferredBlobStore(provider, CONFIG);
            store.start();

            assertThat(store.stat(REF)).isSameAs(RecordingStore.STAT);
            assertThat(store.signedUrl(REF, BlobAccess.READ, TTL)).isSameAs(RecordingStore.URL);
            assertThat(store.delete(REF)).isTrue();
        }
    }

    @Nested
    @DisplayName("After close")
    class AfterClose {

        @Test
        @DisplayName("close releases the created store exactly once")
        void closeIsIdempotent() {
            DeferredBlobStore store = new DeferredBlobStore(provider, CONFIG);
            store.start();

            store.close();
            store.close();

            assertThat(recording.closes)
                    .as("a second close must not reach the store")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("operations and a restart are refused rather than reaching a closed store")
        void closedStoreRefuses() {
            DeferredBlobStore store = new DeferredBlobStore(provider, CONFIG);
            store.start();
            store.close();

            for (Consumer<BlobStore> operation : operations()) {
                assertThatThrownBy(() -> operation.accept(store))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("closed");
            }
            assertThatThrownBy(store::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
            assertThat(recording.calls).isEmpty();
            assertThat(created.get()).isEqualTo(1);
        }
    }

    /** One call of every {@link BlobStore} operation, in declaration order. */
    private static List<Consumer<BlobStore>> operations() {
        return List.of(
                store -> store.beginUpload(REF, 12, "text/plain"),
                store -> store.openDownload(REF),
                store -> store.openDownload(REF, RANGE),
                store -> store.stat(REF),
                store -> store.delete(REF),
                store -> store.signedUrl(REF, BlobAccess.WRITE, TTL));
    }

    private record RecordingProvider(AtomicInteger created, RecordingStore store)
            implements BlobStorageProvider {

        @Override
        public String providerId() {
            return "recording";
        }

        @Override
        public String providerName() {
            return "Recording/Test";
        }

        @Override
        public BlobStore createStore(BlobStorageConfig config) {
            created.incrementAndGet();
            store.config = config;
            return store;
        }
    }

    private record RefusingProvider(RuntimeException refusal) implements BlobStorageProvider {

        @Override
        public String providerId() {
            return "refusing";
        }

        @Override
        public String providerName() {
            return "Refusing/Test";
        }

        @Override
        public BlobStore createStore(BlobStorageConfig config) {
            throw refusal;
        }
    }

    /**
     * Records each call with its arguments. The handle-returning operations answer {@code null}: this
     * store is only ever reached through the class under test, which passes the answer through.
     */
    private static final class RecordingStore implements BlobStore {

        private static final Optional<BlobMetadata> STAT =
                Optional.of(new BlobMetadata(REF, 3, "text/plain"));
        private static final Optional<URI> URL = Optional.of(URI.create("http://127.0.0.1/signed"));

        private final List<String> calls = new ArrayList<>();
        private BlobStorageConfig config;
        private int closes;

        @Override
        public BlobUploadHandle beginUpload(BlobRef ref, long contentLength, String contentType) {
            calls.add("beginUpload " + ref + " " + contentLength + " " + contentType);
            return null;
        }

        @Override
        public BlobDownloadHandle openDownload(BlobRef ref) {
            calls.add("openDownload " + ref);
            return null;
        }

        @Override
        public BlobDownloadHandle openDownload(BlobRef ref, BlobRange range) {
            calls.add("openDownload " + ref + " " + range);
            return null;
        }

        @Override
        public Optional<BlobMetadata> stat(BlobRef ref) {
            calls.add("stat " + ref);
            return STAT;
        }

        @Override
        public boolean delete(BlobRef ref) {
            calls.add("delete " + ref);
            return true;
        }

        @Override
        public Optional<URI> signedUrl(BlobRef ref, BlobAccess access, Duration ttl) {
            calls.add("signedUrl " + ref + " " + access + " " + ttl);
            return URL;
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
