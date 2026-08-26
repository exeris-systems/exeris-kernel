/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Exeris Kernel SPI – Blob storage contracts (ADR-056).
 *
 * <h2>SPI-First Architecture</h2>
 * <p>Binary-object storage as a kernel seam. No vendor or filesystem type appears here — an ArchUnit
 * rule forbids {@code java.io} and {@code java.nio.file} inside this package, because a filesystem type
 * in the contract would encode one driver's addressing model into the seam.
 *
 * <h2>Key Types</h2>
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.storage.blob.BlobStorageProvider} — {@code ServiceLoader} entry point</li>
 *   <li>{@link eu.exeris.kernel.spi.storage.blob.BlobStore} — the operational contract</li>
 *   <li>{@link eu.exeris.kernel.spi.storage.blob.BlobRef} — a <b>tenant-relative</b> object reference</li>
 *   <li>{@link eu.exeris.kernel.spi.storage.blob.BlobUploadHandle} /
 *       {@link eu.exeris.kernel.spi.storage.blob.BlobDownloadHandle} — the two transfer directions</li>
 * </ul>
 *
 * <h2>Two properties worth knowing before reading the types</h2>
 * <p><b>Isolation is structural.</b> A {@code BlobRef} carries no tenant. Every operation resolves it
 * against {@link eu.exeris.kernel.spi.security.StorageContext#isolationKey()} from the ambient scope, so
 * a reference forged from another tenant resolves inside the resolving caller's own namespace instead of
 * escaping it. A context with no isolation key is a terminal deny, not an unscoped fallback.
 *
 * <p><b>The caller owns the buffers, in both directions.</b> Uploads write out of a caller-owned
 * segment; downloads read into one. Nothing crosses the seam owning memory, so one pooled
 * {@link eu.exeris.kernel.spi.memory.LoanedBuffer} can drive an entire transfer and there is no
 * per-chunk ownership rule to get wrong.
 *
 * @since 0.11.0
 */
package eu.exeris.kernel.spi.storage.blob;
