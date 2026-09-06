/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when a PAQS stream handler fails inside the VT boundary.
 *
 * <p>Emitted from {@code PaqsScheduler.runStream()}'s catch blocks around the
 * {@link eu.exeris.kernel.spi.transport.StreamHandler} invocation, once per failing
 * invocation, immediately before the stream is force-closed. An {@link Error} is re-thrown
 * after this event fires and propagates out of the virtual thread; any other {@link Throwable}
 * is isolated at the boundary (logged, not propagated) so one failing handler cannot take down
 * the carrier thread that scheduled it.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.core.transport.PaqsHandlerFailure")
@Label("PAQS Handler Failure")
@Category({"Exeris Kernel", "Transport", "PAQS"})
@Description("Emitted when a PAQS stream handler fails during stream lifecycle processing.")
@StackTrace(false)
public final class PaqsHandlerFailureEvent extends Event {

    /** SPI stream identifier of the stream whose handler failed. */
    @Label("Stream ID")
    public long streamId;

    /** Name of the transport engine the failing stream belonged to. */
    @Label("Engine Name")
    public String engineName;

    /**
     * Fully-qualified class name of the {@link Throwable} caught at the VT boundary
     * ({@code Throwable.getClass().getName()}). The exception message is deliberately not
     * captured — only the class name — consistent with the kernel's secret-safe telemetry
     * convention of not carrying exception messages, which can include request-derived text.
     */
    @Label("Exception Class")
    public String exceptionClass;

    /**
     * Name of the stream-lifecycle phase the failure occurred in. The single existing emission
     * site always supplies {@code "HANDLER"}; the phase distinction exists in the field so a
     * future failure point elsewhere in the stream lifecycle can be reported through the same
     * event with a different value.
     */
    @Label("Lifecycle Phase")
    public String lifecyclePhase;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public PaqsHandlerFailureEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Emits a PAQS handler failure event.
     *
     * @param streamId       the SPI stream identifier of the failing stream
     * @param engineName     the transport engine name
     * @param exceptionClass fully-qualified class name of the caught exception
     * @param lifecyclePhase the stream-lifecycle phase in which the failure occurred
     */
    public static void emit(long streamId,
                            String engineName,
                            String exceptionClass,
                            String lifecyclePhase) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        PaqsHandlerFailureEvent evt = new PaqsHandlerFailureEvent();
        if (evt.isEnabled()) {
            evt.streamId = streamId;
            evt.engineName = engineName;
            evt.exceptionClass = exceptionClass;
            evt.lifecyclePhase = lifecyclePhase;
            evt.commit();
        }
    }
}