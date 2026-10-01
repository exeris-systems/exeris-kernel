/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Community crypto provider in this JVM bound the OpenSSL major the {@code tls-openssl-matrix}
 * entry pins, so the Community TLS suites that run beside it ran against that major and not against
 * the host's own OpenSSL.
 *
 * <p>The matrix points {@code EXERIS_OPENSSL_SSL_PATH} and {@code EXERIS_OPENSSL_CRYPTO_PATH} at the
 * pinned build and passes {@code -Dexeris.test.expectedOpenSslMajor}. An environment that does not
 * reach this module's test JVM would leave the loader on the host's library, and every Community
 * TLS suite would pass against the wrong major. Tagged {@code openssl-matrix}, which this module's
 * default execution excludes; the matrix selects it by name. It fails, never skips, when the
 * property is absent.
 */
@Tag("openssl-matrix")
@DisplayName("Community: the crypto provider binds the OpenSSL major the TLS matrix pins")
class CommunityOpenSslPinnedMajorTest {

    private static final String EVENT_NAME = "eu.exeris.kernel.core.crypto.OpenSslLoad";
    private static final String EXPECTED_MAJOR_PROPERTY = "exeris.test.expectedOpenSslMajor";

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("new CommunityKernelCryptoProvider() loads the pinned major")
    void providerBindsThePinnedMajor() throws Exception {
        String expected = System.getProperty(EXPECTED_MAJOR_PROPERTY);
        assertThat(expected)
                .as("run by the tls-openssl-matrix, which sets -D%s to the pinned SONAME major",
                        EXPECTED_MAJOR_PROPERTY)
                .isNotBlank();

        Path dump = Files.createTempFile("community-openssl-pinned-major", ".jfr");
        try {
            try (Recording recording = new Recording()) {
                recording.enable(EVENT_NAME);
                recording.start();
                assertThat(new CommunityKernelCryptoProvider()).isNotNull();
                recording.stop();
                recording.dump(dump);
            }
            List<RecordedEvent> loads = RecordingFile.readAllEvents(dump).stream()
                    .filter(event -> EVENT_NAME.equals(event.getEventType().getName()))
                    .toList();

            assertThat(loads).as("one OpenSslLoad event for the provider's load").hasSize(1);
            assertThat(loads.getFirst().getInt("versionMajor"))
                    .as("the major the Community provider bound (%s)", loads.getFirst().getString("versionText"))
                    .isEqualTo(Integer.parseInt(expected.trim()));
        } finally {
            Files.deleteIfExists(dump);
        }
    }
}
