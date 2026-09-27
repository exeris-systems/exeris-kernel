/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.transport.TlsTestAuthority;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.core.crypto.tls.TlsFailureDetail;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.storage.blob.BlobDownloadHandle;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import eu.exeris.kernel.spi.storage.blob.BlobUploadHandle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A kernel that boots storage by name with the S3 driver reaches its endpoint by the endpoint's
 * scheme.
 *
 * <p>The store is created in the storage subsystem's {@code start()}, inside the kernel scope, so the
 * crypto provider and the trust key it verifies against are the ones the boot binds: the crypto
 * subsystem's provider and {@code crypto.tls.client.trustFile} read through the kernel's own config
 * provider. Configuration is set as the {@code exeris.}-prefixed system properties that provider
 * reads, and each case restores what it touched. Fails rather than skips when OpenSSL cannot be
 * loaded.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Community S3: a booted kernel reaches its endpoint by the endpoint's scheme")
class CommunityS3BootedEndpointSchemeTest {

    private static final int X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY = 20;
    private static final String TENANT = "tenant-booted";
    private static final BlobRef MISSING = new BlobRef("booted", "missing.bin");
    private static final BlobRef OBJECT = new BlobRef("booted", "object.bin");
    private static final byte[] PAYLOAD = "stored through a booted kernel".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    static Path material;

    private static TlsTestAuthority trusted;
    private static TlsTestAuthority untrusted;

    @BeforeAll
    static void setUp() {
        trusted = TlsTestAuthority.root(material, "booted-s3-trusted-ca");
        untrusted = TlsTestAuthority.root(material, "booted-s3-untrusted-ca");
        // Opens a trust store through OpenSSL, so a JVM that cannot load it fails here, not later.
        new CommunityKernelCryptoProvider().openClientTrust(trusted.certificate()).close();
    }

    @Test
    @DisplayName("http with crypto booted and a trust key set: the store talks plaintext to a plaintext endpoint")
    void httpEndpointIsPlaintextInABootWithCrypto() throws Exception {
        try (S3StubServer stub = S3StubServer.plaintext()) {
            AtomicReference<byte[]> read = new AtomicReference<>();

            withProperties(s3Properties("http://localhost:" + stub.port()), () -> boot(() ->
                    read.set(roundTrip(KernelProviders.BLOB_STORE.get(), KernelProviders.MEMORY_ALLOCATOR.get())),
                    "crypto", "storage"));

            assertThat(read.get()).as("stat, upload and download through the booted store").isEqualTo(PAYLOAD);
            assertThat(stub.tlsRecordsRefused()).as("no TLS record reached the plaintext endpoint").isZero();
            assertThat(stub.requests()).extracting(S3StubServer.Request::method)
                    .as("stat, upload, stat, and a download that stats before it reads")
                    .containsExactly("HEAD", "PUT", "HEAD", "HEAD", "GET");
        }
    }

    @Test
    @DisplayName("https with the issuing CA in the trust key: the store verifies the endpoint and talks TLS")
    void httpsEndpointIsVerifiedAgainstTheBootedTrustKey() throws Exception {
        TlsTestAuthority.Issued leaf = trusted.issue(TlsTestAuthority.dns("localhost"));
        try (S3StubServer stub = S3StubServer.tls(leaf.certificate(), leaf.privateKey())) {
            String authority = "localhost:" + stub.port();
            AtomicReference<byte[]> read = new AtomicReference<>();

            withProperties(s3Properties("https://" + authority), () -> boot(() ->
                    read.set(roundTrip(KernelProviders.BLOB_STORE.get(), KernelProviders.MEMORY_ALLOCATOR.get())),
                    "crypto", "storage"));

            assertThat(read.get()).as("stat, upload and download over verified TLS").isEqualTo(PAYLOAD);
            assertThat(stub.requests()).extracting(S3StubServer.Request::method)
                    .as("stat, upload, stat, and a download that stats before it reads")
                    .containsExactly("HEAD", "PUT", "HEAD", "HEAD", "GET");
            assertThat(stub.requests()).extracting(S3StubServer.Request::host).containsOnly(authority);
            assertThat(stub.serverNames()).as("the endpoint host is sent as the server name")
                    .isNotEmpty().containsOnly("localhost");
        }
    }

