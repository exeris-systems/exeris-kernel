/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sends a real {@code SIGTERM} to a forked kernel JVM and observes, from outside, what the kernel does:
 * whether it drains, in which order its subsystems stop, and with which exit status the process ends.
 */
@DisplayName("Community: SIGTERM-driven ordered stop in a forked JVM")
@DisabledOnOs(OS.WINDOWS)
@SuppressWarnings({"PMD.AvoidUsingHardCodedIP", "PMD.AvoidCatchingGenericException"})
class KernelSigtermShutdownTest {

    private static final long AWAIT_MILLIS = 30_000;
    private static final int SIGTERM_EXIT_STATUS = 143;
    private static final String HOLD_REQUEST =
            "GET /hold HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";

    /** The forked kernel, with its two output streams drained on virtual threads. */
    private static final class Child implements AutoCloseable {
        private final Process process;
        private final Lines stdout = new Lines();
        private final Lines stderr = new Lines();

        Child(String mode) throws IOException {
            List<String> command = new ArrayList<>();
            command.add(ProcessHandle.current().info().command().orElseThrow());
            for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (!argument.startsWith("-javaagent:") && !argument.startsWith("-agentlib:")) {
                    command.add(argument);
                }
            }
            command.add("-cp");
            command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
            command.add(SigtermChildKernel.class.getName());
            command.add(mode);
            process = new ProcessBuilder(command).start();
            Thread.ofVirtual().start(() -> stdout.drain(process.getInputStream()));
            Thread.ofVirtual().start(() -> stderr.drain(process.getErrorStream()));
        }

        int awaitReady() throws InterruptedException {
            String ready = stdout.await("READY ", AWAIT_MILLIS);
            return Integer.parseInt(ready.substring("READY ".length()).trim());
        }

        void sigterm() throws Exception {
            Process kill = new ProcessBuilder("kill", "-TERM", Long.toString(process.pid())).start();
            assertThat(kill.waitFor(10, TimeUnit.SECONDS)).as("kill -TERM returned").isTrue();
            assertThat(kill.exitValue()).as("kill -TERM exit status").isZero();
        }

        void writeLine(String line) throws IOException {
            OutputStream stdin = process.getOutputStream();
            stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }

        int awaitExit() throws InterruptedException {
            assertThat(process.waitFor(AWAIT_MILLIS, TimeUnit.MILLISECONDS))
                    .as("the child exited within %d ms; stderr:%n%s", AWAIT_MILLIS, stderr.all())
                    .isTrue();
            return process.exitValue();
        }

