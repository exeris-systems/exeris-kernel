/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.scheduling;

import eu.exeris.kernel.spi.scheduling.JobDescriptor;
import eu.exeris.kernel.spi.scheduling.JobHandle;
import eu.exeris.kernel.spi.scheduling.JobState;

/**
 * A registered job: its schedule position, its captured identity, and its state.
 *
 * <p>All mutable fields are read and written under the clock lock held by
 * {@link CommunityJobRegistry}, which is also where {@code state()} and {@code cancel()} land. The
 * handle is not a synchronisation point of its own — a second lock here would only create an
 * ordering to get wrong.
 *
 * @since 0.11.0
 */
final class CommunityJobHandle implements JobHandle {

    private final CommunityJobRegistry registry;
    private final String jobId;
    private final JobDescriptor descriptor;
    private final CapturedContext context;

    private long dueNanos;
    private JobState state = JobState.SCHEDULED;

    /* default */ CommunityJobHandle(CommunityJobRegistry registry, String jobId,
                                     JobDescriptor descriptor, CapturedContext context,
                                     long dueNanos) {
        this.registry = registry;
        this.jobId = jobId;
        this.descriptor = descriptor;
        this.context = context;
        this.dueNanos = dueNanos;
    }

    @Override
    public String jobId() {
        return jobId;
    }

    @Override
    public String jobName() {
        return descriptor.jobName();
    }

    @Override
    public JobState state() {
        return registry.stateOf(this);
    }

    @Override
    public boolean cancel() {
        return registry.cancel(this);
    }

    /* default */ JobDescriptor descriptor() {
        return descriptor;
    }



    /* default */ CapturedContext context() {
        return context;
    }

    /* default */ long dueNanos() {
        return dueNanos;
    }

    /* default */ void dueNanos(long value) {
        this.dueNanos = value;
    }

    /* default */ JobState rawState() {
        return state;
    }

    /* default */ void rawState(JobState value) {
        this.state = value;
    }

    /* default */ boolean isTerminal() {
        return state == JobState.COMPLETED || state == JobState.CANCELLED;
    }
}
