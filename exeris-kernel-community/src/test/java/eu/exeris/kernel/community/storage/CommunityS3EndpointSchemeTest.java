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
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.storage.blob.BlobDownloadHandle;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import eu.exeris.kernel.spi.storage.blob.BlobUploadHandle;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The S3 driver reaches its endpoint by the endpoint's scheme, wherever the store is built.
 *
 * <p>Each store is created through {@link CommunityS3BlobStorageProvider#createStore} inside a scope
 * binding the allocator and, per case, a crypto provider and the kernel configuration, the way the
 * storage subsystem creates it, and then talks to an {@link S3StubServer} on {@code localhost}. The
 * transport's decision is read from the {@code TransportTlsClientPosture} event.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Community S3: the endpoint's scheme decides the transport")
class CommunityS3EndpointSchemeTest {

    private static final String POSTURE_EVENT = "eu.exeris.kernel.transport.TransportTlsClientPosture";
    private static final String TENANT = "tenant-scheme";
    private static final BlobRef MISSING = new BlobRef("scheme", "missing.bin");
    private static final BlobRef OBJECT = new BlobRef("scheme", "object.bin");
    private static final byte[] PAYLOAD = "stored over the endpoint's scheme".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    static Path material;

    private static MemoryAllocator allocator;
    private static CommunityKernelCryptoProvider crypto;
    private static TlsTestAuthority trusted;

    @BeforeAll
    static void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        crypto = new CommunityKernelCryptoProvider();
        trusted = TlsTestAuthority.root(material, "s3-trusted-ca");
    }

    @AfterAll
    static void tearDown() {
        allocator.close();
    }

    @Test
    @DisplayName("http: plaintext where the Community crypto provider is bound and a trust is configured")
    void httpEndpointIsPlaintextWhereCommunityCryptoIsBound() throws Exception {
        try (S3StubServer stub = S3StubServer.plaintext()) {
            BlobStore[] created = new BlobStore[1];
            List<RecordedEvent> postures = postures(() -> {
                created[0] = createStore("http://localhost:" + stub.port(), crypto, trustKey(trusted.certificate()));
                return null;
            });

            try (BlobStore store = created[0]) {
                assertThatCode(() -> asTenant(() -> {
                    assertThat(store.stat(MISSING)).isEmpty();
                    upload(store, OBJECT, PAYLOAD);
                    assertThat(download(store, OBJECT)).isEqualTo(PAYLOAD);
                })).as("stat, upload and download over plaintext").doesNotThrowAnyException();
            }

            assertThat(stub.tlsRecordsRefused()).as("no TLS record reached the plaintext endpoint").isZero();
            assertThat(stub.requests()).extracting(S3StubServer.Request::method)
                    .containsExactly("HEAD", "PUT", "HEAD", "GET");
            assertThat(postures).hasSize(1);
            assertThat(postures.getFirst().getString("requirement")).isEqualTo("PLAINTEXT");
            assertThat(postures.getFirst().getString("posture")).isEqualTo("PLAINTEXT_REQUIRED");
        }
    }

    @Test
    @DisplayName("http: a store is built, and answers, where a crypto provider that cannot verify is bound")
    void httpEndpointBuildsUnderAForeignProvider() throws Exception {
        try (S3StubServer stub = S3StubServer.plaintext()) {
            BlobStore[] created = new BlobStore[1];
            assertThatCode(() -> created[0] =
                    createStore("http://localhost:" + stub.port(), new ForeignCryptoProvider(), null))
                    .as("a plaintext endpoint needs no provider that can verify")
                    .doesNotThrowAnyException();

            try (BlobStore store = created[0]) {
                asTenant(() -> assertThat(store.stat(MISSING)).isEmpty());
            }
            assertThat(stub.requests()).extracting(S3StubServer.Request::method).containsExactly("HEAD");
        }
    }

    private static BlobStore createStore(String location, KernelCryptoProvider provider, ConfigProvider config)
            throws Exception {
        BlobStorageConfig blobConfig = new BlobStorageConfig(location, BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL,
                Map.of(CommunityS3Settings.BUCKET, "bucket",
                        CommunityS3Settings.ACCESS_KEY, "access",
                        CommunityS3Settings.SECRET_KEY, "secret"));
        ScopedValue.Carrier scope = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator);
        if (provider != null) {
            scope = scope.where(KernelProviders.CRYPTO_PROVIDER, provider);
        }
        if (config != null) {
            scope = scope.where(KernelProviders.CURRENT_CONFIG, config);
        }
        return scope.call(() -> new CommunityS3BlobStorageProvider().createStore(blobConfig));
    }

    private static void asTenant(Runnable body) {
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.shared(TENANT)).run(body);
    }

    private static void upload(BlobStore store, BlobRef ref, byte[] payload) {
        try (BlobUploadHandle handle = store.beginUpload(ref, payload.length, "application/octet-stream");
             LoanedBuffer buffer = allocator.allocateInfrastructure(payload.length)) {
            MemorySegment.copy(payload, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, payload.length);
            handle.write(buffer.segment(), payload.length);
            handle.commit();
        }
    }

    private static byte[] download(BlobStore store, BlobRef ref) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (BlobDownloadHandle handle = store.openDownload(ref);
             LoanedBuffer buffer = allocator.allocateInfrastructure(1024)) {
            int read;
            while ((read = handle.read(buffer.segment(), 1024)) > 0) {
                byte[] chunk = new byte[read];
                MemorySegment.copy(buffer.segment(), ValueLayout.JAVA_BYTE, 0, chunk, 0, read);
                sink.writeBytes(chunk);
            }
        }
        return sink.toByteArray();
    }

    private static List<RecordedEvent> postures(Callable<?> action) throws Exception {
        Path dump = Files.createTempFile(material, "posture", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(POSTURE_EVENT).withoutStackTrace();
            recording.start();
            try {
                action.call();
            } finally {
                recording.stop();
                recording.dump(dump);
            }
        }
        return RecordingFile.readAllEvents(dump).stream()
                .filter(event -> POSTURE_EVENT.equals(event.getEventType().getName()))
                .toList();
    }

    private static ConfigProvider trustKey(Path trustFile) {
        return new MapConfigProvider(
                Map.of(NativeTcpTransportProvider.CLIENT_TRUST_FILE_KEY, trustFile.toString()), Map.of());
    }

    /** A bound provider that is not the Community one, so it cannot verify an outbound peer. */
    private static final class ForeignCryptoProvider implements KernelCryptoProvider {

        @Override
        public TlsEngine createTlsEngine(CryptoProviderConfig config) {
            throw new UnsupportedOperationException("never asked for an engine in these cases");
        }

        @Override
        public boolean supportsQuic() {
            return false;
        }

        @Override
        public String providerName() {
            return "foreign";
        }

        @Override
        public int priority() {
            return 1;
        }
    }
}
