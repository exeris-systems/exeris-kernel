/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IsolationKeySpec — validation of the registration record")
class IsolationKeySpecTest {

    @Test
    @DisplayName("of() carries the key and ordinal unchanged")
    void ofCarriesComponents() {
        IsolationKeySpec spec = IsolationKeySpec.of("tenant-a", 101);
        assertThat(spec.isolationKey()).isEqualTo("tenant-a");
        assertThat(spec.ordinal()).isEqualTo(101);
    }

    @Test
    @DisplayName("a null key is refused with NullPointerException")
    void nullKeyRefused() {
        assertThatThrownBy(() -> IsolationKeySpec.of(null, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a blank key is refused with IllegalArgumentException")
    void blankKeyRefused() {
        assertThatThrownBy(() -> IsolationKeySpec.of("  ", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ordinal 0 is refused: it is the reserved unscoped value")
    void zeroOrdinalRefused() {
        assertThatThrownBy(() -> IsolationKeySpec.of("tenant-a", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a negative ordinal is refused with IllegalArgumentException")
    void negativeOrdinalRefused() {
        assertThatThrownBy(() -> IsolationKeySpec.of("tenant-a", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
