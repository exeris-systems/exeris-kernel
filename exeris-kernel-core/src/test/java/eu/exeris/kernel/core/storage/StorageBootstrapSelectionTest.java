/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.storage;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStorageProvider;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which blob driver boots is decided by a configured id, not by ranking.
 *
 * <p>Every sibling bootstrap ranks by {@code priority()}. This one cannot: the two Community
 * providers register at the same priority and are not interchangeable — one needs a writable
 * directory, the other credentials and an endpoint — so ServiceLoader order and a class-name
 * tie-break would decide where a tenant's objects land.
 *
 * <p>The cases come in pairs on purpose. A suite that only proved one id wins would pass against an
 * implementation that returns the first element of the list and never reads the id at all.
 */
@DisplayName("Storage bootstrap: the id chooses, not the order")
class StorageBootstrapSelectionTest {

    private static final String FS = "blob-fs-like";
    private static final String S3 = "blob-s3-like";

    @Nested
    @DisplayName("Selection")
    class Selection {

        @Test
        @DisplayName("the first id wins when it is the one configured")
        void firstIdWins() {
            assertThat(StorageBootstrap.select(both(), FS).providerId()).isEqualTo(FS);
        }

        @Test
        @DisplayName("the second id wins when it is the one configured — the discriminating half")
        void secondIdWins() {
            // Without this case the suite passes against `discovered.get(0)`, which reads no id at
            // all and is exactly the defect the key exists to prevent.
            assertThat(StorageBootstrap.select(both(), S3).providerId()).isEqualTo(S3);
        }

        @Test
        @DisplayName("order in the list does not decide — the same id wins from either position")
        void orderDoesNotDecide() {
            assertThat(StorageBootstrap.select(List.of(provider(FS), provider(S3)), S3).providerId())
                    .isEqualTo(S3);
            assertThat(StorageBootstrap.select(List.of(provider(S3), provider(FS)), S3).providerId())
                    .isEqualTo(S3);
        }
    }

    @Nested
    @DisplayName("Refusals — and a refusal that does not name the key cannot be acted on")
    class Refusals {

        @Test
        @DisplayName("an id matching nothing is refused, carrying the key and what was available")
        void unmatchedIdIsRefused() {
            assertThatThrownBy(() -> StorageBootstrap.select(both(), "blob-nope"))
                    .isInstanceOf(BlobStorageException.class)
                    .extracting(thrown -> ((BlobStorageException) thrown).errorCode())
                    .isEqualTo(KernelErrorCodes.EX_BLOB_8008);

            assertThatThrownBy(() -> StorageBootstrap.select(both(), "blob-nope"))
                    .extracting(thrown -> ((BlobStorageException) thrown).rawArgs())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.ARRAY)
                    .as("the key an operator must set, the value they set, and the ids they may "
                            + "choose between — a refusal missing any of the three costs a search")
                    .containsExactly(StorageBootstrap.PROVIDER_KEY, "blob-nope", FS + ", " + S3);
        }

        @Test
        @DisplayName("an empty classpath is a different code, because it is a different fix")
        void noProviderIsItsOwnCode() {
            // EX-BLOB-8007, not 8008: nothing is ambiguous, there is nothing to choose from, and the
            // remedy is a dependency rather than a key.
            assertThatThrownBy(() -> StorageBootstrap.select(List.of(), FS))
                    .isInstanceOf(BlobStorageException.class)
                    .extracting(thrown -> ((BlobStorageException) thrown).errorCode())
                    .isEqualTo(KernelErrorCodes.EX_BLOB_8007);
        }
    }

    private static List<BlobStorageProvider> both() {
        return List.of(provider(FS), provider(S3));
    }

    private static BlobStorageProvider provider(String id) {
        return new StubProvider(id);
    }

    /** A provider that is nothing but an identity — selection reads no other part of it. */
    private record StubProvider(String id) implements BlobStorageProvider {

        @Override
        public String providerId() {
            return id;
        }

        @Override
        public String providerName() {
            return "Stub/" + id;
        }

        @Override
        public BlobStore createStore(BlobStorageConfig config) {
            throw new UnsupportedOperationException("selection never creates a store");
        }
    }
}