    @Test
    @DisplayName("https to a server the trust key does not anchor: refused with 20, and no request is read")
    void httpsEndpointFromAnotherIssuerIsRefused() throws Exception {
        TlsTestAuthority.Issued leaf = untrusted.issue(TlsTestAuthority.dns("localhost"));
        try (S3StubServer stub = S3StubServer.tls(leaf.certificate(), leaf.privateKey())) {
            AtomicReference<Throwable> failure = new AtomicReference<>();

            withProperties(s3Properties("https://localhost:" + stub.port()), () -> boot(() -> {
                BlobStore store = KernelProviders.BLOB_STORE.get();
                try {
                    asTenant(() -> store.stat(MISSING));
                } catch (RuntimeException refused) {
                    failure.set(refused);
                }
            }, "crypto", "storage"));

            TlsHandshakeException refusal = causeOfType(failure.get(), TlsHandshakeException.class);
            assertThat(refusal).as("the call fails with a handshake refusal; it failed with %s", failure.get())
                    .isNotNull();
            assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_2001);
            assertThat(refusal.rawArgs())
                    .containsExactly(X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY,
                            TlsFailureDetail.PEER_VERIFICATION_FAILED);
            assertThat(stub.requests()).as("the server read no request").isEmpty();
        }
    }

    @Test
    @DisplayName("https with no crypto in the boot: storage refuses the boot with EX-NET-4004 and dials nothing")
    void httpsEndpointWithoutCryptoRefusesTheBoot() throws Exception {
        TlsTestAuthority.Issued leaf = trusted.issue(TlsTestAuthority.dns("localhost"));
        try (S3StubServer stub = S3StubServer.tls(leaf.certificate(), leaf.privateKey())) {
            AtomicBoolean mainRan = new AtomicBoolean();

            assertThatThrownBy(() -> withProperties(s3Properties("https://localhost:" + stub.port()),
                    () -> boot(() -> mainRan.set(true), "storage")))
                    .isInstanceOf(KernelBootstrap.BootstrapException.class)
                    .satisfies(thrown -> {
                        TransportException refusal = causeOfType(thrown, TransportException.class);
                        assertThat(refusal).as("the boot failed with: %s", thrown.getMessage()).isNotNull();
                        assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4004);
                    });
            assertThat(mainRan.get()).as("the application does not run").isFalse();
            assertThat(stub.acceptedConnections()).as("nothing dialled the endpoint").isZero();
        }
    }

    private static byte[] roundTrip(BlobStore store, MemoryAllocator allocator) {
        AtomicReference<byte[]> read = new AtomicReference<>();
        asTenant(() -> {
            assertThat(store.stat(MISSING)).isEmpty();
            try (BlobUploadHandle handle = store.beginUpload(OBJECT, PAYLOAD.length, "application/octet-stream");
                 LoanedBuffer buffer = allocator.allocateInfrastructure(PAYLOAD.length)) {
                MemorySegment.copy(PAYLOAD, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, PAYLOAD.length);
                handle.write(buffer.segment(), PAYLOAD.length);
                handle.commit();
            }
            assertThat(store.stat(OBJECT)).isPresent();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (BlobDownloadHandle handle = store.openDownload(OBJECT);
                 LoanedBuffer buffer = allocator.allocateInfrastructure(1024)) {
                int count;
                while ((count = handle.read(buffer.segment(), 1024)) > 0) {
                    byte[] chunk = new byte[count];
                    MemorySegment.copy(buffer.segment(), ValueLayout.JAVA_BYTE, 0, chunk, 0, count);
                    sink.writeBytes(chunk);
                }
            }
            read.set(sink.toByteArray());
        });
        return read.get();
    }

    private static void asTenant(Runnable body) {
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.shared(TENANT)).run(body);
    }

    private static void boot(Runnable kernelMain, String... subsystems) throws KernelBootstrap.BootstrapException {
        KernelBootstrap.builder()
                .selector(BootstrapSelector.forNames(subsystems))
                .build()
                .boot(kernelMain);
    }

    /** The S3 driver's keys for {@code endpoint}, with the trusted authority as the client trust. */
    private static Map<String, String> s3Properties(String endpoint) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("exeris.storage.blob.provider", "blob-s3-community");
        properties.put("exeris.storage.blob.location", endpoint);
        properties.put("exeris.storage.blob.s3.bucket", "bucket");
        properties.put("exeris.storage.blob.s3.accessKey", "access");
        properties.put("exeris.storage.blob.s3.secretKey", "secret");
        properties.put("exeris.crypto.tls.client.trustFile", trusted.certificate().toString());
        return properties;
    }

    private static void withProperties(Map<String, String> properties, BootAction action)
            throws KernelBootstrap.BootstrapException {
        Map<String, String> previous = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            setOrClear(key, value);
        });
        try {
            action.run();
        } finally {
            previous.forEach(CommunityS3BootedEndpointSchemeTest::setOrClear);
        }
    }

    private static void setOrClear(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static <T extends Throwable> T causeOfType(Throwable thrown, Class<T> type) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface BootAction {
        void run() throws KernelBootstrap.BootstrapException;
    }
}
