/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

record FlowExecutionStepResult(int nextStep, boolean terminal) {

    /* default */ static FlowExecutionStepResult stop() {
        return new FlowExecutionStepResult(-1, true);
    }

    /* default */ static FlowExecutionStepResult next(int nextStep) {
        return new FlowExecutionStepResult(nextStep, false);
    }
}
