/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions;

/**
 * Canonical registry of all {@code EX-[DOMAIN]-[ID]} error codes used across the Exeris Kernel.
 *
 * <h2>Design</h2>
 * <p>Codes are exposed as {@code public static final String} constants so that:
 * <ol>
 *   <li>The JIT can inline them as compile-time constants (no field lookup).</li>
 *   <li>Log-scrapers and the Glass-Box binary serializer can pattern-match against a
 *       fixed, well-known string pool.</li>
 *   <li>There is a single source of truth – no duplicated string literals in subclasses.</li>
 * </ol>
 *
 * <h2>The Wall</h2>
 * <p>This class is part of {@code exeris-kernel-spi} and is therefore <em>blind</em> to any
 * implementation detail (io_uring, JDBC, Netty). It contains only structured identifiers.
 *
 * <h2>Format</h2>
 * <pre>
 * EX – mandatory Exeris prefix
 * [DOMAIN] – 3-letter domain tag
 * [ID]     – 4-digit monotonic identifier within the domain
 * </pre>
 *
 * @since 0.5
 */
@SuppressWarnings("unused") // Codes are API contracts — referenced by future exception subclasses and external scrapers
public final class KernelErrorCodes {

    // -----------------------------------------------------------------------
    // EX-MEM – Memory / Off-Heap subsystem
    // -----------------------------------------------------------------------

    /**
     * Off-heap allocator exhausted: the requested byte count exceeds the
     * remaining capacity of the {@code MemoryAllocator} tier.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code long} requestedBytes</li>
     *   <li>index 1 – {@code long} availableBytes</li>
     * </ul>
     */
    public static final String EX_MEM_1001 = "EX-MEM-1001";

    /**
     * Off-heap arena leak detected: a {@code MemorySegment} was not returned
     * to its parent arena before the arena's lifecycle ended.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code long} segmentAddress</li>
     *   <li>index 1 – {@code long} segmentByteSize</li>
     * </ul>
     */
    public static final String EX_MEM_1002 = "EX-MEM-1002";

    /**
     * Allocation hint conflict: two subsystems requested incompatible
     * {@code AllocationHint} tiers for the same buffer slot.
     */
    public static final String EX_MEM_1003 = "EX-MEM-1003";

    // -----------------------------------------------------------------------
    // EX-BOOT – Bootstrap / Subsystem lifecycle
    // -----------------------------------------------------------------------

    /**
     * Circular dependency detected in the subsystem graph: Kahn's topological sort
     * found that the declared {@code dependsOn()} relationships form a cycle, making
     * a valid boot order impossible.
     *
     * <p>This is an <em>unrecoverable architectural defect</em>. The kernel halts
     * immediately; no degraded mode or partial boot is attempted.
     *
     * <p>Semantically associated with
     * {@link eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException}:
     * because that exception is a pure pre-telemetry panic type (plain {@code RuntimeException},
     * no {@code rawArgs}), the bootstrap orchestrator catches it and translates the failure
     * into Glass-Box telemetry using this error code.
     *
     * <p><b>rawArgs layout for Glass-Box</b> (emitted by the orchestrator, not by the exception):
     * <ul>
     *   <li>index 0 – {@code String[]} cycleMembers — ordered subsystem names forming the cycle,
     *       typically derived from
     *       {@link eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException#cycleMembers()}</li>
     * </ul>
     */
    public static final String EX_BOOT_0001 = "EX-BOOT-0001";

    /**
     * Subsystem initialization or startup failure.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <p>This code is shared across multiple bootstrap pathways and therefore
     * does not expose a stable {@code rawArgs} layout for Glass-Box decoding.
     * Callers may attach implementation-specific {@code rawArgs}, but
     * Glass-Box consumers must treat them as opaque and must not rely on a
     * particular arity or field order.</p>
     */
    public static final String EX_BOOT_0002 = "EX-BOOT-0002";

    /**
     * Kernel bootstrap sequence aborted: a mandatory subsystem did not
     * complete initialization within the deadline.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} subsystemName</li>
     *   <li>index 1 – {@code long} deadlineMs</li>
     * </ul>
     */
    public static final String EX_BOOT_0003 = "EX-BOOT-0003";

    /**
     * Memory provider bootstrap failure: the {@code MemoryProvider} could not
     * initialise its off-heap tier (e.g., insufficient system memory, mmap permission
     * denied, or missing native library).
     *
     * <p>This code is intentionally separate from {@link #EX_BOOT_0002} to guarantee
     * a stable, single rawArgs schema per error code – a hard requirement of the
     * binary Glass-Box telemetry contract.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName (e.g. {@code "ExerisEnterprise/GlobalArbiter"})</li>
     *   <li>index 1 – {@code long}   requestedBytes ({@code -1} if unknown)</li>
     * </ul>
     */
    public static final String EX_BOOT_0004 = "EX-BOOT-0004";

    // -----------------------------------------------------------------------
    // EX-BOOT – Telemetry bootstrap
    // -----------------------------------------------------------------------

    /**
     * Telemetry provider failed to initialise one or more sinks.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code String} reason</li>
     * </ul>
     */
    public static final String EX_BOOT_3001 = "EX-BOOT-3001";

    // -----------------------------------------------------------------------
    // EX-NET – Transport / Network layer
    // -----------------------------------------------------------------------

