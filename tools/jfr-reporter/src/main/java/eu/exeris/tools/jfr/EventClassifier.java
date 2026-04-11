package eu.exeris.tools.jfr;

import java.util.List;

final class EventClassifier {

    private EventClassifier() {}

    static Category classify(String className, List<String> stackFrames) {
        if (className.startsWith("eu.exeris.")) {
            for (String frame : stackFrames) {
                if (frame.contains("Test") || frame.contains("Tck")
                        || frame.contains("Harness") || frame.contains("jmh_generated")) {
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
}
