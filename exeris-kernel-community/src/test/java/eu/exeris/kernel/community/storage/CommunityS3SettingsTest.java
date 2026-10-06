/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.community.http.CommunityEndpointScheme;
import eu.exeris.kernel.community.transport.CommunityOutboundTls;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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

    private static BlobStorageConfig endpoint(String location) {
        return new BlobStorageConfig(location, BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL,
                Map.of(CommunityS3Settings.BUCKET, "bucket",
                        CommunityS3Settings.ACCESS_KEY, "access",
                        CommunityS3Settings.SECRET_KEY, "secret"));
    }

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
        @DisplayName("an https endpoint is accepted, on 443 by default, and requires verified TLS")
        void httpsAccepted() {
            CommunityS3Settings settings = CommunityS3Settings.from(endpoint("HTTPS://s3.example.com"));

            assertThat(settings.scheme()).isEqualTo(CommunityEndpointScheme.HTTPS);
            assertThat(settings.port()).isEqualTo(443);
            assertThat(settings.scheme().outboundTls()).isEqualTo(CommunityOutboundTls.VERIFIED);
        }

        @Test
        @DisplayName("a scheme other than http or https, or none, is refused")
        void otherSchemesRefused() {
            for (String location : new String[]{"ftp://s3.example.com", "s3://bucket", "minio.internal",
                    "//minio.internal:9000"}) {
                assertThatThrownBy(() -> CommunityS3Settings.from(endpoint(location)))
                        .as(location)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("location must use the http or https scheme");
            }
        }

        @Test
        @DisplayName("the host is lower-cased and loses one trailing dot, the form a TLS peer name is checked in")
        void hostIsNormalised() {
            CommunityS3Settings settings = CommunityS3Settings.from(endpoint("https://S3.Example.COM."));

            assertThat(settings.host()).isEqualTo("s3.example.com");
            assertThat(settings.dialAuthority()).isEqualTo("s3.example.com:443");
            assertThat(settings.hostHeader()).isEqualTo("s3.example.com");
            assertThat(settings.origin()).isEqualTo("https://s3.example.com");
        }

        @Test
        @DisplayName("an IPv6 endpoint keeps its brackets in every authority")
        void ipv6KeepsItsBrackets() {
            CommunityS3Settings explicit = CommunityS3Settings.from(endpoint("https://[::1]:9000"));
            assertThat(explicit.dialAuthority()).isEqualTo("[::1]:9000");
            assertThat(explicit.hostHeader()).isEqualTo("[::1]:9000");
            assertThat(explicit.origin()).isEqualTo("https://[::1]:9000");

            CommunityS3Settings onDefault = CommunityS3Settings.from(endpoint("https://[::1]"));
            assertThat(onDefault.dialAuthority()).isEqualTo("[::1]:443");
            assertThat(onDefault.hostHeader()).isEqualTo("[::1]");
        }

        @Test
        @DisplayName("host and port are read from the endpoint, defaulting the port to 80")
        void endpointParsed() {
            CommunityS3Settings settings = CommunityS3Settings.from(configWith(Map.of()));
            assertThat(settings.host()).isEqualTo("minio.internal");
            assertThat(settings.port()).isEqualTo(9000);
            assertThat(settings.dialAuthority()).isEqualTo("minio.internal:9000");

            BlobStorageConfig portless = new BlobStorageConfig("http://minio.internal",
                    BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL,
                    Map.of(CommunityS3Settings.BUCKET, "bucket",
                            CommunityS3Settings.ACCESS_KEY, "access",
                            CommunityS3Settings.SECRET_KEY, "secret"));
            assertThat(CommunityS3Settings.from(portless).port()).isEqualTo(80);
            assertThat(CommunityS3Settings.from(portless).dialAuthority())
                    .as("the client engine dials an explicit port")
                    .isEqualTo("minio.internal:80");
        }

        @Test
        @DisplayName("the Host value and the origin omit the scheme's default port, and keep any other")
        void hostHeaderAndOriginOmitOnlyTheDefaultPort() {
            CommunityS3Settings explicit = CommunityS3Settings.from(configWith(Map.of()));
            assertThat(explicit.hostHeader()).isEqualTo("minio.internal:9000");
            assertThat(explicit.origin()).isEqualTo("http://minio.internal:9000");

            BlobStorageConfig defaultPort = new BlobStorageConfig("http://minio.internal:80",
                    BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL,
                    Map.of(CommunityS3Settings.BUCKET, "bucket",
                            CommunityS3Settings.ACCESS_KEY, "access",
                            CommunityS3Settings.SECRET_KEY, "secret"));
            CommunityS3Settings onDefault = CommunityS3Settings.from(defaultPort);
            assertThat(onDefault.hostHeader()).isEqualTo("minio.internal");
            assertThat(onDefault.origin()).isEqualTo("http://minio.internal");
            assertThat(onDefault.dialAuthority()).isEqualTo("minio.internal:80");
        }

        @Test
        @DisplayName("an http endpoint requires plaintext of the client engine's transport")
        void httpIsPlaintext() {
            CommunityS3Settings settings = CommunityS3Settings.from(configWith(Map.of()));

            assertThat(settings.scheme()).isEqualTo(CommunityEndpointScheme.HTTP);
            assertThat(settings.scheme().outboundTls()).isEqualTo(CommunityOutboundTls.PLAINTEXT);
        }

        @Test
        @DisplayName("an endpoint with a path, query, userinfo or fragment is refused, naming the part")
        void componentsBeyondTheAuthorityRefused() {
            Map<String, String> byPart = Map.of(
                    "https://gw.example.com/s3", "path",
                    "https://s3.example.com?x-id=1", "query",
                    "https://key:secret@s3.example.com", "userinfo",
                    "https://s3.example.com#frag", "fragment",
                    "http://key:secret@minio.internal:9000/prefix?x=1", "userinfo");
            byPart.forEach((location, part) ->
                    assertThatThrownBy(() -> CommunityS3Settings.from(endpoint(location)))
                            .as(location)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining(part)
                            .hasMessageNotContaining("key:secret"));
        }

        @Test
        @DisplayName("an empty path or a single slash is still the bare endpoint")
        void bareEndpointAccepted() {
            for (String location : new String[]{"https://s3.example.com", "https://s3.example.com/"}) {
                assertThat(CommunityS3Settings.from(endpoint(location)).origin())
                        .as(location)
                        .isEqualTo("https://s3.example.com");
            }
        }

        @Test
        @DisplayName("an @ in the path is a path, not userinfo, and the host is still named")
        void atSignInPathIsAPath() {
            assertThatThrownBy(() -> CommunityS3Settings.from(endpoint("https://s3.example.com/a@b")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("path")
                    .hasMessageContaining("https://s3.example.com/a@b");
        }

        @Test
        @DisplayName("a refusal for any other reason echoes neither the userinfo nor a cause carrying it")
        void refusalsDoNotEchoUserinfo() {
            Map<String, String> byRefusal = Map.of(
                    "https://key:secret@s3_bucket.internal", "location must carry a host",
                    "https://key:secret@s3 example.com", "location must be an endpoint URI");
            byRefusal.forEach((location, refusal) -> {
                Throwable thrown = catchThrowable(() -> CommunityS3Settings.from(endpoint(location)));
                assertThat(thrown).as(location)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(refusal);
                for (Throwable t = thrown; t != null; t = t.getCause()) {
                    assertThat(t.getMessage()).as(location + " / " + t.getClass().getName())
                            .doesNotContain("key:secret");
                }
            });

            assertThat(catchThrowable(() -> CommunityS3Settings.from(endpoint("https://key:secret@s3 example.com"))))
                    .hasCauseInstanceOf(URISyntaxException.class);
        }

        @Test
        @DisplayName("a password holding a delimiter, or a location without //, still has its userinfo withheld")
        void userinfoWithheldWhereTheAuthorityIsAmbiguous() {
            for (String location : new String[]{
                    "https://AKID:se/cret@s3.example.com",
                    "https://AKID:se?cret@s3.example.com",
                    "https://AKID:se#cret@s3.example.com",
                    "https:AKID:secret@s3.example.com",
                    "https://AKID:se cret@s3.example.com"}) {
                Throwable thrown = catchThrowable(() -> CommunityS3Settings.from(endpoint(location)));
                assertThat(thrown).as(location).isInstanceOf(IllegalArgumentException.class);
                for (Throwable t = thrown; t != null; t = t.getCause()) {
                    assertThat(t.getMessage()).as(location + " / " + t.getClass().getName())
                            .doesNotContain("AKID")
                            .doesNotContain("cret");
                }
            }
        }

        @Test
        @DisplayName("a syntax refusal keeps the position of a fault after the userinfo, moved to the redacted text")
        void syntaxFaultPositionFollowsTheRedaction() {
            Throwable thrown = catchThrowable(() -> CommunityS3Settings.from(endpoint("https://key:secret@s3 example.com")));
            URISyntaxException cause = (URISyntaxException) thrown.getCause();

            assertThat(cause.getInput().charAt(cause.getIndex())).isEqualTo(' ');
            assertThat(cause.getInput()).startsWith("https://<userinfo>@s3");
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
