/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Platform.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.events;

/**
 * Immutable event type specification.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>This record is designed for future migration to {@code value record}:
 * <ul>
 *   <li>No identity operations on this record.</li>
 *   <li>{@code ordinal} and boolean flags are primitive — scalarizable by JIT.</li>
 *   <li>{@code name} is a heap {@link String}, but only used at registration/lookup time,
 *       never in the hot dispatch path (ordinal is used there).</li>
 * </ul>
 *
 * @param name       the canonical event type name (e.g. {@code "UserCreated"})
 * @param ordinal    the integer ordinal assigned at registration time (O(1) routing key)
 * @param persistent {@code true} if events of this type must be durably written to the outbox
 * @param ordered    {@code true} if events of this type require strict FIFO ordering
 *
 * @since 0.5.0
 * @see EventRegistry
 * @see EventDescriptor#eventTypeOrdinal()
 */
public record EventTypeSpec(
        String name,
        int    ordinal,
        boolean persistent,
        boolean ordered
) {

    /**
     * Creates a non-persistent, unordered event type spec (sensible default for in-memory events).
     *
     * @param name    the event type name
     * @param ordinal the pre-assigned ordinal
     * @return spec with {@code persistent=false}, {@code ordered=false}
     */
    @SuppressWarnings("PMD.ShortMethodName") // 'of' is a standard Java factory idiom (cf. List.of, Map.of)
    public static EventTypeSpec of(String name, int ordinal) {
        return new EventTypeSpec(name, ordinal, false, false);
    }

    /**
     * Creates a persistent, ordered event type spec (for domain events that require durability).
     *
     * @param name    the event type name
     * @param ordinal the pre-assigned ordinal
     * @return spec with {@code persistent=true}, {@code ordered=true}
     */
    public static EventTypeSpec ofPersistent(String name, int ordinal) {
        return new EventTypeSpec(name, ordinal, true, true);
    }

    /**
     * Derives the appropriate {@link EventDescriptor} flags from this spec.
     *
     * @return bitmask of flags to use in {@link EventDescriptor#flags()}
     */
    public int toDescriptorFlags() {
        int flagsBitmask = 0;
        if (persistent) {
            flagsBitmask |= EventDescriptor.FLAG_PERSISTENT;
        }
        if (ordered) {
            flagsBitmask |= EventDescriptor.FLAG_ORDERED;
        }
        return flagsBitmask;
    }
}

