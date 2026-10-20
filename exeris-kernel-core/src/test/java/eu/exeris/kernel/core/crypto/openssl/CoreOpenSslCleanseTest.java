/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L0: {@link CoreOpenSslRuntime#cleanse(MemorySegment)} against the real {@code OPENSSL_cleanse}.
 *
 * <p>Requires a loadable OpenSSL 3.x/4.x. Without one the class skips; the
 * {@code tls-openssl-matrix} job selects it by name and refuses a skipped report.
 */
@DisplayName("L0: CoreOpenSslRuntime#cleanse (OPENSSL_cleanse binding)")
class CoreOpenSslCleanseTest {

    private static final byte FILL = (byte) 0xA5;

    private static CoreOpenSslRuntime runtime;

    @BeforeAll
    static void loadRuntime() {
        CoreOpenSslRuntime loaded = null;
        try {
            // CHECKSTYLE:OFF DirectArena — bootstrap loads symbols for the JVM lifetime
            loaded = CoreOpenSslLoader.load(Arena.global());
            // CHECKSTYLE:ON
        } catch (CryptoBootstrapException _) {
            // no OpenSSL 3.x/4.x on this host; the assumption below skips the class
        }
        assumeTrue(loaded != null, "OpenSSL 3.x/4.x not available on this host — skipping cleanse probe");
        runtime = loaded;
    }

    @Test
    @DisplayName("cleanse() overwrites every byte of a confined segment with zero")
    void cleanseZeroesWholeSegment() {
        // CHECKSTYLE:OFF DirectArena — test-owned confined segment
        try (Arena arena = Arena.ofConfined()) { //NOPMD
            MemorySegment secret = arena.allocate(32L, 8L);
            secret.fill(FILL);
            assertThat(secret.toArray(JAVA_BYTE)).containsOnly(FILL);

            runtime.cleanse(secret);

            assertThat(secret.toArray(JAVA_BYTE)).containsOnly((byte) 0);
        }
        // CHECKSTYLE:ON
    }

    @Test
    @DisplayName("cleanse() accepts a zero-length segment and a null segment as no-ops")
    void cleanseIgnoresZeroLengthAndNull() {
        // CHECKSTYLE:OFF DirectArena — test-owned confined segment
        try (Arena arena = Arena.ofConfined()) { //NOPMD
            MemorySegment empty = arena.allocate(0L, 1L);
            assertThatCode(() -> runtime.cleanse(empty)).doesNotThrowAnyException();
        }
        // CHECKSTYLE:ON
        assertThatCode(() -> runtime.cleanse(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cleanse() of a sub-slice leaves the bytes on either side untouched")
    void cleanseSubSliceLeavesNeighboursIntact() {
        // CHECKSTYLE:OFF DirectArena — test-owned confined segment
        try (Arena arena = Arena.ofConfined()) { //NOPMD
            MemorySegment buffer = arena.allocate(32L, 8L);
            buffer.fill(FILL);

            runtime.cleanse(buffer.asSlice(8L, 8L));

            assertThat(buffer.asSlice(0L, 8L).toArray(JAVA_BYTE)).containsOnly(FILL);
            assertThat(buffer.asSlice(8L, 8L).toArray(JAVA_BYTE)).containsOnly((byte) 0);
            assertThat(buffer.asSlice(16L, 16L).toArray(JAVA_BYTE)).containsOnly(FILL);
        }
        // CHECKSTYLE:ON
    }

    @Test
    @DisplayName("cleanse() refuses a heap segment and leaves its bytes untouched")
    void cleanseRefusesHeapSegment() {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, FILL);
        MemorySegment heap = MemorySegment.ofArray(bytes);
        assertThatThrownBy(() -> runtime.cleanse(heap)).isInstanceOf(IllegalArgumentException.class);
        assertThat(bytes).containsOnly(FILL);
    }
}
