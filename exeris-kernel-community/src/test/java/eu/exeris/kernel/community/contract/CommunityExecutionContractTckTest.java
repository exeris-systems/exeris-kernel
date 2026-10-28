/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.contract;

import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.tck.contract.AbstractExecutionContractTck;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Community: ExecutionContract TCK")
class CommunityExecutionContractTckTest extends AbstractExecutionContractTck {

    @Override
    protected ExecutionContract createContract() {
        return ExecutionContract.communityFallback();
    }
}
