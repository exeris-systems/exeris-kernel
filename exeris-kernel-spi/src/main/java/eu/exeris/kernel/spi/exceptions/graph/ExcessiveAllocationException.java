/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.graph;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when a graph driver exceeds its pre-defined off-heap churn threshold.
 *
 * <p>This exception enforces the <em>No Waste Compute</em> mandate: a driver
 * that allocates more memory than it transfers is a performance hazard and must
 * be evicted from the hot-path immediately.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <pre>
 * index 0 → String  driverName       (the offending graph driver identifier)
 * index 1 → long    bytesAllocated   (total off-heap bytes allocated by the driver)
 * index 2 → long    bytesTransferred (actual bytes transferred / used)
 * </pre>
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_GRPH_5005}
 *
 * <p><b>Allocation:</b> allocates (one {@code rawArgs} array per instance, boxing its two
 * long components); no constructor formats a string, and the message text is a shared
 * constant.
 *
 * @implNote The subsystem TCK ({@code GraphChurnRatioTck}) enforces the churn-to-data ratio
 *           this exception describes by failing the build with an assertion, not by
 *           throwing it — no kernel code path raises this exception today.
 * @since 0.5
 */
public final class ExcessiveAllocationException extends ExerisKernelException {

    private static final String MESSAGE = "Driver exceeded churn threshold";

    /**
     * Creates the exception for a driver that exceeded its allocation threshold.
     *
     * @param driverName       identifier of the offending graph driver
     * @param bytesAllocated   total off-heap bytes allocated
     * @param bytesTransferred actual bytes transferred (useful work)
     */
    public ExcessiveAllocationException(String driverName, long bytesAllocated, long bytesTransferred) {
        super(KernelErrorCodes.EX_GRPH_5005, MESSAGE, null, driverName, bytesAllocated, bytesTransferred);
    }
}