    /**
     * TLS encryption (wrap) path failure: the underlying native TLS engine returned a non-positive
     * value or threw an unexpected exception during the per-packet encrypt cycle.
     *
     * <p>Covers: native write errors, flush failures, and any unexpected {@code Throwable}
     * surfaced from the native invocation on the encrypt path.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code int}    nativeErrorCode (provider-specific error code; -1 if N/A)</li>
     *   <li>index 1 – {@code String} detail          (static, non-formatted constant)</li>
     * </ul>
     */
    public static final String EX_NET_2001 = "EX-NET-2001";

    /**
     * Crypto provider bootstrap failure: the {@code KernelCryptoProvider} could not
     * initialise its engine (e.g., missing native cryptographic library, invalid certificate,
     * or insufficient off-heap budget).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName (which provider failed to initialise)</li>
     *   <li>index 1 – {@code String} reason       (failure cause — static constant, never formatted)</li>
     * </ul>
     */
    public static final String EX_NET_2002 = "EX-NET-2002";

    /**
     * TLS decryption (unwrap) path failure: the underlying native TLS engine returned a non-positive
     * value or threw an unexpected exception during the per-packet decrypt cycle.
     *
     * <p>Intentionally separate from {@link #EX_NET_2001} (encrypt path) so that Glass-Box decoders
     * can distinguish a send-side cipher failure from a receive-side cipher failure without parsing
     * the {@code detail} string. This preserves the one-code-one-schema invariant required by the
     * binary telemetry contract.
     *
     * <p>Covers: native read errors, record-layer alerts received from the peer, and any unexpected
     * {@code Throwable} from the native invocation on the decrypt path.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code int}    nativeErrorCode (provider-specific error code; -1 if N/A)</li>
     *   <li>index 1 – {@code String} detail          (static, non-formatted constant)</li>
     * </ul>
     */
    public static final String EX_NET_2003 = "EX-NET-2003";

    /**
     * Transport-level protocol handshake or bind failure.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code int}    port (or -1 if unknown)</li>
     * </ul>
     */
    public static final String EX_NET_4001 = "EX-NET-4001";

    /**
     * Transport send failure: a frame or datagram could not be delivered.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code long}   bytesSent</li>
     * </ul>
     */
    public static final String EX_NET_4002 = "EX-NET-4002";

    /**
     * Transport receive timeout: no bytes arrived within the deadline.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code long}   timeoutMs</li>
     * </ul>
     */
    public static final String EX_NET_4003 = "EX-NET-4003";

    /**
     * Transport engine bootstrap failure: the {@code TransportProvider} could not
     * create a {@code TransportEngine} (e.g., missing native library, socket allocation
     * failure, insufficient off-heap budget for slab pools).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName (which transport failed)</li>
     *   <li>index 1 – {@code String} reason        (static failure description)</li>
     * </ul>
     */
    public static final String EX_NET_4004 = "EX-NET-4004";

    /**
     * Transport engine start failure: the engine was created but could not be started
     * (e.g., port already in use, carrier loop thread creation failed, io_uring ring
     * setup returned an error).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName (which engine failed to start)</li>
     *   <li>index 1 – {@code int}    port          (port that could not be bound; -1 if N/A)</li>
     * </ul>
     */
    public static final String EX_NET_4005 = "EX-NET-4005";

    /**
     * PAQS (Priority-Aware Queue Scheduler) load-shedding: an incoming stream was
     * rejected at the network edge because its priority was below the current
     * load-shedding threshold.
     *
     * <p>This is a deliberate, non-fatal policy decision — not a hardware failure.
     * No connection state is allocated for the shed stream.
     *
     * <p><b>Two surfaces, one code.</b> PAQS request-edge shedding emits this code as the JFR
     * {@code StreamShedEvent} (typed event fields — streamId, priority, action, transport, occupancy — read
     * by name, not by rawArgs index). The streaming stream-open shed (ADR-043) additionally <em>throws</em>
     * it via {@link eu.exeris.kernel.spi.exceptions.transport.TransportException#streamShed(String, long)};
     * the rawArgs layout below is that exception carrier's schema.
     *
     * <p><b>rawArgs layout for Glass-Box (exception carrier):</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code long}   streamId (identifier of the shed stream)</li>
     * </ul>
     */
    public static final String EX_NET_4006 = "EX-NET-4006";

    /**
     * Transport buffer exhaustion: no available {@code LoanedBuffer} segments remain
     * in the ingress {@code SlabPool}. Backpressure must be initiated.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code int}    poolCapacity  (total slab slots in the pool)</li>
     *   <li>index 2 – {@code int}    activeSlabs   (slabs currently in use)</li>
     * </ul>
     */
    public static final String EX_NET_4007 = "EX-NET-4007";

    // -----------------------------------------------------------------------
    // EX-HTTP – HTTP codec subsystem (HTTP/1.1, HTTP/2, HPACK, Huffman)
    // -----------------------------------------------------------------------

    /**
     * Huffman decoding/encoding violation in HPACK string literal processing.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} detail message</li>
     * </ul>
     */
    public static final String EX_HTTP_4001 = "EX-HTTP-4001";

