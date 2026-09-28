/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.lang.Thread.VirtualThreadScheduler;
import java.lang.Thread.VirtualThreadTask;

/**
 * Custom VirtualThreadScheduler loaded by the JDK via
 * {@code -Djdk.virtualThreadScheduler.implClass=eu.exeris.kernel.core.transport.scheduler.locality.ExerisCarrierScheduler}.
 *
 * <p>Routes virtual thread tasks to their designated carrier thread when one is assigned
 * (via {@link CarrierSchedulingContext} attachment), or when {@code -Djdk.pollerMode=3}
 * (per-carrier pollers) is enabled for the read-poller thread. Otherwise falls back to
 * the JDK's built-in scheduler.
 */
public class ExerisCarrierScheduler implements VirtualThreadScheduler {

    private static volatile ExerisCarrierScheduler instance;

    private final VirtualThreadScheduler builtinScheduler;
    private final ExerisCarrierGroup group;
    private final boolean perCarrierPollers;
    private final boolean assignAllVThreads;

    public ExerisCarrierScheduler(VirtualThreadScheduler builtinScheduler) {
        this.builtinScheduler = builtinScheduler;
        this.perCarrierPollers = Integer.getInteger("jdk.pollerMode", -1) == 3;
        this.assignAllVThreads = Boolean.getBoolean("exeris.locality.allVthreads");
        this.group = new ExerisCarrierGroup(this);
        instance = this;
    }

    public static ExerisCarrierScheduler instance() {
        return ensureInstalled();
    }

    public static ExerisCarrierGroup group() {
        ExerisCarrierScheduler scheduler = ensureInstalled();
        return scheduler != null ? scheduler.group : null;
    }

    public ExerisCarrierGroup carrierGroup() {
        return group;
    }

    @Override
    public void onStart(VirtualThreadTask task) {
        if (task.attachment() instanceof CarrierSchedulingContext context) {
            ExerisCarrierThread carrier = context.carrier();
            if (carrier != null) {
                carrier.enqueue(task);
                return;
            }
            task.attach(null);
        } else {
            ExerisCarrierThread currentCarrier = ExerisCarrierThread.currentCarrier();
            if (perCarrierPollers && currentCarrier != null && task.thread().getName() != null
                    && task.thread().getName().endsWith("-Read-Poller")) {
                CarrierSchedulingContext context =
                        new CarrierSchedulingContext(currentCarrier.id(), currentCarrier);
                context.setVThreadId(task.thread().threadId());
                task.attach(context);
                currentCarrier.enqueue(task);
                return;
            }
            if (assignAllVThreads && group != null && group.size() > 0) {
                ExerisCarrierThread carrier = currentCarrier != null
                        ? currentCarrier
                        : group.selectCarrier();
                CarrierSchedulingContext context =
                        new CarrierSchedulingContext(carrier.id(), carrier);
                context.setVThreadId(task.thread().threadId());
                task.attach(context);
                carrier.enqueue(task);
                return;
            }
        }
        builtinScheduler.onStart(task);
    }

    @Override
    public void onContinue(VirtualThreadTask task) {
        if (task.attachment() instanceof CarrierSchedulingContext context) {
            ExerisCarrierThread carrier = context.carrier();
            if (carrier != null) {
                carrier.enqueue(task);
                return;
            }
            task.attach(null);
        }
        builtinScheduler.onContinue(task);
    }

    static ExerisCarrierScheduler ensureInstalled() {
        ExerisCarrierScheduler inst = instance;
        if (inst != null) {
            return inst;
        }
        Thread.ofVirtual().unstarted(() -> { }).start();
        return instance;
    }

    public static boolean isAvailable() {
        return ensureInstalled() != null;
    }
}
