/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.model.FlowState;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("PMD.PublicMemberInNonPublicType")
final class CoreFlowCatalog {

    public static final ConcurrentMap<String, CoreFlowExecutionPlan> SHARED_PLAN_CATALOG = new ConcurrentHashMap<>();
    public static final ConcurrentMap<FlowKey, FlowState> TERMINAL_STATE_CATALOG = new ConcurrentHashMap<>();

    private CoreFlowCatalog() {
    }
}
