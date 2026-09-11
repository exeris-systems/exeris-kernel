/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.util.List;

/**
 * Attributes an allocation to the frame that made it and names the kind of object made.
 *
 * <p>Two rules, each over an enumerated set that {@code README.md} repeats verbatim:
 * <ol>
 *   <li>The <b>owner frame</b> is the first frame whose class is outside
 *       {@link #RUNTIME_FRAME_PREFIXES}. A test frame further down the stack does not change who
 *       owns the allocation: production code called from a TCK still allocated.</li>
 *   <li>The owner is {@link Owner#TEST_HARNESS} only when the owner frame itself is a harness class
 *       ({@link #HARNESS_PACKAGE_SEGMENTS}, {@link #HARNESS_CLASS_SUFFIXES}, {@link #HARNESS_PREFIXES});
 *       {@link Owner#PRODUCTION} when it is {@code eu.exeris.*}; {@link Owner#THIRD_PARTY} otherwise.</li>
 * </ol>
 *
 * <p>Substring tests on "test" are deliberately absent: they matched {@code latest}, {@code contest}
 * and {@code testkit} alike.
 */
final class EventClassifier {

    static final String EXERIS_PREFIX = "eu.exeris.";

    /** Frames whose class starts with one of these are never an owner: the JDK and the coverage agent. */
    static final List<String> RUNTIME_FRAME_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.jacoco.");

    /** A package segment that marks a class as harness wherever it appears in the name. */
    static final List<String> HARNESS_PACKAGE_SEGMENTS = List.of(
            ".tck.", ".testkit.", ".test.", ".jmh_generated.");

    /** A suffix of the outer simple class name (nested and lambda suffixes stripped) that marks a harness class. */
    static final List<String> HARNESS_CLASS_SUFFIXES = List.of("Test", "IT", "Tck");

    /** Test frameworks and runners. */
    static final List<String> HARNESS_PREFIXES = List.of(
            "org.junit.", "org.opentest4j.", "org.assertj.", "org.mockito.",
            "org.openjdk.jmh.", "org.apache.maven.surefire.");

    static final List<String> LOOM_PREFIXES = List.of(
            "java.lang.VirtualThread", "java.lang.ThreadBuilders$",
            "jdk.internal.vm.Continuation", "jdk.internal.vm.StackChunk",
            "java.util.concurrent.ForkJoinTask$");

    static final List<String> PANAMA_PREFIXES = List.of(
            "jdk.internal.foreign.", "java.lang.foreign.");

    static final List<String> JDK_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.");

    private EventClassifier() {}

    /**
     * The first frame outside {@link #RUNTIME_FRAME_PREFIXES}, or {@code null} when there is none.
     *
     * @param frames the recorded stack, top-most first
     * @return the owner frame or {@code null}
     */
    static Frame ownerFrame(List<Frame> frames) {
        for (Frame frame : frames) {
            if (!startsWithAny(frame.className(), RUNTIME_FRAME_PREFIXES)) {
                return frame;
            }
        }
        return null;
    }

    static Owner classifyOwner(Frame owner) {
        if (owner == null) {
            return Owner.NO_OWNER;
        }
        String className = owner.className();
        if (isHarnessClass(className)) {
            return Owner.TEST_HARNESS;
        }
        if (className.startsWith(EXERIS_PREFIX)) {
            return Owner.PRODUCTION;
        }
        return Owner.THIRD_PARTY;
    }

    static boolean isHarnessClass(String className) {
        if (startsWithAny(className, HARNESS_PREFIXES)) {
            return true;
        }
        for (String segment : HARNESS_PACKAGE_SEGMENTS) {
            if (className.contains(segment)) {
                return true;
            }
        }
        String outer = outerSimpleName(className);
        for (String suffix : HARNESS_CLASS_SUFFIXES) {
            if (outer.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    static ObjectKind classifyObject(String className) {
        if (className.startsWith(EXERIS_PREFIX)) {
            return ObjectKind.EXERIS;
        }
        if (startsWithAny(className, LOOM_PREFIXES)) {
            return ObjectKind.LOOM;
        }
        if (startsWithAny(className, PANAMA_PREFIXES)) {
            return ObjectKind.PANAMA;
        }
        if (className.startsWith("[") || className.endsWith("[]")) {
            return ObjectKind.ARRAY;
        }
        if (startsWithAny(className, JDK_PREFIXES)) {
            return ObjectKind.JDK;
        }
        return ObjectKind.OTHER;
    }

    /** {@code eu.exeris.a.Outer$Inner$$Lambda/0x…} → {@code Outer}. */
    static String outerSimpleName(String className) {
        int dot = className.lastIndexOf('.');
        String simple = dot >= 0 ? className.substring(dot + 1) : className;
        int dollar = simple.indexOf('$');
        return dollar >= 0 ? simple.substring(0, dollar) : simple;
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
