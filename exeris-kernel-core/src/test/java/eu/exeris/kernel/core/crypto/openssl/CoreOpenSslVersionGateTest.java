/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for the pure OpenSSL version band-check ({@code 3 <= major <= 4}).
 *
 * <p>Exercises {@link CoreOpenSslLoader#isSupportedVersion(int, long)} and
 * {@link CoreOpenSslLoader#assertSupported(int, int, long)} with no native library: the
 * decision logic is extracted precisely so it stays deterministic on hosts without OpenSSL.
 */
@DisplayName("L0: CoreOpenSslLoader version band-check (3.x..4.x)")
class CoreOpenSslVersionGateTest {

    // Packed OPENSSL_version_num() values (0xMNN00PP0 form).
    private static final long NUM_1_1_1 = 0x10101000L;
    private static final long NUM_3_0_0 = 0x30000000L;
    private static final long NUM_3_1_2 = 0x30100020L;
    private static final long NUM_3_5_0 = 0x30500000L;
    private static final long NUM_4_0_0 = 0x40000000L;
    private static final long NUM_5_0_0 = 0x50000000L;

    @Test
    @DisplayName("rejects 1.1.1 (major 1, num below the 3.0 floor)")
    void rejects111() {
        assertThat(CoreOpenSslLoader.isSupportedVersion(1, NUM_1_1_1)).isFalse();
        assertThatThrownBy(() -> CoreOpenSslLoader.assertSupported(1, 1, NUM_1_1_1))
                .isInstanceOf(CryptoBootstrapException.class);
    }

    @Test
    @DisplayName("rejects 2.x (below the supported major band)")
    void rejects2x() {
        assertThat(CoreOpenSslLoader.isSupportedVersion(2, 0x20000000L)).isFalse();
        assertThatThrownBy(() -> CoreOpenSslLoader.assertSupported(2, 0, 0x20000000L))
                .isInstanceOf(CryptoBootstrapException.class);
    }

    @Test
    @DisplayName("rejects major 5 (unproven future ABI, fail-fast)")
    void rejectsMajor5() {
        assertThat(CoreOpenSslLoader.isSupportedVersion(5, NUM_5_0_0)).isFalse();
        assertThatThrownBy(() -> CoreOpenSslLoader.assertSupported(5, 0, NUM_5_0_0))
                .isInstanceOf(CryptoBootstrapException.class);
    }

    @Test
    @DisplayName("accepts 3.0, 3.1.2, 3.5 and 4.0; band ceiling is keyed on major (<=4), not the packed num")
    void acceptsSupportedBand() {
        assertThat(CoreOpenSslLoader.isSupportedVersion(3, NUM_3_0_0)).isTrue();
        assertThat(CoreOpenSslLoader.isSupportedVersion(3, NUM_3_1_2)).isTrue();
        assertThat(CoreOpenSslLoader.isSupportedVersion(3, NUM_3_5_0)).isTrue();
        assertThat(CoreOpenSslLoader.isSupportedVersion(4, NUM_4_0_0)).isTrue();
        // Band ceiling is keyed on MAJOR (<=4), not the packed num: a major-4 lib with a packed
        // num near the top of the 4.x range is still accepted.
        assertThat(CoreOpenSslLoader.isSupportedVersion(4, 0x4FFFFFFFL)).isTrue();

        assertThatCode(() -> CoreOpenSslLoader.assertSupported(3, 0, NUM_3_0_0)).doesNotThrowAnyException();
        assertThatCode(() -> CoreOpenSslLoader.assertSupported(3, 1, NUM_3_1_2)).doesNotThrowAnyException();
        assertThatCode(() -> CoreOpenSslLoader.assertSupported(3, 5, NUM_3_5_0)).doesNotThrowAnyException();
        assertThatCode(() -> CoreOpenSslLoader.assertSupported(4, 0, NUM_4_0_0)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cross-major guard: agreeing majors pass, disagreeing majors throw")
    void crossMajorGuard() {
        // majorsAgree is the pure equality decision behind the SONAME cross-load guard.
        assertThat(CoreOpenSslLoader.majorsAgree(4, 4)).isTrue();
        assertThat(CoreOpenSslLoader.majorsAgree(4, 3)).isFalse();

        assertThatCode(() -> CoreOpenSslLoader.assertSameMajor(4, 4)).doesNotThrowAnyException();
        assertThatCode(() -> CoreOpenSslLoader.assertSameMajor(3, 3)).doesNotThrowAnyException();
        assertThatThrownBy(() -> CoreOpenSslLoader.assertSameMajor(4, 3))
                .isInstanceOf(CryptoBootstrapException.class);
    }

    @Test
    @DisplayName("rejects a major in band but a num below the 3.0 floor (belt-and-braces)")
    void rejectsInBandMajorButLowNum() {
        assertThat(CoreOpenSslLoader.isSupportedVersion(3, NUM_1_1_1)).isFalse();
    }
}
