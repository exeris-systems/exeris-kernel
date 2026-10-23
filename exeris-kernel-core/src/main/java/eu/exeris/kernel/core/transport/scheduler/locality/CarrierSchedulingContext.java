/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Attachment stored on {@link java.lang.Thread.VirtualThreadTask} to associate
 * a virtual thread with its assigned carrier thread.
 *
 * <p>The carrier is the thread's <em>current</em> home: the target of its next wakeup. It can be
 * rewritten by another thread (a carrier that takes the task from a peer's queue) before that
 * thread runs the task, so it is published with release/acquire semantics through a
 * {@link VarHandle}: whoever routes the task's next wakeup sees the carrier the task last ran on.
 *
 * <p>A pinned context is never moved: its task is only ever run by the carrier it was routed to.
 */
public final class CarrierSchedulingContext {
    private static final VarHandle HOME;

    static {
        try {
            HOME = MethodHandles.lookup().findVarHandle(
                    CarrierSchedulingContext.class, "home", ExerisCarrierThread.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int carrierId;
    private final boolean pinned;
    @SuppressWarnings("unused")
    private ExerisCarrierThread home;
    private long vThreadId;

    public CarrierSchedulingContext(int carrierId, ExerisCarrierThread carrier) {
        this(carrierId, carrier, false);
    }

    CarrierSchedulingContext(int carrierId, ExerisCarrierThread carrier, boolean pinned) {
        this.carrierId = carrierId;
        this.pinned = pinned;
        this.vThreadId = -1;
        HOME.setRelease(this, carrier);
    }

    /** Id of the current home carrier; the id given at construction while there is none. */
    public int carrierId() {
        ExerisCarrierThread current = carrier();
        return current != null ? current.id() : carrierId;
    }

    /** The carrier this task's next wakeup is routed to. */
    public ExerisCarrierThread carrier() {
        return (ExerisCarrierThread) HOME.getAcquire(this);
    }

    /** Re-homes the task; called before the new carrier runs it. Never valid on a pinned context. */
    void moveTo(ExerisCarrierThread carrier) {
        if (pinned) {
            throw new IllegalStateException("a pinned context is never moved");
        }
        HOME.setRelease(this, carrier);
    }

    /** Whether the task may only run on the carrier it was routed to. */
    boolean pinned() {
        return pinned;
    }

    public long vThreadId() {
        return vThreadId;
    }

    public void setVThreadId(long vThreadId) {
        this.vThreadId = vThreadId;
    }
}
