/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.security;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;

/**
 * TCK: JFR Zero-Allocation monitor for the Security authenticate() hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>The authentication loop: allocate token buffer → call
 * {@code provider.authenticate(token)} → extract principal → close buffer.
 * Enterprise tier reuses pre-allocated JWT parsing buffers from the slab pool.
 * Community tier allocates per-call (bounded).
 *
 * @since 0.5
 */
public abstract class SecurityZeroAllocTck extends AbstractSubsystemZeroAllocTck {

    /**
     * Creates the contract; subclasses supply the provider binding via {@link #createProvider()}
     * and the token binding via {@link #createValidTokenBuffer()}.
     */
    public SecurityZeroAllocTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Creates the {@link SecurityProvider} under test.
     *
     * @return a configured provider instance
     */
    protected abstract SecurityProvider createProvider();

    /**
     * Creates a {@link LoanedBuffer} containing a valid, parseable token.
     *
     * @return a loaned buffer holding a valid token
     */
    protected abstract LoanedBuffer createValidTokenBuffer();

    private SecurityProvider provider;

    @Override protected String subsystemName()      { return "Security"; }
    @Override protected String hotPathDescription()  { return "authenticate(token) → extract principalId → close buffer"; }
    @Override protected int warmupIterations()       { return 100; }
    @Override protected int hotPathIterations()      { return 1_000; }
    @Override protected int maxExerisAllocationsPerIteration() { return 6; }

    @Override
    protected void bootstrapSubsystem() {
        provider = createProvider();
    }

    @Override
    protected void runSingleIteration() {
        try (LoanedBuffer token = createValidTokenBuffer()) {
            AuthenticationResult auth = provider.authenticate(token);
            // Force the JIT to treat auth as live (prevent dead-code elimination)
            if (auth.principal().principalId() == null) {
                throw new AssertionError("unreachable");
            }
        }
    }

    @Override
    protected void tearDownSubsystem() {
        // SecurityProvider has no close() — stateless by design
    }
}
