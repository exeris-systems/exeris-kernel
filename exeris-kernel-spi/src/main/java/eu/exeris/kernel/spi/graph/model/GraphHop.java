/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph.model;

import java.util.Objects;

/**
 * One hop of a {@link GraphPathSpec}: follow {@code edge}'s relationship type, in the direction its
 * descriptor names, between {@code minRepeat} and {@code maxRepeat} consecutive times.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Structured so it can be migrated to a {@code value record} once JEP 401 is mainline.
 * Avoid identity operations ({@code ==}, {@code synchronized}, {@code System.identityHashCode()}).
 *
 * @param edge      relationship type, direction and backing table of this hop
 * @param minRepeat fewest consecutive edges of this hop on a matching walk (at least 1)
 * @param maxRepeat most consecutive edges of this hop on a matching walk (at least {@code minRepeat})
 * @since 0.13
 */
public value record GraphHop(GraphEdgeDescriptor edge, int minRepeat, int maxRepeat) {

    private static final int MIN_REPEAT = 1;

    /**
     * Rejects {@code null} edge, a {@code minRepeat} below 1, or a {@code maxRepeat} below {@code minRepeat}.
     *
     * @throws NullPointerException     if {@code edge} is {@code null}
     * @throws IllegalArgumentException if {@code minRepeat} is below 1 or {@code maxRepeat} is below {@code minRepeat}
     */
    public GraphHop {
        Objects.requireNonNull(edge, "edge must not be null");
        if (minRepeat < MIN_REPEAT) {
            throw new IllegalArgumentException("minRepeat must be >= 1, got: " + minRepeat);
        }
        if (maxRepeat < minRepeat) {
            throw new IllegalArgumentException("maxRepeat must be >= minRepeat " + minRepeat + ", got: " + maxRepeat);
        }
    }

    /**
     * Returns a hop of exactly one repetition of {@code edge}.
     *
     * @param edge relationship type, direction and backing table
     * @return a single-repetition hop
     * @throws NullPointerException if {@code edge} is {@code null}
     */
    @SuppressWarnings("PMD.ShortMethodName") // 'of' is a standard Java factory idiom (cf. List.of, Map.of)
    public static GraphHop of(GraphEdgeDescriptor edge) {
        return new GraphHop(edge, 1, 1);
    }
}
