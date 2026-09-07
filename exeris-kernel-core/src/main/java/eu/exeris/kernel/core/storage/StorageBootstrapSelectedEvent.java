/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.storage;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event recording which {@code BlobStorageProvider} bootstrap selected (ADR-056).
 *
 * <p>Mirrors {@code SchedulingBootstrapSelectedEvent}, with the priority kept even though it decides
 * nothing here: a future driver that raises it would change nothing about selection, and a recording
 * that shows equal priorities is what tells a reader the id — not the ranking — is what chose.
 * Single-phase commit on the bootstrap thread.
 *
 * @since 0.12
 */
@Name("eu.exeris.kernel.storage.StorageBootstrapSelected")
@Label("Storage Bootstrap Selected")
@Description("Records the BlobStorageProvider chosen by configured id at bootstrap")
@Category({"Exeris Kernel", "Storage"})
@StackTrace(false)
public final class StorageBootstrapSelectedEvent extends Event {

    @Label("Provider Class")
    /* default */ String providerClass;

    @Label("Provider Id")
    /* default */ String providerId;

    @Label("Priority")
    /* default */ int priority;

    @Label("Location")
    /* default */ String location;

    /**
     * Records the selection.
     *
     * @param providerClass implementation class name of the selected provider
     * @param providerId    its stable provider id — the value that chose it
     * @param priority      its priority, recorded rather than used
     * @param location      the configured root location
     */
    public static void emit(String providerClass, String providerId, int priority, String location) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        StorageBootstrapSelectedEvent event = new StorageBootstrapSelectedEvent();
        if (event.isEnabled()) {
            event.providerClass = providerClass;
            event.providerId = providerId;
            event.priority = priority;
            event.location = location;
            event.commit();
        }
    }
}
