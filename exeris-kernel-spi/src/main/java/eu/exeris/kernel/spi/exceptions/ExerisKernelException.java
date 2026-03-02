/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Abstract base exception for the Exeris Kernel – the "Black Box" foundation.
 *
 * <h2>Zero-Allocation Telemetry Contract</h2>
 * <p>This class is the cornerstone of the <em>Zero-Allocation Telemetry</em> pattern. It enforces
 * that <strong>no String formatting occurs on the hot-path</strong>. Domain context is captured
 * as a raw {@code Object[]} array ({@link #rawArgs}), which the Enterprise Black-Box tier can
 * serialize directly to a binary crash-log struct without ever invoking {@code toString()} or
 * triggering GC pressure.
 *
 * <h2>Error Code Format</h2>
 * <p>Every exception MUST supply an {@code EX-[DOMAIN]-[ID]} code:
 * <ul>
 *   <li>{@code EX-MEM-*} – Memory / Off-Heap subsystem</li>
 *   <li>{@code EX-BOOT-*} – Bootstrap / Subsystem lifecycle</li>
 *   <li>{@code EX-NET-*} – Transport / Network layer</li>
 *   <li>{@code EX-SEC-*} – Security / Principal context</li>
 *   <li>{@code EX-RUN-*} – Runtime / Scheduler</li>
 * </ul>
 *
 * <h2>Usage – subclass pattern</h2>
 * <pre>{@code
 * // CORRECT – raw args, zero String allocation on hot-path:
 * public MemoryExhaustedException(long requestedBytes, long availableBytes) {
 *     super("EX-MEM-1001", "Off-heap allocator exhausted", null,
 *           requestedBytes, availableBytes);
 * }
 *
 * // WRONG – do NOT do this in any subclass constructor:
 * super("Memory exhausted: requested %d bytes".formatted(requestedBytes));
 * }</pre>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This class lives in {@code exeris-kernel-spi} and has <strong>zero knowledge</strong> of
 * io_uring, Netty, JDBC, or any protocol-specific concept. It is a pure contract.
 *
 * @see <a href="../../../../../../docs/subsystems/exceptions.md">exceptions.md – Exceptions Subsystem</a>
 * @since 0.5.0
 */
public abstract class ExerisKernelException extends RuntimeException {

    /** Shared empty array returned when no rawArgs are supplied — zero allocation per construction. */
    private static final Object[] EMPTY_ARGS = new Object[0];

    /**
     * Structured error code in {@code EX-[DOMAIN]-[ID]} format.
     * Immutable; used by log scrapers and Black-Box binary serializers.
     */
    private final String errorCode;

    /**
     * Distributed trace identifier – auto-generated at throw site.
     * <p>Two allocations are intentionally permitted at construction time:
     * a {@code UUID} (128 bits, critical for distributed forensics) and an
     * {@link Instant} timestamp. Both are bounded, non-recurring, and acceptable
     * given that exceptions are never on the hot-path.
     */
    private final UUID traceId;

    /**
     * Nanosecond-precision timestamp captured at construction time via
     * {@link Instant#now()} and stored as an immutable {@link Instant}.
     * <p>This field is intentionally kept as a high-fidelity timestamp object;
     * if a future version requires zero-allocation/flattened storage, this may
     * evolve to a primitive epoch-based representation (e.g. epoch-nanos).
     */
    private final Instant timestamp;

    /**
     * Raw domain arguments – the "Black Box payload".
     *
     * <p><strong>Contract:</strong> these values are <em>never</em> concatenated
     * or formatted inside this class. The Enterprise tier reads them as a typed
     * binary struct (e.g., {@code long}, {@code int}, {@code Enum}) with zero GC
     * overhead. Community tier may lazily call {@code toString()} on them only
     * in non-critical logging paths.
     *
     * <p>The array reference is stored directly – no defensive copy – because
     * subclass constructors pass freshly constructed argument lists and this
     * class must not introduce any additional allocation.
     */
    @SuppressWarnings("java:S1948") // rawArgs intentionally Object[] for binary telemetry
    private final Object[] rawArgs;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Primary constructor – all subclasses MUST delegate here.
     *
     * @param errorCode a non-null {@code EX-[DOMAIN]-[ID]} code  (e.g. {@code "EX-MEM-1001"})
     * @param message   a static, pre-defined message template – <strong>no runtime formatting</strong>
     * @param cause     optional chained throwable; may be {@code null}
     * @param rawArgs   zero or more domain-specific raw arguments stored as-is for binary telemetry
     * @throws NullPointerException if {@code errorCode} is {@code null}
     */
    protected ExerisKernelException(
            String errorCode,
            String message,
            Throwable cause,
            Object... rawArgs) {
        super(message, cause);
        Objects.requireNonNull(errorCode, "errorCode must not be null – every kernel exception requires an EX- code");
        this.errorCode = errorCode;
        this.traceId   = UUID.randomUUID();
        this.timestamp = Instant.now();
        // Null-safe: treat missing rawArgs as an empty array to avoid NPE in telemetry
        this.rawArgs   = (rawArgs != null) ? rawArgs : EMPTY_ARGS;
    }

    /**
     * Convenience constructor without a cause.
     *
     * @param errorCode a non-null {@code EX-[DOMAIN]-[ID]} code
     * @param message   static message template
     * @param rawArgs   raw domain arguments
     */
    protected ExerisKernelException(
            String errorCode,
            String message,
            Object... rawArgs) {
        this(errorCode, message, null, rawArgs);
    }

    /**
     * Low-level constructor for zero-allocation Sentinel Exceptions.
     *
     * <p>Disables suppression and/or stack-trace generation to prevent heap allocations
     * on hot-path network and protocol operations. The {@code reserved} parameter is
     * reserved for future binary-layout extensions and is currently ignored.
     *
     * <p><strong>Performance Contract:</strong> passing {@code writableStackTrace=false}
     * eliminates the {@link Throwable#fillInStackTrace()} allocation on the hot path —
     * a key requirement for L0 Sentinel exception patterns.
     *
     * @param errorCode          a non-null {@code EX-[DOMAIN]-[ID]} code
     * @param message            static message template — no runtime formatting
     * @param cause              optional chained throwable; may be {@code null}
     * @param enableSuppression  whether suppression is enabled or disabled
     * @param writableStackTrace whether the stack trace should be writable
     * @param reserved           reserved for future binary-layout extensions; pass {@code -1}
     * @param rawArgs            zero or more domain-specific raw arguments
     */
    @SuppressWarnings({
        "java:S107",      // Many params required by RuntimeException low-level constructor contract
        "PMD.UseVarargs"  // ARCH-DECISION: Object[] is intentional — NOT a varargs candidate.
                          // Changing to Object... would create a compiler ambiguity with the
                          // primary (String, String, Throwable, Object...) constructor, silently
                          // routing Sentinel construction through the wrong call chain and
                          // bypassing the enableSuppression/writableStackTrace zero-alloc contract.
                          // See: docs/subsystems/exceptions.md § "Sentinel Pattern"
    })
    protected ExerisKernelException(
            String errorCode,
            String message,
            Throwable cause,
            boolean enableSuppression,
            boolean writableStackTrace,
            int reserved,
            Object[] rawArgs) {
        super(message, cause, enableSuppression, writableStackTrace);
        Objects.requireNonNull(errorCode, "errorCode must not be null – every kernel exception requires an EX- code");
        this.errorCode = errorCode;
        this.traceId   = UUID.randomUUID();
        this.timestamp = Instant.now();
        this.rawArgs   = (rawArgs != null) ? rawArgs : EMPTY_ARGS;
    }

    // -----------------------------------------------------------------------
    // Accessors – read-only, zero-allocation
    // -----------------------------------------------------------------------

    /**
     * Returns the structured error code in {@code EX-[DOMAIN]-[ID]} format.
     * <p>This value is safe to include in network responses in PROD mode
     * because it contains no user data.
     *
     * @return the immutable error code; never {@code null}
     */
    public final String errorCode() {
        return errorCode;
    }

    /**
     * Returns the auto-generated distributed trace identifier.
     * <p>Correlate this UUID with APM / Black-Box logs to reconstruct
     * the full causal chain across microservices.
     *
     * @return a non-null {@link UUID}
     */
    public final UUID traceId() {
        return traceId;
    }

    /**
     * Returns the nanosecond-precision instant at which this exception was constructed.
     * <p>Can be used by the Black-Box serializer to order crash events on the timeline.
     *
     * @return a non-null {@link Instant}
     */
    public final Instant timestamp() {
        return timestamp;
    }

    /**
     * Returns the raw domain arguments stored for binary telemetry.
     *
     * <h3>Enterprise Black-Box Contract</h3>
     * <p>The caller <strong>MUST NOT</strong> invoke {@code toString()} on any element
     * on the hot-path. The Enterprise tier maps element types to fixed-size binary
     * records (e.g., index 0 is {@code long requestedBytes}, index 1 is {@code long availableBytes}).
     * This mapping is documented in each concrete subclass Javadoc.
     *
     * <p>The array is returned by reference – <em>no defensive copy</em> – to preserve
     * zero-allocation semantics. Callers must treat it as read-only.
     *
     * @return a non-null, possibly empty {@code Object[]} of raw domain arguments
     */
    public final Object[] rawArgs() {
        return rawArgs;
    }

}



