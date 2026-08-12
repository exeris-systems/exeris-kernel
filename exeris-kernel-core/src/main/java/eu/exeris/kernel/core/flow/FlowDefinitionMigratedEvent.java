/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Emitted when a parked saga is carried forward onto a newer definition version (ADR-064).
 *
 * <p>The refusals on this path already emit {@link FlowSchemaMismatchEvent}; success did not, which
 * left the one outcome an operator most needs during a version retirement invisible. A saga silently
 * changing which definition version it runs under is a subsystem state transition, and rolling a
 * retirement out means watching the migrated count drain to zero — not inferring it from an absence
 * of refusals.
 *
 * <p>Payload is definition metadata and instance identity only. The transform's {@code opaqueState}
 * is user data and is never recorded.
 */
@Name("eu.exeris.kernel.flow.DefinitionMigrated")
@Label("Flow Saga Definition Migrated")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted when a parked saga is migrated from the definition version it parked under onto"
    + " a version this engine hosts, before the resumed step runs.")
@StackTrace(false)
final class FlowDefinitionMigratedEvent extends Event {

    @Label("Engine Name")
    @Description("Human-readable engine name from FlowEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Definition Name")
    @Description("FlowDefinition name of the migrated saga")
    /* default */ String definitionName;

    @Label("Instance Id (most)")
    @Description("Most-significant 64 bits of the flow instance UUID")
    /* default */ long instanceIdMost;

    @Label("Instance Id (least)")
    @Description("Least-significant 64 bits of the flow instance UUID")
    /* default */ long instanceIdLeast;

    @Label("From Version")
    @Description("Definition version the saga parked under")
    /* default */ int fromVersion;

    @Label("To Version")
    @Description("Definition version the saga resumes on — the first one this engine hosts")
    /* default */ int toVersion;

    @Label("Hops")
    @Description("Number of adjacent transforms applied; a rising value across a fleet means old"
        + " versions are being retired faster than parked sagas drain")
    /* default */ int hops;

    /* default */ static void emit(
            String engineName,
            String definitionName,
            long instanceIdMost,
            long instanceIdLeast,
            int fromVersion,
            int toVersion) {
        FlowDefinitionMigratedEvent event = new FlowDefinitionMigratedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = engineName;
        event.definitionName = definitionName;
        event.instanceIdMost = instanceIdMost;
        event.instanceIdLeast = instanceIdLeast;
        event.fromVersion = fromVersion;
        event.toVersion = toVersion;
        event.hops = toVersion - fromVersion;
        event.commit();
    }
}