    /**
     * HPACK decoding violation (RFC 7541 §3 / §4 / §6).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} detail message</li>
     * </ul>
     */
    public static final String EX_HTTP_4002 = "EX-HTTP-4002";

    /**
     * HTTP/2 SETTINGS validation violation (RFC 7540 §6.5.2).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} settingName</li>
     *   <li>index 1 – {@code long} actualValue</li>
     *   <li>index 2+ – expected bounds or expected literal contract</li>
     * </ul>
     */
    public static final String EX_HTTP_4003 = "EX-HTTP-4003";

    /**
     * HTTP/1.1 parse violation (malformed framing or DoS guard breach).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} detail message</li>
     * </ul>
     */
    public static final String EX_HTTP_4004 = "EX-HTTP-4004";

    /**
     * HTTP/2 CONTINUATION sequencing violation (RFC 7540 §6.10).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} detail message</li>
     * </ul>
     */
    public static final String EX_HTTP_4005 = "EX-HTTP-4005";

    /**
     * HTTP/2 Frame encoding/construction violation (RFC 7540 §4 / §6).
     * Raised when frame type, stream ID, payload size, or frame structure violates
     * protocol constraints during encoding or codec construction.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} detail message</li>
     * </ul>
     */
    public static final String EX_HTTP_4006 = "EX-HTTP-4006";

    /**
     * HTTP SPI provider bootstrap failure: no {@code HttpProvider} is available on the
     * classpath, or the selected provider could not initialise its engine.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName (or {@code "unknown"} if no provider found)</li>
     * </ul>
     */
    public static final String EX_HTTP_4007 = "EX-HTTP-4007";

    /**
     * HTTP server engine start failure: the {@code HttpServerEngine} could not bind the
     * configured port (port in use, TLS context creation failure, etc.).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code int}    port (or -1 if not applicable)</li>
     * </ul>
     */
    public static final String EX_HTTP_4008 = "EX-HTTP-4008";

    /**
     * HTTP client engine connection failure: the {@code HttpClientEngine} could not
     * establish a connection to the target host (DNS resolution failure, connection
     * refused, TLS handshake timeout).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code String} host</li>
     *   <li>index 2 – {@code int}    port (or -1 if not applicable)</li>
     * </ul>
     */
    public static final String EX_HTTP_4009 = "EX-HTTP-4009";

    /**
     * HTTP/2 Rapid Reset flood defense (CVE-2023-44487). A peer opened-then-reset streams past
     * the per-connection rapid-reset budget without performing matching work; the connection is
     * terminated with {@code GOAWAY(ENHANCE_YOUR_CALM)}. Secret-safe — connection-scoped counts
     * only, never request content.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code int} resetCount (net inbound resets at trip time)</li>
     *   <li>index 1 – {@code int} lastProcessedStreamId</li>
     * </ul>
     */
    public static final String EX_HTTP_4010 = "EX-HTTP-4010";

    /**
     * Server-push streaming: {@code HttpStreamExchange.emit(...)} was called after the stream had
     * already closed (peer disconnect, graceful close, or abortive teardown). Carried by
     * {@link eu.exeris.kernel.spi.exceptions.http.StreamClosedException} so an imperative emit loop
     * exits on the throw without leaking a parked virtual thread (ADR-043). Secret-safe — carries
     * only stream-scoped counters, never event payload.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code long} eventsEmitted (events successfully emitted before close)</li>
     * </ul>
     */
    public static final String EX_HTTP_4011 = "EX-HTTP-4011";

    /**
     * Server-push streaming: the authenticated principal's token expired while a stream was held
     * open. The stream is deterministically closed (fail-closed per ADR-012 §5 — no fail-open
     * fallthrough); validation happens at open-time and against an expiry deadline, never via a
     * per-emit re-fetch. Secret-safe — carries only stream-scoped counters, never token content.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code long} streamAgeMillis (elapsed since stream open at expiry)</li>
     *   <li>index 1 – {@code long} eventsEmitted (events successfully emitted before expiry)</li>
     * </ul>
     */
    public static final String EX_HTTP_4012 = "EX-HTTP-4012";

    /**
     * Inbound request body could not be decoded into the handler's target type — the bytes are
     * syntactically invalid for the binding, or do not bind to that type. A <em>caller</em> fault,
     * carried by {@link eu.exeris.kernel.spi.exceptions.http.RequestBodyDecodeException} so a
     * handler can answer {@code 400 Bad Request} without inspecting a message string.
     *
     * <p>Distinct from a decoder that is missing or unregistered, which stays an
     * {@code IllegalStateException} and is a <em>deployment</em> fault ({@code 5xx}). ADR-036 §2
     * puts status mapping on the handler; that mapping is only expressible if the two failures are
     * different types, so the split is part of the SPI contract rather than a driver detail.
     *
     * <p>Secret-safe: carries the target type name and the body length only. The body itself is
     * request content and never reaches telemetry — a guarantee the decoder TCK holds across the
     * whole {@code getCause()} chain, not just this exception's own message, because consumers log
     * causes and a binding that quotes the offending input would leak through that path.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} targetTypeName (binary name of the requested payload type)</li>
     *   <li>index 1 – {@code long}   bodySize (bytes offered to the decoder)</li>
     * </ul>
     */
    public static final String EX_HTTP_4013 = "EX-HTTP-4013";

