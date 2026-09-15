/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import eu.exeris.kernel.tck.fake.AllocationWindowFixtureEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The marker's vocabulary is written in one Maven build and read in another, with no dependency
 * between them — deliberately, and the event name, the eight-and-more field names, the boundary
 * spellings, the contract modes and the bytes sentinel are therefore spelled out three times: in
 * the TCK's event, in {@link TckMarker}, and in this module's fixture writer.
 *
 * <p>Nothing joined those three. Renaming a field left the TCK's self-test green and this module's
 * tests green, and the break appeared only in the published report. This test reads the TCK's
 * source across the same {@code ../../} hop the POM already makes for the licence header, and
 * checks the three against each other.
 */
class MarkerVocabularyTest {

    private static final Path TCK_SRC =
            Path.of("../../exeris-kernel-tck/src/main/java/eu/exeris/kernel/tck/contract");
    private static final Path EVENT = TCK_SRC.resolve("AllocationWindowEvent.java");
    private static final Path MONITOR = TCK_SRC.resolve("JfrAllocationMonitor.java");

    /** {@code public <type> <name>;} — the event's fields, never its constructor. */
    private static final Pattern PUBLIC_FIELD = Pattern.compile("^\\s*public\\s+\\w+\\s+(\\w+);\\s*$");

    @Test
    @DisplayName("the reader's field names are exactly the writer's, and the fixture's are too")
    void theThreeSpellingsOfTheFieldSetAgree() throws IOException {
        Set<String> written = publicFields(EVENT);
        Set<String> read = readerFieldConstants();
        Set<String> fixture = Arrays.stream(AllocationWindowFixtureEvent.class.getDeclaredFields())
                .filter(f -> Modifier.isPublic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(written).as("AllocationWindowEvent's fields").isNotEmpty();
        assertThat(read)
                .as("every F_* constant in TckMarker names a field the TCK actually writes")
                .containsExactlyInAnyOrderElementsOf(written);
        assertThat(fixture)
                .as("the fixture writer must mint the same event the TCK does")
                .containsExactlyInAnyOrderElementsOf(written);
    }

    @Test
    @DisplayName("the event name and class the reader looks for are the ones the TCK registers")
    void theEventIsIdentifiedTheSameWayOnBothSides() throws IOException {
        assertThat(literalAfter(EVENT, "@Name\\(\"([^\"]+)\"\\)")).isEqualTo(TckMarker.EVENT_NAME);
        assertThat(TckMarker.EVENT_CLASS)
                .isEqualTo("eu.exeris.kernel.tck.contract.AllocationWindowEvent");
        assertThat(EVENT).exists();
    }

    @Test
    @DisplayName("the boundary spellings, the contract modes and the bytes sentinel agree")
    void theValueVocabularyAgrees() throws IOException {
        String monitor = Files.readString(MONITOR);

        assertThat(literal(monitor, "BOUNDARY_START = \"([^\"]+)\"")).isEqualTo(TckMarker.START);
        assertThat(literal(monitor, "BOUNDARY_END = \"([^\"]+)\"")).isEqualTo(TckMarker.END);
        assertThat(literal(monitor, "BOUNDARY_ABORT = \"([^\"]+)\"")).isEqualTo(TckMarker.ABORT);

        // Mode.marker() lower-cases the constant and turns '_' into '-'.
        assertThat(modeSpellings(monitor))
                .as("every Contract.Mode must have a spelling the reader knows")
                .containsExactlyInAnyOrder(TckMarker.MODE_ZERO, TckMarker.MODE_BOUNDED,
                        TckMarker.MODE_BOUNDED_BYTES, TckMarker.MODE_UNSPECIFIED);

        assertThat(literal(monitor, "ALLOCATED_BYTES_UNAVAILABLE = (-?\\d+)L"))
                .isEqualTo(String.valueOf(TckMarker.BYTES_UNAVAILABLE));
    }

    private static Set<String> publicFields(Path source) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (String line : Files.readAllLines(source)) {
            Matcher m = PUBLIC_FIELD.matcher(line);
            if (m.matches()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /** The value of every {@code F_*} constant on {@link TckMarker}, found by reflection so a new one cannot be missed. */
    private static Set<String> readerFieldConstants() {
        Set<String> names = new LinkedHashSet<>();
        for (Field f : TckMarker.class.getDeclaredFields()) {
            if (f.getName().startsWith("F_") && f.getType() == String.class) {
                f.setAccessible(true);
                try {
                    names.add((String) f.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return names;
    }

    private static Set<String> modeSpellings(String monitor) {
        String body = monitor.substring(monitor.indexOf("public enum Mode {"),
                monitor.indexOf("String marker()"));
        Set<String> spellings = new LinkedHashSet<>();
        Matcher m = Pattern.compile("^\\s{12}([A-Z][A-Z_]*)[,;]\\s*$", Pattern.MULTILINE).matcher(body);
        while (m.find()) {
            spellings.add(m.group(1).toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        }
        return spellings;
    }

    private static String literalAfter(Path source, String regex) throws IOException {
        return literal(Files.readString(source), regex);
    }

    private static String literal(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertThat(m.find()).as("no match for %s — the TCK source moved", regex).isTrue();
        return m.group(1);
    }
}
