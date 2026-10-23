/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Global group of carrier threads, typically configured 1-to-1 with CPU cores.
 */
public final class ExerisCarrierGroup {
    private static final System.Logger LOG = System.getLogger(ExerisCarrierGroup.class.getName());
    private static volatile ExerisCarrierGroup activeGroup;

    private final ExerisCarrierThread[] carriers;

    ExerisCarrierGroup(ExerisCarrierScheduler scheduler) {
        this(scheduler, LocalityFlags.fromSystem(), 0);
    }

    /** A {@code carrierCount} of zero or less takes the count from the system properties. */
    ExerisCarrierGroup(ExerisCarrierScheduler scheduler, LocalityFlags flags, int carrierCount) {
        String affinityProp = System.getProperty("exeris.carrier.affinity");
        int[] cores = parseAffinityCores(affinityProp);
        int defaultCount = (cores != null && cores.length > 0)
                ? cores.length
                : Runtime.getRuntime().availableProcessors();
        int count = carrierCount > 0 ? carrierCount : Integer.getInteger("exeris.carrier.count", defaultCount);
        this.carriers = new ExerisCarrierThread[count];
        for (int i = 0; i < count; i++) {
            int core = (cores != null && i < cores.length) ? cores[i] : -1;
            carriers[i] = new ExerisCarrierThread(i, scheduler, core, flags, carriers);
        }
        // started only once every peer exists: a carrier that steals reads the whole array
        for (ExerisCarrierThread carrier : carriers) {
            carrier.start();
        }
        activeGroup = this;
    }

    public static ExerisCarrierGroup activeGroup() {
        if (activeGroup == null) {
            ExerisCarrierScheduler.ensureInstalled();
        }
        return activeGroup;
    }

    private static int[] parseAffinityCores(String prop) {
        if (prop == null || prop.isBlank()) {
            return null;
        }
        try {
            String[] parts = prop.split(",");
            int[] result = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Integer.parseInt(parts[i].trim());
            }
            return result;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING,
                    "[ExerisCarrierGroup] Invalid exeris.carrier.affinity property: {0}", prop);
            return null;
        }
    }

    public int size() {
        return carriers.length;
    }

    public ExerisCarrierThread carrier(int index) {
        return carriers[Math.floorMod(index, carriers.length)];
    }

    public ExerisCarrierThread selectCarrier() {
        return carriers[ThreadLocalRandom.current().nextInt(carriers.length)];
    }

    /**
     * Power of two choices: the shorter run queue of two randomly chosen carriers, by an O(1)
     * length estimate. Spreads new work without a global view and without hot-spotting one carrier.
     */
    ExerisCarrierThread selectShorterOfTwo() {
        return selectShorterOfTwo(null);
    }

    /** As {@link #selectShorterOfTwo()}; counts a pick that differs from the first draw when {@code stats} is given. */
    ExerisCarrierThread selectShorterOfTwo(SchedulerStats stats) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        ExerisCarrierThread first = carriers[random.nextInt(carriers.length)];
        ExerisCarrierThread second = carriers[random.nextInt(carriers.length)];
        ExerisCarrierThread chosen = second.queueLengthEstimate() < first.queueLengthEstimate() ? second : first;
        if (stats != null && chosen != first) {
            stats.startP2cOther.increment();
        }
        return chosen;
    }

    public void shutdown() {
        for (ExerisCarrierThread carrier : carriers) {
            carrier.shutdown();
        }
    }
}
