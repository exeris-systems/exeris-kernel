/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import java.util.Objects;

/**
 * SPI: A single HTTP header field — name and value pair (RFC 9110 §6.3).
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record}. No {@code synchronized}, no identity {@code ==},
 * no {@code System.identityHashCode()}. Scalarises cleanly via C2 JIT Escape Analysis
 * on hot-path header iteration loops. Will migrate to {@code value record} (JEP 401)
 * once mainline GA is reached.
 *
 * <h2>Case Sensitivity</h2>
 * <p>HTTP/2 (RFC 9113 §8.2) mandates lowercase header names. HTTP/1.1 (RFC 9110 §5.1)
 * is case-insensitive. {@link #nameEqualsIgnoreCase(String)} provides the correct
 * comparison for both wire formats.
 *
 * @param name  header field name; non-null, non-blank
 * @param value header field value; non-null (empty string is a valid value per RFC 9110)
 * @apiNote Store HTTP/2 header names lowercased. This record preserves whatever case it is given,
 *          so a name that reaches an HTTP/2 encoder in mixed case is a protocol violation the
 *          carrier cannot correct for you; compare with {@link #nameEqualsIgnoreCase(String)}
 *          rather than {@link String#equals(Object)} on either wire format.
 * @since 0.5
 */
public record HttpHeader(String name, String value) {

    /**
     * Rejects a null name or value and a blank name — a field with no name can be neither matched
     * nor written to the wire, while an empty <em>value</em> is legal per RFC 9110 §5.5.
     *
     * @throws NullPointerException     if {@code name} or {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public HttpHeader {
        Objects.requireNonNull(name, "Header name must not be null");
        Objects.requireNonNull(value, "Header value must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Header name must not be blank");
        }
    }

    /**
     * Returns {@code true} if this header's name equals the given name
     * using case-insensitive comparison (RFC 9110 §5.1).
     *
     * @param other name to compare; must not be {@code null}
     * @return {@code true} if names match case-insensitively
     */
    public boolean nameEqualsIgnoreCase(String other) {
        return name.equalsIgnoreCase(other);
    }
}

