/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.util.List;
import java.util.Locale;

final class EventClassifier {

    private EventClassifier() {}

    static Category classify(String className, List<String> stackFrames) {
        if (className.startsWith("eu.exeris.")) {
            if (isTestHarnessClass(className)) {
                return Category.TEST_HARNESS;
            }
            for (String frame : stackFrames) {
                String lower = frame.toLowerCase(Locale.ROOT);
                if (lower.contains("test") || lower.contains("tck")
                        || lower.contains("harness") || lower.contains("jmh_generated")) {
                    return Category.TEST_HARNESS;
                }
            }
            return Category.PRODUCTION;
        }
        if (className.startsWith("jdk.internal.foreign")) {
            return Category.PANAMA;
        }
        if (className.contains("VirtualThread") || className.contains("Continuation")) {
            return Category.LOOM_RUNTIME;
        }
        return Category.JVM_NOISE;
    }

    private static boolean isTestHarnessClass(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        return lower.contains(".tck.") || lower.contains(".test.")
                || lower.endsWith("test") || lower.endsWith("tck")
                || lower.contains("harness") || lower.contains("jmh_generated");
    }
}
