/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.diagnostics.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the <b>shipped executable</b>: the shaded jar from {@code package}, spawned as a child
 * process, spoken to over NDJSON on stdio — the same way {@code exeris-ai-bridge} spawns it.
 *
 * <p>This is the layer where the module's claim actually lives. Everything before it tests sources
 * or a reactor classpath; the artifact users run is assembled afterwards by the shade plugin, which
 * flattens a dozen jars into one, merges {@code META-INF/services} so {@code ServiceLoader} still
 * finds providers, and picks exactly one class for every duplicated name. Each of those steps can
 * lose something no earlier test was looking at.
 *
 * <p>Bound to failsafe rather than surefire because it needs {@code package} to have run. The jar
 * path arrives as {@code exeris.cli.jar} from the module POM rather than being globbed out of
 * {@code target/}, so a stale jar from an earlier version cannot be picked up silently.
 *
 * <p>The child is launched with a bare {@code -jar} and <b>no {@code --enable-preview}</b>. That is
 * an assertion, not an omission: ADR-066 says the distributed artifact is preview-clean, and this is
 * the only place in the reactor where that claim is executed rather than scanned. A preview-stamped
 * class anywhere in the jar's boot path fails this suite with the JVM's own message.
 */
@DisplayName("DiagnosticsCli — the shipped shaded jar, out-of-process")
class DiagnosticsCliShadedJarIT {

    /** Cold JVM boot plus kernel inspect; far above what it takes, far below "never". */
    private static final int CHILD_TIMEOUT_SECONDS = 120;

    private static List<String> responses;
    private static String stderr;
    private static int exitCode;

    @BeforeAll
    static void runTheShippedJar() throws Exception {
        Path jar = Path.of(System.getProperty("exeris.cli.jar", ""));
        assertThat(jar)
                .as("shaded jar under test — set by the module POM as exeris.cli.jar")
                .isRegularFile();

        Path capturedStdout = Files.createTempFile("diagnostics-cli-it-", ".out");
        Path capturedStderr = Files.createTempFile("diagnostics-cli-it-", ".err");
        // BOTH streams go to files rather than pipes. Writing every request before reading any
        // response is only safe while the responses fit the OS pipe buffer, and that is an
        // assumption about payload size that a growing REQUESTS list or a provider-rich classpath
        // would quietly invalidate — into a deadlock, which reads as a CI hang rather than as a
        // failure. A file has no such bound, and it costs one temp file.
        Process child = new ProcessBuilder(javaBinary(), "-jar", jar.toString())
                .redirectOutput(capturedStdout.toFile())
                .redirectError(capturedStderr.toFile())
                .start();

        // Closing stdin is what ends serve(): the loop runs to EOF.
        try (BufferedWriter toChild = new BufferedWriter(
                new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8))) {
            for (String request : DiagnosticsProtocolContract.REQUESTS) {
                toChild.write(request);
                toChild.write('\n');
            }
        }

        boolean exited = child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            child.destroyForcibly();
        }
        responses = Files.readAllLines(capturedStdout, StandardCharsets.UTF_8);
        stderr = Files.readString(capturedStderr, StandardCharsets.UTF_8);
        Files.deleteIfExists(capturedStdout);
        Files.deleteIfExists(capturedStderr);

        assertThat(exited).as("the CLI must exit on stdin EOF; stderr was:%n%s", stderr).isTrue();
        exitCode = child.exitValue();
    }

    /** The JVM running this test, so the child is the same runtime the build was verified on. */
    private static String javaBinary() {
        return ProcessHandle.current().info().command().orElseGet(
                () -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    @Test
    @DisplayName("the shipped jar serves every method in one session and exits 0")
    void servesTheWholeProtocolAndExitsCleanly() {
        DiagnosticsProtocolContract.assertEveryRequestAnswered(responses, stderr);
        assertThat(exitCode).as("clean exit on EOF; stderr was:%n%s", stderr).isZero();
    }

    @Test
    @DisplayName("shading kept every META-INF/services entry — all nine providers are still found")
    void shadedServiceFilesSurvive() {
        DiagnosticsProtocolContract.assertProviderInventoryComplete(responses);
    }

    @Test
    @DisplayName("the shipped jar runs without --enable-preview (ADR-066: the distributed artifact is preview-clean)")
    void shippedJarIsPreviewClean() {
        assertThat(stderr)
                .as("a preview-stamped class in the jar's boot path would say so here")
                .doesNotContain("Preview features are not enabled")
                .doesNotContain("UnsupportedClassVersionError");
    }

    @Test
    @DisplayName("stdout carries only the protocol — logging must not desync the NDJSON framing")
    void stdoutCarriesOnlyProtocol() {
        assertThat(responses)
                .as("every stdout line must be a JSON object; consumers frame on newlines")
                .allSatisfy(line -> assertThat(line).startsWith("{").endsWith("}"));
    }

    @Test
    @DisplayName("capturedAt is ISO-8601 in the shipped jar too")
    void instantsAreIso8601() {
        DiagnosticsProtocolContract.assertInstantsAreIso8601(responses);
    }

    @Test
    @DisplayName("an empty Optional is JSON null in the shipped jar too")
    void emptyOptionalIsNull() {
        DiagnosticsProtocolContract.assertEmptyOptionalIsNull(responses);
    }

    @Test
    @DisplayName("getBootstrapDag and describeSubsystem answer from the shipped jar")
    void topologyMethodsAnswer() {
        DiagnosticsProtocolContract.assertBootstrapDagPopulated(responses);
        DiagnosticsProtocolContract.assertDescribeSubsystemBothWays(responses);
        DiagnosticsProtocolContract.assertErgonomicsDescribeThisJvm(responses);
    }
}
