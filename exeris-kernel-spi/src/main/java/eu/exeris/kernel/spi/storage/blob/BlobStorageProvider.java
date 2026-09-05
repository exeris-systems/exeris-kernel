/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.storage.blob;

/**
 * SPI: ServiceLoader discovery handle for a blob-storage driver (ADR-056 §1).
 *
 * <p>Discovery and construction only — {@link BlobStore} does the work. This split is the convention
 * every sibling SPI package follows ({@code PersistenceProvider}, {@code EventProvider},
 * {@code FlowProvider}, {@code TransportProvider}, {@code MemoryProvider}), so a reader who knows one
 * knows this one.
 *
 * @since 0.11
 */
public interface BlobStorageProvider {

    /**
     * Returns the stable machine-readable identifier for this driver (e.g. {@code "blob-fs-community"}).
     *
     * @return non-blank provider id
     */
    String providerId();

    /**
     * Returns the human-readable driver name (e.g. {@code "ExerisCommunity/FilesystemBlob"}).
     *
     * @return non-blank provider name
     */
    String providerName();

    /**
     * Returns the selection priority: {@code 0} for Community, {@code 100} for Enterprise.
     *
     * @return non-negative priority; higher wins
     */
    default int priority() {
        return 0;
    }

    /**
     * Creates a store from the given configuration.
     *
     * @param config the driver-interpreted configuration; never {@code null}
     * @return a new store; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.storage.BlobStorageException if the store cannot be created
     */
    BlobStore createStore(BlobStorageConfig config);
}
