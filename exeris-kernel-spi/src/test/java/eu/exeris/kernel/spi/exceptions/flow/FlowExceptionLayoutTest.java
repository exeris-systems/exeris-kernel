/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.flow;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: Flow exception rawArgs binary layouts.
 *
 * <h2>FlowEngineException rawArgs (EX-FLOW-7002)</h2>
 * <pre>
 * index 0 → String  engineName
 * index 1 → String  phase           ("START" | "STOP" | "COMPILE" | "SCHEDULE")
 * index 2 → String  staticReasonCode (e.g. "STARTUP_FAILED", "COMPILE_FAILED", "QUEUE_FULL")
 * index 3 → int     contextValue    (queue depth for SCHEDULE; -1 when not applicable)
 * </pre>
 *
 * <h2>FlowExecutionException rawArgs (EX-FLOW-7003)</h2>
 * <pre>
 * index 0 → String  definitionName
 * index 1 → long    instanceIdMost
 * index 2 → long    instanceIdLeast
 * index 3 → int     stepIndex
 * index 4 → String  staticReasonCode ("STEP_FAILED" | "COMPENSATION_FAILED")
 * index 5 → String  causeType        (class name or "none")
 * </pre>
 *
 * @since 0.5.0
 */
@DisplayName("L0: Flow Exception rawArgs Binary Layouts")
class FlowExceptionLayoutTest {

    // -----------------------------------------------------------------------
    // FlowEngineException — startupFailure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("FlowEngineException.startupFailure (EX-FLOW-7002)")
    class StartupFailure {

        @Test
        @DisplayName("rawArgs[0]=engineName, [1]='START', [2]='STARTUP_FAILED', [3]=-1")
        void rawArgsLayout() {
            RuntimeException root = new RuntimeException("port conflict");
            FlowEngineException ex = FlowEngineException.startupFailure("SagaEngine", root);

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_FLOW_7002);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(4);
            assertThat(raw[0]).isEqualTo("SagaEngine");
            assertThat(raw[1]).isEqualTo("START");
            assertThat(raw[2]).isEqualTo("STARTUP_FAILED");
            assertThat(raw[3]).isEqualTo(-1);
        }

        @Test
        @DisplayName("Cause is preserved by reference")
        void causePreserved() {
            RuntimeException root = new RuntimeException("init error");
            FlowEngineException ex = FlowEngineException.startupFailure("E", root);
            assertThat(ex.getCause()).isSameAs(root);
        }

