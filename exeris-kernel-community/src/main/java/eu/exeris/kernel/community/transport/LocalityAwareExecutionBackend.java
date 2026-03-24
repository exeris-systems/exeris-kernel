/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.core.transport.scheduler.StreamExecutionBackend;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;

/**
 * Locality-aware PAQS execution backend for research track C4.
 *
 * <p>Pins Virtual Thread continuations to a caller-supplied {@link ForkJoinPool},
 * reducing cross-pool migrations compared to the JVM default carrier scheduler when
 * multiple CPU-intensive streams are co-located on the same {@link NativeTcpCarrier}.
 *
 * <p>The pool is used as a VT carrier scheduler, not as a structured-concurrency
 * replacement; lifecycle management is the caller's responsibility.
 *
 * @since 0.5.1
 */
final class LocalityAwareExecutionBackend implements StreamExecutionBackend {

    private static final Method VT_SCHEDULER_METHOD = resolveVtSchedulerMethod();

    private final ForkJoinPool carrierPool;
    private final boolean strictMode;
    private final boolean schedulerOverrideEnabled;

    /* default */ LocalityAwareExecutionBackend(ForkJoinPool carrierPool) {
        this(carrierPool, false);
    }

    /* default */ LocalityAwareExecutionBackend(ForkJoinPool carrierPool, boolean strictMode) {
        this.carrierPool = Objects.requireNonNull(carrierPool, "carrierPool must not be null");
        this.strictMode = strictMode;
        this.schedulerOverrideEnabled = VT_SCHEDULER_METHOD != null;
        if (strictMode && !schedulerOverrideEnabled) {
            throw new IllegalStateException("VT scheduler override unavailable");
        }
    }

    @Override
    public void start(String threadName, Runnable task) {
        // VT carrier pool: pins continuation to a dedicated FJP for cache-affinity research (C4).
        Thread.Builder.OfVirtual builder = Thread.ofVirtual().name(threadName);
        configureSchedulerIfSupported(builder, carrierPool, strictMode).start(task);
    }

    /* default */ boolean isSchedulerOverrideEnabled() {
        return schedulerOverrideEnabled;
    }

    private static Method resolveVtSchedulerMethod() {
        for (Method method : Thread.Builder.OfVirtual.class.getMethods()) {
            if ("scheduler".equals(method.getName()) && method.getParameterCount() == 1) {
                return method;
            }
        }
        return null;
    }

    private static Thread.Builder.OfVirtual configureSchedulerIfSupported(
            Thread.Builder.OfVirtual builder,
            ForkJoinPool scheduler,
            boolean strictMode
    ) {
        Method method = VT_SCHEDULER_METHOD;
        if (method == null) {
            return builder;
        }
        try {
            Object configured = method.invoke(builder, scheduler);
            if (configured instanceof Thread.Builder.OfVirtual configuredBuilder) {
                return configuredBuilder;
            }
            if (strictMode) {
                throw new IllegalStateException("VT scheduler override failed");
            }
        } catch (ReflectiveOperationException ignored) {
            if (strictMode) {
                throw new IllegalStateException("VT scheduler override failed");
            }
            // Fallback to default scheduler when JVM build does not expose scheduler customization.
        }
        return builder;
    }
}
