/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.storage;

import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStorageProvider;
import eu.exeris.kernel.spi.storage.blob.BlobStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Selects a {@link BlobStorageProvider} <em>by configured id</em> and creates its store (ADR-056).
 *
 * <p>Every sibling bootstrap ranks by {@code priority()} and takes the winner. This one cannot, and
 * the reason is a property of the drivers rather than a preference: the two Community blob providers
 * register at the same priority, so ranking would decide a tenant's objects by ServiceLoader order
 * and a tie-break on class name. They are not interchangeable — one needs a writable directory, the
 * other credentials and a reachable endpoint — so the choice has to be <b>stated</b>, not inferred.
 *
 * <p><b>Absent configuration is not an ambiguous configuration.</b> A deployment that never asked
 * for blob storage does not reach this class at all; the subsystem that calls it is opt-in on the
 * same key. What is refused here is asking for storage without saying which, which is the only
 * situation where a silent pick would be wrong.
 *
 * @since 0.12
 */
public final class StorageBootstrap {

    /** The key that both enables blob storage and names its driver. */
    public static final String PROVIDER_KEY = "storage.blob.provider";

    private static final String COMPONENT = "StorageBootstrap";

    private StorageBootstrap() {
        // Utility holder — not instantiable.
    }

    /**
     * The provider that was named and the store it created.
     *
     * @param provider the selected provider
     * @param store    the store it produced
     */
    public record BootstrapResult(BlobStorageProvider provider, BlobStore store) {
    }

    /**
     * Resolves the configured provider from the classpath and creates its store.
     *
     * @param providerId the configured provider id; must name a discovered driver
     * @param config     the store configuration
     * @return the provider and its store
     * @throws BlobStorageException ({@code EX-BLOB-8007}) if no driver is present, or
     *                               ({@code EX-BLOB-8008}) if none matches {@code providerId}
     */
    public static BootstrapResult loadWithProvider(String providerId, BlobStorageConfig config) {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(config, "config must not be null");

        List<BlobStorageProvider> discovered = new ArrayList<>();
        ServiceLoader.load(BlobStorageProvider.class).forEach(discovered::add);

        BlobStorageProvider provider = select(discovered, providerId);
        BlobStore store = provider.createStore(config);

        StorageBootstrapSelectedEvent.emit(
                provider.getClass().getName(),
                provider.providerId(),
                provider.priority(),
                config.location());

        return new BootstrapResult(provider, store);
    }

    /**
     * Picks the one provider whose id equals {@code providerId}.
     *
     * <p>Separated from the {@link ServiceLoader} call so the rule can be tested against a list —
     * the interesting behaviour is which provider wins and what the refusal says, and neither
     * depends on how the candidates were found.
     *
     * @param discovered the providers on the classpath
     * @param providerId the configured id
     * @return the matching provider; never {@code null}
     * @throws BlobStorageException ({@code EX-BLOB-8007}) if {@code discovered} is empty, or
     *                               ({@code EX-BLOB-8008}) if no element matches
     */
    public static BlobStorageProvider select(List<BlobStorageProvider> discovered, String providerId) {
        Objects.requireNonNull(discovered, "discovered must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");

        if (discovered.isEmpty()) {
            throw BlobStorageException.noProvider(COMPONENT);
        }
        return discovered.stream()
                .filter(candidate -> providerId.equals(candidate.providerId()))
                .findFirst()
                .orElseThrow(() -> BlobStorageException.selectionUnresolved(
                        PROVIDER_KEY, providerId, availableIds(discovered)));
    }

    /**
     * The ids an operator may choose between, for the refusal message.
     *
     * @param discovered the providers on the classpath
     * @return comma-joined provider ids in discovery order
     */
    public static String availableIds(List<BlobStorageProvider> discovered) {
        return discovered.stream()
                .map(BlobStorageProvider::providerId)
                .collect(Collectors.joining(", "));
    }
}
