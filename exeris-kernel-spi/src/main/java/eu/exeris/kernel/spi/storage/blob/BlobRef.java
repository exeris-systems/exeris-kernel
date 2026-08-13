/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.storage.blob;

import java.util.Objects;

/**
 * SPI: A <b>tenant-relative</b> reference to one stored object (ADR-056 §4).
 *
 * <p>A {@code BlobRef} names a container and a key <em>within the caller's namespace</em>. It carries no
 * tenant, no bucket, and no path: {@link BlobStore} resolves it to a physical location using the ambient
 * {@link eu.exeris.kernel.spi.security.StorageContext}. A reference forged or replayed from another tenant
 * therefore resolves inside the <em>resolving</em> caller's own namespace instead of escaping it — the
 * isolation property holds structurally rather than by validation.
 *
 * <h2>Why the key is validated here</h2>
 * <p>Both plausible drivers interpolate the key into a namespace — a filesystem path, or an object key
 * under a bucket prefix. Rejecting traversal at construction makes the unsafe reference unrepresentable,
 * which is stronger than each driver rediscovering the check. Drivers remain free to apply further
 * restrictions their addressing model requires.
 *
 * @param container the logical container; non-blank
 * @param key       the object key within the container; non-blank, and free of traversal syntax
 * @since 0.11.0
 */
public value record BlobRef(String container, String key) {

    private static final String PARENT_SEGMENT = "..";
    private static final String CURRENT_SEGMENT = ".";
    private static final char SEPARATOR = '/';
    private static final char BACKSLASH = '\\';
    private static final char NUL = (char) 0;

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if either component is {@code null}
     * @throws IllegalArgumentException if either component is blank, or the key contains a
     *                                  relative-navigation segment ({@code .} or {@code ..}), an
     *                                  absolute-path prefix, a backslash, or a NUL byte
     */
    public BlobRef {
        Objects.requireNonNull(container, "container must not be null");
        Objects.requireNonNull(key, "key must not be null");
        requireSafeContainer(container);
        requireSafeKey(key);
    }

    private static void requireSafeContainer(String container) {
        if (container.isBlank()) {
            throw new IllegalArgumentException("container must not be blank");
        }
        if (container.indexOf(SEPARATOR) >= 0 || container.indexOf(BACKSLASH) >= 0
                || container.indexOf(NUL) >= 0
                || PARENT_SEGMENT.equals(container) || CURRENT_SEGMENT.equals(container)) {
            throw new IllegalArgumentException("container must be a single safe segment");
        }
    }

    /**
     * Rejects anything that could resolve outside the tenant namespace once interpolated.
     */
    private static void requireSafeKey(String key) {
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        requireSafeCharacters(key);
        requireSafeSegments(key);
    }

    /**
     * A backslash is rejected alongside the separator because it separates path segments on some
     * filesystems, so allowing it would make one key mean different things in different drivers.
     */
    private static void requireSafeCharacters(String key) {
        if (key.indexOf(NUL) >= 0 || key.indexOf(BACKSLASH) >= 0) {
            throw new IllegalArgumentException("key must not contain a NUL byte or a backslash");
        }
        if (key.charAt(0) == SEPARATOR) {
            throw new IllegalArgumentException("key must be relative, not absolute");
        }
    }

    /**
     * A lone {@code "."} is rejected alongside {@code ".."}. It cannot escape the namespace, but it is a
     * relative-navigation token rather than an object name, and a driver that resolved it against a
     * filesystem would silently address the container directory itself while one storing the key
     * verbatim would address a distinct object named {@code "."}. Rejecting it keeps one key from
     * meaning two things.
     */
    private static void requireSafeSegments(String key) {
        for (String segment : key.split(String.valueOf(SEPARATOR), -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("key must not contain an empty path segment");
            }
            if (PARENT_SEGMENT.equals(segment) || CURRENT_SEGMENT.equals(segment)) {
                throw new IllegalArgumentException(
                        "key must not contain a relative-navigation segment");
            }
        }
    }
}
