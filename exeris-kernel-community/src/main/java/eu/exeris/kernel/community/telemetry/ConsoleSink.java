/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

import java.io.PrintStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Community: {@link TelemetrySink} that writes human-readable output to {@link System#out}
 * (or a configurable {@link PrintStream}).
 *
 * <h2>Zero-Allocation Note</h2>
 * <p>This sink intentionally calls {@code getMessage()} and formats strings — it is a
 * <b>diagnostic, low-frequency</b> path. For production use, prefer {@link JfrTelemetrySink} or
 * the Enterprise {@code BinaryGlassBoxSink}. This sink MUST NOT be used in benchmarks
 * or hot-path testing.
 *
 * @since 0.5.0
 */
public final class ConsoleSink implements TelemetrySink {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private volatile boolean closed;
    private final PrintStream out;

    public ConsoleSink() {
        this(System.out);
    }

    public ConsoleSink(PrintStream out) {
        this.out = out;
    }

    @Override
    public void emit(KernelEvent event) {
        if (closed) {
            return;
        }
        String timestamp = FMT.format(event.timestamp());
        String level = event.level().name();
        String code  = event.code();
        String comp  = event.component();
        String msg   = event.exception() != null ? event.exception().getMessage() : "(no exception)";
        out.printf("[%s] [%s] [%s] %s — %s%n", timestamp, level, code, comp, msg);
    }

    @Override
    public void increment(String name, long delta) {
        // Console sink: counter increments are silent (too noisy for human output)
    }

    @Override
    public void gauge(String name, long value) {
        // Console sink: gauge updates are silent
    }

    @Override
    public void latency(String name, long nanoseconds) {
        // Console sink: latency samples are silent
    }

    @Override
    public String sinkName() {
        return "ExerisCommunity/ConsoleSink";
    }

    @Override
    public void close() {
        closed = true;
        // System.out is not owned by this sink — do not close it
    }
}

