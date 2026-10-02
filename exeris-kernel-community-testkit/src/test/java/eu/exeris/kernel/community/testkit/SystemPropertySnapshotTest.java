/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct coverage for the two restore paths.
 *
 * <p>The asymmetry is the whole point: a property that was <em>unset</em> at capture must be cleared,
 * not set to the empty string. Restoring it to {@code ""} would leave a value the kernel's config
 * resolver treats differently from absence, and the resulting failure surfaces in an unrelated suite
 * that happens to run afterwards.
 */
@DisplayName("SystemPropertySnapshot")
class SystemPropertySnapshotTest {

    private static final String PRESET_KEY = "exeris.testkit.snapshot.preset";
    private static final String UNSET_KEY = "exeris.testkit.snapshot.unset";

    @AfterEach
    void clearProbeProperties() {
        System.clearProperty(PRESET_KEY);
        System.clearProperty(UNSET_KEY);
    }

    @Nested
    @DisplayName("restore()")
    class Restore {

        @Test
        @DisplayName("puts back the value a property had at capture time")
        void restoresPreviousValue() {
            System.setProperty(PRESET_KEY, "original");
            SystemPropertySnapshot snapshot = SystemPropertySnapshot.capture(PRESET_KEY);

            System.setProperty(PRESET_KEY, "overwritten");
            snapshot.restore();

            assertThat(System.getProperty(PRESET_KEY)).isEqualTo("original");
        }

        @Test
        @DisplayName("clears a property that was unset at capture time")
        void clearsPropertyThatWasUnset() {
            System.clearProperty(UNSET_KEY);
            SystemPropertySnapshot snapshot = SystemPropertySnapshot.capture(UNSET_KEY);

            System.setProperty(UNSET_KEY, "leaked");
            snapshot.restore();

            assertThat(System.getProperty(UNSET_KEY))
                    .as("restoring to \"\" instead of clearing would leave a value the config resolver "
                            + "reads differently from absence")
                    .isNull();
        }

        @Test
        @DisplayName("handles both kinds in one snapshot")
        void handlesMixedSnapshot() {
            System.setProperty(PRESET_KEY, "original");
            System.clearProperty(UNSET_KEY);
            SystemPropertySnapshot snapshot = SystemPropertySnapshot.capture(PRESET_KEY, UNSET_KEY);

            System.setProperty(PRESET_KEY, "overwritten");
            System.setProperty(UNSET_KEY, "leaked");
            snapshot.restore();

            assertThat(System.getProperty(PRESET_KEY)).isEqualTo("original");
            assertThat(System.getProperty(UNSET_KEY)).isNull();
        }
    }
}
