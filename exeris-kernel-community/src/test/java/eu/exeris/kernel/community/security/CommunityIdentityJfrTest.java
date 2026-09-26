/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.identity.IdentityProvider;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the Community OIDC provider commits secret-safe JFR events on validation and rejection.
 */
@DisplayName("CommunityOidcIdentityProvider — JFR validation/rejection events")
class CommunityIdentityJfrTest {

    private static final String VALIDATION = "eu.exeris.kernel.security.IdentityValidation";
    private static final String REJECTION = "eu.exeris.kernel.security.IdentityRejection";

    @Test
    @DisplayName("commits IdentityValidation on success and IdentityRejection on deny")
    void emitsValidationAndRejection(@TempDir Path tmp) throws Exception {
        IdentityProvider provider = new CommunityOidcIdentityProvider(
                TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);

        Path jfr = tmp.resolve("identity.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(VALIDATION);
            recording.enable(REJECTION);
            recording.start();

            try (LoanedBuffer valid = TestJwt.builder().toBuffer()) {
                provider.authenticate(valid);
            }
            try (LoanedBuffer bad = TestJwt.builder().kid(TestJwt.UNKNOWN_KID).toBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(bad))
                        .isInstanceOf(SecurityAuthenticationException.class);
            }

            recording.stop();
            recording.dump(jfr);
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(jfr);

        RecordedEvent validation = events.stream()
                .filter(e -> VALIDATION.equals(e.getEventType().getName()))
                .findFirst().orElseThrow();
        assertThat(validation.getString("providerId")).isEqualTo("oidc-community");
        assertThat(validation.getString("issuer")).isEqualTo(TestJwt.EXPECTED_ISSUER);

        RecordedEvent rejection = events.stream()
                .filter(e -> REJECTION.equals(e.getEventType().getName()))
                .findFirst().orElseThrow();
        assertThat(rejection.getString("providerId")).isEqualTo("oidc-community");
        assertThat(rejection.getString("reason")).isEqualTo("unknown-kid");
    }
}
