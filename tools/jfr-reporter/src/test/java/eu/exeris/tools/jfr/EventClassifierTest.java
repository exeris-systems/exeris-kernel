/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventClassifierTest {

    private static Frame f(String className, String method) {
        return new Frame(className, method, 1);
    }

    @Test
    @DisplayName("production code called from a TCK owns its allocation - a test frame below does not exempt it")
    void productionUnderHarnessIsProduction() {
        List<Frame> stack = List.of(
                f("java.lang.Thread$Builder", "unstarted"),
                f("eu.exeris.kernel.core.flow.CoreFlowRuntime", "launch"),
                f("eu.exeris.kernel.core.flow.CoreFlowRuntime", "schedule"),
                f("eu.exeris.kernel.tck.contract.flow.FlowZeroAllocTck", "runSingleIteration"),
                f("eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck", "lambda$allocationProfileMatchesTierContract$0"),
                f("org.junit.platform.commons.util.ReflectionUtils", "invokeMethod"));

        Frame owner = EventClassifier.ownerFrame(stack);

        assertThat(owner).isEqualTo(f("eu.exeris.kernel.core.flow.CoreFlowRuntime", "launch"));
        assertThat(EventClassifier.classifyOwner(owner)).isEqualTo(Owner.PRODUCTION);
    }

    @Test
    @DisplayName("an allocation the TCK makes itself is harness")
    void harnessOwnedIsHarness() {
        List<Frame> stack = List.of(
                f("eu.exeris.kernel.tck.contract.events.EventBusZeroAllocTck", "runSingleIteration"),
                f("eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck", "lambda$allocationProfileMatchesTierContract$0"));

        assertThat(EventClassifier.classifyOwner(EventClassifier.ownerFrame(stack))).isEqualTo(Owner.TEST_HARNESS);
    }

    @Test
    @DisplayName("JDK and coverage-agent frames are never owners")
    void runtimeFramesAreSkipped() {
        List<Frame> stack = List.of(
                f("java.util.concurrent.ConcurrentHashMap", "putVal"),
                f("jdk.internal.misc.Unsafe", "allocateInstance"),
                f("sun.nio.ch.SocketChannelImpl", "read"),
                f("com.sun.crypto.provider.AESCrypt", "encrypt"),
                f("org.jacoco.agent.rt.internal_1234.Offline", "getProbes"),
                f("eu.exeris.kernel.core.flow.CoreIdempotencyGuard", "tryClaimStep"));

        assertThat(EventClassifier.ownerFrame(stack).className())
                .isEqualTo("eu.exeris.kernel.core.flow.CoreIdempotencyGuard");
    }

    @Test
    @DisplayName("a stack with only JDK frames, or no frames, has no owner")
    void noOwner() {
        assertThat(EventClassifier.classifyOwner(EventClassifier.ownerFrame(List.of()))).isEqualTo(Owner.NO_OWNER);
        assertThat(EventClassifier.classifyOwner(EventClassifier.ownerFrame(List.of(
                f("java.lang.Thread", "run"), f("jdk.internal.vm.Continuation", "enter")))))
                .isEqualTo(Owner.NO_OWNER);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck, TEST_HARNESS",
            "eu.exeris.kernel.tck.contract.JfrAllocationMonitor, TEST_HARNESS",
            "eu.exeris.kernel.community.events.CommunityEventBusZeroAllocTckTest, TEST_HARNESS",
            "eu.exeris.kernel.core.transport.syscall.SyscallLoopbackRoundTripIT, TEST_HARNESS",
            "eu.exeris.kernel.community.graph.GraphChurnRatioTck$Fixture, TEST_HARNESS",
            "eu.exeris.kernel.community.CommunityFlowZeroAllocTckTest$$Lambda/0x0000000801234567, TEST_HARNESS",
            "eu.exeris.kernel.community.testkit.FakeClock, TEST_HARNESS",
            "eu.exeris.kernel.perf.jmh_generated.EventBusBenchmark_publish_jmhTest, TEST_HARNESS",
            "org.junit.jupiter.engine.execution.MethodInvocation, TEST_HARNESS",
            "org.openjdk.jmh.runner.BenchmarkHandler, TEST_HARNESS",
            "org.apache.maven.surefire.booter.ForkedBooter, TEST_HARNESS",
            "eu.exeris.kernel.core.flow.CoreFlowRuntime, PRODUCTION",
            "eu.exeris.kernel.core.events.InMemoryEventBus, PRODUCTION",
            "eu.exeris.kernel.community.memory.CommunityArenaShardPool$Bucket, PRODUCTION",
            "eu.exeris.kernel.core.latest.Snapshot, PRODUCTION",
            "eu.exeris.kernel.core.flow.Contest, PRODUCTION",
            "eu.exeris.kernel.core.flow.Unit, PRODUCTION",
            "org.postgresql.core.v3.QueryExecutorImpl, THIRD_PARTY",
            "org.apache.kafka.clients.producer.KafkaProducer, THIRD_PARTY",
    })
    void ownerCategoryByClass(String className, Owner expected) {
        assertThat(EventClassifier.classifyOwner(f(className, "m"))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "eu.exeris.kernel.core.flow.FlowKey, EXERIS",
            "eu.exeris.kernel.core.telemetry.jfr.TelemetryJfrEvents$KernelLifecycleJfrEvent, EXERIS",
            "java.lang.VirtualThread, LOOM",
            "java.lang.VirtualThread$VThreadContinuation, LOOM",
            "java.lang.ThreadBuilders$VirtualThreadBuilder, LOOM",
            "jdk.internal.vm.Continuation, LOOM",
            "jdk.internal.vm.StackChunk, LOOM",
            "java.util.concurrent.ForkJoinTask$RunnableExecuteAction, LOOM",
            "java.util.concurrent.ForkJoinTask$AdaptedRunnableAction, LOOM",
            "jdk.internal.foreign.NativeMemorySegmentImpl, PANAMA",
            "java.lang.foreign.Arena$1, PANAMA",
            "byte[], ARRAY",
            "java.lang.Object[], ARRAY",
            "[B, ARRAY",
            "[Ljava.lang.Object;, ARRAY",
            "java.util.concurrent.ConcurrentHashMap$Node, JDK",
            "java.lang.String, JDK",
            "java.util.concurrent.ConcurrentLinkedQueue$Node, JDK",
            "org.postgresql.jdbc.PgResultSet, OTHER",
    })
    void objectKindByClass(String className, ObjectKind expected) {
        assertThat(EventClassifier.classifyObject(className)).isEqualTo(expected);
    }
}
