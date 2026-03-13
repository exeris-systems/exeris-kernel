/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.hpack;

/**
 * RFC 7541 Appendix A — HPACK Static Table (61 entries, 1-indexed).
 *
 * <h2>Contract</h2>
 * <p>All data is stored as interned {@code String} constants on the Java heap.
 * This is bootstrap-time infrastructure — the table is small (61 entries),
 * read-only, and shared across all encoding/decoding contexts per RFC 7541 §2.3.1.
 *
 * <h2>Index Address Space</h2>
 * <p>Indices 1–61 map to static table entries. Index 0 is invalid per RFC 7541 §6.1.
 * Indices &gt; 61 refer to the dynamic table (handled by {@link HpackDynamicTable}).
 *
 * @since 0.5.0
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541#appendix-A">RFC 7541 Appendix A</a>
 */
public final class HpackStaticTable {

    /** Number of entries in the static table (RFC 7541: 61). */
    public static final int SIZE = 61;

    private static final String AUTHORITY = ":authority";
    private static final String METHOD = ":method";
    private static final String PATH = ":path";
    private static final String SCHEME = ":scheme";
    private static final String STATUS = ":status";

    private static final String[] NAMES = {
            null,
            AUTHORITY,
            METHOD, METHOD,
            PATH, PATH,
            SCHEME, SCHEME,
            STATUS, STATUS, STATUS, STATUS, STATUS, STATUS, STATUS,
            "accept-charset",
            "accept-encoding",
            "accept-language",
            "accept-ranges",
            "accept",
            "access-control-allow-origin",
            "age",
            "allow",
            "authorization",
            "cache-control",
            "content-disposition",
            "content-encoding",
            "content-language",
            "content-length",
            "content-location",
            "content-range",
            "content-type",
            "cookie",
            "date",
            "etag",
            "expect",
            "expires",
            "from",
            "host",
            "if-match",
            "if-modified-since",
            "if-none-match",
            "if-range",
            "if-unmodified-since",
            "last-modified",
            "link",
            "location",
            "max-forwards",
            "proxy-authenticate",
            "proxy-authorization",
            "range",
            "referer",
            "refresh",
            "retry-after",
            "server",
            "set-cookie",
            "strict-transport-security",
            "transfer-encoding",
            "user-agent",
            "vary",
            "via",
            "www-authenticate"
    };

    private static final String[] VALUES = {
            null,
            "",
            "GET", "POST",
            "/", "/index.html",
            "http", "https",
            "200", "204", "206", "304", "400", "404", "500",
            "",
            "gzip, deflate",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            ""
    };

    private HpackStaticTable() {
    }

    /**
     * Returns the header name at the given 1-based static table index.
     *
     * @param index 1-based index (1–61)
     * @return header name
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public static String getName(int index) {
        if (index < 1 || index > SIZE) {
            throw new IndexOutOfBoundsException("HPACK static table index out of range: " + index);
        }
        return NAMES[index];
    }

    /**
     * Returns the header value at the given 1-based static table index.
     *
     * @param index 1-based index (1–61)
     * @return header value (may be empty string)
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public static String getValue(int index) {
        if (index < 1 || index > SIZE) {
            throw new IndexOutOfBoundsException("HPACK static table index out of range: " + index);
        }
        return VALUES[index];
    }

    /**
     * Finds the best matching static table index for the given name and value.
     *
     * <p>Returns a packed result: the low 8 bits are the 1-based index (0 if not found),
     * bit 8 is the full-match flag (1 = name+value match, 0 = name-only match).
     *
     * @param name  header field name
     * @param value header field value
     * @return packed result: {@code (fullMatch ? 0x100 : 0) | index}, or 0 if not found
     */
    public static int find(String name, String value) {
        int nameMatch = 0;
        for (int idx = 1; idx <= SIZE; idx++) {
            if (NAMES[idx].equals(name)) {
                if (VALUES[idx].equals(value)) {
                    return 0x100 | idx;
                }
                if (nameMatch == 0) {
                    nameMatch = idx;
                }
            }
        }
        return nameMatch;
    }
}