    /**
     * A WebSocket send was attempted on a connection that is no longer writable — the handler
     * closed it, the peer went away, or the engine closed it on a protocol fault.
     *
     * <p>The receive direction deliberately does <em>not</em> raise this: a closed connection is the
     * ordinary end of a receive loop and returns {@code null}, while a send that cannot happen means
     * the handler had something to say and could not, which it has to see.
     *
     * <p>Secret-safe: carries counters and the close code, never message content — the text a
     * handler was trying to send is exactly the kind of payload most likely to be sensitive.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code long} connectionAgeMillis (how long the connection had been open)</li>
     *   <li>index 1 – {@code long} messagesSent (messages successfully sent before this attempt)</li>
     *   <li>index 2 – {@code int}  closeCode (RFC 6455 close code observed, 0 when none was seen)</li>
     * </ul>
     */
    public static final String EX_HTTP_4014 = "EX-HTTP-4014";

    /**
     * A WebSocket peer broke RFC 6455 and the connection is being closed for it — a reserved bit
     * set, a fragmented control frame, an oversize control payload, a continuation with no message
     * in progress, a message past the configured ceiling, a binary opcode on a text-only contract,
     * or a text payload that is not valid UTF-8.
     *
     * <p>Caller fault by construction: every case is something the peer put on the wire. The
     * distinction matters to an operator, who should not page for a malformed client.
     *
     * <p>Secret-safe, and deliberately more so than most: the offending frame is exactly the input
     * most likely to be hostile, so neither the message nor the rawArgs quote any of it. The
     * diagnostic value is in <em>which rule</em> broke and how the connection was closed, which the
     * close code carries on its own.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 &ndash; {@code int} closeCode (the RFC 6455 code the connection closes with)</li>
     * </ul>
     */
    public static final String EX_HTTP_4015 = "EX-HTTP-4015";

    // -----------------------------------------------------------------------
    // EX-PERS – Persistence subsystem
    // -----------------------------------------------------------------------

    /**
     * Persistence provider bootstrap failure: the {@code PersistenceProvider} could not
     * initialise its engine (e.g., connection refused, authentication failed, or
     * missing native driver library).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName (e.g. {@code "ExerisEnterprise/PgNative"})</li>
     *   <li>index 1 – {@code String} sanitizedConnectionUrl — userinfo ({@code user:password@})
     *       stripped before capture to prevent credential leakage into telemetry dumps</li>
     * </ul>
     */
    public static final String EX_PERS_5001 = "EX-PERS-5001";

    /**
     * Persistence connection acquisition failure: pool exhausted or timeout.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code long}   timeoutMs</li>
     *   <li>index 2 – {@code int}    activeConnections</li>
     * </ul>
     */
    public static final String EX_PERS_5002 = "EX-PERS-5002";

    /**
     * Persistence query execution failure: protocol error, SQL error, or I/O.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} sqlState (PostgreSQL SQLSTATE code)</li>
     *   <li>index 1 – {@code String} detail</li>
     * </ul>
     */
    public static final String EX_PERS_5003 = "EX-PERS-5003";

    /**
     * Persistence authentication failure: SCRAM/MD5/cleartext auth rejected.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} authMechanism</li>
     *   <li>index 1 – {@code String} serverMessage</li>
     * </ul>
     */
    public static final String EX_PERS_5004 = "EX-PERS-5004";

    /**
     * Persistence transport failure: socket I/O error during read/write.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code long}   fileDescriptor</li>
     *   <li>index 2 – {@code int}    errno</li>
     * </ul>
     */
    public static final String EX_PERS_5005 = "EX-PERS-5005";

    /**
     * Persistence interceptor initialization error: a {@code ConnectionInterceptor}
     * failed to prepare the connection for the given isolation context
     * (e.g., RLS {@code SET LOCAL} rejected, schema switch refused).
     *
     * <p>The engine discards the connection on this error — it MUST NOT be
     * returned to the pool.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} interceptorClass (simple class name of the failing interceptor)</li>
     *   <li>index 1 – {@code String} isolationKey (value from {@code StorageContext.isolationKey()},
     *                                or {@code "[none]"} for system-scope)</li>
     * </ul>
     */
    public static final String EX_PERS_5006 = "EX-PERS-5006";

    /**
     * No {@link eu.exeris.kernel.spi.persistence.PersistenceProvider} found on the classpath.
     *
     * <p>Kernel start is aborted. Add {@code exeris-kernel-community} or
     * {@code exeris-kernel-enterprise} to the runtime dependencies.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} message (human-readable diagnostic)</li>
     * </ul>
     */
    public static final String EX_PERS_5007 = "EX-PERS-5007";

    /**
     * A converting accessor was asked for a column type it does not implement (ADR-080 §2).
     *
     * <p>Refusal rather than a rendering: decoding an unimplemented type's bytes as text produces a
     * plausible wrong answer on a data path, which is the silent-corruption class ADR-080 exists to
     * close. The decision is made from the <em>declared</em> column type, never from an OID range —
     * a native {@code enum} is a text passthrough on the wire and would be rendered correctly by a
     * range heuristic that then corrupts ranges and composites sharing that range.
     *
     * <p>The refusal is a property of the column, not of the row: a SQL NULL in an unsupported
     * column still refuses, because {@code null} would report "no value here" when the truth is
     * "this column cannot be rendered".
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} declaredTypeName (the driver's name for the column type,
     *                                as reported by the result metadata)</li>
     *   <li>index 1 – {@code Integer} columnIndex (zero-based)</li>
     *   <li>index 2 – {@code String} accessor (the SPI method that refused, e.g. {@code "getString"})</li>
     * </ul>
     *
     * @since 0.12
     */
    public static final String EX_PERS_5008 = "EX-PERS-5008";

