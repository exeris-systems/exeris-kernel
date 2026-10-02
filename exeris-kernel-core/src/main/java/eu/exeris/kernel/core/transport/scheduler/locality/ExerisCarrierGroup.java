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
        String affinityProp = System.getProperty("exeris.carrier.affinity");
        int[] cores = parseAffinityCores(affinityProp);
        int defaultCount = (cores != null && cores.length > 0)
                ? cores.length
                : Runtime.getRuntime().availableProcessors();
        int count = Integer.getInteger("exeris.carrier.count", defaultCount);
        this.carriers = new ExerisCarrierThread[count];
        for (int i = 0; i < count; i++) {
            int core = (cores != null && i < cores.length) ? cores[i] : -1;
            carriers[i] = new ExerisCarrierThread(i, scheduler, core);
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

    public void shutdown() {
        for (ExerisCarrierThread carrier : carriers) {
            carrier.shutdown();
        }
    }
}
