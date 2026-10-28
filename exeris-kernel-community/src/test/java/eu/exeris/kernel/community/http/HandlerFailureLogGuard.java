/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails a test class whose run logged a handler failure from the HTTP/1.1 request loop or the HTTP/2
 * stream handler. A peer that ended the connection is an expected outcome, and neither layer logs it
 * as a failure, so a record here means a write or read was reported as something other than the
 * closed-stream signal.
 *
 * <p>Install it with {@code @ExtendWith}; it listens on the Community logger for the length of the
 * class and checks in {@link #afterAll}. It leaves every other handler and level as they were.
 */
public final class HandlerFailureLogGuard implements BeforeAllCallback, AfterAllCallback {

    private static final String COMMUNITY_LOGGER = "eu.exeris.kernel.community";
    private static final String HTTP1_FAILURE = "Community HTTP stream handling failed";
    private static final String HTTP2_FAILURE = "Community HTTP/2 stream handler failed";
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(HandlerFailureLogGuard.class);

    /**
     * Creates the extension; JUnit instantiates it once per annotated class.
     */
    public HandlerFailureLogGuard() {
        super();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        Recorder recorder = new Recorder();
        // java.util.logging holds a logger weakly, and a collected logger takes its handlers with it, so
        // the guard keeps the logger it attached to for as long as the class runs.
        Logger logger = Logger.getLogger(COMMUNITY_LOGGER);
        logger.addHandler(recorder);
        context.getStore(NAMESPACE).put(Recorder.class, recorder);
        context.getStore(NAMESPACE).put(Logger.class, logger);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Recorder recorder = context.getStore(NAMESPACE).remove(Recorder.class, Recorder.class);
        Logger logger = context.getStore(NAMESPACE).remove(Logger.class, Logger.class);
        logger.removeHandler(recorder);
        assertThat(recorder.failures())
                .as("handler failures logged while the class ran")
                .isEmpty();
    }

    private static final class Recorder extends Handler {

        private final List<String> failures = new CopyOnWriteArrayList<>();

        private Recorder() {
            super();
            setLevel(Level.ALL);
        }

        @Override
        public void publish(LogRecord logRecord) {
            if (logRecord == null) {
                return;
            }
            String message = logRecord.getMessage();
            if (HTTP1_FAILURE.equals(message) || HTTP2_FAILURE.equals(message)) {
                failures.add(message + ": " + logRecord.getThrown());
            }
        }

        @Override
        public void flush() {
            // nothing is buffered
        }

        @Override
        public void close() {
            // nothing is held
        }

        private List<String> failures() {
            return failures;
        }
    }
}