    // -----------------------------------------------------------------------
    // EX-SEC – Security / Principal context
    // -----------------------------------------------------------------------

    /**
     * PrincipalContext missing from the current {@code ScopedValue} slot.
     */
    public static final String EX_SEC_2001 = "EX-SEC-2001";

    /**
     * Token validation failed (expired, malformed, or revoked).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} tokenType (e.g. "JWT", "OPAQUE")</li>
     *   <li>index 1 – {@code String} failureReason (e.g. "expired", "malformed", "revoked")</li>
     * </ul>
     */
    public static final String EX_SEC_2002 = "EX-SEC-2002";

    /**
     * Insufficient privileges (RBAC): principal lacks required role(s).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} requiredRole</li>
     * </ul>
     */
    public static final String EX_SEC_2003 = "EX-SEC-2003";

    /**
     * StorageContext missing from the current {@code ScopedValue} slot.
     */
    public static final String EX_SEC_2004 = "EX-SEC-2004";

    // -----------------------------------------------------------------------
    // EX-GRPH – Graph subsystem (L2 Data Synthesis)
    // -----------------------------------------------------------------------

    /**
     * Graph engine bootstrap failure: the {@code GraphProvider} could not
     * initialise its engine (e.g., missing backend driver, connection refused,
     * or insufficient off-heap budget for slab pools).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName (e.g. {@code "ExerisCommunity/JdbcGraph"})</li>
     *   <li>index 1 – {@code String} reason       (static failure description)</li>
     * </ul>
     */
    public static final String EX_GRPH_5001 = "EX-GRPH-5001";

    /**
     * Graph query execution failure: traversal, MATCH, or CRUD operation failed.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} queryType (e.g. "BFS", "MATCH", "SHORTEST_PATH")</li>
     *   <li>index 1 – {@code String} detail    (static failure description)</li>
     * </ul>
     */
    public static final String EX_GRPH_5002 = "EX-GRPH-5002";

    /**
     * Graph dual-write sync failure: relational change could not be reflected
     * in the graph structure.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} edgeType (e.g. "FOLLOWS", "SIMILAR_TO")</li>
     *   <li>index 1 – {@code String} detail   (static failure description)</li>
     * </ul>
     */
    public static final String EX_GRPH_5003 = "EX-GRPH-5003";

    /**
     * Path not found: shortest-path algorithm failed to reach target node.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code long} sourceMost   (UUID.getMostSignificantBits())</li>
     *   <li>index 1 – {@code long} sourceLeast  (UUID.getLeastSignificantBits())</li>
     *   <li>index 2 – {@code long} targetMost   (UUID.getMostSignificantBits())</li>
     *   <li>index 3 – {@code long} targetLeast  (UUID.getLeastSignificantBits())</li>
     * </ul>
     */
    public static final String EX_GRPH_5004 = "EX-GRPH-5004";

    /**
     * Excessive allocation detected: graph driver exceeded pre-defined churn threshold.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} driverName</li>
     *   <li>index 1 – {@code long}   bytesAllocated</li>
     *   <li>index 2 – {@code long}   bytesTransferred</li>
     * </ul>
     */
    public static final String EX_GRPH_5005 = "EX-GRPH-5005";

    // -----------------------------------------------------------------------
    // EX-EVENT – Event Engine subsystem (L3 Logic Engines)
    // -----------------------------------------------------------------------

    /**
     * Generic event engine failure (no specific category).
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} message (human-readable diagnostic)</li>
     * </ul>
     */
    public static final String EX_EVENT_6001 = "EX-EVENT-6001";

    /**
     * Event bus publish failure: queue is full and the implementation cannot accept the event.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} eventType   (event type name)</li>
     *   <li>index 1 – {@code long}   queueDepth  (current depth when overflow occurred)</li>
     *   <li>index 2 – {@code long}   queueCapacity</li>
     * </ul>
     */
    public static final String EX_EVENT_6002 = "EX-EVENT-6002";

    /**
     * Event registry conflict: an event type was registered twice with different settings.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} eventType  (conflicting type name)</li>
     *   <li>index 1 – {@code int}    ordinal     (ordinal that is already in use)</li>
     * </ul>
     */
    public static final String EX_EVENT_6003 = "EX-EVENT-6003";

    /**
     * Event provider creation failure: the {@code EventProvider} could not create an engine
     * from the given configuration.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code String} reason (static failure description)</li>
     * </ul>
     */
    public static final String EX_EVENT_6004 = "EX-EVENT-6004";

    /**
     * Outbox event moved to dead-letter queue: maximum retries exhausted or broker rejected the event.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} eventType   (event type name)</li>
     *   <li>index 1 – {@code String} reason      (static failure reason)</li>
     *   <li>index 2 – {@code int}    retryCount  (number of delivery attempts)</li>
     * </ul>
     */
    public static final String EX_EVENT_6005 = "EX-EVENT-6005";

