/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.storage.blob;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for {@link BlobRef} (ADR-056 §4/§6).
 *
 * <p>ADR-056 §6 makes key-injection defence a driver obligation. It is discharged here instead, one
 * level earlier: rejecting traversal at construction makes the unsafe reference unrepresentable, so no
 * driver can forget the check and no store has to be trusted to remember it. Driver-specific
 * restrictions remain the driver's business — this is the floor, not the ceiling.
 */
@DisplayName("BlobRef — tenant-relative reference with an injection-safe key")
class BlobRefTest {

    @Nested
    @DisplayName("Rejects keys that could resolve outside the tenant namespace")
    class UnsafeKeys {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "../escape",
                "nested/../../escape",
                "..",
                ".",
                "./file.bin",
                "nested/./file.bin",
                "/absolute/path",
                "windows\\separator",
                "double//separator",
                "trailing/",
                "   ",
        })
        @DisplayName("traversal, absolute, and malformed keys are rejected at construction")
        void rejectsUnsafeKey(String key) {
            assertThatThrownBy(() -> new BlobRef("documents", key))
                    .as("an unsafe key must be unrepresentable, not merely discouraged — every "
                            + "driver interpolates it into a namespace")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a NUL byte is rejected — it truncates a path in a native call")
        void rejectsNulByte() {
            // Composed rather than written as a literal: a raw NUL in a source file breaks the tools
            // that later read it, and the escape form is easy to mistake for the two-character text.
            String keyWithNul = "report" + (char) 0 + ".pdf";

            assertThatThrownBy(() -> new BlobRef("documents", keyWithNul))
                    .as("otherwise an ordinary key, so this can only fail on the NUL itself")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a container carrying a separator is rejected — it is one segment, not a path")
        void rejectsContainerWithSeparator() {
            assertThatThrownBy(() -> new BlobRef("documents/nested", "file.bin"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new BlobRef("..", "file.bin"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new BlobRef(".", "file.bin"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Accepts the shapes applications actually use")
    class SafeKeys {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "file.bin",
                "nested/path/file.bin",
                "2026/07/30/report.pdf",
                "name with spaces.txt",
                "unicode-ąćę.txt",
                "dot.in.name.tar.gz",
                "..leading-dots-in-a-name",
        })
        @DisplayName("ordinary keys, including nesting, survive validation")
        void acceptsSafeKey(String key) {
            assertThat(new BlobRef("documents", key).key())
                    .as("the check must reject traversal without outlawing normal object names — "
                            + "a validator that fails legitimate keys gets removed, not fixed")
                    .isEqualTo(key);
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equal components mean equal references — no identity in the carrier")
        void equalityIsByValue() {
            assertThat(new BlobRef("documents", "a/b.bin"))
                    .as("Valhalla readiness: a carrier compared by identity would break as a value "
                            + "class")
                    .isEqualTo(new BlobRef("documents", "a/b.bin"))
                    .hasSameHashCodeAs(new BlobRef("documents", "a/b.bin"));
        }

        @Test
        @DisplayName("null components are rejected")
        void rejectsNulls() {
            assertThatThrownBy(() -> new BlobRef(null, "file.bin"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new BlobRef("documents", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
