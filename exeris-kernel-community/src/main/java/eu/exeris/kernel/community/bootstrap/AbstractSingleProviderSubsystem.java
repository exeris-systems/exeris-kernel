/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
     * @implSpec Return the interface type itself, not an implementation class — the value is passed
     *           directly to {@link java.util.ServiceLoader#load(Class)}.
     */
    protected abstract Class<T> contract();

    /**
     * Returns the provider's priority accessor, used to pick a winner deterministically.
     *
     * @return the priority reader
     * @implSpec Return a function that reads the discovered provider's own priority, not a fixed
     *           constant; ties between two providers at the same priority are broken by class name.
     */
    protected abstract ToIntFunction<T> priority();

    /**
     * Returns the kernel slot the discovered provider is bound into.
     *
     * @return the target slot
     * @implSpec Return the same {@link ScopedValue} constant on every call — it is used once, in
     *           {@link #providerBindings()}, as the key the discovered provider is bound under.
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

    /**
     * Discovers this subsystem's provider via {@link CommunityProviderDiscovery#highestPriority},
     * using {@link #contract()} to select the SPI type and {@link #priority()} to break ties.
     */
    @Override
    public final void initialize() {
        provider = CommunityProviderDiscovery.highestPriority(contract(), priority());
    }

    /**
     * Marks this subsystem running when discovery found a provider.
     *
     * @implSpec Overriders that need additional startup behavior must call {@code super.start()} (or
     *           otherwise call {@link #markRunning}) so {@link #isRunning()} keeps reflecting
     *           discovery — this default has no other effect.
     */
    @Override
    public void start() {
        markRunning(provider != null);
    }

    /**
     * Marks this subsystem stopped.
     *
     * @implSpec Overriders that release additional resources must call {@code super.stop()} so
     *           {@link #isRunning()} is cleared; see {@link CommunityCryptoSubsystem#stop()} for the
     *           pattern of closing a native handle after the flag is updated.
     */
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