    /**
     * Projection handler threw during state fold: the handler passed to
     * {@code ProjectionEngine} threw a {@code RuntimeException} while applying
     * an event, causing the projection to diverge.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} projectionName     (logical name of the projection)</li>
     *   <li>index 1 – {@code int}    eventTypeOrdinal   (ordinal of the failing event type)</li>
     * </ul>
     */
    public static final String EX_EVENT_6006 = "EX-EVENT-6006";

    /**
     * Event-loop virtual-thread uncaught exception: the loop's dispatch thread terminated
     * unexpectedly, stopping all further event processing for this loop.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} loopName       (thread / loop identifier)</li>
     *   <li>index 1 – {@code String} exceptionType  (simple class name of the throwable)</li>
     * </ul>
     */
    public static final String EX_EVENT_6007 = "EX-EVENT-6007";

    /**
     * Event-log append version conflict: an optimistic-concurrency append
     * ({@code EventStreamAppender.append(streamId, expectedVersion, …)}, ADR-049) was rejected
     * because the caller's {@code expectedVersion} did not match the stream's current head
     * sequence. Fail-closed — the event is not appended and the head is unchanged.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} streamType       (the stream's type qualifier)</li>
     *   <li>index 1 – {@code long}   expectedVersion  (version the caller expected)</li>
     *   <li>index 2 – {@code long}   actualVersion    (the stream's actual head)</li>
     * </ul>
     */
    public static final String EX_EVENT_6008 = "EX-EVENT-6008";

    // -----------------------------------------------------------------------
    // EX-FLOW – Flow Engine / Saga Orchestration subsystem
    // -----------------------------------------------------------------------

    /**
     * Flow provider engine creation failure: the {@code FlowProvider} could not create
     * an engine from the given configuration.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code String} reason (static failure description)</li>
     * </ul>
     */
    public static final String EX_FLOW_7001 = "EX-FLOW-7001";

    /**
     * Flow engine lifecycle failure: start, stop, compile, or scheduler operation failed.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} engineName</li>
     *   <li>index 1 – {@code String} phase — one of: {@code "START"}, {@code "STOP"},
     *       {@code "COMPILE"}, {@code "SCHEDULE"}</li>
     *   <li>index 2 – {@code String} staticReasonCode — e.g. {@code "STARTUP_FAILED"},
     *       {@code "COMPILE_FAILED"}, {@code "QUEUE_FULL"}</li>
     *   <li>index 3 – {@code int}    contextValue — phase-specific numeric context
     *       (queue depth for SCHEDULE); {@code -1} when not applicable</li>
     * </ul>
     */
    public static final String EX_FLOW_7002 = "EX-FLOW-7002";

    /**
     * Flow step execution failure: a step returned FAIL or threw an unrecoverable exception.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} definitionName</li>
     *   <li>index 1 – {@code long}   instanceIdMost</li>
     *   <li>index 2 – {@code long}   instanceIdLeast</li>
     *   <li>index 3 – {@code int}    stepIndex</li>
     *   <li>index 4 – {@code String} staticReasonCode — e.g. {@code "STEP_FAILED"},
     *       {@code "COMPENSATION_FAILED"}</li>
     *   <li>index 5 – {@code String} causeType — {@code cause.getClass().getName()}
     *       or {@code "none"}; class names are stable and not user-controlled</li>
     * </ul>
     */
    public static final String EX_FLOW_7003 = "EX-FLOW-7003";

    /**
     * Flow registry conflict: a step or transition was registered with a duplicate or
     * unknown identifier.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code int}    stepId</li>
     *   <li>index 1 – {@code String} reason</li>
     * </ul>
     */
    public static final String EX_FLOW_7004 = "EX-FLOW-7004";

    // -----------------------------------------------------------------------
    // EX-CFG – Config subsystem (L0 Bootstrap)
    // -----------------------------------------------------------------------

    /**
     * Required configuration property missing: a mandatory key was not found in any
     * source (environment variable, file, Vault) during L0 bootstrap.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} missingKey (dot-path key, e.g. {@code "network.port"})</li>
     *   <li>index 1 – {@code String} providerName (which ConfigProvider was active)</li>
     * </ul>
     */
    public static final String EX_CFG_1001 = "EX-CFG-1001";

    /**
     * Configuration type mismatch: a property value exists but cannot be deserialized
     * into the requested target type.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} key          (dot-path key)</li>
     *   <li>index 1 – {@code String} expectedType (simple class name of the target type)</li>
     *   <li>index 2 – {@code String} actualValue  (caller-sanitized string snapshot of the
     *       underlying configuration value; callers <strong>MUST</strong> redact or truncate
     *       this field so that it never contains secrets, credentials, tokens, or other
     *       sensitive material. Emitting a raw config value verbatim constitutes a
     *       CWE-532 (Information Exposure Through Log Files) violation against the
     *       Exeris Security Contract. Any truncation or redaction is performed by the
     *       caller before emitting {@code rawArgs} to the Glass-Box telemetry sink.)</li>
     * </ul>
     */
    public static final String EX_CFG_1002 = "EX-CFG-1002";

