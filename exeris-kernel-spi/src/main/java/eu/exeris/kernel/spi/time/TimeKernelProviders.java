/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.time;

/**
 * {@link ScopedValue} slot for the {@link TimeSource} the kernel reads time from (ADR-082).
 *
 * <h2>Separation from {@code KernelProviders}</h2>
 * <p>The slot lives in the package that owns its type, so the generic
 * {@link eu.exeris.kernel.spi.context.KernelProviders} registry names no type from a package whose
 * maturity differs from its own (ADR-100). {@code KernelProviders} keeps a deprecated member that is
 * the very same {@link ScopedValue} instance declared here, not a second slot: a binding made
 * through either name is visible through both.
 *
 * <h2>Binding (bootstrap side)</h2>
 * {@snippet lang="java" :
 * ScopedValue
 *     .where(TimeKernelProviders.TIME_SOURCE, source)
 *     .run(kernel::startSubsystems);
 * }
 *
 * <h2>Reading (subsystem side)</h2>
 * {@snippet lang="java" :
 * java.time.Instant now = TimeKernelProviders.timeSource().wallTime();
 * }
 *
 * <p><b>Allocation:</b> zero-alloc — reading the slot allocates nothing.
 * <p><b>Thread confinement:</b> any thread inside the binding scope; the class documentation of
 * {@link eu.exeris.kernel.spi.context.KernelProviders} states which threads see a binding.
 * <p><b>Ownership:</b> whoever binds the source owns it; a reader borrows it for the duration of the
 * binding scope.
 *
 * @since 0.13
 */
public final class TimeKernelProviders {

    /**
     * Where the kernel reads time it will decide on (ADR-082).
     *
     * <p>Bound once at bootstrap.
     *
     * @apiNote Read it through {@link #timeSource()} rather than directly: an unbound kernel must
     *          still tell the time, and a call site that forgets its own {@code orElse} looks
     *          migrated while remaining undrivable.
     * @since 0.13
     */
    public static final ScopedValue<TimeSource> TIME_SOURCE = ScopedValue.newInstance();

    private TimeKernelProviders() {
        // Static ScopedValue slot only — never instantiated.
    }

    /**
     * Returns the bound {@link TimeSource}, or the platform clock when none is bound.
     *
     * <p>Unbound is the ordinary case for anything running outside a kernel scope — a test, a
     * standalone driver — so this returns a default rather than throwing. Deciding reads call this;
     * measuring reads call {@link System#nanoTime()} directly, which ADR-082 rules on.
     *
     * @return the bound source, or {@link TimeSource#SYSTEM}; never {@code null}
     * @since 0.13
     */
    public static TimeSource timeSource() {
        return TIME_SOURCE.orElse(TimeSource.SYSTEM);
    }
}
