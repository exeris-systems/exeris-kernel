/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpRetryPolicy;
import eu.exeris.kernel.tck.contract.http.AbstractHttpRetryPolicyTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: HttpRetryPolicy TCK (ADR-045 default policy)")
class CommunityHttpRetryPolicyTckTest extends AbstractHttpRetryPolicyTck {

    private static final int MAX_ATTEMPTS = 3;

    @Override
    protected HttpRetryPolicy createPolicy() {
        // Deterministic jitter (0.5) so backoff delays are reproducible under the TCK.
        return new CommunityHttpRetryPolicy(MAX_ATTEMPTS, 100L, 2_000L, () -> 0.5);
    }

    @Override
    protected int maxAttempts() {
        return MAX_ATTEMPTS;
    }
}
