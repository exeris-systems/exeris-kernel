/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the JFR emission for the JWKS verification-key rotation lifecycle
 * ({@link CommunityJwksKeyRotationEvent}), closing the ROADMAP merge-gate clause
 * "emit a {@code JwksKeyRotationEvent} JFR record at fetch / rotation / cutover".
 *
 * <p>Drives {@link CommunityRotatingKeySet} directly through the three observable phases
 * ({@code ROTATION}, {@code CUTOVER}, {@code STALE_DENY}) against a controllable
 * {@link Clock} and a fail-injectable {@link KeySetSource}. Uses a synchronous
 * {@link Recording} dump (not {@code RecordingStream}) for deterministic assertions —
 * and the event is committed single-phase, never straddling a blocking op on a
 * virtual thread.
 */
@DisplayName("CommunityJwksKeyRotation — JFR rotation/cutover/stale-deny emission")
class CommunityJwksKeyRotationJfrTest {

    private static final String EVENT = "eu.exeris.kernel.security.CommunityJwksKeyRotation";
    private static final String ROTATED_KID = "rotated-jfr-key";

    @Test
    @DisplayName("rotation, cutover, and stale-deny each commit a phase-tagged event")
    void emitsEachRotationPhase(@TempDir Path tmp) throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ArmedKeySetSource source = new ArmedKeySetSource();
        RSAPublicKey key = TestJwt.testPublicKey();
        Map<String, RSAPublicKey> initial = Map.of(TestJwt.TEST_KID, key);
        CommunityRotatingKeySet keySet =
                new CommunityRotatingKeySet(initial, source, KeyRotationPolicy.defaults(), clock);

        Path jfr = tmp.resolve("jwks-rotation.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(EVENT);
            recording.start();

            // (1) ROTATION: current is stale, source returns a genuinely-new key set.
            clock.advance(Duration.ofMinutes(61L));
            source.armNewKeySet(Map.of(ROTATED_KID, key));
            assertThat(keySet.resolve(ROTATED_KID)).isSameAs(key);

            // (2) CUTOVER: the old kid lives only in the retiring generation; advance past
            // the overlap window so it is denied (current stays fresh, no refresh fires).
            clock.advance(Duration.ofMinutes(11L));
            assertThatThrownBy(() -> keySet.resolve(TestJwt.TEST_KID))
                    .isInstanceOf(SecurityAuthenticationException.class);

            // (3) STALE_DENY: current is stale again, the retiring generation is past overlap,
            // and the source fails to refresh — deterministic deny.
            clock.advance(Duration.ofMinutes(61L));
            source.armFailure();
            assertThatThrownBy(() -> keySet.resolve(ROTATED_KID))
                    .isInstanceOf(SecurityAuthenticationException.class);

            recording.stop();
            recording.dump(jfr);
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(jfr).stream()
                .filter(e -> e.getEventType().getName().equals(EVENT))
                .toList();

        assertThat(events).extracting(e -> e.getString("phase"))
                .contains("ROTATION", "CUTOVER", "STALE_DENY");
        // Secret-safe: the declared surface is only opaque labels and counts — no key material.
        assertThat(events.getFirst().getEventType().getFields())
                .extracting(jdk.jfr.ValueDescriptor::getName)
                .contains("phase", "newKid", "retiredKid", "currentKeyCount", "previousKeyCount");
    }

    /** Source that can be armed to return a new key set or to fail the next refresh. */
    private static final class ArmedKeySetSource implements KeySetSource {

        private Map<String, RSAPublicKey> next;
        private boolean failArmed;

        private void armNewKeySet(Map<String, RSAPublicKey> keys) {
            this.next = keys;
        }

        private void armFailure() {
            this.failArmed = true;
        }

        @Override
        public Map<String, RSAPublicKey> load() throws KeySetRefreshException {
            if (failArmed) {
                throw new KeySetRefreshException("refresh-failed");
            }
            return next;
        }
    }

    /** Test-only mutable {@link Clock} for deterministic time travel. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant start) {
            this.instant = start;
        }

        private void advance(Duration amount) {
            this.instant = this.instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
