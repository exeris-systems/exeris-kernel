/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.diagnostics;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceConfigurationError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-033 Obligation 8: each {@link KernelDiagnostics} method emits its audit event <em>on call</em>,
 * so a call that throws leaves the same audit record as one that returns. Without it an operator
 * reconstructing a session from JFR sees silence where a call failed, and silence is also what an
 * un-called method looks like.
 *
 * <p>The control case asserts the fixture observes the event at all, so a red subject is the defect
 * and not broken plumbing. Events are read from a synchronous {@link Recording} dump, as in
 * {@link CommunityKernelDiagnosticsJfrTest}, so there is no asynchronous delivery to wait for.
 *
 * <p>{@code getJvmErgonomics} has no throwing case: {@link CommunityRuntimeErgonomics} degrades every
 * reading it cannot make to {@code Optional.empty()}, and there is no seam through which a test can
 * make it throw.
 */
@DisplayName("CommunityKernelDiagnostics — a call is audited when it is made, including one that throws")
class DiagnosticsAuditOnThrowTest {

    private static final String AUDIT_EVENT = "eu.exeris.kernel.diagnostics.KernelDiagnostics";

    private final KernelDiagnostics diagnostics = new CommunityKernelDiagnostics();

    /** A subsystem whose live-state query fails — what the DAG and descriptor builders ask of it. */
    private record HostileSubsystem() implements Subsystem {
        @Override public String name() {
            return "hostile";
        }
        @Override public List<String> dependsOn() {
            return List.of();
        }
        @Override public BootstrapPhase phase() {
            return BootstrapPhase.RUNTIME;
        }
        @Override public void initialize() {
        }
        @Override public void start() {
        }
        @Override public void stop() {
        }
        @Override public boolean isRunning() {
            throw new IllegalStateException("live-state query failed");
        }
    }

    /** A subsystem that answers normally, so the control case exercises the returning path. */
    private record QuietSubsystem() implements Subsystem {
        @Override public String name() {
            return "quiet";
        }
        @Override public List<String> dependsOn() {
            return List.of();
        }
        @Override public BootstrapPhase phase() {
            return BootstrapPhase.RUNTIME;
        }
        @Override public void initialize() {
        }
        @Override public void start() {
        }
        @Override public void stop() {
        }
    }

    @Test
    @DisplayName("CONTROL: a getBootstrapDag call that returns is audited, so the fixture observes the event")
    void returningCallIsAudited(@TempDir Path tmp) throws IOException {
        List<String> audited = auditedMethods(tmp, () ->
                ScopedValue.where(KernelProviders.SUBSYSTEMS, List.<Subsystem>of(new QuietSubsystem()))
                        .run(() -> assertThat(diagnostics.getBootstrapDag().nodes()).hasSize(1)));

        assertThat(audited)
                .as("if this fails the fixture is broken, not the subject")
                .containsExactly("getBootstrapDag");
    }

    @Test
    @DisplayName("a getBootstrapDag call whose subsystem state query throws is still audited")
    void throwingBootstrapDagIsAudited(@TempDir Path tmp) throws IOException {
        List<String> audited = auditedMethods(tmp, () ->
                ScopedValue.where(KernelProviders.SUBSYSTEMS, List.<Subsystem>of(new HostileSubsystem()))
                        .run(() -> assertThatThrownBy(diagnostics::getBootstrapDag)
                                .isInstanceOf(IllegalStateException.class)));

        assertThat(audited)
                .as("the event is emitted on call, so a call that throws still leaves its audit record")
                .containsExactly("getBootstrapDag");
    }

    @Test
    @DisplayName("a describeSubsystem call whose lookup throws is still audited")
    void throwingDescribeSubsystemIsAudited(@TempDir Path tmp) throws IOException {
        List<String> audited = auditedMethods(tmp, () ->
                ScopedValue.where(KernelProviders.SUBSYSTEMS, List.<Subsystem>of(new HostileSubsystem()))
                        .run(() -> assertThatThrownBy(() -> diagnostics.describeSubsystem("hostile"))
                                .isInstanceOf(IllegalStateException.class)));

        assertThat(audited)
                .as("the event is emitted on call, so a call that throws still leaves its audit record")
                .containsExactly("describeSubsystem");
    }

    @Test
    @DisplayName("a describeSubsystem call rejecting a null name is still audited")
    void nullNameDescribeSubsystemIsAudited(@TempDir Path tmp) throws IOException {
        List<String> audited = auditedMethods(tmp, () ->
                assertThatThrownBy(() -> diagnostics.describeSubsystem(null))
                        .isInstanceOf(NullPointerException.class));

        assertThat(audited)
                .as("the call was made, so it is audited before its argument is checked")
                .containsExactly("describeSubsystem");
    }

    @Test
    @DisplayName("a listProviders call whose provider discovery fails is still audited")
    void throwingListProvidersIsAudited(@TempDir Path tmp) throws IOException {
        Path services = tmp.resolve("classpath/META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(MemoryProvider.class.getName()),
                "eu.exeris.kernel.community.diagnostics.AbsentMemoryProvider\n", StandardCharsets.UTF_8);

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        List<String> audited;
        try (URLClassLoader broken = new URLClassLoader(
                new URL[] {tmp.resolve("classpath").toUri().toURL()}, previous)) {
            thread.setContextClassLoader(broken);
            audited = auditedMethods(tmp, () ->
                    assertThatThrownBy(diagnostics::listProviders)
                            .isInstanceOf(ServiceConfigurationError.class));
        } finally {
            thread.setContextClassLoader(previous);
        }

        assertThat(audited)
                .as("the event is emitted on call, so a call that throws still leaves its audit record")
                .containsExactly("listProviders");
    }

    /** Runs {@code call} under a recording and returns the {@code method} of every audit event it saw. */
    private static List<String> auditedMethods(Path tmp, Runnable call) throws IOException {
        Path jfr = tmp.resolve("audit.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(AUDIT_EVENT);
            recording.start();
            call.run();
            recording.stop();
            recording.dump(jfr);
        }
        return RecordingFile.readAllEvents(jfr).stream()
                .filter(e -> AUDIT_EVENT.equals(e.getEventType().getName()))
                .map(e -> e.getString("method"))
                .toList();
    }
}
