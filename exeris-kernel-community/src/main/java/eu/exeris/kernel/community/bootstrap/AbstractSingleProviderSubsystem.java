/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * A subsystem whose whole job is: discover one provider, bind it into one slot.
 *
 * <p>Security and crypto are exactly that, and were exactly the same twenty lines apart from the type
 * names — extracting only the {@code ServiceLoader} call left the skeleton behind and made the
 * duplication ratio worse, because the file got shorter while the repeated part did not. What varies
 * between the two is the contract type, the slot, and the DAG position; everything else is this class.
 *
 * <p>Persistence and HTTP deliberately do not extend it. They build engines and own lifecycles that
 * are not "one provider, one slot", and bending them to fit would trade honest difference for a
 * shared shape that lies.
 *
 * @param <T> the SPI provider contract this subsystem discovers
 */
/* default */ abstract class AbstractSingleProviderSubsystem<T> extends AbstractCommunitySubsystem {

    private T provider;

    /**
     * Returns the SPI contract to discover through {@link java.util.ServiceLoader}.
     *
     * @return the contract type
     */
    protected abstract Class<T> contract();

    /**
     * Returns the provider's priority accessor, used to pick a winner deterministically.
     *
     * @return the priority reader
     */
    protected abstract ToIntFunction<T> priority();

    /**
     * Returns the kernel slot the discovered provider is bound into.
     *
     * @return the target slot
     */
    protected abstract ScopedValue<T> slot();

    /**
     * Returns the discovered provider, or {@code null} when none is on the classpath.
     *
     * @return the provider or {@code null}
     */
    protected final T provider() {
        return provider;
    }

    @Override
    public final void initialize() {
        provider = CommunityProviderDiscovery.highestPriority(contract(), priority());
    }

    @Override
    public void start() {
        markRunning(provider != null);
    }

    @Override
    public void stop() {
        markRunning(false);
    }

    /**
     * Binds the provider, or leaves the slot untouched when none was found.
     *
     * <p>Absence is a configuration, not a failure. Leaving the slot unbound is what makes the readers
     * downstream fail closed — binding a placeholder would turn "no provider" into "a provider that
     * says yes to nothing in particular", which is far harder to diagnose.
     *
     * @return the carrier enricher for this subsystem
     */
    @Override
    public final UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (provider == null) {
            return defaultProviderBindings();
        }
        return CommunityCarrierBindings.operator(CommunityCarrierBindings.binding(slot(), provider));
    }
}
