/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

/**
 * Which field of which allocation event means bytes, and what those bytes are.
 *
 * <p>The three events are not the same measurement, and their byte fields must never be added
 * across types:
 * <ul>
 *   <li>{@code jdk.ObjectAllocationSample} carries {@code weight}: the sampler's extrapolation of
 *       bytes allocated on that thread since its previous sample. Summing weights per frame or
 *       thread estimates allocation pressure; a single weight is not an object size.</li>
 *   <li>{@code jdk.ObjectAllocationInNewTLAB} fires when a TLAB fills. {@code allocationSize} is
 *       the one object that did not fit; {@code tlabSize} is the buffer just handed out, and is
 *       the closer extrapolation of what the previous buffer's worth of allocation represented.</li>
 *   <li>{@code jdk.ObjectAllocationOutsideTLAB} carries the exact {@code allocationSize} of one
 *       large object.</li>
 * </ul>
 */
final class SizePolicy {

    static final String SAMPLE = "jdk.ObjectAllocationSample";
    static final String IN_NEW_TLAB = "jdk.ObjectAllocationInNewTLAB";
    static final String OUTSIDE_TLAB = "jdk.ObjectAllocationOutsideTLAB";

    /** What a {@link Size#bytes()} figure is. */
    enum SizeKind { SAMPLE_WEIGHT, TLAB_SIZE, EXACT, UNKNOWN }

    /**
     * A byte figure and what it means.
     *
     * @param bytes the figure
     * @param kind  its meaning
     */
    record Size(long bytes, SizeKind kind) {}

    private SizePolicy() {}

    /**
     * Picks the byte figure for an event. Missing fields arrive as {@code 0}.
     *
     * @param eventType      the JFR event type name
     * @param allocationSize the {@code allocationSize} field, or 0
     * @param weight         the {@code weight} field, or 0
     * @param tlabSize       the {@code tlabSize} field, or 0
     * @return the figure and its kind
     */
    static Size sizeOf(String eventType, long allocationSize, long weight, long tlabSize) {
        return switch (eventType) {
            case SAMPLE -> new Size(weight, SizeKind.SAMPLE_WEIGHT);
            case IN_NEW_TLAB -> new Size(tlabSize, SizeKind.TLAB_SIZE);
            case OUTSIDE_TLAB -> new Size(allocationSize, SizeKind.EXACT);
            default -> new Size(0L, SizeKind.UNKNOWN);
        };
    }
}
