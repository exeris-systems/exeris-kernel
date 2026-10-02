/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import eu.exeris.kernel.core.transport.scheduler.StreamExecutionBackend;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PAQS {@link StreamExecutionBackend} implementation that executes admitted streams
 * on dedicated carrier threads with full continuation locality, distributing
 * streams across available carriers in round-robin order.
 */
public final class RoundRobinCarrierExecutionBackend implements StreamExecutionBackend {

    private final ExerisCarrierGroup group;
    private final AtomicInteger counter = new AtomicInteger(0);

    public RoundRobinCarrierExecutionBackend() {
        this(ExerisCarrierGroup.activeGroup());
    }

    public RoundRobinCarrierExecutionBackend(ExerisCarrierGroup group) {
        if (group == null) {
            throw new IllegalStateException(
                    "ExerisCarrierScheduler is not installed. Ensure "
                            + "-Djdk.virtualThreadScheduler.implClass="
                            + "eu.exeris.kernel.core.transport.scheduler.locality.ExerisCarrierScheduler is set."
            );
        }
        this.group = group;
    }

    public static boolean isAvailable() {
        return ExerisCarrierGroup.activeGroup() != null;
    }

    public static RoundRobinCarrierExecutionBackend createIfAvailable() {
        ExerisCarrierGroup group = ExerisCarrierGroup.activeGroup();
        return group != null ? new RoundRobinCarrierExecutionBackend(group) : null;
    }

    @Override
    public void start(String threadName, Runnable task) {
        int idx = Math.floorMod(counter.getAndIncrement(), group.size());
        ExerisCarrierThread carrier = group.carrier(idx);
        ThreadFactory factory = carrier.virtualThreadFactory();
        Thread thread = factory.newThread(task);
        if (threadName != null) {
            thread.setName(threadName);
        }
        thread.start();
    }

    public ExerisCarrierGroup group() {
        return group;
    }
}
