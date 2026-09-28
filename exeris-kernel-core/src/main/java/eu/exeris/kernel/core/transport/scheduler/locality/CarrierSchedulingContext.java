/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
