/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.isolation;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.IsolationKeyRegistry;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;

import java.util.Optional;

/**
 * Core: stamps a publication's isolation ordinal from the publisher's bound storage context
 * (ADR-101).
 *
 * <h2>Rule</h2>
 * <p>The ordinal a publication carries is the one the publisher's bound isolation key resolves to,
 * never a value the caller chose on its own. An unscoped publication (no bound context, or an empty
 * key) carries {@code 0}. A caller may pre-stamp the ordinal its context resolves to, and that
 * descriptor passes through unchanged. Any other combination is refused, so a publisher cannot
 * address a tenant's subscribers by writing a number.
 *
 * <p>Each refusal is {@code EX-EVENT-6009} with a static reason, and no handler is given the event.
 *
 * <h2>Allocation</h2>
 * <p>The stamp returns the descriptor it was given when no change is needed, so the common path
 * allocates nothing. A new descriptor is built only when the ordinal is filled in.
 *
 * @since 0.13
 */
public final class IsolationStamp {

    /** Refusal reason: the bound isolation key is not registered. */
    /* default */ static final String REASON_UNREGISTERED_KEY = "unregistered-isolation-key";

    /** Refusal reason: the declared ordinal disagrees with the bound context. */
    /* default */ static final String REASON_MISMATCH = "isolation-mismatch";

    private IsolationStamp() {
    }

    /**
     * Returns the descriptor the bus should route, with its isolation ordinal resolved from the
     * publisher's bound storage context.
     *
     * @param descriptor the descriptor as the caller submitted it (non-null)
     * @param registry   the registry that resolves isolation keys to ordinals (non-null)
     * @return {@code descriptor} itself when its ordinal already matches, or a copy carrying the
     *         resolved ordinal when it was {@code 0}
     * @throws EventBusException {@code EX-EVENT-6009} with reason {@code "unregistered-isolation-key"}
     *         when the bound key is not registered, or {@code "isolation-mismatch"} when the
     *         descriptor's non-zero ordinal does not match the bound context, or no context is bound
     */
    public static EventDescriptor stamp(EventDescriptor descriptor, IsolationKeyRegistry registry) {
        int effective = boundOrdinal(descriptor.eventTypeOrdinal(), registry);
        int declared  = descriptor.isolationOrdinal();
        if (declared == effective) {
            return descriptor;
        }
        if (declared == 0) {
            return EventDescriptor.of(
                    descriptor.eventIdHigh(), descriptor.eventIdLow(),
                    descriptor.streamIdHigh(), descriptor.streamIdLow(),
                    descriptor.eventTypeOrdinal(), descriptor.flags(),
                    descriptor.occurredAtEpochMs(), effective);
        }
        throw EventBusException.publishFailed(descriptor.eventTypeOrdinal(), REASON_MISMATCH, null);
    }

    /**
     * Resolves the ordinal of the bound isolation key, or {@code 0} when the publication is unscoped.
     */
    private static int boundOrdinal(int eventTypeOrdinal, IsolationKeyRegistry registry) {
        if (!KernelProviders.STORAGE_CONTEXT.isBound()) {
            return 0;
        }
        Optional<String> key = KernelProviders.STORAGE_CONTEXT.get().isolationKey();
        if (key.isEmpty()) {
            return 0;
        }
        int ordinal = registry.ordinalOf(key.get());
        if (ordinal < 0) {
            throw EventBusException.publishFailed(eventTypeOrdinal, REASON_UNREGISTERED_KEY, null);
        }
        return ordinal;
    }
}
