/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 *   <li>{@code name} and {@code topic} are heap {@link String}s, but only used at
 *       registration/lookup time, never in the hot dispatch path (the primitive
 *       {@code ordinal} is used there — see {@link EventDescriptor#eventTypeOrdinal()}).</li>
 * </ul>
 *
 * <h2>Binding-Agnostic Topic (ADR-050)</h2>
 * <p>{@code topic} is the optional, binding-agnostic routing target for events of this
 * type — the kernel sink for the SDK {@code @DomainEvent.topic} attribute. It is carried
 * here (on the per-<em>type</em> registration record) rather than on {@link EventDescriptor}
 * because a topic is a static per-type attribute, not per-event-instance data: putting it on
 * the descriptor would duplicate a type constant into every instance and would force the
 * primitive-only {@code EventDescriptor} to carry a non-primitive (or an interned ordinal),
 * breaking its Valhalla layout. Semantics:
 * <ul>
 *   <li>{@code null} / blank — <b>no topic override</b> (a blank {@code topic} is normalized to
 *       {@code null} at construction). Bindings fall back to their default routing (e.g. the Kafka
 *       binding derives the topic from the event-type {@code name}).</li>
 *   <li>non-blank — the binding-agnostic routing target. Broker bindings (e.g. Kafka) map it
 *       to the concrete broker topic; the in-memory {@link EventBus} treats it as
 *       <b>advisory</b> and does not route on it (topic-blind by design — ADR-049 H2).</li>
 * </ul>
 * <p>Whether a binding honours {@code topic} is a binding concern; the value round-trips
 * through the {@link EventRegistry} unchanged on every binding.
 *
 * @param name       the canonical event type name (e.g. {@code "UserCreated"})
 * @param ordinal    the integer ordinal assigned at registration time (O(1) routing key)
 * @param persistent {@code true} if events of this type must be durably written to the outbox
 * @param ordered    {@code true} if events of this type require strict FIFO ordering
 * @param topic      optional binding-agnostic routing target ({@code null}/blank = no override);
 *                   see the "Binding-Agnostic Topic" section above
 *
 * @since 0.5.0
 * @see EventRegistry
 * @see EventDescriptor#eventTypeOrdinal()
 */
public record EventTypeSpec(
        String name,
        int    ordinal,
        boolean persistent,
        boolean ordered,
        String topic
) {

    /**
     * Compact constructor — normalizes a blank {@code topic} to {@code null} so "no override" has a
     * single canonical form. Without this, {@code topic == null} and a blank {@code topic} would be
     * distinct under the record's structural {@link #equals(Object)} — a spurious registry conflict
     * ({@code EX-EVENT-6003}) when the 2-arg and 3-arg factories are mixed for the same type — even
     * though {@link #hasTopic()} already treats both as "no override". Normalizing here keeps the
     * identity view and the effective-value view of {@code topic} in agreement.
     */
    @SuppressWarnings("PMD.NullAssignment") // deliberate blank->null canonicalization of "no override"
    public EventTypeSpec {
        if (topic != null && topic.isBlank()) {
            topic = null;
        }
    }

    /**
     * Creates a non-persistent, unordered event type spec with no topic override
     * (sensible default for in-memory events).
     *
     * @param name    the event type name
     * @param ordinal the pre-assigned ordinal
     * @return spec with {@code persistent=false}, {@code ordered=false}, {@code topic=null}
     */
    @SuppressWarnings("PMD.ShortMethodName") // 'of' is a standard Java factory idiom (cf. List.of, Map.of)
    public static EventTypeSpec of(String name, int ordinal) {
        return new EventTypeSpec(name, ordinal, false, false, null);
    }

    /**
     * Creates a non-persistent, unordered event type spec carrying a binding-agnostic
     * {@code topic} (ADR-050).
     *
     * @param name    the event type name
     * @param ordinal the pre-assigned ordinal
     * @param topic   the binding-agnostic routing target ({@code null}/blank = no override)
     * @return spec with {@code persistent=false}, {@code ordered=false}
     */
    @SuppressWarnings("PMD.ShortMethodName") // 'of' is a standard Java factory idiom (cf. List.of, Map.of)
    public static EventTypeSpec of(String name, int ordinal, String topic) {
        return new EventTypeSpec(name, ordinal, false, false, topic);
    }

    /**
     * Creates a persistent, ordered event type spec with no topic override
     * (for domain events that require durability).
     *
     * @param name    the event type name
     * @param ordinal the pre-assigned ordinal
     * @return spec with {@code persistent=true}, {@code ordered=true}, {@code topic=null}
     */
    public static EventTypeSpec ofPersistent(String name, int ordinal) {
        return new EventTypeSpec(name, ordinal, true, true, null);
    }

    /**
     * Creates a persistent, ordered event type spec carrying a binding-agnostic {@code topic}
     * (ADR-050) — the shape the generated {@code *EventPublisher} uses to land a captured
     * {@code @DomainEvent.topic}.
     *
     * @param name    the event type name
     * @param ordinal the pre-assigned ordinal
     * @param topic   the binding-agnostic routing target ({@code null}/blank = no override)
     * @return spec with {@code persistent=true}, {@code ordered=true}
     */
    public static EventTypeSpec ofPersistent(String name, int ordinal, String topic) {
        return new EventTypeSpec(name, ordinal, true, true, topic);
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

    /**
     * @return {@code true} if this spec carries a {@code topic} override; {@code false} when a
     *         binding should fall back to its default routing (e.g. the event-type name). A blank
     *         {@code topic} is normalized to {@code null} in the canonical constructor, so this is
     *         equivalent to {@code topic != null}.
     */
    public boolean hasTopic() {
        return topic != null;
    }
}
