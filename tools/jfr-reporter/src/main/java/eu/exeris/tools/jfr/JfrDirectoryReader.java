package eu.exeris.tools.jfr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

final class JfrDirectoryReader {

    private static final Logger LOGGER = Logger.getLogger(JfrDirectoryReader.class.getName());

    private static final Set<String> ALLOC_TYPES = Set.of(
            "jdk.ObjectAllocationInNewTLAB",
            "jdk.ObjectAllocationOutsideTLAB",
            "jdk.ObjectAllocationSample"
    );

    private JfrDirectoryReader() {}

    /**
     * Reads all .jfr files in dir, groups events by subsystem extracted from filename.
     * Filename pattern from JfrAllocationMonitor: {TestClass}-{Subsystem}-{yyyyMMdd-HHmmss}.jfr
     */
    static Map<String, List<AllocEvent>> readDirectory(Path dir) throws IOException {
        Map<String, List<AllocEvent>> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> jfrFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jfr"))
                    .sorted()
                    .toList();
            for (Path jfrFile : jfrFiles) {
                String subsystem = extractSubsystem(jfrFile.getFileName().toString());
                List<AllocEvent> events = readFile(jfrFile);
                result.computeIfAbsent(subsystem, k -> new ArrayList<>()).addAll(events);
                LOGGER.info(() -> "[jfr-reporter]   " + jfrFile.getFileName() + " → subsystem=" + subsystem + " events=" + events.size());
            }
        }
        return result;
    }

    private static String extractSubsystem(String filename) {
        String base = filename.endsWith(".jfr") ? filename.substring(0, filename.length() - 4) : filename;
        String[] parts = base.split("-");
        if (parts.length >= 2) {
            return parts[1].toLowerCase(Locale.ROOT);
        }
        return "unknown";
    }

    private static List<AllocEvent> readFile(Path jfrFile) {
        List<AllocEvent> events = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                AllocEvent event = toAllocEvent(rf.readEvent());
                if (event != null) {
                    events.add(event);
                }
            }
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, ex,
                    () -> "[jfr-reporter] WARN: failed to read " + jfrFile);
        }
        return events;
    }

    private static AllocEvent toAllocEvent(RecordedEvent event) {
        String typeName = event.getEventType().getName();
        if (!ALLOC_TYPES.contains(typeName)) {
            return null;
        }

        String className = extractClassName(event);
        if (className == null) {
            return null;
        }

        long sizeBytes = extractSizeBytes(event);
        String threadName = extractThreadName(event);
        List<String> frames = extractFrames(event);
        Category category = EventClassifier.classify(className, frames);
        long tEpochMillis = event.getStartTime().toEpochMilli();

        return new AllocEvent(tEpochMillis, typeName, className, threadName, sizeBytes, frames, category);
    }

    private static String extractClassName(RecordedEvent e) {
        try {
            RecordedClass objClass = e.getValue("objectClass");
            return objClass != null ? objClass.getName() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long extractSizeBytes(RecordedEvent e) {
        try {
            long allocationSize = e.getLong("allocationSize");
            if (allocationSize > 0L) {
                return allocationSize;
            }
        } catch (Exception ignored) {
            // Some JFR allocation event variants do not expose allocationSize; fall through to the next probe.
        }
        try {
            long weight = e.getLong("weight");
            if (weight > 0L) {
                return weight;
            }
        } catch (Exception ignored) {
            // Some JFR allocation event variants do not expose weight; returning 0 keeps fallback probing intentional.
        }
        return 0L;
    }

    private static String extractThreadName(RecordedEvent e) {
        RecordedThread thread = e.getThread();
        if (thread != null && thread.getJavaName() != null) {
            return thread.getJavaName();
        }
        return "unknown";
    }

    private static List<String> extractFrames(RecordedEvent e) {
        List<String> frames = new ArrayList<>();
        RecordedStackTrace stack = e.getStackTrace();
        if (stack == null) return frames;
        for (RecordedFrame frame : stack.getFrames()) {
            if (frame.getMethod() != null && frame.getMethod().getType() != null) {
                String fqn = frame.getMethod().getType().getName();
                int dot = fqn.lastIndexOf('.');
                String simpleName = dot >= 0 ? fqn.substring(dot + 1) : fqn;
                frames.add(fqn
                        + "." + frame.getMethod().getName()
                        + "(" + simpleName
                        + ".java:" + frame.getLineNumber() + ")");
            }
        }
        return frames;
    }
}
