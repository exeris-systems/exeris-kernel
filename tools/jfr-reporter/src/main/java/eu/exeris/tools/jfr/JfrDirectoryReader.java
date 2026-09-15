/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Reads every {@code .jfr} under a directory into one {@link RecordingData} per file: the
 * allocation events with both attributions applied, and the window marker when the TCK wrote one.
 */
final class JfrDirectoryReader {

    private static final Logger LOGGER = Logger.getLogger(JfrDirectoryReader.class.getName());
    private static final String LOG_PREFIX = "[jfr-reporter] ";

    private static final Set<String> ALLOC_TYPES = Set.of(
            SizePolicy.IN_NEW_TLAB,
            SizePolicy.OUTSIDE_TLAB,
            SizePolicy.SAMPLE
    );

    /**
     * One recording as read.
     *
     * @param file     the recording
     * @param identity where it belongs: from the marker when the file holds exactly one clean
     *                 window, from the file name when it holds no boundary at all, {@code NONE}
     *                 otherwise
     * @param pairing  the marker pairs in the file, and whatever could not be paired
     * @param events   the allocation events, in file order
     */
    record RecordingData(Path file, RecordingIdentity identity, TckMarker.Pairing pairing, List<AllocEvent> events) {

        List<TckMarker.Window> windows() {
            return pairing.windows();
        }

        /** The measured window, or {@code null} unless the file holds exactly one. */
        TckMarker.Window window() {
            return windows().size() == 1 ? windows().get(0) : null;
        }

        boolean windowed() {
            return window() != null;
        }

        /** The events that belong to this recording's subsystem: those inside the window when there is one. */
        List<AllocEvent> attributedEvents() {
            TckMarker.Window w = window();
            if (w == null) {
                return events;
            }
            return events.stream().filter(e -> w.contains(e.t())).toList();
        }

        boolean tckShapedName() {
            return RecordingIdentity.fromFilename(file.getFileName().toString()).identified();
        }
    }

    private JfrDirectoryReader() {}

    /**
     * Reads all {@code .jfr} files below {@code dir}, sorted by path.
     *
     * @param dir the module's {@code target/} directory
     * @return one entry per file
     * @throws IOException if the directory cannot be walked
     */
    static List<RecordingData> readDirectory(Path dir) throws IOException {
        List<RecordingData> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> jfrFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jfr"))
                    .sorted()
                    .toList();
            for (Path jfrFile : jfrFiles) {
                RecordingData data = readFile(jfrFile);
                result.add(data);
                LOGGER.info(() -> LOG_PREFIX + "  " + jfrFile.getFileName()
                        + " → subsystem=" + data.identity().subsystem()
                        + " source=" + data.identity().source()
                        + " events=" + data.events().size());
            }
        }
        return result;
    }

    static RecordingData readFile(Path jfrFile) {
        List<AllocEvent> events = new ArrayList<>();
        List<TckMarker.Boundary> boundaries = new ArrayList<>();
        int malformedMarkers = 0;
        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                if (TckMarker.isMarker(event)) {
                    TckMarker.Boundary boundary = TckMarker.read(event);
                    if (boundary == null) {
                        malformedMarkers++;
                    } else {
                        boundaries.add(boundary);
                    }
                    continue;
                }
                AllocEvent alloc = toAllocEvent(event);
                if (alloc != null) {
                    events.add(alloc);
                }
            }
        } catch (IOException | RuntimeException ex) {
            // One unreadable file degrades to "not a measurement"; it does not end the run. Before
            // this catch, an unchecked exception here propagated out of Main and failed the job,
            // leaving no evidence.json at all for the modules that were readable.
            LOGGER.log(Level.WARNING, ex, () -> LOG_PREFIX + "WARN: failed to read " + jfrFile);
            return new RecordingData(jfrFile, RecordingIdentity.none(), TckMarker.Pairing.of(List.of()), events);
        }
        TckMarker.Pairing pairing = TckMarker.pairAll(boundaries);
        String fileName = jfrFile.getFileName().toString();
        RecordingIdentity identity = malformedMarkers > 0
                ? RecordingIdentity.none()
                : identify(pairing, fileName);
        return new RecordingData(jfrFile, identity, pairing, events);
    }

    /**
     * Where a recording belongs.
     *
     * <p>The file name is a fallback for recordings written before the marker existed, so it is
     * consulted only when the file holds no boundary at all. A file that wrote boundaries and could
     * not close them is not a measurement whatever its name says — without that rule a recording
     * left by a crashed workload passes the TCK file-name pattern, is admitted as its subsystem,
     * and contributes every event it holds to that subsystem's aggregates with no window to scope
     * them.
     */
    private static RecordingIdentity identify(TckMarker.Pairing pairing, String fileName) {
        if (!pairing.clean()) {
            return RecordingIdentity.none();
        }
        List<TckMarker.Window> windows = pairing.windows();
        if (windows.size() == 1) {
            return RecordingIdentity.fromMarker(windows.get(0).subsystem(), windows.get(0).testClass());
        }
        if (windows.isEmpty()) {
            return RecordingIdentity.fromFilename(fileName);
        }
        return RecordingIdentity.none();
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
        long allocationSize = longField(event, "allocationSize");
        SizePolicy.Size size = SizePolicy.sizeOf(typeName,
                allocationSize, longField(event, "weight"), longField(event, "tlabSize"));
        RecordedThread thread = event.getThread();
        String threadName = thread != null && thread.getJavaName() != null ? thread.getJavaName() : "unknown";
        long threadId = thread != null ? thread.getJavaThreadId() : -1L;
        List<Frame> frames = extractFrames(event);
        Frame owner = EventClassifier.ownerFrame(frames);
        return new AllocEvent(
                event.getStartTime(),
                typeName,
                className,
                threadName,
                threadId,
                size.bytes(),
                size.kind(),
                allocationSize,
                frames,
                owner,
                EventClassifier.classifyOwner(owner),
                EventClassifier.classifyObject(className));
    }

    private static String extractClassName(RecordedEvent e) {
        if (!e.hasField("objectClass")) {
            return null;
        }
        RecordedClass objClass = e.getValue("objectClass");
        return objClass != null ? objClass.getName() : null;
    }

    private static long longField(RecordedEvent e, String name) {
        return e.hasField(name) ? e.getLong(name) : 0L;
    }

    private static List<Frame> extractFrames(RecordedEvent e) {
        List<Frame> frames = new ArrayList<>();
        RecordedStackTrace stack = e.getStackTrace();
        if (stack == null) {
            return frames;
        }
        for (RecordedFrame frame : stack.getFrames()) {
            if (frame.getMethod() != null && frame.getMethod().getType() != null) {
                frames.add(new Frame(
                        frame.getMethod().getType().getName(),
                        frame.getMethod().getName(),
                        frame.getLineNumber()));
            }
        }
        return frames;
    }
}
