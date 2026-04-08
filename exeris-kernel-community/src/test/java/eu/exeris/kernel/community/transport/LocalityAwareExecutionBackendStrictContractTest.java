/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
// package eu.exeris.kernel.community.transport;

// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

// import java.lang.reflect.Method;
// import java.util.concurrent.CountDownLatch;
// import java.util.concurrent.ForkJoinPool;
// import java.util.concurrent.TimeUnit;

// import static org.assertj.core.api.Assertions.assertThat;
// import static org.assertj.core.api.Assertions.assertThatThrownBy;
// import static org.junit.jupiter.api.Assumptions.assumeTrue;

// @DisplayName("LocalityAwareExecutionBackend strict contract")
// /** Strict tests are opt-in due runtime API variability across JVMs. */
// @EnabledIfSystemProperty(named = "exeris.strict.tests", matches = "true")
// class LocalityAwareExecutionBackendStrictContractTest {

//     @Test
//     @DisplayName("strict mode follows JVM scheduler override capability")
//     void strictModeBehaviorMatchesJvmCapability() {
//         boolean capabilityAvailable = isVtSchedulerOverrideAvailable();
//         ForkJoinPool pool = new ForkJoinPool(1);

//         try {
//             if (capabilityAvailable) {
//                 LocalityAwareExecutionBackend backend = new LocalityAwareExecutionBackend(pool, true);
//                 assertThat(backend.isSchedulerOverrideEnabled()).isTrue();
//             } else {
//                 assertThatThrownBy(() -> new LocalityAwareExecutionBackend(pool, true))
//                         .isInstanceOf(IllegalStateException.class)
//                         .hasMessageContaining("unavailable");
//             }
//         } finally {
//             pool.shutdown();
//         }
//     }

//     @Test
//     @DisplayName("non-strict mode always constructs")
//     void nonStrictModeAlwaysConstructs() {
//         boolean capabilityAvailable = isVtSchedulerOverrideAvailable();
//         ForkJoinPool pool = new ForkJoinPool(1);

//         try {
//             LocalityAwareExecutionBackend backend = new LocalityAwareExecutionBackend(pool, false);
//             assertThat(backend.isSchedulerOverrideEnabled()).isEqualTo(capabilityAvailable);
//         } finally {
//             pool.shutdown();
//         }
//     }

//     @Test
//     @DisplayName("strict mode start runs task when scheduler override is supported")
//     void strictModeStartRunsTaskWhenSupported() throws InterruptedException {
//         assumeTrue(isVtSchedulerOverrideAvailable(), "VT scheduler override unavailable on this JVM");

//         ForkJoinPool pool = new ForkJoinPool(1);
//         try {
//             LocalityAwareExecutionBackend backend = new LocalityAwareExecutionBackend(pool, true);
//             CountDownLatch taskRan = new CountDownLatch(1);

//             backend.start("strict-locality-test", taskRan::countDown);

//             assertThat(taskRan.await(2, TimeUnit.SECONDS)).isTrue();
//         } finally {
//             pool.shutdown();
//         }
//     }

//     private static boolean isVtSchedulerOverrideAvailable() {
//         for (Method method : Thread.Builder.OfVirtual.class.getMethods()) {
//             if ("scheduler".equals(method.getName()) && method.getParameterCount() == 1) {
//                 return true;
//             }
//         }
//         return false;
//     }
// }
