/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Resolves the kernel artifact version from the Maven-filtered
 * {@code exeris-kernel.properties} resource ({@code kernel.version=${project.version}}),
 * so the version stamp emitted in JFR never drifts from the POM.
 *
 * <p>Resolved once at class-init and cached. Returns {@code "unknown"} when the
 * resource is missing or its placeholder was not filtered (e.g. classes loaded
 * outside a Maven build).
 */
final class KernelVersion {

    private static final String RESOURCE = "/exeris-kernel.properties";
    private static final String UNKNOWN = "unknown";
    private static final String CURRENT = load();

    private KernelVersion() {
    }

    /** Returns the kernel artifact version, or {@code "unknown"} if unavailable. */
    /* default */ static String current() {
        return CURRENT;
    }

    private static String load() {
        try (InputStream stream = KernelVersion.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return UNKNOWN;
            }
            Properties props = new Properties();
            props.load(stream);
            String version = props.getProperty("kernel.version", "");
            // Guard against an unfiltered placeholder leaking through.
            return version.isBlank() || version.startsWith("${") ? UNKNOWN : version;
        } catch (IOException _) {
            return UNKNOWN;
        }
    }
}