        @Override
        public void close() {
            process.destroyForcibly();
        }
    }

    /** Lines read from one stream, kept in order and handed out to whoever awaits a prefix. */
    private static final class Lines {
        private final List<String> all = new CopyOnWriteArrayList<>();
        private final LinkedBlockingQueue<String> pending = new LinkedBlockingQueue<>();

        void drain(InputStream stream) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    all.add(line);
                    pending.add(line);
                }
            } catch (IOException ex) {
                // The stream ends when the child dies; what was read is already recorded.
            }
        }

        String await(String prefix, long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                long remaining = deadline - System.nanoTime();
                String line = remaining > 0 ? pending.poll(remaining, TimeUnit.NANOSECONDS) : null;
                assertThat(line).as("a line starting with '%s' within %d ms; saw:%n%s", prefix, millis, all)
                        .isNotNull();
                if (line.startsWith(prefix)) {
                    return line;
                }
            }
        }

        List<String> all() {
            return new ArrayList<>(all);
        }

        List<String> withPrefix(String prefix) {
            return all.stream().filter(l -> l.startsWith(prefix)).map(l -> l.substring(prefix.length())).toList();
        }
    }

    /** The held connection: its reader records when the response, or its absence, was complete. */
    private static final class HeldRequest implements AutoCloseable {
        private final Socket socket;
        private final AtomicReference<String> response = new AtomicReference<>();
        private final AtomicLong finishedNanos = new AtomicLong();
        private final Thread reader;

        HeldRequest(int port, Lines childStdout) throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(25_000);
            OutputStream out = socket.getOutputStream();
            out.write(HOLD_REQUEST.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            reader = Thread.ofVirtual().unstarted(() -> {
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                try {
                    socket.getInputStream().transferTo(received);
                } catch (IOException ex) {
                    // A reset ends the read; what arrived before it is the response.
                }
                response.set(received.toString(StandardCharsets.ISO_8859_1));
                finishedNanos.set(System.nanoTime());
            });
            childStdout.await("IN-FLIGHT", AWAIT_MILLIS);
            reader.start();
        }

        String awaitResponse() throws InterruptedException {
            reader.join(30_000);
            assertThat(reader.isAlive()).as("the held connection ended").isFalse();
            return response.get();
        }

        long finishedNanos() {
            return finishedNanos.get();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** Polls a fresh connect until the listener refuses it; returns when that was observed. */
    private static long awaitRefusal(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            } catch (ConnectException refused) {
                return System.nanoTime();
            } catch (IOException other) {
                // Not a refusal yet (timeout, reset): keep polling until the deadline.
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the listener kept accepting connections for 5 s after SIGTERM");
    }

    private static void assertCompleteOrderedStop(Child child) {
        List<String> started = child.stderr.withPrefix("START ");
        List<String> begun = child.stderr.withPrefix("STOP-BEGIN ");
        List<String> ended = child.stderr.withPrefix("STOP-END ");
        assertThat(started).as("subsystems started").isNotEmpty();
        assertThat(begun).as("stop order is the reverse of start order").isEqualTo(started.reversed());
        assertThat(ended).as("every stop completed").containsExactlyInAnyOrderElementsOf(begun);
        List<String> lines = child.stderr.all();
        for (String name : begun) {
            assertThat(lines.indexOf("STOP-END " + name)).as("STOP-END after STOP-BEGIN of %s", name)
                    .isGreaterThan(lines.indexOf("STOP-BEGIN " + name));
        }
        assertThat(ended.getLast()).as("memory is released last").isEqualTo("memory");
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("SIGTERM: the in-flight request completes, ingress refuses, subsystems stop in reverse start order"
            + " with memory last, exit 143")
    void sigtermDrainsInFlightRequestAndStopsInOrder() throws Exception {
        try (Child child = new Child("hook-on")) {
            int port = child.awaitReady();
            try (HeldRequest held = new HeldRequest(port, child.stdout)) {
                child.sigterm();
                long refusedAt = awaitRefusal(port);
                String response = held.awaitResponse();

                assertThat(refusedAt).as("refusal observed before the held response arrived")
                        .isLessThan(held.finishedNanos());
                assertThat(response).startsWith("HTTP/1.1 200").endsWith("done");
                assertThat(child.awaitExit()).isEqualTo(SIGTERM_EXIT_STATUS);
                assertCompleteOrderedStop(child);
            }
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("SIGTERM with the hook disabled: the in-flight request is cut and nothing is stopped")
    void withoutTheHookSigtermCutsTheInFlightRequest() throws Exception {
        try (Child child = new Child("hook-off")) {
            int port = child.awaitReady();
            try (HeldRequest held = new HeldRequest(port, child.stdout)) {
                child.sigterm();
                String response = held.awaitResponse();

                assertThat(response).doesNotContain("HTTP/1.1 200").doesNotContain("done");
                assertThat(child.awaitExit()).isEqualTo(SIGTERM_EXIT_STATUS);
                assertThat(child.stderr.withPrefix("STOP-BEGIN ")).isEmpty();
            }
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("System.exit from kernelMain: the hook stops every subsystem and the process exits with the"
            + " requested status")
    void systemExitFromKernelMainRunsTheOrderedStop() throws Exception {
        try (Child child = new Child("exit-main")) {
            child.awaitReady();
            child.writeLine("EXIT");

            assertThat(child.awaitExit()).isEqualTo(7);
            assertCompleteOrderedStop(child);
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("System.exit from a subsystem's own thread: the hook stops every subsystem without waiting for"
            + " that thread")
    void systemExitFromASubsystemThreadRunsTheOrderedStop() throws Exception {
        try (Child child = new Child("exit-worker")) {
            child.awaitReady();
            child.writeLine("EXIT");

            assertThat(child.awaitExit()).isEqualTo(9);
            assertCompleteOrderedStop(child);
            assertThat(child.stderr.withPrefix("STOP-END ")).contains("exit-probe");
        }
    }
}
