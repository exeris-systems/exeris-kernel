/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.scheduling;

import eu.exeris.kernel.spi.scheduling.JobTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When a trigger next fires, expressed as a deadline on the dispatcher's monotonic scale.
 *
 * <p>Separated from the scheduler because this is arithmetic over triggers and the scheduler is a
 * queue and a lifecycle. It is also where the two clocks meet: cron names calendar instants, while
 * the dispatcher waits on monotonic nanos, and the conversion belongs in one place.
 *
 * @since 0.11.0
 */
final class CommunityTriggerClock {

    private final CommunitySchedulerClock clock;
    private final Map<String, CommunityCronSchedule> cronSchedules = new ConcurrentHashMap<>();

    /* default */ CommunityTriggerClock(CommunitySchedulerClock clock) {
        this.clock = clock;
    }

    /** Compiles a cron expression once, at submission, rather than on every fire. */
    /* default */ void register(String jobId, JobTrigger trigger) {
        if (trigger instanceof JobTrigger.Cron cron) {
            cronSchedules.put(jobId, new CommunityCronSchedule(cron.expression()));
        }
    }

    /* default */ long firstDue(String jobId, JobTrigger trigger) {
        return switch (trigger) {
            case JobTrigger.OneShot oneShot -> clock.nanoTime() + oneShot.delay().toNanos();
            case JobTrigger.FixedInterval interval ->
                    clock.nanoTime() + interval.initialDelay().toNanos();
            case JobTrigger.Cron _ -> cronDue(jobId);
        };
    }

    /* default */ long nextDue(String jobId, JobTrigger trigger) {
        return switch (trigger) {
            case JobTrigger.FixedInterval interval -> clock.nanoTime() + interval.interval().toNanos();
            case JobTrigger.Cron _ -> cronDue(jobId);
            case JobTrigger.OneShot _ -> clock.nanoTime();
        };
    }

    private long cronDue(String jobId) {
        Instant now = clock.wallTime();
        Instant next = cronSchedules.get(jobId).nextFireAfter(now);
        return clock.nanoTime() + Duration.between(now, next).toNanos();
    }
}
