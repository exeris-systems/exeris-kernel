/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.core.storage.StorageBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.storage.blob.BlobAccess;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobStorageProvider;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The storage subsystem boots by name through {@link KernelBootstrap} (ADR-056).
 *
 * <p>An application that boots subsystems by name writes {@code BootstrapSelector.forNames("storage")}
 * and nothing more. Three things then have to hold that no subsystem-direct test can see: a
 * {@code SubsystemProvider} on the classpath supplies a subsystem called {@code storage}, the
 * selector's dependency closure pulls in {@code memory} without it being named, and the operator's
 * {@code storage.blob.*} keys reach the subsystem through the discovered {@code ConfigProvider}.
 * {@link CommunityStorageSubsystemTest} drives the subsystem with a hand-bound config and cannot fail
 * on any of them; these cases boot the kernel the way a generated application does.
 *
 * <p>Configuration is set as {@code exeris.}-prefixed system properties because that is what
 * {@code CommunityConfigProvider} reads — the key spelling under test is the one an operator writes.
 * Each case restores the properties it touched.
 *
 * <p>Both Community drivers register at the same priority, so the key is the only thing that
 * separates them. A selection that checked the id and then took the first driver discovered would
 * still bind a driver whenever the id is known, and it binds the right one whenever the key names the
 * driver discovered first. One configured case therefore names the driver discovered second and the
 * other the driver discovered first, and each asserts the discovery order it relies on rather than
 * assuming it.
 *
 * <p>{@code boot()} stops what it started once the application returns. That is checked on a store
 * reference the application kept, not on the {@code BLOB_STORE} slot: a {@code ScopedValue} binding
 * never outlives the frame that bound it, so the slot reads unbound after {@code boot()} returns
 * whether or not the store was closed.
 *
 * <p>The S3 cases need no endpoint: creating the store parses its settings and builds a client without
 * dialling, and a presigned URL is computed locally. The URL is what shows a store was created from the
 * operator's keys, rather than a slot being bound to something that never started.
 */
@DisplayName("Community: storage boots by name through KernelBootstrap")
class CommunityStorageBootstrapIntegrationTest {

    private static final String FS_PROVIDER = "blob-fs-community";
    private static final String S3_PROVIDER = "blob-s3-community";
    private static final String S3_ENDPOINT = "http://127.0.0.1:9000";
    private static final String S3_BUCKET = "objects";
    private static final String S3_ACCESS_KEY = "exeris-access";
    private static final String PROVIDER_PROPERTY = "exeris." + StorageBootstrap.PROVIDER_KEY;
    private static final String LOCATION_PROPERTY = "exeris.storage.blob.location";

    @Test
    @DisplayName("unconfigured: the closure brings memory up with storage, and nothing is bound")
    void unconfiguredStorageBootsWithItsDependencyAndBindsNothing() throws Exception {
        AtomicBoolean allocatorBound = new AtomicBoolean();
        AtomicReference<List<String>> active = new AtomicReference<>(List.of());
        AtomicBoolean storageRunning = new AtomicBoolean();
        AtomicBoolean blobStoreBound = new AtomicBoolean(true);
        AtomicBoolean blobProviderBound = new AtomicBoolean(true);

        withProperties(properties(null, null), () -> bootStorage(() -> {
            allocatorBound.set(KernelProviders.MEMORY_ALLOCATOR.isBound());
            // SUBSYSTEMS travels in the carrier that holds the provider bindings, and in this boot
            // only memory binds anything, so the slot is bound exactly when memory is. Read
            // unguarded, a missing memory would surface as an unbound-slot error rather than as
            // the assertion below that names it.
            if (KernelProviders.SUBSYSTEMS.isBound()) {
                List<Subsystem> subsystems = KernelProviders.SUBSYSTEMS.get();
                active.set(subsystems.stream().map(Subsystem::name).toList());
                storageRunning.set(subsystems.stream().anyMatch(
                        subsystem -> "storage".equals(subsystem.name()) && subsystem.isRunning()));
            }
            blobStoreBound.set(KernelProviders.BLOB_STORE.isBound());
            blobProviderBound.set(KernelProviders.BLOB_STORAGE_PROVIDER.isBound());
        }));

        assertThat(allocatorBound.get())
                .as("forNames(\"storage\") does not name memory; the dependency closure has to bring "
                        + "it up, and MEMORY_ALLOCATOR is what it binds")
                .isTrue();
        assertThat(active.get())
                .as("memory arrives through the closure and initializes first, and nothing else is "
                        + "pulled in")
                .containsExactly("memory", "storage");
        assertThat(storageRunning.get())
                .as("an unset storage.blob.provider has nothing to run, which is not a failure")
                .isTrue();
        assertThat(blobStoreBound.get())
                .as("an unset storage.blob.provider means blob storage is off")
                .isFalse();
        assertThat(blobProviderBound.get())
                .as("an unset storage.blob.provider selects no driver")
                .isFalse();
    }

