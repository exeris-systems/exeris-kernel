/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event for Community-tier JWKS verification-key rotation lifecycle.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Phases: {@code ROTATION} (a new generation replaces the current one — one event per
 * actual rotation), {@code CUTOVER_DENY} (a per-request deny: a kid resolves only to a
 * retired generation whose overlap window has closed), and {@code STALE_DENY} (a per-request
 * deny: refresh failed and the current generation is past its stale-fetch budget). Zero
 * overhead when JFR is not recording ({@link #isEnabled()} check).
 *
 * <h2>Secret-Safe</h2>
 * <p>Carries only opaque {@code kid} labels and integer key counts — never key material
 * (no modulus/exponent/{@code byte[]}) and never token bytes.
 *
 * @since 0.9
 */
@Name("eu.exeris.kernel.security.CommunityJwksKeyRotation")
@Label("Community JWKS Key Rotation")
@Description("Emitted on JWKS verification-key refresh, rotation cutover, and stale-fetch deny")
@Category({"Exeris Kernel", "Security", "JWKS"})
@StackTrace(false)
final class CommunityJwksKeyRotationEvent extends Event {

    @Label("Phase")
    /* default */ String phase;

    @Label("Retired Kid")
    /* default */ String retiredKid;

    @Label("Current Key Count")
    /* default */ int currentKeyCount;

    @Label("Previous Key Count")
    /* default */ int previousKeyCount;

    /* default */ static void emit(String phase, String retiredKid,
                                   int currentKeyCount, int previousKeyCount) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityJwksKeyRotationEvent event = new CommunityJwksKeyRotationEvent();
        if (event.isEnabled()) {
            event.phase = phase;
            event.retiredKid = retiredKid;
            event.currentKeyCount = currentKeyCount;
            event.previousKeyCount = previousKeyCount;
            event.commit();
        }
    }
}
