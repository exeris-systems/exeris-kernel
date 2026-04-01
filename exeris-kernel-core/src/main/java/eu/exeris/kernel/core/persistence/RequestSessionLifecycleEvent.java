/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("eu.exeris.kernel.persistence.RequestSessionLifecycle")
@Label("Persistence Request Session Lifecycle")
@Category({"Exeris Kernel", "Persistence"})
@StackTrace(false)
public final class RequestSessionLifecycleEvent extends Event {

    private static final EventType EVENT_TYPE =
            EventType.getEventType(RequestSessionLifecycleEvent.class);

    @Label("Operation")
    public String operation;

    @Label("Isolation")
    public String isolation;

    @Label("Read Only")
    public boolean readOnly;

    @Label("Has Session")
    public boolean hasSession;

    public static void emit(String operation,
                            TransactionIsolation isolation,
                            boolean readOnly,
                            boolean hasSession) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        RequestSessionLifecycleEvent event = new RequestSessionLifecycleEvent();
        event.operation = operation;
        if (isolation != null) {
            event.isolation = isolation.name();
        }
        event.readOnly = readOnly;
        event.hasSession = hasSession;
        event.commit();
    }
}