    @Test
    @DisplayName("configured: the driver the key names is bound, not the first one discovered")
    void configuredStorageBindsTheNamedDriverNotTheFirstDiscovered() throws Exception {
        List<String> discovered = discoveredProviderIds();
        assertThat(discovered.indexOf(S3_PROVIDER))
                .as("this case separates the driver the key names from the first driver discovered "
                        + "only while the named driver is not discovered first; discovery order: %s",
                        discovered)
                .isPositive();
        AtomicBoolean blobStoreBound = new AtomicBoolean();
        AtomicReference<String> providerId = new AtomicReference<>();

        withProperties(s3Properties(true), () -> bootStorage(() -> {
            blobStoreBound.set(KernelProviders.BLOB_STORE.isBound());
            providerId.set(KernelProviders.BLOB_STORAGE_PROVIDER.get().providerId());
        }));

        assertThat(blobStoreBound.get())
                .as("storage.blob.provider set through the kernel's own config provider binds "
                        + "BLOB_STORE for the application")
                .isTrue();
        assertThat(providerId.get())
                .as("both Community drivers register at the same priority; only the key separates "
                        + "them, and it names %s, discovered after %s", S3_PROVIDER, discovered.get(0))
                .isEqualTo(S3_PROVIDER);
    }

    @Test
    @DisplayName("configured: naming the driver discovered first binds that driver")
    void configuredStorageBindsTheFirstDiscoveredDriverWhenItIsNamed(@TempDir Path root)
            throws Exception {
        List<String> discovered = discoveredProviderIds();
        assertThat(discovered.indexOf(FS_PROVIDER))
                .as("this case is the other direction: the named driver is the one discovered first, "
                        + "which a selection preferring any later driver gets wrong; discovery order: %s",
                        discovered)
                .isZero();
        AtomicBoolean blobStoreBound = new AtomicBoolean();
        AtomicReference<String> providerId = new AtomicReference<>();

        withProperties(properties(FS_PROVIDER, root.toString()), () -> bootStorage(() -> {
            blobStoreBound.set(KernelProviders.BLOB_STORE.isBound());
            providerId.set(KernelProviders.BLOB_STORAGE_PROVIDER.get().providerId());
        }));

        assertThat(blobStoreBound.get())
                .as("storage.blob.provider set through the kernel's own config provider binds "
                        + "BLOB_STORE for the application")
                .isTrue();
        assertThat(providerId.get())
                .as("the key names %s, and a selection preferring a later driver binds another",
                        FS_PROVIDER)
                .isEqualTo(FS_PROVIDER);
    }

    @Test
    @DisplayName("shutdown: a store the application kept past boot() refuses work")
    void storeKeptPastBootRefusesWork() throws Exception {
        AtomicReference<BlobStore> kept = new AtomicReference<>();
        AtomicReference<URI> signedInsideBoot = new AtomicReference<>();

        withProperties(s3Properties(true), () -> bootStorage(() -> {
            kept.set(KernelProviders.BLOB_STORE.get());
            signedInsideBoot.set(signReport(kept.get()).orElse(null));
        }));

        assertThat(signedInsideBoot.get())
                .as("the same reference answers while the kernel runs")
                .isNotNull();
        assertThat(catchThrowable(() -> signReport(kept.get())))
                .as("boot() stops storage when the application returns, and a store the application "
                        + "kept refuses work instead of answering from a released driver")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Blob store is closed");
    }

