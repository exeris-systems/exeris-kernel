/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.storage;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The selection event records where the store lives, never the credentials a location may carry.
 *
 * <p>The event is committed before the selected driver reads its configuration, so a driver that
 * refuses a location with userinfo does so after the location is already in the recording. The event
 * is also driver-agnostic: for a filesystem driver the location is a directory, where {@code @} is an
 * ordinary character. The cases therefore come in both directions — an implementation that redacts
 * every {@code @} and one that redacts none each fail one of them.
 */
@DisplayName("StorageBootstrapSelected — a location is recorded without its userinfo")
class StorageBootstrapSelectedEventTest {

    private static final String SELECTED = "eu.exeris.kernel.storage.StorageBootstrapSelected";
    private static final long SETTLE_SECONDS = 10L;

    @Nested
    @DisplayName("Recorded event")
    class Recorded {

        @Test
        @DisplayName("an endpoint's userinfo is replaced; a directory containing @ is recorded as configured")
        void userinfoRedactedPathKept() throws Exception {
            Map<String, RecordedEvent> byProvider = new ConcurrentHashMap<>();
            CountDownLatch seen = new CountDownLatch(2);

            try (RecordingStream stream = new RecordingStream()) {
                stream.enable(SELECTED);
                stream.onEvent(SELECTED, event -> {
                    if (byProvider.putIfAbsent(event.getString("providerId"), event) == null) {
                        seen.countDown();
                    }
                });
                stream.startAsync();

                StorageBootstrapSelectedEvent.emit("S3Like", "blob-s3-551", 0,
                        "https://key:secret@s3.example.com");
                StorageBootstrapSelectedEvent.emit("FsLike", "blob-fs-551", 0,
                        "/var/exeris/blobs/tenant@eu");

                assertThat(seen.await(SETTLE_SECONDS, TimeUnit.SECONDS))
                        .as("both selections must be recorded")
                        .isTrue();
            }

            assertThat(byProvider.get("blob-s3-551").getString("location"))
                    .isEqualTo("https://<userinfo>@s3.example.com")
                    .doesNotContain("key")
                    .doesNotContain("secret");
            assertThat(byProvider.get("blob-fs-551").getString("location"))
                    .as("an @ outside an authority is part of a path, not userinfo")
                    .isEqualTo("/var/exeris/blobs/tenant@eu");
        }
    }

    @Nested
    @DisplayName("Recorded location")
    class RecordedLocation {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', value = {
            "https://user@s3.example.com           | https://<userinfo>@s3.example.com",
            "http://k:s@localhost:9000             | http://<userinfo>@localhost:9000",
            "https://a@b:c@s3.example.com          | https://<userinfo>@s3.example.com",
            "https://k:s@s3.example.com/p@q        | https://<userinfo>@s3.example.com/p@q",
            "https://k:s@s3.example.com?x=a@b      | https://<userinfo>@s3.example.com?x=a@b",
            "https://k:s@s3.example.com#a@b        | https://<userinfo>@s3.example.com#a@b",
            "https://k:s@ho st                     | https://<userinfo>@ho st",
            "s3+custom.v1://k:s@host               | s3+custom.v1://<userinfo>@host",
        })
        @DisplayName("the userinfo of an authority is replaced, everything else is kept")
        void authorityUserinfoIsReplaced(String location, String recorded) {
            assertThat(StorageBootstrapSelectedEvent.recordedLocation(location)).isEqualTo(recorded);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "/var/exeris/blobs/tenant@eu",
            "blobs@eu",
            "C:\\blobs\\a@b",
            "file:///srv/blobs/x@y",
            "https://s3.example.com:9000",
            "https://s3.example.com/p@q",
            "https://s3.example.com?x=a@b",
            "./a://b@c",
            "1a://k:s@host",
        })
        @DisplayName("a location with no userinfo in an authority is recorded as configured")
        void everythingElseIsUnchanged(String location) {
            assertThat(StorageBootstrapSelectedEvent.recordedLocation(location)).isEqualTo(location);
        }
    }
}
