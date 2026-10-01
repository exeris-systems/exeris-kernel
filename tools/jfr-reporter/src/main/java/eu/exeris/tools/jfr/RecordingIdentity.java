/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which subsystem and test class a recording belongs to, and where that knowledge came from.
 *
 * @param subsystem lower-cased subsystem name, or {@code null} when unknown
 * @param testClass simple name of the test class that produced the recording, or {@code null}
 * @param source    how the identity was established
 */
record RecordingIdentity(String subsystem, String testClass, Source source) {

    /** Where a recording's identity came from. */
    enum Source {
        /** The {@code eu.exeris.tck.AllocationWindow} marker events inside the file. */
        MARKER,
        /** The {@code {TestClass}-{Subsystem}-{yyyyMMdd}-{HHmmss}.jfr} file name (recordings made before the marker existed). */
        FILENAME,
        /** Neither: {@code surefire.jfr}, {@code jmh-benchmarks.jfr}, {@code pin-*.jfr}. */
        NONE
    }

    /**
     * The file name {@code JfrAllocationMonitor} writes. The test-class group admits no dash, so the
     * pinning monitor's {@code pin-<label>-<ts>} files do not match.
     */
    private static final Pattern TCK_FILE = Pattern.compile(
            "^(?<test>[A-Za-z0-9_$]+)-(?<subsystem>[A-Za-z0-9_]+)-\\d{8}-\\d{6}$");

    static RecordingIdentity none() {
        return new RecordingIdentity(null, null, Source.NONE);
    }

    static RecordingIdentity fromMarker(String subsystem, String testClass) {
        return new RecordingIdentity(subsystem.toLowerCase(Locale.ROOT), testClass, Source.MARKER);
    }

    /**
     * Parses a recording file name.
     *
     * @param filename the file name, with or without the {@code .jfr} extension
     * @return the identity, {@link #none()} when the name is not the TCK shape
     */
    static RecordingIdentity fromFilename(String filename) {
        String base = filename.endsWith(".jfr") ? filename.substring(0, filename.length() - 4) : filename;
        Matcher m = TCK_FILE.matcher(base);
        if (!m.matches()) {
            return none();
        }
        return new RecordingIdentity(
                m.group("subsystem").toLowerCase(Locale.ROOT), m.group("test"), Source.FILENAME);
    }

    boolean identified() {
        return source != Source.NONE;
    }
}
