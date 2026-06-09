/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * <p>Emitted on successful refresh/rotation, on observed cutover (overlap expiry), and on
 * stale-fetch deny. Zero overhead when JFR is not recording ({@link #isEnabled()} check).
 *
 * <h2>Secret-Safe</h2>
 * <p>Carries only opaque {@code kid} labels and integer key counts — never key material
 * (no modulus/exponent/{@code byte[]}) and never token bytes.
 *
 * @since 0.9.0
 */
@Name("eu.exeris.kernel.security.CommunityJwksKeyRotation")
@Label("Community JWKS Key Rotation")
@Description("Emitted on JWKS verification-key refresh, rotation cutover, and stale-fetch deny")
@Category({"Exeris Kernel", "Security", "JWKS"})
@StackTrace(false)
final class CommunityJwksKeyRotationEvent extends Event {

    @Label("Phase")
    /* default */ String phase;

    @Label("New Kid")
    /* default */ String newKid;

    @Label("Retired Kid")
    /* default */ String retiredKid;

    @Label("Current Key Count")
    /* default */ int currentKeyCount;

    @Label("Previous Key Count")
    /* default */ int previousKeyCount;

    /* default */ static void emit(String phase, String newKid, String retiredKid,
                                   int currentKeyCount, int previousKeyCount) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityJwksKeyRotationEvent event = new CommunityJwksKeyRotationEvent();
        if (event.isEnabled()) {
            event.phase = phase;
            event.newKid = newKid;
            event.retiredKid = retiredKid;
            event.currentKeyCount = currentKeyCount;
            event.previousKeyCount = previousKeyCount;
            event.commit();
        }
    }
}
