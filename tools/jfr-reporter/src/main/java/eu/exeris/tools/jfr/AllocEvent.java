package eu.exeris.tools.jfr;

import java.util.List;

public record AllocEvent(
        long tMillis,
        String eventType,
        String className,
        String threadName,
        long sizeBytes,
        List<String> stackFrames,
        Category category
) {}
