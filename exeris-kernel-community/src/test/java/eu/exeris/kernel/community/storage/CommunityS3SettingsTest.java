/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the driver refuses to be configured with.
 *
 * <p>Every case here is a startup failure rather than a runtime one, which is the whole point: a
 * ceiling that cannot be honoured, an endpoint that cannot be reached safely, or a missing credential
 * are all facts known before the first transfer, and discovering them at the first transfer means
 * discovering them in production.
 *
 * @since 0.11.0
 */
@DisplayName("Community S3: configuration")
class CommunityS3SettingsTest {

    private static final String ENDPOINT = "http://minio.internal:9000";

    private static BlobStorageConfig configWith(Map<String, String> overrides) {
        Map<String, String> properties = new HashMap<>(Map.of(
                CommunityS3Settings.BUCKET, "bucket",
                CommunityS3Settings.ACCESS_KEY, "access",
                CommunityS3Settings.SECRET_KEY, "secret"));
        properties.putAll(overrides);
        properties.values().removeIf(String::isEmpty);
        return new BlobStorageConfig(ENDPOINT, BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL, properties);
    }

    @Nested
    @DisplayName("Object ceiling")
    class ObjectCeiling {

        @Test
        @DisplayName("a ceiling larger than one buffer can address is refused at construction")
        void oversizedCeilingRefused() {
            String tooLarge = Long.toString(CommunityS3Settings.MAX_SUPPORTED_OBJECT_BYTES + 1);

            assertThatThrownBy(() -> CommunityS3Settings.from(
                    configWith(Map.of(CommunityS3Settings.MAX_OBJECT_BYTES, tooLarge))))
                    .as("an unbounded ceiling would let a transfer pass its own limit check and then "
                            + "narrow to a wrapped int at allocation — the failure the named ceiling "
                            + "exists to replace with a loud refusal")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(CommunityS3Settings.MAX_OBJECT_BYTES);
        }

        @Test
        @DisplayName("a 4 GiB ceiling is refused, not silently wrapped")
        void beyondIntRangeRefused() {
            // The concrete shape an operator would reach for after reading "raise this to the largest
            // object a deployment genuinely stores".
            assertThatThrownBy(() -> CommunityS3Settings.from(
                    configWith(Map.of(CommunityS3Settings.MAX_OBJECT_BYTES,
                            Long.toString(4L * 1024 * 1024 * 1024)))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the largest accepted ceiling still leaves the engine's buffer addressable")
        void largestAcceptedCeilingFits() {
            CommunityS3Settings settings = CommunityS3Settings.from(configWith(Map.of(
                    CommunityS3Settings.MAX_OBJECT_BYTES,
                    Long.toString(CommunityS3Settings.MAX_SUPPORTED_OBJECT_BYTES))));

            // The engine adds its own framing allowance on top of what it is handed; the bound exists
            // so that sum, not merely the object size, still fits.
            assertThat(settings.engineBodyCeiling() + 8L * 1024)
                    .isLessThanOrEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("a non-positive or unparseable ceiling is refused")
        void invalidCeilingRefused() {
            assertThatThrownBy(() -> CommunityS3Settings.from(
                    configWith(Map.of(CommunityS3Settings.MAX_OBJECT_BYTES, "0"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CommunityS3Settings.from(
                    configWith(Map.of(CommunityS3Settings.MAX_OBJECT_BYTES, "not-a-number"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Endpoint")
    class Endpoint {

        @Test
        @DisplayName("an https endpoint is refused rather than downgraded to cleartext")
        void httpsRefused() {
            BlobStorageConfig config = new BlobStorageConfig("https://s3.example.com",
                    BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL,
                    Map.of(CommunityS3Settings.BUCKET, "bucket",
                            CommunityS3Settings.ACCESS_KEY, "access",
                            CommunityS3Settings.SECRET_KEY, "secret"));

            assertThatThrownBy(() -> CommunityS3Settings.from(config))
                    .as("the client engine has no TLS, so accepting this would send SigV4 credentials "
                            + "in the clear because a scheme was ignored")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("http scheme");
        }

        @Test
        @DisplayName("host and port are read from the endpoint, defaulting the port to 80")
        void endpointParsed() {
            CommunityS3Settings settings = CommunityS3Settings.from(configWith(Map.of()));
            assertThat(settings.host()).isEqualTo("minio.internal");
            assertThat(settings.port()).isEqualTo(9000);

            BlobStorageConfig portless = new BlobStorageConfig("http://minio.internal",
                    BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL,
                    Map.of(CommunityS3Settings.BUCKET, "bucket",
                            CommunityS3Settings.ACCESS_KEY, "access",
                            CommunityS3Settings.SECRET_KEY, "secret"));
            assertThat(CommunityS3Settings.from(portless).port()).isEqualTo(80);
        }
    }

    @Nested
    @DisplayName("Required properties")
    class RequiredProperties {

        @Test
        @DisplayName("a missing credential names the key it wanted")
        void missingCredentialNamed() {
            assertThatThrownBy(() -> CommunityS3Settings.from(
                    configWith(Map.of(CommunityS3Settings.SECRET_KEY, ""))))
                    .as("an operator reading this message should not have to guess which of three keys "
                            + "was absent")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(CommunityS3Settings.SECRET_KEY);
            assertThatThrownBy(() -> CommunityS3Settings.from(
                    configWith(Map.of(CommunityS3Settings.BUCKET, ""))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(CommunityS3Settings.BUCKET);
        }

        @Test
        @DisplayName("region and ceiling fall through to their documented defaults")
        void optionalDefaults() {
            CommunityS3Settings settings = CommunityS3Settings.from(configWith(Map.of()));

            assertThat(settings.region()).isEqualTo(CommunityS3Settings.DEFAULT_REGION);
            assertThat(settings.maxObjectBytes())
                    .isEqualTo(CommunityS3Settings.DEFAULT_MAX_OBJECT_BYTES);
        }
    }
}
