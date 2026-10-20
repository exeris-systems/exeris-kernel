/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import eu.exeris.kernel.spi.exceptions.events.EventRegistryException;

import java.util.Set;

/**
 * SPI: Tenant isolation key registry (ADR-101).
 *
 * <p>Maps an isolation key to the positive ordinal that {@link EventDescriptor#isolationOrdinal()}
 * carries. The bus compares ordinals, never strings, so a key must be registered before any
 * publication or subscription that names it.
 *
 * @implSpec An implementation does <b>not</b> assign ordinals: the caller supplies them through
 *           {@link IsolationKeySpec}, and the registry is the arbiter of uniqueness. Lookup is O(1),
 *           and {@link #ordinalOf(String)} performs no allocation.
 * @apiNote Reach the registry through {@link EventEngine#isolationRegistry()}. Register every key
 *          before publishing under its storage context: a publication under a bound but
 *          unregistered key is refused with {@code EX-EVENT-6009}.
 * @since 0.13
 * @see IsolationKeySpec
 * @see EventDescriptor#isolationOrdinal()
 */
public interface IsolationKeyRegistry {

    /**
     * Admits an isolation key into the routing table, claiming its ordinal for the life of the
     * registry.
     *
     * @param spec the isolation key specification (non-null)
     * @throws EventRegistryException {@code EX-EVENT-6012} if the key is already registered with a
     *         different ordinal, or the ordinal is already claimed by a different key;
     *         {@code rawArgs} carry {@code [String isolationKey, int ordinal]}
     * @implSpec Idempotent for an identical re-registration: the same key with the same ordinal has
     *           no effect and does not raise. Only a genuine disagreement conflicts.
     */
    void register(IsolationKeySpec spec);

    /**
     * Looks up the registration record for an isolation key.
     *
     * @param isolationKey the isolation key (non-null)
     * @return the registered spec, or {@code null} if the key was never registered
     * @implSpec O(1) — a hash lookup, not a scan.
     */
    IsolationKeySpec resolve(String isolationKey);

    /**
     * Resolves an isolation key to the integer the dispatch path compares, without materialising
     * the registration record.
     *
     * @param isolationKey the isolation key (non-null)
     * @return the ordinal claimed at registration, or {@code -1} if the key is not registered
     * @apiNote This is the hot-path lookup: it avoids handling an {@link IsolationKeySpec} at all.
     */
    default int ordinalOf(String isolationKey) {
        IsolationKeySpec spec = resolve(isolationKey);
        return spec != null ? spec.ordinal() : -1;
    }

    /**
     * Enumerates the isolation keys currently registered, as a snapshot that does not track later
     * registrations.
     *
     * @return an immutable set of the registered keys; empty when nothing is registered
     * @apiNote May allocate a fresh {@link Set} per call — for diagnostics and startup checks, not
     *          for a dispatch path.
     */
    Set<String> registeredKeys();

    /**
     * Answers whether an isolation key has a registration, without materialising it.
     *
     * @param isolationKey the isolation key (non-null)
     * @return {@code true} when {@link #resolve} would return a spec for this key
     */
    default boolean isRegistered(String isolationKey) {
        return resolve(isolationKey) != null;
    }

    /**
     * Counts the registrations held, the cardinality of {@link #registeredKeys()} without building
     * it.
     *
     * @return the number of registered isolation keys; never negative
     */
    int size();
}
