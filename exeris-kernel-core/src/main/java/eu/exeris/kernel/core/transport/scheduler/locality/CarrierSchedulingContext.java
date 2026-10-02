/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

/**
 * Attachment stored on {@link java.lang.Thread.VirtualThreadTask} to associate
 * a virtual thread with its assigned carrier thread.
 */
public final class CarrierSchedulingContext {
    private final int carrierId;
    private final ExerisCarrierThread carrier;
    private long vThreadId;

    public CarrierSchedulingContext(int carrierId, ExerisCarrierThread carrier) {
        this.carrierId = carrierId;
        this.carrier = carrier;
        this.vThreadId = -1;
    }

    public int carrierId() {
        return carrierId;
    }

    public ExerisCarrierThread carrier() {
        return carrier;
    }

    public long vThreadId() {
        return vThreadId;
    }

    public void setVThreadId(long vThreadId) {
        this.vThreadId = vThreadId;
    }
}
