/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ADR-077 names one mitigation and records it as unproven: the staleness signal. This runs it.
 *
 * <p>A route declared {@code LONG_RUNNING} that stops blocking pays extra acquires forever, and the
 * only thing that makes the mismatch visible is {@code eu.exeris.kernel.http.RouteExecution}
 * carrying the handler duration. An event that is documented and never fires is worse than no
 * mitigation, because the ADR would be relying on it.
 */
@DisplayName("Community: the LONG_RUNNING staleness signal actually fires (ADR-077)")
class RouteExecutionEventTest {

    private static final String EVENT_NAME = "eu.exeris.kernel.http.RouteExecution";
    private static final String BLOCKING_PATH = "/blocking-route";
    private static final String PROMPT_PATH = "/prompt-route";

    private record RecordingExchange(HttpRequest request) implements HttpExchange {
        @Override
        public void respond(HttpResponse response) {
            // discarded; this test is about the JFR event, not the response
        }
    }

    private static void dispatchOn(RouteRequirement requirement, String path) {
        HttpRequest request =
                new HttpRequest(HttpMethod.GET, path, HttpVersion.HTTP_1_1, List.of(), null);
        new CommunityHttpRequestDispatcher(
                mock(MemoryAllocator.class), null, null, null, (method, p) -> requirement)
                .dispatch(request, new RecordingExchange(request),
                        exchange -> exchange.respond(
                                HttpResponse.noBody(HttpStatus.OK, request.version())));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a LONG_RUNNING route emits the event with its method, path and duration")
    void longRunningEmitsTheSignal() throws Exception {
        AtomicReference<RecordedEvent> seen = new AtomicReference<>();
        CountDownLatch arrived = new CountDownLatch(1);

        try (RecordingStream stream = new RecordingStream()) {
            stream.enable(EVENT_NAME);
            stream.onEvent(EVENT_NAME, event -> {
                // Await the event itself, never a proxy: a counter incremented beside the emit can
                // be observed by the faster thread one statement before the thing being asserted.
                if (seen.compareAndSet(null, event)) {
                    arrived.countDown();
                }
            });
            stream.startAsync();

            dispatchOn(RouteRequirement.permitAll().longRunning(), BLOCKING_PATH);

            assertThat(arrived.await(20, TimeUnit.SECONDS))
                    .as("the staleness signal ADR-077 relies on must actually reach a recording")
                    .isTrue();
        }

        RecordedEvent event = seen.get();
        assertThat(event.getString("method")).isEqualTo("GET");
        assertThat(event.getString("path")).isEqualTo(BLOCKING_PATH);
        assertThat(event.getString("declaredExecution")).isEqualTo("LONG_RUNNING");
        assertThat(event.getLong("handlerDurationNs"))
                .as("a duration of zero would make the signal useless for spotting a stale declaration")
                .isPositive();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a PROMPT route emits nothing, so the signal means what it says")
    void promptEmitsNothing() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        CountDownLatch controlArrived = new CountDownLatch(1);

        try (RecordingStream stream = new RecordingStream()) {
            stream.enable(EVENT_NAME);
            stream.onEvent(EVENT_NAME, event -> {
                paths.add(event.getString("path"));
                if (BLOCKING_PATH.equals(event.getString("path"))) {
                    controlArrived.countDown();
                }
            });
            stream.startAsync();

            // Order matters: the PROMPT dispatch runs FIRST, so if it emitted, its event is committed
            // before the control's and is delivered ahead of it on the same recording. Waiting for the
            // control is therefore also waiting past the point where a PROMPT event would have shown.
            dispatchOn(RouteRequirement.permitAll(), PROMPT_PATH);
            dispatchOn(RouteRequirement.permitAll().longRunning(), BLOCKING_PATH);

            assertThat(controlArrived.await(20, TimeUnit.SECONDS))
                    .as("the control must arrive, or this test cannot tell silence from deafness")
                    .isTrue();
        }

        assertThat(paths)
                .as("only the LONG_RUNNING route may emit; a PROMPT event would make the signal "
                    + "mean 'a request happened' rather than 'a declaration may be stale'")
                .containsExactly(BLOCKING_PATH);
    }
}