    /**
     * Hot-reload file read error: the {@code NIO WatchService} watcher failed to read
     * or re-parse the configuration file on a change notification.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} filename (relative path under the config directory)</li>
     *   <li>index 1 – {@code String} reason   (static failure description)</li>
     * </ul>
     */
    public static final String EX_CFG_1003 = "EX-CFG-1003";

    /**
     * Immutable config key reload refused: the {@code NIO WatchService} watcher observed
     * an on-disk change to a key marked {@code @Immutable} (a sealed trust anchor) and
     * refused to apply it. The previous, sealed value remains authoritative — no field is
     * mutated. This is a security-relevant audit signal, not a runtime failure.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} filename (relative path under the config directory)</li>
     *   <li>index 1 – {@code String} key      (dot-path key of the sealed field; never the value)</li>
     * </ul>
     */
    public static final String EX_CFG_1004 = "EX-CFG-1004";

    // -----------------------------------------------------------------------
    // EX-RUN – Runtime / Scheduler
    // -----------------------------------------------------------------------

    /**
     * Virtual Thread pinned the carrier: a blocking call inside a virtual thread
     * has prevented the carrier from being reused.
     *
     * <p><b>rawArgs layout for Glass-Box:</b>
     * <ul>
     *   <li>index 0 – {@code long}   blockTimeMs</li>
     *   <li>index 1 – {@code String} carrierThreadName</li>
     * </ul>
     */
    public static final String EX_RUN_3002 = "EX-RUN-3002";

    // -----------------------------------------------------------------------
    // EX-DIAG – KernelDiagnostics SPI introspection audit (ADR-033)
    // -----------------------------------------------------------------------
    //
    // INFO-level audit codes (NOT exceptions): each out-of-process KernelDiagnostics
    // call emits one JFR event so operators can audit who introspected the kernel.
    // Cold path — emission allocation is acceptable (ADR-033 Obligation 2).

    /** {@link eu.exeris.kernel.spi.diagnostics.KernelDiagnostics#listProviders()} was invoked. */
    public static final String EX_DIAG_1001 = "EX-DIAG-1001";

    // EX-DIAG-1002 retired (pre-1.0): the listCapabilities() method it audited was removed — it
    // restated getBootstrapDag() with each subsystem providing only itself and could not deliver the
    // ADR-024 composition graph from runtime (that lives build-time/platform-side). The code is left
    // as a reserved gap rather than renumbered so 1003..1005 stay stable.

    /** {@link eu.exeris.kernel.spi.diagnostics.KernelDiagnostics#getBootstrapDag()} was invoked. */
    public static final String EX_DIAG_1003 = "EX-DIAG-1003";

    /** {@link eu.exeris.kernel.spi.diagnostics.KernelDiagnostics#describeSubsystem(String)} was invoked. */
    public static final String EX_DIAG_1004 = "EX-DIAG-1004";

    /** {@link eu.exeris.kernel.spi.diagnostics.KernelDiagnostics#getJvmErgonomics()} was invoked. */
    public static final String EX_DIAG_1005 = "EX-DIAG-1005";

    // =======================================================================
    // EX-BLOB-8xxx — Blob storage (ADR-056)
    // =======================================================================

    /**
     * The referenced object does not exist in the resolved tenant namespace.
     *
     * <p>rawArgs layout:
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code String} container — never the object key, which can carry
     *       application data</li>
     * </ul>
     */
    public static final String EX_BLOB_8001 = "EX-BLOB-8001";

    /**
     * A blob operation was attempted with no isolation key in the ambient
     * {@link eu.exeris.kernel.spi.security.StorageContext} — there is no namespace to resolve the
     * tenant-relative reference against, so the operation is denied rather than placed unscoped
     * (ADR-056 §5).
     *
     * <p>rawArgs layout:
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code String} deny reason — the isolation strategy name of the ambient
     *       context when it carries no key, or a driver reason code when resolution left the
     *       tenant namespace</li>
     * </ul>
     */
    public static final String EX_BLOB_8002 = "EX-BLOB-8002";

    /**
     * I/O failure during an upload or download transfer.
     *
     * <p>rawArgs layout:
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code String} container</li>
     * </ul>
     */
    public static final String EX_BLOB_8003 = "EX-BLOB-8003";

    /**
     * An upload wrote a different number of bytes than the content length it declared.
     *
     * <p>rawArgs layout:
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code long}   declaredLength</li>
     *   <li>index 2 – {@code long}   actualLength</li>
     * </ul>
     */
    public static final String EX_BLOB_8004 = "EX-BLOB-8004";

    /**
     * A transfer was refused because it exceeds the driver's configured single-object ceiling.
     *
     * <p>Distinct from {@link #EX_BLOB_8003}: nothing failed. A driver that must hold an object in one
     * buffer has a size beyond which it will not try, and an operator who sees this code raises the
     * ceiling or reaches for a driver that streams. Folding it into a transfer failure would leave that
     * operator debugging an I/O fault that never happened.
     *
     * <p>rawArgs layout:
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code long}   declaredBytes — the object size the caller asked for</li>
     *   <li>index 2 – {@code long}   ceilingBytes — the configured limit</li>
     * </ul>
     */
    public static final String EX_BLOB_8005 = "EX-BLOB-8005";

