/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import eu.exeris.kernel.tck.contract.storage.AbstractBlobStorageTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * TCK: the S3-compatible driver against a real MinIO endpoint.
 *
 * <p>The second binding of {@link AbstractBlobStorageTck}, and the point of the exercise. A contract
 * with one binding describes that binding; the filesystem driver could satisfy any rule written after
 * looking at it. Running the same suite against an object store — different addressing, different
 * failure statuses, different notion of a directory — is what makes those rules statements about blob
 * storage.
 *
 * <p>Against a live server rather than a stub, because the things most likely to be wrong here are the
 * things a stub would be written to accept: a SigV4 signature that does not verify, a percent-encoded
 * key that addresses a different object, a {@code Range} header off by one. MinIO is the oracle for all
 * three.
 *
 * <p>{@code @Tag("integration")}, so it runs in the community integration gate rather than in the
 * default build — the same gate that already carries the Keycloak and Postgres suites.
 *
 * @since 0.11.0
 */
@Tag("integration")
@Testcontainers
@DisplayName("TCK: Community S3 blob store (MinIO)")
class CommunityS3BlobStorageTckIT extends AbstractBlobStorageTck {

    private static final String IMAGE = "minio/minio:RELEASE.2025-04-22T22-12-26Z";
    private static final String BUCKET = "exeris-blobs";
    private static final String ACCESS_KEY = "exeris-test-access";
    private static final String SECRET_KEY = "exeris-test-secret";
    private static final int MINIO_PORT = 9000;

    /**
     * The bucket is created as a directory before {@code minio server} starts.
     *
     * <p>MinIO's single-drive backend treats a top-level directory under the drive as a bucket, which
     * makes the fixture one shell command instead of a signed bucket-creation request — and keeps the
     * suite from depending on the very signing code it is meant to be testing.
     */
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(IMAGE)
            .withExposedPorts(MINIO_PORT)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
            .withCommand("-c", "mkdir -p /data/" + BUCKET + " && exec minio server /data")
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(MINIO_PORT).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    /**
     * Drives the driver's own staging and response buffers.
     *
     * <p>Separate from the allocator the TCK gives the caller, and {@code PARANOID} for the same
     * reason: a driver that forgot to release a staging buffer or a response body would otherwise leak
     * silently, and this suite is the only place that exercises those paths.
     *
     * <p>Closed in {@code @AfterAll} rather than per test. A subclass {@code @AfterEach} runs before the
     * superclass one, so closing it per test would tear down the pool while the store was still open.
     */
    private static MemoryAllocator storeAllocator;

    @AfterAll
    static void closeStoreAllocator() {
        if (storeAllocator != null) {
            storeAllocator.close();
            storeAllocator = null;
        }
    }

    @Override
    protected BlobStore createStore() {
        if (storeAllocator == null) {
            storeAllocator = new CommunityMemoryProvider().createAllocator(
                    MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.PARANOID));
        }
        return new CommunityS3BlobStore(config(), storeAllocator, Clock.systemUTC());
    }

    @Override
    protected MemoryProvider createMemoryProvider() {
        return new CommunityMemoryProvider();
    }

    /** An object store can always sign, so the uniform answer is "yes" (ADR-056 §7). */
    @Override
    protected boolean supportsSignedUrls() {
        return true;
    }

    private static BlobStorageConfig config() {
        return new BlobStorageConfig(
                "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(MINIO_PORT),
                BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL,
                Map.of(CommunityS3Settings.BUCKET, BUCKET,
                        CommunityS3Settings.ACCESS_KEY, ACCESS_KEY,
                        CommunityS3Settings.SECRET_KEY, SECRET_KEY,
                        CommunityS3Settings.REGION, CommunityS3Settings.DEFAULT_REGION));
    }
}
