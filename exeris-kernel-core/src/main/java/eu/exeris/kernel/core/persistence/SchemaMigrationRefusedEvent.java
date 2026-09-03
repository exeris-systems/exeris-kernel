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
 * JFR event emitted when the migration runner refuses to boot because a migration's checksum no
 * longer matches the one recorded in {@code exeris_schema_history} (ADR-073 §2).
 *
 * <p>The refusal itself is carried by a thrown {@code PersistenceProviderException}, which is the
 * right mechanism for stopping the boot and the wrong one for telling an operator what happened: a
 * fleet that will not start at 03:00 is exactly the moment nobody wants to be parsing an exception
 * message out of a log aggregator. This is a bootstrap failure point, which the kernel's JFR-first
 * guidance names explicitly.
 *
 * <p>Both checksums are carried, not just the mismatch, because the actionable question is *which
 * file changed* — an operator comparing {@link #recordedChecksum} against a colleague's deployment
 * can tell a local edit from a bad artefact without access to this database.
 *
 * <p>Boot-time and instantaneous: no {@code begin()}, so there is no window in which a blocking
 * operation could straddle the event and leave a carrier-bound {@code EventWriter} flushing a stale
 * buffer. Nothing here parks.
 *
 * @since 0.12.0
 */
@Name("eu.exeris.kernel.persistence.SchemaMigrationRefused")
@Label("Persistence — Schema Migration Refused")
@Description("Emitted when a migration recorded in the schema-history ledger no longer matches the "
        + "file on the classpath, so the kernel refuses to boot against a drifted database")
@Category({"Exeris Kernel", "Persistence"})
@StackTrace(false)
public final class SchemaMigrationRefusedEvent extends Event {

    /** Ledger key of the refused migration, e.g. {@code 0.11.1}. */
    @Label("Version")
    public String version;

    /** Classpath resource whose bytes no longer match what was applied. */
    @Label("Script")
    public String script;

    /** Checksum recorded when the migration was applied to this database. */
    @Label("Recorded Checksum")
    public String recordedChecksum;

    /** Checksum of the file currently on the classpath. */
    @Label("Classpath Checksum")
    public String classpathChecksum;

    /**
     * The refusal's four fields, carried together.
     *
     * <p>Follows the {@code PersistenceAdmissionStageEvent.Payload} precedent rather than four bare
     * {@code String} parameters — which PMD's {@code UseObjectForClearerAPI} rejects, and rightly:
     * two of the four are checksums and swapping them at a call site would produce an event that is
     * wrong in the exact direction an operator would act on.
     *
     * @param version           ledger key of the refused migration, e.g. {@code 0.11.1}
     * @param script            classpath resource path
     * @param recordedChecksum  checksum stored in the ledger when the migration was applied
     * @param classpathChecksum checksum of the file as it is now
     */
    public record Payload(String version,
                          String script,
                          String recordedChecksum,
                          String classpathChecksum) {
    }

    /**
     * Commits a schema-drift refusal.
     *
     * <p>The {@link Payload} is allocated by the caller, so unlike the hot-path events in this
     * package this one does allocate when JFR is inactive — one record, once, on a path that is
     * about to end the boot. Trading that for a call site where two checksums cannot be silently
     * transposed is the right way round.
     */
    public static void commitRefusal(Payload payload) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        SchemaMigrationRefusedEvent event = new SchemaMigrationRefusedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.version           = payload.version();
        event.script            = payload.script();
        event.recordedChecksum  = payload.recordedChecksum();
        event.classpathChecksum = payload.classpathChecksum();
        event.commit();
    }
}
