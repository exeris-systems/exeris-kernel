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

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private static final String PHASE_CUTOVER = "CUTOVER";
    private static final String PHASE_STALE_DENY = "STALE_DENY";

    private final KeySetSource source;
    private final KeyRotationPolicy policy;
    private final Clock clock;
    private final Object refreshLock = new Object();
    private final AtomicReference<Generations> generations;

    /* default */ CommunityRotatingKeySet(Map<String, RSAPublicKey> initialKeys,
                                          KeySetSource source,
                                          KeyRotationPolicy policy,
                                          Clock clock) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Map<String, RSAPublicKey> current =
                Map.copyOf(Objects.requireNonNull(initialKeys, "initialKeys must not be null"));
        this.generations = new AtomicReference<>(new Generations(current, clock.instant(), null, null));
    }

    @Override
    public RSAPublicKey resolve(String kid) {
        Generations gens = generations.get();

        if (isStale(gens.currentInstalledAt())) {
            gens = attemptRefresh();
        }

        RSAPublicKey current = gens.current().get(kid);
        if (current != null) {
            return current;
        }

        RSAPublicKey previous = gens.previous() == null ? null : gens.previous().get(kid);
        if (previous != null) {
            if (withinOverlap(gens.previousInstalledAt())) {
                return previous;
            }
            CommunityJwksKeyRotationEvent.emit(
                    PHASE_CUTOVER, null, kid, gens.current().size(), gens.previous().size());
            throw new SecurityAuthenticationException(JWT_TYPE, ERR_ROTATED_OUT);
        }

        return null;
    }

    private boolean isStale(Instant currentInstalledAt) {
        Duration age = Duration.between(currentInstalledAt, clock.instant());
        return age.compareTo(policy.staleFetchBudget()) > 0;
    }

    private boolean withinOverlap(Instant previousInstalledAt) {
        if (previousInstalledAt == null) {
            return false;
        }
        Duration age = Duration.between(previousInstalledAt, clock.instant());
        return age.compareTo(policy.overlapWindow()) <= 0;
    }

    private Generations attemptRefresh() {
        synchronized (refreshLock) {
            Generations gens = generations.get();
            // Another thread may have refreshed while we waited on the lock.
            if (!isStale(gens.currentInstalledAt())) {
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

            Instant now = clock.instant();
            if (fresh.equals(gens.current())) {
                // Same material — refresh the stamp, do not rotate or evict previous.
                Generations refreshed = new Generations(
                        gens.current(), now, gens.previous(), gens.previousInstalledAt());
                generations.compareAndSet(gens, refreshed);
                return generations.get();
            }

            Map<String, RSAPublicKey> newCurrent = Map.copyOf(fresh);
            Generations rotated = new Generations(newCurrent, now, gens.current(), now);
            generations.compareAndSet(gens, rotated);
            CommunityJwksKeyRotationEvent.emit(
                    PHASE_ROTATION, null, null, newCurrent.size(), gens.current().size());
            return generations.get();
        }
    }

    private Generations denyIfStaleBeyondOverlap(Generations gens) {
        boolean previousStillOpen = withinOverlap(gens.previousInstalledAt());
        if (isStale(gens.currentInstalledAt()) && !previousStillOpen) {
            int prevCount = gens.previous() == null ? 0 : gens.previous().size();
            CommunityJwksKeyRotationEvent.emit(
                    PHASE_STALE_DENY, null, null, gens.current().size(), prevCount);
            throw new SecurityAuthenticationException(JWT_TYPE, ERR_STALE);
        }
        return gens;
    }

    /**
     * Immutable holder for the current and (optional) previous key generations, each with
     * the instant it was installed. Swapped atomically — never mutated in place.
     */
    private record Generations(
            Map<String, RSAPublicKey> current,
            Instant currentInstalledAt,
            Map<String, RSAPublicKey> previous,
            Instant previousInstalledAt) {
    }
}
