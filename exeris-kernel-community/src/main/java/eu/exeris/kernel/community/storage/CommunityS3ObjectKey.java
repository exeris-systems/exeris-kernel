/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.storage.blob.BlobRef;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Where a {@link BlobRef} lands in the bucket's key space, and nothing else.
 *
 * <p>The counterpart of {@code CommunityFilesystemBlobLayout}, and deliberately the same shape:
 * {@code t-<hex(isolationKey)>/<container>/<key>}. One layout across both drivers means an operator
 * reading a bucket listing and a directory tree sees the same structure, and a tenant's objects can be
 * moved between them without re-deriving where they go.
 *
 * <h2>Why there is no containment backstop here</h2>
 * <p>The filesystem layout re-checks containment after resolution because {@code Path.normalize()} can
 * move a path upwards, so the check guards against something that actually happens. Nothing here
 * normalises: the key is built by concatenation and percent-encoded on the way to the wire, so a
 * {@code startsWith} assertion on a string this class just concatenated would confirm its own last
 * statement. {@link BlobRef} already refuses relative-navigation segments, and the hex-encoded tenant
 * prefix is one opaque segment whatever the isolation key contains — the same two properties the
 * filesystem layout relies on.
 *
 * @since 0.11.0
 */
final class CommunityS3ObjectKey {

    private static final CommunityBlobFailures FAILURES =
            CommunityBlobFailures.forProvider(CommunityBlobFailures.S3_PROVIDER);

    private static final String TENANT_PREFIX = "t-";
    private static final char SEPARATOR = '/';
    private static final String UNRESERVED_EXTRA = "-._~";
    private static final HexFormat HEX = HexFormat.of();
    /** SigV4 canonicalisation requires percent-escapes in uppercase hex. */
    private static final HexFormat PERCENT_HEX = HexFormat.of().withUpperCase();

    private CommunityS3ObjectKey() {
        // Utility holder — not instantiable.
    }

    /**
     * Resolves a tenant-relative reference to an object key inside the caller's namespace.
     *
     * @param ref       the tenant-relative reference
     * @param operation the {@code CommunityBlobFailures.OP_*} constant for the call in flight, so a
     *                  denial is attributed to the operation the caller made rather than to resolution
     * @return the bucket-relative object key
     * @throws eu.exeris.kernel.spi.exceptions.storage.BlobStorageException if the ambient context
     *                                                                     carries no isolation key
     */
    /* default */ static String resolve(BlobRef ref, String operation) {
        return requireTenantPrefix(operation) + ref.container() + SEPARATOR + ref.key();
    }

    /**
     * Returns the caller's tenant prefix, denying when the ambient context carries no isolation key.
     *
     * <p>ADR-056 §5: an absent key means there is no namespace to resolve into, and falling back to the
     * bucket root would place one tenant's object where every tenant can reach it.
     *
     * <p>The prefix is hex-encoded for the reason the filesystem layout encodes its directory name: the
     * isolation key is an unconstrained {@code String} arriving from a verified token claim, and
     * encoding makes it one opaque segment. A key carrying separators cannot become a nested prefix
     * chain, and the mapping is injective, so two tenants cannot collide through any normalisation
     * applied downstream.
     *
     * @param operation the {@code CommunityBlobFailures.OP_*} constant for the call in flight
     * @return the tenant prefix, terminated by a separator
     */
    /* default */ static String requireTenantPrefix(String operation) {
        StorageContext context = KernelProviders.storageContextOrSystem();
        String isolationKey = context.isolationKey().orElse(null);
        if (isolationKey == null || isolationKey.isBlank()) {
            throw FAILURES.isolationDenied(operation,
                    CommunityBlobFailures.REASON_NO_ISOLATION_KEY, context.strategy().name());
        }
        return TENANT_PREFIX + HEX.formatHex(isolationKey.getBytes(StandardCharsets.UTF_8)) + SEPARATOR;
    }

    /**
     * Percent-encodes an object key for a request target and for the SigV4 canonical URI.
     *
     * <p>Separators are left intact — they delimit path segments, and encoding them would address a
     * different object than the one the key names. Everything outside RFC 3986's unreserved set is
     * encoded, in the uppercase-hex form SigV4 canonicalisation requires; the request target and the
     * signed canonical URI are the same string, so a mismatch between them is unrepresentable.
     *
     * <p>{@code URLEncoder} is not used: it is form encoding, which turns a space into {@code +} and
     * leaves {@code *} alone. Both would produce a signature the store rejects.
     *
     * @param objectKey the bucket-relative object key
     * @return the encoded path
     */
    /* default */ static String encode(String objectKey) {
        StringBuilder encoded = new StringBuilder(objectKey.length() + objectKey.length() / 2);
        for (byte raw : objectKey.getBytes(StandardCharsets.UTF_8)) {
            char symbol = (char) (raw & 0xFF);
            if (isUnreserved(symbol) || symbol == SEPARATOR) {
                encoded.append(symbol);
            } else {
                encoded.append('%').append(PERCENT_HEX.toHighHexDigit(raw))
                        .append(PERCENT_HEX.toLowHexDigit(raw));
            }
        }
        return encoded.toString();
    }

    /**
     * Percent-encodes a query-string component, where a separator is data rather than structure.
     *
     * @param value the raw component
     * @return the encoded component
     */
    /* default */ static String encodeQueryComponent(String value) {
        StringBuilder encoded = new StringBuilder(value.length() + value.length() / 2);
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            char symbol = (char) (raw & 0xFF);
            if (isUnreserved(symbol)) {
                encoded.append(symbol);
            } else {
                encoded.append('%').append(PERCENT_HEX.toHighHexDigit(raw))
                        .append(PERCENT_HEX.toLowHexDigit(raw));
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(char symbol) {
        return symbol >= 'A' && symbol <= 'Z'
                || symbol >= 'a' && symbol <= 'z'
                || symbol >= '0' && symbol <= '9'
                || UNRESERVED_EXTRA.indexOf(symbol) >= 0;
    }
}
