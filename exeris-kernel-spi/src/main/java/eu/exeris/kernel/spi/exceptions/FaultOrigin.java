/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions;

/**
 * SPI: who has to change something for a failed operation to succeed.
 *
 * <p>A kernel exception has always said <em>what</em> went wrong — an error code, a message, and
 * {@code rawArgs}. It has never said <em>whose fault</em> it was, and that is the question a
 * protocol adapter must answer to pick a status code. Until 0.12 the only place the kernel stated
 * it was ADR-036 §2, and it stated it by naming two exception <em>types</em>: a body that will not
 * bind is a {@code 400}, a decoder that is not registered is a {@code 5xx}. That works only for a
 * caller who already knows both types by name — and the natural
 * {@code catch (RuntimeException) -> 500} turns every malformed request into a server error.
 *
 * <h2>The default is the conservative one</h2>
 * <p>{@link #SYSTEM} is what an unclassified failure reports, which is what the runtime already did
 * before this enum existed — so nothing changes for an exception nobody has classified, and
 * classifying more of them later is an addition rather than a change of behaviour. The asymmetry is
 * deliberate: reporting a caller's mistake as a server error is a worse error message, while
 * reporting a broken deployment as the caller's mistake hides an outage behind a {@code 4xx} that
 * nobody pages on.
 *
 * <h2>Not a status code</h2>
 * <p>This says who is at fault, not what to answer. The mapping is the adapter's: HTTP reads
 * {@link #CALLER} as {@code 4xx} but chooses between {@code 400}, {@code 401}, {@code 403} and
 * {@code 409} from the exception itself, and a non-HTTP binding maps it somewhere else entirely.
 * Keeping status out of the SPI is what lets the exceptions stay protocol-blind (The Wall).
 *
 * <p>More constants may be added — a retryable-transient origin is the obvious candidate — so a
 * {@code switch} over this enum needs a {@code default}.
 *
 * @since 0.12
 */
public enum FaultOrigin {

    /**
     * The request, its arguments or its credentials are at fault. Repeating it unchanged fails the
     * same way, and no operator action makes it succeed.
     */
    CALLER,

    /**
     * The runtime, its configuration or its dependencies are at fault. The caller can do nothing
     * about it, and the same request may succeed once the deployment is fixed. This is what an
     * unclassified failure reports.
     */
    SYSTEM;

    /**
     * Classifies any throwable, so a caller needs one call rather than an {@code instanceof} plus a
     * remembered default. Named {@code classify} rather than {@code of} because it answers a
     * question about the argument instead of building a value from it.
     *
     * <p>Anything that is not an {@link ExerisKernelException} is {@link #SYSTEM}: a JDK exception
     * carries no origin, and guessing one from its type is how a {@code NoSuchElementException} from
     * an unbound kernel binding came to be answered as a bad request.
     *
     * @param failure the throwable to classify; may be {@code null}
     * @return the declared origin, or {@link #SYSTEM} for {@code null} or a non-kernel throwable
     */
    public static FaultOrigin classify(Throwable failure) {
        return failure instanceof ExerisKernelException kernelFailure
                ? kernelFailure.faultOrigin()
                : SYSTEM;
    }
}
