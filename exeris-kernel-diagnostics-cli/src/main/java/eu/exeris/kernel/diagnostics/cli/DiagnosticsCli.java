/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.diagnostics.cli;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnosticsProvider;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Stdio CLI for the {@link KernelDiagnostics} SPI (ADR-033).
 *
 * <h2>Model</h2>
 * <p>A consumer (e.g. {@code exeris-ai-bridge}) {@code spawn()}s this process. It boots the kernel in
 * <b>read-only inspect mode</b> ({@link KernelBootstrap#inspect(Runnable)} — resolves the subsystem
 * topology only: load + selector closure + topological sort, never {@code initialize()} or
 * {@code start()}; no ports, no connections, no infrastructure), resolves the highest-priority
 * {@link KernelDiagnosticsProvider} via {@link ServiceLoader} (Community = 0, Enterprise overlay = 100 on
 * the same binary), and serves newline-delimited JSON: one request object per line on stdin, one response
 * object per line on stdout. No network surface; trusts the spawning process (auth-free local mode).
 *
 * <h2>Protocol (NDJSON)</h2>
 * Four methods, one line each way. Anything else — an unknown method, a line that is not JSON, a
 * {@code describeSubsystem} without a name — comes back as an error object rather than closing the
 * channel.
 * {@snippet lang="json" :
 *   → {"method":"listProviders"}
 *   ← {"schemaVersion":"1.0","capturedAt":"…","providers":[…]}
 *   → {"method":"getBootstrapDag"}
 *   ← {"schemaVersion":"1.0",…,"nodes":[…]}
 *   → {"method":"describeSubsystem","name":"transport"}
 *   ← {"schemaVersion":"1.0",…,"subsystem":{…}}
 *   → {"method":"getJvmErgonomics"}
 *   ← {"schemaVersion":"1.0",…,"gcName":"…","availableProcessors":…,"cpuQuotaMicros":null}
 *   ← {"error":"…"}
 * }
 *
 * <p><b>Optional fields serialise as JSON {@code null}, not absent keys</b> (default
 * {@code tools.jackson} databind config): an empty {@code Optional} on a snapshot (e.g.
 * {@code cpuQuotaMicros} on a non-container host) appears as {@code "cpuQuotaMicros":null}.
 * Consumers must treat {@code null} and an absent key alike.
 *
 * <p><b>Allocation:</b> allocates per request — one parsed tree and one response string. Nothing
 * is pooled and no allocation budget applies to this tool.
 * <p><b>Thread confinement:</b> owner thread — {@link #serve} reads, dispatches and writes on its
 * calling thread, and holds no lock that would make a second one safe.
 * <p><b>Ownership:</b> {@link #serve} wraps the streams it is handed and closes both when the
 * input reaches end of file, so handing it {@code System.in} and {@code System.out} closes those.
 * The caller owns the process, and closing the child's stdin is what ends a session — the protocol
 * has no quit method.
 *
 * @since 0.9
 */
public final class DiagnosticsCli {

    /** Protocol goes to stdout; diagnosis goes here, which {@code System.Logger} routes to stderr. */
    private static final Logger LOG = System.getLogger(DiagnosticsCli.class.getName());

    private final KernelDiagnostics diagnostics;
    private final ObjectMapper mapper;

    /**
     * Binds an already-resolved diagnostics implementation and mapper, so a test can drive
     * {@link #handle} and {@link #serve} without booting a kernel or loading a service.
     *
     * @param diagnostics the resolved implementation every method delegates to
     * @param mapper      the mapper whose defaults are the wire contract; see {@link #newMapper()}
     * @throws NullPointerException if either argument is {@code null}
     */
    /* default */ DiagnosticsCli(KernelDiagnostics diagnostics, ObjectMapper mapper) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Resolves the subsystem topology in inspect mode and serves the NDJSON protocol on the
     * process's own stdin and stdout until stdin reaches end of file.
     *
     * <p>Nothing is initialised or started: {@link KernelBootstrap#inspect(Runnable)} loads,
     * selector-filters and topologically sorts the subsystem inventory and stops there, so this
     * process opens no port, no connection and no native library. Every answer it gives describes
     * a static composition, never a running one.
     *
     * @param args ignored — the CLI takes no options and its whole input is the request stream
     * @throws KernelBootstrap.BootstrapException if config resolution or topology resolution
     *         fails, which happens before the first request is read and ends the process rather
     *         than producing an error response
     * @throws java.io.UncheckedIOException if reading stdin or writing stdout fails mid-session;
     *         a protocol-level failure is answered on the wire instead, and only the channel
     *         itself breaking gets out this way
     * @throws IllegalStateException if no {@link KernelDiagnosticsProvider} is on the classpath
     */
    public static void main(String[] args) throws KernelBootstrap.BootstrapException {
        ObjectMapper mapper = newMapper();
        KernelBootstrap.builder()
                .selector(BootstrapSelector.all())
                .build()
                .inspect(() -> {
                    DiagnosticsCli cli = new DiagnosticsCli(loadDiagnostics(), mapper);
                    try {
                        cli.serve(System.in, System.out);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /**
     * Supplies the mapper whose <em>unconfigured</em> defaults are this CLI's wire contract.
     *
     * <p>{@code tools.jackson} (Jackson 3) has {@code java.time} and {@code Optional} support built
     * into databind rather than in separate modules, and {@code DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS}
     * is off by default there, so a bare mapper already writes ISO-8601 instants and an empty
     * {@code Optional} as JSON {@code null} — the two properties this CLI's protocol promises.
     *
     * <p>Bare constructor rather than a {@code JsonMapper.builder()} round-trip, for the reason
     * {@code CommunityJsonMappers} gives: a builder can drift from the bare-constructor defaults,
     * and here those defaults ARE the contract.
     *
     * @return a new mapper; never {@code null}
     */
    /* default */ static ObjectMapper newMapper() {
        return new ObjectMapper();
    }

    /**
     * Resolves the winning provider — the one with the highest
     * {@link KernelDiagnosticsProvider#priority()} on the classpath — and creates its
     * implementation.
     *
     * <p>The open-core convention puts the Community provider at 0 and an Enterprise overlay at
     * 100, so dropping the overlay onto the same binary changes which implementation answers
     * without changing this call. Ties are broken by whatever order {@link ServiceLoader} yields,
     * which is not a contract; two providers at one priority is a configuration to fix, not a
     * choice this method makes.
     *
     * @return the implementation of the highest-priority provider; never {@code null}
     * @throws IllegalStateException if no {@link KernelDiagnosticsProvider} is registered
     * @throws ServiceConfigurationError if a registered provider cannot be instantiated —
     *         propagated, not translated, since there is no diagnostics channel to report it on
     *         yet
     */
    /* default */ static KernelDiagnostics loadDiagnostics() {
        return ServiceLoader.load(KernelDiagnosticsProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(KernelDiagnosticsProvider::priority))
                .orElseThrow(() -> new IllegalStateException(
                        "No KernelDiagnosticsProvider found on the classpath"))
                .create();
    }

    /**
     * Reads NDJSON requests from {@code input} and writes NDJSON responses to {@code output} until
     * the input reaches end of file.
     *
     * <p>One response line is written and flushed per non-blank request line, so a consumer
     * reading line by line never waits on a buffer. Blank lines are skipped without a response,
     * which means responses correspond to non-blank requests in order, not to lines.
     *
     * @param input  request stream, read as UTF-8; closed on return
     * @param output response stream, written as UTF-8 and flushed per response; closed on return
     * @throws IOException if reading {@code input} or writing {@code output} fails. A failure
     *         inside a request is answered on the wire by {@link #handle}; only the channel
     *         breaking surfaces here
     */
    /* default */ void serve(InputStream input, OutputStream output) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
                if (!line.isBlank()) {
                    writer.write(handle(line));
                    writer.write('\n');
                    writer.flush();
                }
                line = reader.readLine();
            }
        }
    }

    /**
     * Handles one request line and returns the JSON response line. Never throws: malformed input,
     * unknown methods, and a failure inside the diagnostics implementation all come back as
     * {@code {"error":"…"}} on the same line the caller is waiting for.
     *
     * <p>The dispatch catch takes {@link LinkageError} and {@link ServiceConfigurationError}
     * alongside {@link RuntimeException}, and that is deliberate rather than defensive breadth.
     * {@code listProviders} instantiates every provider on the classpath through
     * {@link ServiceLoader}, so a provider whose class initialiser fails arrives here as an
     * {@code Error}, not an exception — and letting it escape ends the process, which costs the
     * caller the whole NDJSON channel rather than one answer. The protocol has no request ids and a
     * consumer caches the child across calls, so a dead process is a dead session. One broken
     * provider must degrade one method.
     *
     * @param requestLine one request, as a single line of JSON; a blank line never reaches here
     * @return the response line, without its terminating newline — either a serialised snapshot or
     *         an {@code {"error":"…"}} object; never {@code null}
     */
    // PMD.AvoidCatchingGenericException — the generic catch IS the contract here: this method is the
    // process boundary, and its documented promise is that nothing escapes it. Narrowing the catch
    // to the exception types seen so far would mean the next unforeseen one ends the session, which
    // is exactly the failure this guard exists to prevent. Everything caught is reported, not
    // swallowed: the caller gets an error response and the operator gets the stack on stderr.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ String handle(String requestLine) {
        JsonNode request;
        try {
            request = mapper.readTree(requestLine);
        } catch (JacksonException e) {
            return error("malformed request: " + e.getOriginalMessage());
        }
        String method = request.path("method").asString("");
        try {
            return switch (method) {
                case "listProviders" -> mapper.writeValueAsString(diagnostics.listProviders());
                case "getBootstrapDag" -> mapper.writeValueAsString(diagnostics.getBootstrapDag());
                case "getJvmErgonomics" -> mapper.writeValueAsString(diagnostics.getJvmErgonomics());
                case "describeSubsystem" -> describeSubsystem(request);
                case "" -> error("missing 'method'");
                default -> error("unknown method: " + method);
            };
        } catch (RuntimeException | LinkageError | ServiceConfigurationError e) {
            LOG.log(Logger.Level.ERROR, () -> "diagnostics method '" + method + "' failed", e);
            // The throwable's own text goes back over the wire, which is a decision and not an
            // oversight: this process has no network surface and trusts whoever spawned it (see
            // the class javadoc), the caller is the only party that can act on the failure, and a
            // response saying only "it failed" would send an operator to a stderr they may not be
            // capturing. That reasoning is local to a trusted stdio tool. A subsystem-facing error
            // path does NOT inherit it — Throwable.toString() carries wrapped-cause text, which is
            // where connection strings and principal names surface.
            return error("method '" + method + "' failed: " + e);
        }
    }

    private String describeSubsystem(JsonNode request) {
        String name = request.path("name").asString("");
        if (name.isBlank()) {
            return error("describeSubsystem requires a non-blank 'name'");
        }
        return mapper.writeValueAsString(diagnostics.describeSubsystem(name));
    }

    private String error(String message) {
        try {
            return mapper.writeValueAsString(Map.of("error", message));
        } catch (JacksonException _) {
            return "{\"error\":\"serialization failure\"}";
        }
    }
}
