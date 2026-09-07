/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @since 0.11
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
     * <p>Written as three explicit shapes rather than an accumulated {@code ScopedValue.Carrier}:
     * every chain terminates in its own {@code run}, which is both what static analysis can verify
     * and what a reader can check at a glance. Either context may be absent on its own — only the
     * both-absent case is a refusal.
     */
    /* default */ void run(Runnable body) {
        if (principal != null && storage != null) {
            ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, principal)
                    .where(KernelProviders.STORAGE_CONTEXT, storage)
                    .run(body);
            return;
        }
        if (principal != null) {
            ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, principal).run(body);
            return;
        }
        if (storage != null) {
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, storage).run(body);
            return;
        }
        // Only reachable if the §5 guard in the scheduler is removed. It runs the body unrebound —
        // exactly what the guard forbids — rather than throwing, so removing the guard shows up as
        // the job running (which the contract test catches) instead of as an incidental failure that
        // would make the same test pass for the wrong reason.
        body.run();
    }
}
