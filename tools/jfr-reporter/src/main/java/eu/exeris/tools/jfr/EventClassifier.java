package eu.exeris.tools.jfr;

import java.util.List;
import java.util.Locale;

final class EventClassifier {

    private EventClassifier() {}

    static Category classify(String className, List<String> stackFrames) {
        if (className.startsWith("eu.exeris.")) {
            if (isTestHarnessClass(className)) {
                return Category.test_harness;
            }
            for (String frame : stackFrames) {
                String lower = frame.toLowerCase(Locale.ROOT);
                if (lower.contains("test") || lower.contains("tck")
                        || lower.contains("harness") || lower.contains("jmh_generated")) {
                    return Category.test_harness;
                }
            }
            return Category.production;
        }
        if (className.startsWith("jdk.internal.foreign")) {
            return Category.panama;
        }
        if (className.contains("VirtualThread") || className.contains("Continuation")) {
            return Category.loom_runtime;
        }
        return Category.jvm_noise;
    }

    private static boolean isTestHarnessClass(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        return lower.contains(".tck.") || lower.contains(".test.")
                || lower.endsWith("test") || lower.endsWith("tck")
                || lower.contains("harness") || lower.contains("jmh_generated");
    }
}