        @Test
        @DisplayName("null cause is accepted")
        void nullCauseAccepted() {
            FlowEngineException ex = FlowEngineException.startupFailure("E", null);
            assertThat(ex.getCause()).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // FlowEngineException — compileFailure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("FlowEngineException.compileFailure (EX-FLOW-7002)")
    class CompileFailure {

        @Test
        @DisplayName("rawArgs[1]='COMPILE', rawArgs[2]='COMPILE_FAILED', rawArgs[3]=-1")
        void rawArgsLayout() {
            FlowEngineException ex = FlowEngineException.compileFailure("SagaEngine", null);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(4);
            assertThat(raw[0]).isEqualTo("SagaEngine");
            assertThat(raw[1]).isEqualTo("COMPILE");
            assertThat(raw[2]).isEqualTo("COMPILE_FAILED");
            assertThat(raw[3]).isEqualTo(-1);
        }
    }

    // -----------------------------------------------------------------------
    // FlowEngineException — schedulerFull
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("FlowEngineException.schedulerFull (EX-FLOW-7002)")
    class SchedulerFull {

        @Test
        @DisplayName("rawArgs[1]='SCHEDULE', [2]='QUEUE_FULL', [3]=queueDepth (int)")
        void rawArgsLayout() {
            FlowEngineException ex = FlowEngineException.schedulerFull("SagaEngine", 512);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(4);
            assertThat(raw[0]).isEqualTo("SagaEngine");
            assertThat(raw[1]).isEqualTo("SCHEDULE");
            assertThat(raw[2]).isEqualTo("QUEUE_FULL");
            assertThat(raw[3]).isEqualTo(512);
        }

        @Test
        @DisplayName("rawArgs[3] is Integer type — binary serializer contract")
        void slot3IsInteger() {
            FlowEngineException ex = FlowEngineException.schedulerFull("E", 100);
            assertThat(ex.rawArgs()[3]).isInstanceOf(Integer.class);
        }

        @Test
        @DisplayName("getCause() is null — schedulerFull has no cause")
        void noCause() {
            assertThat(FlowEngineException.schedulerFull("E", 0).getCause()).isNull();
        }

        @Test
        @DisplayName("Is unchecked (RuntimeException)")
        void isUnchecked() {
            assertThat(FlowEngineException.schedulerFull("E", 0))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // -----------------------------------------------------------------------
    // FlowExecutionException — stepFailure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("FlowExecutionException.stepFailure (EX-FLOW-7003)")
    class StepFailure {

        @Test
        @DisplayName("rawArgs: [definitionName, idMost, idLeast, stepIndex, 'STEP_FAILED', causeType]")
        void rawArgsLayout() {
            RuntimeException cause = new RuntimeException("db error");
            FlowExecutionException ex = FlowExecutionException.stepFailure(
                    "OrderSaga", 0xDEADBEEFL, 0xCAFEBABEL, 3, cause);

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_FLOW_7003);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(6);
            assertThat(raw[0]).isEqualTo("OrderSaga");
            assertThat(raw[1]).isEqualTo(0xDEADBEEFL);
            assertThat(raw[2]).isEqualTo(0xCAFEBABEL);
            assertThat(raw[3]).isEqualTo(3);
            assertThat(raw[4]).isEqualTo("STEP_FAILED");
            assertThat(raw[5]).isEqualTo("java.lang.RuntimeException");
        }

        @Test
        @DisplayName("null cause maps causeType to 'none' sentinel")
        void nullCauseMapsToNone() {
            FlowExecutionException ex = FlowExecutionException.stepFailure(
                    "OrderSaga", 1L, 2L, 0, null);
            assertThat(ex.rawArgs()[5]).isEqualTo("none");
        }

        @Test
        @DisplayName("rawArgs[1] and [2] are Long — binary serializer contract")
        void idSlotsAreLong() {
            FlowExecutionException ex = FlowExecutionException.stepFailure("S", 1L, 2L, 0, null);
            assertThat(ex.rawArgs()[1]).isInstanceOf(Long.class);
            assertThat(ex.rawArgs()[2]).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("rawArgs[3] is Integer — binary serializer contract")
        void stepIndexIsInteger() {
            FlowExecutionException ex = FlowExecutionException.stepFailure("S", 1L, 2L, 5, null);
            assertThat(ex.rawArgs()[3]).isInstanceOf(Integer.class);
        }
    }

    // -----------------------------------------------------------------------
    // FlowExecutionException — compensationFailure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("FlowExecutionException.compensationFailure (EX-FLOW-7003)")
    class CompensationFailure {

        @Test
        @DisplayName("rawArgs[4]='COMPENSATION_FAILED' sentinel is correct")
        void rawArgsReasonCode() {
            FlowExecutionException ex = FlowExecutionException.compensationFailure(
                    "OrderSaga", 1L, 2L, 2, new RuntimeException("rollback fail"));
            assertThat(ex.rawArgs()[4]).isEqualTo("COMPENSATION_FAILED");
        }

        @Test
        @DisplayName("Cause is preserved by reference")
        void causePreserved() {
            RuntimeException root = new RuntimeException("rollback fail");
            FlowExecutionException ex = FlowExecutionException.compensationFailure(
                    "OrderSaga", 1L, 2L, 2, root);
            assertThat(ex.getCause()).isSameAs(root);
        }
    }
}

