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
import jdk.jfr.Timespan;

/**
 * JFR events for the Community OIDC {@link CommunityOidcIdentityProvider} (ADR-040 §7).
 *
 * <h2>Single-phase commit</h2>
 * <p>Each event is built and committed in one shot <em>after</em> validation completes — never a
 * {@code begin() → blocking-fetch → commit()} straddle on a virtual thread (a JWKS fetch may block
 * during validation; emitting afterwards keeps the carrier-bound {@code EventWriter} off the
 * blocking path).
 *
 * <h2>Secret-safe</h2>
 * <p>Carries only the provider id, the (public) issuer identifier, a coarse duration, and an opaque
 * deny reason code — never the raw token, key material, or sensitive claim values.
 *
 * @since 0.10.0
 */
final class CommunityIdentityJfrEvents {

    private CommunityIdentityJfrEvents() {
        // Utility holder — not instantiable.
    }

    /* default */ static void emitValidation(String providerId, String issuer, long startNanos) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        IdentityValidationEvent event = new IdentityValidationEvent();
        if (event.isEnabled()) {
            event.providerId = providerId;
            event.issuer = issuer;
            event.durationNanos = System.nanoTime() - startNanos;
            event.commit();
        }
    }

    /* default */ static void emitRejection(String providerId, String reason) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        IdentityRejectionEvent event = new IdentityRejectionEvent();
        if (event.isEnabled()) {
            event.providerId = providerId;
            event.reason = reason;
            event.commit();
        }
    }

    @Name("eu.exeris.kernel.security.IdentityValidation")
    @Label("Identity Validation")
    @Description("Emitted when an IdentityProvider verifies a token into a PrincipalContext")
    @Category({"Exeris Kernel", "Security", "Identity"})
    @StackTrace(false)
    /* default */ static final class IdentityValidationEvent extends Event {

        @Label("Provider ID")
        /* default */ String providerId;

        @Label("Issuer")
        /* default */ String issuer;

        @Label("Validation Duration")
        @Timespan(Timespan.NANOSECONDS)
        /* default */ long durationNanos;
    }

    @Name("eu.exeris.kernel.security.IdentityRejection")
    @Label("Identity Rejection")
    @Description("Emitted when an IdentityProvider denies a token (fail-closed)")
    @Category({"Exeris Kernel", "Security", "Identity"})
    @StackTrace(false)
    /* default */ static final class IdentityRejectionEvent extends Event {

        @Label("Provider ID")
        /* default */ String providerId;

        @Label("Reason")
        /* default */ String reason;
    }
}
