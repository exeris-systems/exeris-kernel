/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where an S3 object key lands, and what it looks like on the wire.
 *
 * <p>The integration gate proves a round trip works; it cannot prove <em>why</em> two tenants stay
 * apart, because a driver that concatenated the isolation key verbatim would round-trip just as
 * happily. These cases pin the properties the separation rests on — an opaque, injective tenant
 * segment, and an encoding that cannot smuggle structure into a request target.
 *
 * @since 0.11.0
 */
@DisplayName("Community S3: object-key layout")
class CommunityS3ObjectKeyTest {

    private static final String CONTAINER = "documents";

    private static String asTenant(String tenant, BlobRef ref) {
        return ScopedValue.where(KernelProviders.STORAGE_CONTEXT,
                        ImmutableStorageContext.shared(tenant))
                .call(() -> CommunityS3ObjectKey.resolve(ref, CommunityBlobFailures.OP_STAT));
    }

    @Nested
    @DisplayName("Tenant separation")
    class TenantSeparation {

        @Test
        @DisplayName("the same reference resolves to a different key for a different tenant")
        void perTenantKey() {
            BlobRef ref = new BlobRef(CONTAINER, "report.pdf");

            assertThat(asTenant("tenant-alpha", ref))
                    .as("if one reference produced one key, the bucket would be a shared namespace")
                    .isNotEqualTo(asTenant("tenant-beta", ref));
        }

        @Test
        @DisplayName("the tenant segment is hex, so a key carrying separators cannot become structure")
        void hostileKeyStaysOneSegment() {
            BlobRef ref = new BlobRef(CONTAINER, "report.pdf");

            String resolved = asTenant("../../escape", ref);

            assertThat(resolved)
                    .as("a verbatim tenant segment would let a claim value re-point the prefix")
                    .doesNotContain("..")
                    .startsWith("t-")
                    .isEqualTo("t-2e2e2f2e2e2f657363617065/" + CONTAINER + "/report.pdf");
        }

        @Test
        @DisplayName("distinct tenants cannot collide, even ones differing only in case")
        void encodingIsInjective() {
            BlobRef ref = new BlobRef(CONTAINER, "report.pdf");

            assertThat(asTenant("Tenant", ref))
                    .as("hex is injective; a case-folding store would otherwise merge these two")
                    .isNotEqualTo(asTenant("tenant", ref));
        }

        @Test
        @DisplayName("an unscoped context is denied rather than resolved to the bucket root")
        void unscopedDenied() {
            BlobRef ref = new BlobRef(CONTAINER, "report.pdf");

            assertThatThrownBy(() -> ScopedValue.where(KernelProviders.STORAGE_CONTEXT,
                            ImmutableStorageContext.GLOBAL)
                    .call(() -> CommunityS3ObjectKey.resolve(ref, CommunityBlobFailures.OP_STAT)))
                    .as("ADR-056 §5 — with no isolation key there is no namespace, and the bucket root "
                            + "is reachable by every tenant")
                    .isInstanceOfSatisfying(BlobStorageException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_BLOB_8002));
        }
    }

    @Nested
    @DisplayName("Percent-encoding")
    class PercentEncoding {

        @Test
        @DisplayName("separators stay, everything reserved is escaped in uppercase hex")
        void encodesReservedCharacters() {
            assertThat(CommunityS3ObjectKey.encode("t-61/docs/a b+c.txt"))
                    .as("SigV4 canonicalisation compares byte for byte, and it expects uppercase "
                            + "escapes")
                    .isEqualTo("t-61/docs/a%20b%2Bc.txt");
        }

        @Test
        @DisplayName("a space is not encoded as plus, and a star is not left bare")
        void avoidsFormEncoding() {
            // The two ways URLEncoder differs from RFC 3986, and both produce a rejected signature.
            assertThat(CommunityS3ObjectKey.encode("a b")).isEqualTo("a%20b");
            assertThat(CommunityS3ObjectKey.encode("a*b")).isEqualTo("a%2Ab");
        }

        @Test
        @DisplayName("non-ASCII is encoded per UTF-8 byte")
        void encodesUtf8ByBytes() {
            assertThat(CommunityS3ObjectKey.encode("zażółć")).isEqualTo("za%C5%BC%C3%B3%C5%82%C4%87");
        }

        @Test
        @DisplayName("a query component escapes the separator too, because there it is data")
        void queryComponentEscapesSeparator() {
            assertThat(CommunityS3ObjectKey.encodeQueryComponent("key/20260801/us-east-1"))
                    .isEqualTo("key%2F20260801%2Fus-east-1");
        }
    }
}
