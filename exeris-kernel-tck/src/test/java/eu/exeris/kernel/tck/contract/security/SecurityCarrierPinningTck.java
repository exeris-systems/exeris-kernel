/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.security;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.tck.contract.AbstractSubsystemCarrierPinningTck;
import org.junit.jupiter.api.DisplayName;

/**
 * TCK: Carrier pinning verifier for the Security authenticate() hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>{@code provider.authenticate(token)} — token validation must never block
 * a carrier thread (no synchronized, no blocking I/O on the hot path).
 *
 * @since 0.5.0
 * @see AbstractSubsystemCarrierPinningTck
 * @see SecurityZeroAllocTck
 */
@DisplayName("Security carrier pinning TCK")
public abstract class SecurityCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {

    protected abstract SecurityProvider createProvider();
    protected abstract LoanedBuffer createValidTokenBuffer();

    private SecurityProvider provider;
    private LoanedBuffer     validToken;

    @Override protected String subsystemName()      { return "Security"; }
    @Override protected String hotPathDescription() { return "authenticate(token) → extract principalId"; }

    @Override
    protected void bootstrapSubsystem() {
        provider   = createProvider();
        validToken = createValidTokenBuffer();
    }

    @Override
    protected void runSingleIteration() {
        var auth = provider.authenticate(validToken);
        if (auth.principal().principalId() == null) throw new AssertionError("unreachable");
    }

    @Override
    protected void tearDownSubsystem() {
        if (validToken != null) validToken.close();
        // SecurityProvider is stateless — no close()
    }
}

