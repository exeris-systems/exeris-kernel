/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.security.identity.KeyRotationPolicy;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link JwksKeyResolver} that maintains a current and a single retiring (previous) key
 * generation and applies a {@link KeyRotationPolicy} with an overlap window and a
 * stale-fetch budget.
 *
 * <h2>Behavior (fail-closed, ADR-012)</h2>
 * <ul>
 *   <li>On resolve, if the current snapshot age exceeds {@code staleFetchBudget}, attempt a
 *       {@link KeySetSource} refresh. A genuinely-new key set rotates: current becomes
 *       previous (stamped now), the new set becomes current.</li>
 *   <li>If refresh fails AND the current snapshot is over budget AND no previous generation
 *       is still inside its overlap window, deny terminally with reason {@code jwks-stale}.</li>
 *   <li>{@code kid} in current → return key.</li>
 *   <li>{@code kid} only in previous, overlap still open → return key.</li>
 *   <li>{@code kid} only in previous, overlap expired → terminal deny {@code kid-rotated-out}.</li>
 *   <li>{@code kid} in neither → return {@code null} (validator throws {@code unknown-kid}).</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * <p>The generations holder is an immutable record swapped via a single {@code AtomicReference}.
 * Hot-path reads are lock-free and never observe a torn state. The cold refresh/rotation
 * critical section is guarded by an intrinsic lock so concurrent virtual threads coalesce
 * on a single refresh rather than racing partial rotations.
 *
 * @since 0.9.0
 */
/* default */ final class CommunityRotatingKeySet implements JwksKeyResolver {

    private static final String JWT_TYPE = "JWT";
    private static final String ERR_STALE = "jwks-stale";
    private static final String ERR_ROTATED_OUT = "kid-rotated-out";

    private static final String PHASE_ROTATION = "ROTATION";
    private static final String PHASE_CUTOVER_DENY = "CUTOVER_DENY";
    private static final String PHASE_STALE_DENY = "STALE_DENY";

    private final KeySetSource source;
    private final Clock clock;
    private final long staleBudgetMillis;
    private final long overlapMillis;
    private final Object refreshLock = new Object();
    private final AtomicReference<Generations> generations;

    /* default */ CommunityRotatingKeySet(Map<String, RSAPublicKey> initialKeys,
                                          KeySetSource source,
                                          KeyRotationPolicy policy,
                                          Clock clock) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        this.staleBudgetMillis = policy.staleFetchBudget().toMillis();
        this.overlapMillis = policy.overlapWindow().toMillis();
        Map<String, RSAPublicKey> current =
                Map.copyOf(Objects.requireNonNull(initialKeys, "initialKeys must not be null"));
        // An empty initial generation holds no verification keys, so it is stamped at the epoch —
        // immediately stale. The first resolve() then triggers a JWKS refresh rather than denying
        // every token until the stale-fetch budget elapses (this is what lets overJwksEndpoint seed
        // an empty key set and fetch lazily on first use). A seeded generation stamps at now.
        long installedAtMillis = current.isEmpty() ? 0L : clock.millis();
        this.generations = new AtomicReference<>(new Generations(current, installedAtMillis, null, 0L));
    }

    @Override
    public RSAPublicKey resolve(String kid) {
        // Hot path: compare epoch-millis (no Instant/Duration allocation per request).
        Generations gens = generations.get();

        if (isStale(gens.currentInstalledAtMillis(), clock.millis())) {
            gens = attemptRefresh();
        }

        RSAPublicKey current = gens.current().get(kid);
        if (current != null) {
            return current;
        }

        RSAPublicKey previous = gens.previous() == null ? null : gens.previous().get(kid);
        if (previous != null) {
            if (withinOverlap(gens.previousInstalledAtMillis(), clock.millis())) {
                return previous;
            }
            // Per-request deny: the kid lives only in a retired generation whose overlap
            // window has closed. Emitted on every such attempt (an old token in a loop will
            // re-emit), so the phase is named as a deny, not a one-shot transition.
            CommunityJwksKeyRotationEvent.emit(
                    PHASE_CUTOVER_DENY, kid, gens.current().size(), gens.previous().size());
            throw new SecurityAuthenticationException(JWT_TYPE, ERR_ROTATED_OUT);
        }

        return null;
    }

    private boolean isStale(long installedAtMillis, long nowMillis) {
        return nowMillis - installedAtMillis > staleBudgetMillis;
    }

    private boolean withinOverlap(long installedAtMillis, long nowMillis) {
        return nowMillis - installedAtMillis <= overlapMillis;
    }

    private Generations attemptRefresh() {
        synchronized (refreshLock) {
            Generations gens = generations.get();
            // Another thread may have refreshed while we waited on the lock.
            if (!isStale(gens.currentInstalledAtMillis(), clock.millis())) {
                return gens;
            }

            Map<String, RSAPublicKey> fresh;
            try {
                fresh = source.load();
            } catch (KeySetRefreshException _) {
                return denyIfStaleBeyondOverlap(gens);
            }

            if (fresh == null) {
                return denyIfStaleBeyondOverlap(gens);
            }

            long now = clock.millis();
            if (fresh.equals(gens.current())) {
                // Same material — refresh the stamp, do not rotate or evict previous.
                Generations refreshed = new Generations(
                        gens.current(), now, gens.previous(), gens.previousInstalledAtMillis());
                generations.compareAndSet(gens, refreshed);
                return generations.get();
            }

            Map<String, RSAPublicKey> newCurrent = Map.copyOf(fresh);
            Generations rotated = new Generations(newCurrent, now, gens.current(), now);
            generations.compareAndSet(gens, rotated);
            CommunityJwksKeyRotationEvent.emit(
                    PHASE_ROTATION, null, newCurrent.size(), gens.current().size());
            return generations.get();
        }
    }

    private Generations denyIfStaleBeyondOverlap(Generations gens) {
        long now = clock.millis();
        boolean previousStillOpen =
                gens.previous() != null && withinOverlap(gens.previousInstalledAtMillis(), now);
        if (isStale(gens.currentInstalledAtMillis(), now) && !previousStillOpen) {
            int prevCount = gens.previous() == null ? 0 : gens.previous().size();
            CommunityJwksKeyRotationEvent.emit(
                    PHASE_STALE_DENY, null, gens.current().size(), prevCount);
            throw new SecurityAuthenticationException(JWT_TYPE, ERR_STALE);
        }
        return gens;
    }

    /**
     * Immutable holder for the current and (optional) previous key generations, each with
     * the epoch-millis instant it was installed ({@code previousInstalledAtMillis} is unused
     * when {@code previous} is {@code null}). Swapped atomically — never mutated in place.
     */
    private record Generations(
            Map<String, RSAPublicKey> current,
            long currentInstalledAtMillis,
            Map<String, RSAPublicKey> previous,
            long previousInstalledAtMillis) {
    }
}
