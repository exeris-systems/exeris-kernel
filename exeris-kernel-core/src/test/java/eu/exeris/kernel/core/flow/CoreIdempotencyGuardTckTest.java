/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.IdempotencyGuard;
import eu.exeris.kernel.tck.contract.flow.AbstractIdempotencyGuardTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Core: CoreIdempotencyGuard TCK")
class CoreIdempotencyGuardTckTest extends AbstractIdempotencyGuardTck {

    @Override
    protected IdempotencyGuard createGuard() {
        return new CoreIdempotencyGuard();
    }
}
