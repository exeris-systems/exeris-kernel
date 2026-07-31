/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.storage.blob;

/**
 * SPI: The single operation a signed URL grants (ADR-056 §7).
 *
 * <p>Deliberately two values. A URL that grants both directions is not a narrower capability than the
 * credential that minted it, which is the only reason to hand one out.
 *
 * @since 0.11.0
 */
public enum BlobAccess {

    /** Read the referenced object, and nothing else. */
    READ,

    /** Write the referenced object, and nothing else. */
    WRITE
}
