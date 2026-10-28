/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when a persistence binding boots with a database role that bypasses row-level
 * security because the operator consented to it explicitly (ADR-012 §4c.4).
 *
 * <p>A role with {@code SUPERUSER} or {@code BYPASSRLS} skips every policy, {@code FORCE}d ones
 * included, so tenant isolation then rests on application code alone. The refusal is the default;
 * this event is the downgrade's trace, emitted on every boot that runs with the consent so a fleet
 * audit can find the deployments that opted out of the database-level guarantee.
 *
 * <p>The event lives in Core rather than in a binding so that every persistence binding reports the
 * same event name and field layout.
 *
 * <p>Boot-time and instantaneous: no {@code begin()}, so there is no window in which a blocking
 * operation could straddle the event and leave a carrier-bound {@code EventWriter} flushing a stale
 * buffer.
 *
 * @since 0.13
 */
@Name("eu.exeris.kernel.persistence.RlsBypassRole")
@Label("Persistence — RLS Bypass Role")
@Description("Emitted when a persistence binding runs with a database role that bypasses "
        + "row-level security because the operator explicitly allowed it")
@Category({"Exeris Kernel", "Persistence"})
@StackTrace(false)
public final class RlsBypassRoleEvent extends Event {

    /** Provider id of the binding that observed the role. */
    @Label("Provider")
    public String providerName;

    /** Database role the pool connects as. */
    @Label("Role")
    public String roleName;

    /** The attribute that makes the role bypass: {@code SUPERUSER}, {@code BYPASSRLS} or both, comma-separated. */
    @Label("Attribute")
    public String attribute;

    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public RlsBypassRoleEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * The downgrade's three fields, carried together.
     *
     * <p>A record rather than three bare {@code String} parameters: the role name and the attribute
     * are both free text, and transposing them at a call site would produce an event that names an
     * attribute as a role.
     *
     * @param providerName provider id of the binding
     * @param roleName     database role the pool connects as
     * @param attribute    {@code SUPERUSER}, {@code BYPASSRLS} or {@code SUPERUSER,BYPASSRLS}
     */
    public value record Payload(String providerName, String roleName, String attribute) {
    }

    /**
     * Commits a downgrade report.
     *
     * @param payload the downgrade's fields, allocated by the caller; see {@link Payload}
     */
    public static void commitDowngrade(Payload payload) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        RlsBypassRoleEvent event = new RlsBypassRoleEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.providerName = payload.providerName();
        event.roleName     = payload.roleName();
        event.attribute    = payload.attribute();
        event.commit();
    }
}