    /**
     * A remote blob store answered, and refused.
     *
     * <p>The status code is carried rather than wrapped in a cause, because it is the whole diagnosis:
     * {@code 403} is a credential or clock-skew fault in the caller's own configuration, {@code 5xx} is
     * the store's problem. Reporting both as a generic transfer failure would erase the one field that
     * decides who has to act.
     *
     * <p>rawArgs layout:
     * <ul>
     *   <li>index 0 – {@code String} providerName</li>
     *   <li>index 1 – {@code String} container — never the object key, which can carry
     *       application data</li>
     *   <li>index 2 – {@code int}    statusCode</li>
     * </ul>
     */
    public static final String EX_BLOB_8006 = "EX-BLOB-8006";

    /**
     * Blob storage was configured, and no {@code BlobStorageProvider} is on the classpath to serve
     * it. Distinct from {@link #EX_BLOB_8008}: nothing is ambiguous, there is simply nothing to
     * choose from, and the fix is a dependency rather than a key.
     *
     * <p>rawArgs layout:
     * <ul>
     *   <li>index 0 – {@code String} component — the bootstrap component that looked</li>
     * </ul>
     */
    public static final String EX_BLOB_8007 = "EX-BLOB-8007";

    /**
     * The configured blob provider id does not resolve to exactly one provider on the classpath.
     *
     * <p>One code rather than two for "no such id" and "the id is ambiguous", because the operator
     * action is the same in both: write a different value for one key. The message distinguishes
     * them; the code is what an alert routes on, and both route to the same person.
     *
     * <p>Thrown at boot, never per request — a store nobody could choose is not a runtime condition.
     *
     * <p>rawArgs layout:
     * <ul>
     *   <li>index 0 – {@code String} configKey — the key to set, so a refusal names its own remedy</li>
     *   <li>index 1 – {@code String} configuredId — what was set, or the empty string when unset</li>
     *   <li>index 2 – {@code String} availableIds — comma-joined provider ids actually discovered</li>
     * </ul>
     */
    public static final String EX_BLOB_8008 = "EX-BLOB-8008";

    /**
     * Blob storage is switched on and a configuration key it needs is unset.
     *
     * <p>Separate from {@link #EX_BLOB_8008} because the rawArgs mean different things and Glass-Box
     * tooling reads them positionally: 8008's last slot is the list of provider ids that were
     * available, and a free-text hint in that position would be parsed as ids. Different failure,
     * different layout, different code.
     *
     * <p>rawArgs layout:
     * <ul>
     *   <li>index 0 – {@code String} configKey — the key that is unset</li>
     *   <li>index 1 – {@code String} expected — what a value for it looks like</li>
     * </ul>
     */
    public static final String EX_BLOB_8009 = "EX-BLOB-8009";

    // -----------------------------------------------------------------------
    // Scheduling (EX-JOB-9xxx) — ADR-057
    // -----------------------------------------------------------------------

    /**
     * Job dispatch refused: the submission captured no identity context, and running under an
     * ambient or default identity is not an option (ADR-057 §5).
     *
     * <p>Never carried on a thrown exception, so it has no {@code rawArgs} layout: a dispatched job
     * runs on its own thread and has no caller to throw to. It is recorded on the
     * {@code eu.exeris.kernel.scheduling.JobFailure} JFR event, alongside {@code schedulerName},
     * {@code jobName} and {@code jobId}.
     */
    public static final String EX_JOB_9001 = "EX-JOB-9001";

    /**
     * A job was submitted to a scheduler that has already been closed.
     *
     * <p>rawArgs layout:
     * <ul>
     *   <li>index 0 – {@code String} schedulerName</li>
     *   <li>index 1 – {@code String} jobName</li>
     * </ul>
     */
    public static final String EX_JOB_9002 = "EX-JOB-9002";

    /**
     * A dispatched job body threw. The scheduler records the failure and keeps running; a repeating
     * job stays scheduled, because one failed run is not evidence the schedule is wrong.
     *
     * <p>JFR-only, for the same reason as {@link #EX_JOB_9001}: recorded on the
     * {@code eu.exeris.kernel.scheduling.JobFailure} event with {@code schedulerName},
     * {@code jobName}, {@code jobId}, and the body's exception class as the reason.
     */
    public static final String EX_JOB_9003 = "EX-JOB-9003";

    /**
     * No {@code JobSchedulerProvider} was found on the classpath at bootstrap.
     *
     * <p>rawArgs layout:
     * <ul>
     *   <li>index 0 – {@code String} component that attempted the selection</li>
     * </ul>
     */
    public static final String EX_JOB_9004 = "EX-JOB-9004";

    // -----------------------------------------------------------------------
    // EX-UNK – no code of its own
    // -----------------------------------------------------------------------

    /**
     * Stamped on a telemetry record that carried no error code — the sink needs something for the
     * code field and inventing a domain-specific one would be a guess.
     *
     * <p>Registered rather than left as a sink-local literal because a scraper meets it in the same
     * position as every other code and has to be able to look it up. It is the one code that means
     * "the emitter did not say", and an operator seeing it should look at the emitter, not at this
     * table.
     */
    public static final String EX_UNK_0000 = "EX-UNK-0000";

    // -----------------------------------------------------------------------
    // Constructor – utility class, no instantiation
    // -----------------------------------------------------------------------

    private KernelErrorCodes() {
        // Utility class – no instances
    }
}
