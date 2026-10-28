/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.graph;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Reserved: no kernel code path throws this exception. It is the code a graph driver uses to report,
 * at runtime, that it allocated more than its churn budget for the bytes it returned; the churn
 * contract itself is enforced at build time by {@code GraphChurnRatioTck}, by assertion.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <pre>
 * index 0 → String  driverName       (the offending graph driver identifier)
 * index 1 → long    bytesAllocated   (total bytes allocated by the driver)
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
 * @since 0.5
 */
public final class ExcessiveAllocationException extends ExerisKernelException {

    private static final String MESSAGE = "Driver exceeded churn threshold";

    /**
     * Creates the exception for a driver that exceeded its allocation threshold.
     *
     * @param driverName       identifier of the offending graph driver
     * @param bytesAllocated   total bytes allocated
     * @param bytesTransferred actual bytes transferred (useful work)
     */
    public ExcessiveAllocationException(String driverName, long bytesAllocated, long bytesTransferred) {
        super(KernelErrorCodes.EX_GRPH_5005, MESSAGE, null, driverName, bytesAllocated, bytesTransferred);
    }
}

