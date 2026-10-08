/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.contract;

/**
 * {@link ScopedValue} slot for the {@link ExecutionContract} decided at bootstrap.
 *
 * <h2>Separation from {@code KernelProviders}</h2>
 * <p>The slot lives in the package that owns its type, so the generic
 * {@link eu.exeris.kernel.spi.context.KernelProviders} registry names no type from a package whose
 * maturity differs from its own (ADR-100).
 *
 * <h2>Binding (bootstrap side)</h2>
 * {@snippet lang="java" :
 * ScopedValue
 *     .where(ContractKernelProviders.EXECUTION_CONTRACT, contract)
 *     .run(kernel::startSubsystems);
 * }
 *
 * <h2>Reading (subsystem side)</h2>
 * {@snippet lang="java" :
 * ExecutionContract contract = ContractKernelProviders.executionContract();
 * }
 *
 * <p><b>Allocation:</b> zero-alloc — reading the slot allocates nothing.
 * <p><b>Thread confinement:</b> any thread inside the binding scope; the class documentation of
 * {@link eu.exeris.kernel.spi.context.KernelProviders} states which threads see a binding.
 * <p><b>Ownership:</b> the kernel bootstrapper owns the bound contract; a reader borrows it for the
 * duration of the binding scope.
 *
 * @since 0.13
 */
public final class ContractKernelProviders {

    /**
     * The active {@link ExecutionContract}, decided once by the kernel's contract gate.
     *
     * <p>Bound by {@code KernelBootstrap.boot()} in {@code exeris-kernel-core}: the contract gate runs
     * after configuration and before any subsystem is initialized, and its contract is bound for the
     * whole kernel scope. {@code KernelBootstrap.inspect()} runs no gate and leaves this unbound.
     *
     * @apiNote Reach the binding through the typed accessor {@link #executionContract()}.
     * @since 0.13
     */
    public static final ScopedValue<ExecutionContract> EXECUTION_CONTRACT = ScopedValue.newInstance();

    private ContractKernelProviders() {
        // Static ScopedValue slot only — never instantiated.
    }

    /**
     * Returns the active {@link ExecutionContract} from the current kernel scope.
     *
     * <p>Available inside the scope of {@code KernelBootstrap.boot()}, once the contract gate has decided
     * the contract.
     *
     * @return execution contract bound by the kernel bootstrapper
     * @throws java.util.NoSuchElementException if called outside the kernel scope
     * @since 0.13
     */
    public static ExecutionContract executionContract() {
        return EXECUTION_CONTRACT.get();
    }
}