    @Test
    @DisplayName("misconfigured: an id naming no driver refuses the boot with EX-BLOB-8008")
    void unknownProviderIdRefusesTheBoot(@TempDir Path root) {
        AtomicBoolean mainRan = new AtomicBoolean();

        assertThatThrownBy(() -> withProperties(properties("blob-elsewhere", root.toString()),
                () -> bootStorage(() -> mainRan.set(true))))
                .isInstanceOf(KernelBootstrap.BootstrapException.class)
                .satisfies(thrown -> {
                    BlobStorageException refusal = causeOfType(thrown, BlobStorageException.class);
                    assertThat(refusal)
                            .as("a set key that names nothing is a refusal, not blob storage off; "
                                    + "the boot failed with: %s", thrown.getMessage())
                            .isNotNull();
                    assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_BLOB_8008);
                    assertThat(refusal.rawArgs())
                            .as("the key and the value the operator set, read through the kernel's "
                                    + "own config provider")
                            .startsWith(StorageBootstrap.PROVIDER_KEY, "blob-elsewhere");
                });
        assertThat(mainRan.get())
                .as("the application does not run on a kernel whose storage failed to boot")
                .isFalse();
    }

    @Test
    @DisplayName("S3: the store is created inside the boot, from the keys the operator set")
    void s3StoreIsCreatedInsideTheKernelScope() throws Exception {
        AtomicReference<String> providerId = new AtomicReference<>();
        AtomicReference<URI> signed = new AtomicReference<>();

        withProperties(s3Properties(true), () -> bootStorage(() -> {
            providerId.set(KernelProviders.BLOB_STORAGE_PROVIDER.get().providerId());
            signed.set(signReport(KernelProviders.BLOB_STORE.get()).orElseThrow());
        }));

        assertThat(providerId.get()).isEqualTo(S3_PROVIDER);
        URI url = signed.get();
        assertThat(url)
                .as("the S3 driver takes MEMORY_ALLOCATOR, which is bound only once the kernel scope "
                        + "is entered; a store created before it cannot exist, and a slot bound to a "
                        + "store that was never created cannot sign")
                .isNotNull();
        assertThat(url.getHost()).isEqualTo("127.0.0.1");
        assertThat(url.getPort()).isEqualTo(9000);
        assertThat(url.getRawPath())
                .as("path-style under the configured bucket, inside the tenant's prefix")
                .startsWith("/" + S3_BUCKET + "/t-")
                .endsWith("/reports/q3.pdf");
        assertThat(url.getRawQuery())
                .as("signed with the access key forwarded from storage.blob.s3.accessKey")
                .contains("X-Amz-Credential=" + S3_ACCESS_KEY + "%2F")
                .contains("X-Amz-Signature=");
    }

    @Test
    @DisplayName("S3 misconfigured: the driver's refusal still fails the boot, and main does not run")
    void s3ConfigurationRefusalFailsTheBoot() {
        AtomicBoolean mainRan = new AtomicBoolean();

        assertThatThrownBy(() -> withProperties(s3Properties(false),
                () -> bootStorage(() -> mainRan.set(true))))
                .isInstanceOf(KernelBootstrap.BootstrapException.class)
                .satisfies(thrown -> assertThat(causeOfType(thrown, IllegalArgumentException.class))
                        .as("storage.blob.s3.bucket unset is the driver's own refusal, naming the "
                                + "property; the boot failed with: %s", thrown.getMessage())
                        .isNotNull()
                        .hasMessageContaining("s3.bucket"));
        assertThat(mainRan.get())
                .as("the store is created in start(), and a refusal there is still a refused boot")
                .isFalse();
    }

    /** Provider ids in the order the storage bootstrap discovers them, through the same lookup. */
    private static List<String> discoveredProviderIds() {
        List<String> ids = new ArrayList<>();
        ServiceLoader.load(BlobStorageProvider.class).forEach(provider -> ids.add(provider.providerId()));
        return ids;
    }

    /** A READ URL for {@code reports/q3.pdf}, signed by {@code store} for tenant {@code tenant-a}. */
    private static Optional<URI> signReport(BlobStore store) {
        return ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.shared("tenant-a"))
                .call(() -> store.signedUrl(new BlobRef("reports", "q3.pdf"), BlobAccess.READ,
                        Duration.ofMinutes(5)));
    }

    private static void bootStorage(Runnable kernelMain) throws KernelBootstrap.BootstrapException {
        KernelBootstrap.builder()
                .selector(BootstrapSelector.forNames("storage"))
                .build()
                .boot(kernelMain);
    }

    /** The two storage keys a case sets; a {@code null} value leaves the key unset. */
    private static Map<String, String> properties(String providerId, String location) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(PROVIDER_PROPERTY, providerId);
        properties.put(LOCATION_PROPERTY, location);
        return properties;
    }

    /**
     * The S3 driver's keys; {@code withBucket} false leaves {@code storage.blob.s3.bucket} unset, which
     * the driver refuses.
     */
    private static Map<String, String> s3Properties(boolean withBucket) {
        Map<String, String> properties = properties(S3_PROVIDER, S3_ENDPOINT);
        properties.put("exeris.storage.blob.s3.bucket", withBucket ? S3_BUCKET : null);
        properties.put("exeris.storage.blob.s3.accessKey", S3_ACCESS_KEY);
        properties.put("exeris.storage.blob.s3.secretKey", "exeris-secret");
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
            previous.forEach(CommunityStorageBootstrapIntegrationTest::setOrClear);
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
