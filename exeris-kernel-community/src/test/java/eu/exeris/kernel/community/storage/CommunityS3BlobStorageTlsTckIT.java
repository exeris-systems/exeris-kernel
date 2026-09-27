/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.community.transport.NativeTcpTransportProvider;
import eu.exeris.kernel.community.transport.TlsTestAuthority;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.storage.blob.BlobAccess;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import eu.exeris.kernel.spi.storage.blob.BlobUploadHandle;
import eu.exeris.kernel.tck.contract.storage.AbstractBlobStorageTck;
import org.bouncycastle.asn1.x509.GeneralName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: the S3-compatible driver against a real MinIO endpoint over {@code https}.
 *
 * <p>The oracle for SigV4 over TLS: {@link CommunityS3BlobStorageTckIT} runs the same suite over
 * {@code http}, and this runs it with the store built the way the storage subsystem builds it — the
 * Community crypto provider and {@code crypto.tls.client.trustFile} bound — so every request travels
 * over a connection that verified MinIO's certificate against the endpoint host. MinIO serves a leaf
 * issued per run by a {@link TlsTestAuthority}, whose subject alternative name is the host Testcontainers
 * reaches the container on. One case beyond the suite fetches a presigned {@code GET} with the JDK's
 * own client, trusting the same authority, so a presigned URL's scheme and {@code Host} are checked by
 * the server too.
 *
 * <p>{@code @Tag("integration")}, so it runs in the community integration gate beside the {@code http}
 * binding.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("TCK: Community S3 blob store (MinIO over https)")
class CommunityS3BlobStorageTlsTckIT extends AbstractBlobStorageTck {

    /** The image {@link CommunityS3BlobStorageTckIT} pins, and for the same reasons. */
    private static final String IMAGE = "bitnamilegacy/minio:2025.4.22-debian-12-r2"
            + "@sha256:50cec18ac4184af4671a78aedd5554942c8ae105d51a465fa82037949046da01";
    private static final String DATA_DIR = "/bitnami/minio/data";
    private static final String CERTS_DIR = "/tmp/exeris-minio-certs";
    private static final String BUCKET = "exeris-blobs";
    private static final String ACCESS_KEY = "exeris-test-access";
    private static final String SECRET_KEY = "exeris-test-secret";
    private static final int MINIO_PORT = 9000;
    private static final int READABLE = 0644;

    private static final Path MATERIAL = temporaryDirectory();
    private static final TlsTestAuthority AUTHORITY = TlsTestAuthority.root(MATERIAL, "minio-ca");
    private static final TlsTestAuthority.Issued LEAF = AUTHORITY.issue(sanFor(dockerHost()));

    /** MinIO serves {@code https} on its API port once {@code --certs-dir} holds a key pair. */
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(IMAGE)
            .withExposedPorts(MINIO_PORT)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCopyFileToContainer(MountableFile.forHostPath(LEAF.certificate(), READABLE),
                    CERTS_DIR + "/public.crt")
            .withCopyFileToContainer(MountableFile.forHostPath(LEAF.privateKey(), READABLE),
                    CERTS_DIR + "/private.key")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
            .withCommand("-c", "mkdir -p " + DATA_DIR + "/" + BUCKET
                    + " && exec minio server --certs-dir " + CERTS_DIR + " " + DATA_DIR)
            .waitingFor(Wait.forHttps("/minio/health/live").allowInsecure()
                    .forPort(MINIO_PORT).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    private static final CommunityKernelCryptoProvider CRYPTO = new CommunityKernelCryptoProvider();

    /**
     * Backs the store's staging and response buffers, {@code PARANOID} for the reason the {@code http}
     * binding gives. Closed in {@code @AfterAll}, since a subclass {@code @AfterEach} runs before the
     * superclass one that closes the store.
     */
    private static MemoryAllocator storeAllocator;

    @AfterAll
    static void closeStoreAllocatorAndDeleteMaterial() throws IOException {
        if (storeAllocator != null) {
            storeAllocator.close();
            storeAllocator = null;
        }
        try (Stream<Path> files = Files.walk(MATERIAL)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Override
    protected BlobStore createStore() {
        if (storeAllocator == null) {
            storeAllocator = new CommunityMemoryProvider().createAllocator(
                    MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.PARANOID));
        }
        MapConfigProvider trust = new MapConfigProvider(
                Map.of(NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY, AUTHORITY.certificate().toString()),
                Map.of());
        return ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, storeAllocator)
                .where(KernelProviders.CRYPTO_PROVIDER, CRYPTO)
                .where(KernelProviders.CURRENT_CONFIG, trust)
                .call(() -> new CommunityS3BlobStorageProvider().createStore(config()));
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

    @Test
    @DisplayName("a presigned https GET is honoured by MinIO for a client that trusts its authority")
    void presignedHttpsGetIsHonoured() throws Exception {
        byte[] payload = "presigned over https".getBytes(StandardCharsets.US_ASCII);
        BlobRef ref = new BlobRef("presigned", "object.bin");
        URI[] url = new URI[1];
        try (BlobStore store = createStore()) {
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.shared("tenant-presign"))
                    .run(() -> {
                        upload(store, ref, payload);
                        url[0] = store.signedUrl(ref, BlobAccess.READ, Duration.ofMinutes(5)).orElseThrow();
                    });
        }

        assertThat(url[0].getScheme()).isEqualTo("https");
        HttpClient client = HttpClient.newBuilder().sslContext(trusting(AUTHORITY.certificate())).build();
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(url[0]).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).as("MinIO verified the presigned signature").isEqualTo(200);
        assertThat(response.body()).isEqualTo(payload);
    }

    private static void upload(BlobStore store, BlobRef ref, byte[] payload) {
        try (BlobUploadHandle handle = store.beginUpload(ref, payload.length, "application/octet-stream");
             LoanedBuffer buffer = storeAllocator.allocateInfrastructure(payload.length)) {
            MemorySegment.copy(payload, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, payload.length);
            handle.write(buffer.segment(), payload.length);
            handle.commit();
        }
    }

    private static BlobStorageConfig config() {
        return new BlobStorageConfig(
                "https://" + MINIO.getHost() + ":" + MINIO.getMappedPort(MINIO_PORT),
                BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL,
                Map.of(CommunityS3Settings.BUCKET, BUCKET,
                        CommunityS3Settings.ACCESS_KEY, ACCESS_KEY,
                        CommunityS3Settings.SECRET_KEY, SECRET_KEY,
                        CommunityS3Settings.REGION, CommunityS3Settings.DEFAULT_REGION));
    }

    private static SSLContext trusting(Path anchor) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        try (InputStream in = Files.newInputStream(anchor)) {
            store.setCertificateEntry("anchor", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    /** The host Testcontainers reaches a container on, which the store's endpoint names. */
    private static String dockerHost() {
        return DockerClientFactory.instance().dockerHostIpAddress();
    }

    /** An IP entry for an address literal, a DNS entry otherwise: the entry the client checks the host against. */
    private static GeneralName sanFor(String host) {
        boolean literal = host.indexOf(':') >= 0 || host.chars().allMatch(c -> c == '.' || Character.isDigit(c));
        return literal ? TlsTestAuthority.ip(host) : TlsTestAuthority.dns(host);
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("exeris-minio-tls");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
