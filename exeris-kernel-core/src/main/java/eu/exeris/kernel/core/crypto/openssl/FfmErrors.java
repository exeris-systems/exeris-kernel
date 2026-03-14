/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.openssl;

/**
 * Package-private utility: FFM {@code invokeExact} error-propagation helpers.
 *
 * <p>FFM {@code MethodHandle.invokeExact()} declares {@code throws Throwable}, which forces
 * every call site to catch {@code Throwable}. A naive catch-and-rethrow-as-domain-exception
 * would silently swallow {@link Error} subclasses ({@code OutOfMemoryError},
 * {@code StackOverflowError}, {@code VirtualMachineError}), masking fatal JVM conditions
 * as recoverable TLS failures.
 *
 * <p>Callers must invoke {@link #rethrowIfError(Throwable)} as the first statement inside
 * every {@code catch (Throwable t)} block that wraps an {@code invokeExact} call.
 *
 * @since 0.5.0
 */
final class FfmErrors {

    private FfmErrors() {
        // utility
    }

    /**
     * Rethrows {@code throwable} immediately if it is an {@link Error}.
     *
     * @param throwable the throwable caught from an {@code invokeExact} call
     */
    /* package */ static void rethrowIfError(Throwable throwable) {
        if (throwable instanceof Error error) {
            throw error;
        }
    }
}
