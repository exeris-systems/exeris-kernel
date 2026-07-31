/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.scheduling;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.StorageContext;

/**
 * The identity a job carries from submission to dispatch (ADR-057 §5).
 *
 * <p>The two contexts travel together, so they are modelled together rather than as two nullable
 * fields on the handle: "was anything captured" is one question, not two.
 *
 * @param principal the submitting principal, or {@code null} if none was bound
 * @param storage   the submitting storage context, or {@code null} if none was bound
 * @since 0.11.0
 */
record CapturedContext(PrincipalContext principal, StorageContext storage) {

    /** Snapshots whatever is bound on the submitting thread. */
    /* default */ static CapturedContext capture() {
        // orElse(null) is not available: ScopedValue rejects a null fallback.
        return new CapturedContext(
                KernelProviders.PRINCIPAL_CONTEXT.isBound()
                        ? KernelProviders.PRINCIPAL_CONTEXT.get() : null,
                KernelProviders.STORAGE_CONTEXT.isBound()
                        ? KernelProviders.STORAGE_CONTEXT.get() : null);
    }

    /** True when nothing was bound, which makes the job undispatchable (ADR-057 §5). */
    /* default */ boolean isEmpty() {
        return principal == null && storage == null;
    }

    /**
     * Rebinds whatever was captured and runs the body.
     *
     * <p>Chained through {@code ScopedValue.Carrier} rather than nested {@code where(...).run(...)}
     * calls, which is the shape the bootstrap path already uses for multi-value rebinds. Either
     * context may be absent on its own — only the both-absent case is a refusal.
     */
    /* default */ void run(Runnable body) {
        ScopedValue.Carrier carrier = null;
        if (principal != null) {
            carrier = ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, principal);
        }
        if (storage != null) {
            carrier = carrier == null
                    ? ScopedValue.where(KernelProviders.STORAGE_CONTEXT, storage)
                    : carrier.where(KernelProviders.STORAGE_CONTEXT, storage);
        }
        if (carrier == null) {
            // Only reachable if the §5 guard in the scheduler is removed. It runs the body unrebound
            // — exactly what the guard forbids — rather than dereferencing null, so removing the
            // guard shows up as the job running (which the contract test catches) instead of as an
            // incidental NullPointerException that would make the same test pass for the wrong reason.
            body.run();
            return;
        }
        carrier.run(body);
    }
}
