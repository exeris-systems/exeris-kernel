/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.diagnostics.cli;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The NDJSON protocol contract, asserted against responses from a <b>live kernel</b> — shared by the
 * two live suites so they check the same clauses at two different layers:
 *
 * <ul>
 *   <li>{@link DiagnosticsCliLiveKernelTest} — in-process, on the module's resolved test classpath.
 *       Sees dependency resolution.</li>
 *   <li>{@code DiagnosticsCliShadedJarIT} — out-of-process, against the packaged executable.
 *       Sees shading, the merged {@code META-INF/services}, and the manifest.</li>
 * </ul>
 *
 * <p>Neither layer is redundant, because the two fail apart: a dependency the reactor resolves can
 * still be dropped or version-collapsed by the shade plugin, and a jar that starts can still have
 * been built from a classpath that never held the right artifact. {@link DiagnosticsCliTest} sees
 * neither — it drives {@code handle()} against a fake and never boots anything.
 */
final class DiagnosticsProtocolContract {

    /**
     * Every method the CLI serves, in one session, in the order a consumer would send them.
     *
     * <p>They are sent as ONE session on purpose. The failure this suite exists for did not make a
     * method return the wrong answer — it made the method kill the process, which costs a consumer
     * every later request too (the adapter caches the child and its close is sticky). A per-method
     * session would have found the broken method and hidden the blast radius.
     */
    static final List<String> REQUESTS = List.of(
            "{\"method\":\"listProviders\"}",
            "{\"method\":\"getBootstrapDag\"}",
            "{\"method\":\"getJvmErgonomics\"}",
            "{\"method\":\"describeSubsystem\",\"name\":\"memory\"}",
            "{\"method\":\"describeSubsystem\",\"name\":\"no-such-subsystem\"}");

    /** Index into {@link #REQUESTS} and into a well-formed response list. */
    private static final int LIST_PROVIDERS = 0;
    private static final int BOOTSTRAP_DAG = 1;
    private static final int JVM_ERGONOMICS = 2;
    private static final int DESCRIBE_KNOWN = 3;
    private static final int DESCRIBE_UNKNOWN = 4;

    /**
     * Every SPI {@code CommunityProviderInventory} sweeps. Spelled out rather than counted: a
     * ServiceLoader that silently finds nothing still produces a well-formed snapshot, so a test
     * that only checked the response parsed would pass against an empty inventory.
     */
    private static final List<String> EVERY_SPI_TYPE = List.of(
            "memory", "crypto", "telemetry", "persistence", "events",
            "flow", "transport", "graph", "security");

    private DiagnosticsProtocolContract() {
    }

    /** One response line per request, none of them an error. */
    static void assertEveryRequestAnswered(List<String> responses, String diagnosis) {
        assertThat(responses)
                .as("one NDJSON response per request; the child's stderr was:%n%s", diagnosis)
                .hasSameSizeAs(REQUESTS);
        assertThat(responses)
                .as("every request in REQUESTS is valid, so none may answer with an error")
                .noneMatch(line -> line.contains("\"error\""));
    }

    /**
     * The provider inventory is complete.
     *
     * <p>{@code listProviders} is the only method that instantiates every provider through
     * {@link java.util.ServiceLoader}, so it is the only one that runs their class initialisers —
     * and the events provider's builds a {@code tools.jackson} mapper at class-init. That made it
     * the method that noticed when the CLI's own Jackson pin pulled {@code jackson-annotations}
     * below what the kernel's Jackson needs, and it will be the method that notices next time.
     */
    static void assertProviderInventoryComplete(List<String> responses) {
        String out = responses.get(LIST_PROVIDERS);
        assertThat(out).startsWith("{\"schemaVersion\":\"1.0\"");
        assertThat(out).contains(EVERY_SPI_TYPE.stream()
                .map(spiType -> "\"spiType\":\"" + spiType + "\"")
                .toArray(String[]::new));
        assertThat(out).contains("\"providerName\":\"ExerisCommunity/");
    }

    /**
     * {@code capturedAt} is an ISO-8601 string on every snapshot, never an epoch number.
     *
     * <p>Under Jackson 2 this took {@code JavaTimeModule} plus an explicit
     * {@code WRITE_DATES_AS_TIMESTAMPS} disable. Under {@code tools.jackson} it is the bare-mapper
     * default and the CLI registers nothing — which is exactly why it needs asserting rather than
     * assuming: the configuration that used to make it true is gone.
     */
    static void assertInstantsAreIso8601(List<String> responses) {
        // Size first, and not for tidiness. Each clause here runs as its own @Test, so this one
        // does not inherit assertEveryRequestAnswered's check — and allSatisfy over an EMPTY list
        // passes. An empty list is precisely what the defect this suite exists for produced, so
        // without this the one assertion that survives a dead session is the one that proves least.
        assertThat(responses).hasSameSizeAs(REQUESTS);
        assertThat(responses).allSatisfy(line -> assertThat(line).containsPattern(
                "\"capturedAt\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z\""));
    }

    /**
     * An empty {@code Optional} serialises as JSON {@code null}, not as an absent key — the CLI's
     * documented wire promise, and the other half of what {@code Jdk8Module} used to provide.
     *
     * <p>The unknown-subsystem response carries it deterministically. {@code cpuQuotaMicros} is
     * asserted as a PRESENT KEY only: whether it holds null or a number depends on whether the
     * machine running the test is in a container, and a test that demanded null would pass here and
     * fail in CI for a reason that has nothing to do with the CLI.
     */
    static void assertEmptyOptionalIsNull(List<String> responses) {
        assertThat(responses.get(DESCRIBE_UNKNOWN)).contains("\"subsystem\":null");
        assertThat(responses.get(JVM_ERGONOMICS)).contains("\"cpuQuotaMicros\":");
    }

    /** The DAG carries the topology inspect mode resolved, not an empty node list. */
    static void assertBootstrapDagPopulated(List<String> responses) {
        assertThat(responses.get(BOOTSTRAP_DAG))
                .contains("\"nodes\":[{")
                .contains("\"name\":\"memory\"")
                .contains("\"phase\":\"FOUNDATION\"");
    }

    /** Ergonomics describe the JVM actually running, so the numbers must be real. */
    static void assertErgonomicsDescribeThisJvm(List<String> responses) {
        assertThat(responses.get(JVM_ERGONOMICS))
                .containsPattern("\"gcName\":\"[^\"]+\"")
                .containsPattern("\"availableProcessors\":[1-9]\\d*")
                .containsPattern("\"heapMaxBytes\":[1-9]\\d*");
    }

    /** A known name resolves to a descriptor; an unknown one resolves to null, not to an error. */
    static void assertDescribeSubsystemBothWays(List<String> responses) {
        assertThat(responses.get(DESCRIBE_KNOWN))
                .contains("\"requestedName\":\"memory\"")
                .contains("\"subsystem\":{\"name\":\"memory\"")
                .contains("\"dependsOn\":[]");
        assertThat(responses.get(DESCRIBE_UNKNOWN))
                .contains("\"requestedName\":\"no-such-subsystem\"")
                .contains("\"subsystem\":null");
    }
}
