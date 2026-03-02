/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.persistence;

/**
 * Backward-compatibility shim for {@link AbstractTransactionalExecutorTck}.
 *
 * <p>Renamed to {@link AbstractTransactionalExecutorTck} to accurately reflect the
 * SPI interface under test ({@link eu.exeris.kernel.spi.persistence.TransactionalExecutor}).
 * Migrate all subclasses to extend {@link AbstractTransactionalExecutorTck} directly.
 * This shim will be removed in 0.6.0.
 *
 * @deprecated Use {@link AbstractTransactionalExecutorTck} instead.
 * @since 0.5.0
 */
@Deprecated(since = "0.5.0", forRemoval = true)
public abstract class AbstractTransactionOrchestratorTck extends AbstractTransactionalExecutorTck {
    // Backward-compatibility bridge only — no logic here.
    // Extend AbstractTransactionalExecutorTck directly in all new subclasses.
}
