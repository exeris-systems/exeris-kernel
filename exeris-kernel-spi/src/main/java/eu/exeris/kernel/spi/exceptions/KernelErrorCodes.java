/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions;

/**
 * Canonical registry of all {@code EX-[DOMAIN]-[ID]} error codes used across the Exeris Kernel.
 *
 * <h2>Design</h2>
 * <p>Codes are exposed as {@code public static final String} constants so that:
 * <ol>
 *   <li>The JIT can inline them as compile-time constants (no field lookup).</li>
 *   <li>Log-scrapers and the Black-Box binary serializer can pattern-match against a
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
 * @since 0.5.0
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
     * <p><b>rawArgs layout for Black-Box:</b>
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
     * <p><b>rawArgs layout for Black-Box:</b>
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
     * Subsystem initialization or startup failure.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} subsystemName</li>
     *   <li>index 1 – {@code SubsystemException.Phase} phase (INITIALIZE | START | STOP)</li>
     *   <li>index 2 – {@code String} detailMessage (static, no runtime formatting)</li>
     * </ul>
     */
    public static final String EX_BOOT_0002 = "EX-BOOT-0002";

    /**
     * Kernel bootstrap sequence aborted: a mandatory subsystem did not
     * complete initialization within the deadline.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
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
     * binary Black-Box telemetry contract.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
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
     * <p><b>rawArgs layout for Black-Box:</b>
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
     * TLS/Crypto operation failure (handshake, wrap, unwrap, or shutdown).
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code int}    opensslErrorCode (SSL_get_error(); 0 if N/A)</li>
     *   <li>index 1 – {@code String} detail</li>
     * </ul>
     */
    public static final String EX_NET_2001 = "EX-NET-2001";

    /**
     * Transport-level protocol handshake or bind failure.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code int}    port (or -1 if unknown)</li>
     * </ul>
     */
    public static final String EX_NET_4001 = "EX-NET-4001";

    /**
     * Transport send failure: a frame or datagram could not be delivered.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code long}   bytesSent</li>
     * </ul>
     */
    public static final String EX_NET_4002 = "EX-NET-4002";

    /**
     * Transport receive timeout: no bytes arrived within the deadline.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} transportName</li>
     *   <li>index 1 – {@code long}   timeoutMs</li>
     * </ul>
     */
    public static final String EX_NET_4003 = "EX-NET-4003";

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
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code String} tokenType (e.g. "JWT", "OPAQUE")</li>
     * </ul>
     */
    public static final String EX_SEC_2002 = "EX-SEC-2002";

    // -----------------------------------------------------------------------
    // EX-RUN – Runtime / Scheduler
    // -----------------------------------------------------------------------

    /**
     * Virtual Thread pinned the carrier: a blocking call inside a virtual thread
     * has prevented the carrier from being reused.
     *
     * <p><b>rawArgs layout for Black-Box:</b>
     * <ul>
     *   <li>index 0 – {@code long}   blockTimeMs</li>
     *   <li>index 1 – {@code String} carrierThreadName</li>
     * </ul>
     */
    public static final String EX_RUN_3002 = "EX-RUN-3002";

    // -----------------------------------------------------------------------
    // Constructor – utility class, no instantiation
    // -----------------------------------------------------------------------

    private KernelErrorCodes() {
        // Utility class – no instances
    }
}
