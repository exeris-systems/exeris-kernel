/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.memory;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: Memory exception rawArgs binary layouts.
 *
 * <h2>MemoryExhaustedException rawArgs (EX-MEM-1001)</h2>
 * <pre>
 * index 0 → long  requestedBytes
 * index 1 → long  availableBytes
 * </pre>
 *
 * <h2>MemoryBootstrapException rawArgs (EX-BOOT-0004)</h2>
 * <pre>
 * index 0 → String  providerName
 * index 1 → long    requestedBytes  (-1 = unknown)
 * </pre>
 *
 * @since 0.5.0
 */
@DisplayName("L0: Memory Exception rawArgs Binary Layouts")
class MemoryExceptionLayoutTest {

    // -----------------------------------------------------------------------
    // MemoryExhaustedException — EX-MEM-1001
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("MemoryExhaustedException (EX-MEM-1001)")
    class MemoryExhaustedContract {

        @Test
        @DisplayName("rawArgs[0]=requestedBytes (long), rawArgs[1]=availableBytes (long) — exact slot offsets")
        void rawArgsLayout() {
            MemoryExhaustedException ex = new MemoryExhaustedException(4096L, 512L, null);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo(4096L);
            assertThat(raw[1]).isEqualTo(512L);
        }

        @Test
        @DisplayName("rawArgs types are Long — critical for binary serializer's type switch")
        void rawArgsTypes() {
            MemoryExhaustedException ex = new MemoryExhaustedException(8192L, 1024L, null);
            assertThat(ex.rawArgs()[0]).isInstanceOf(Long.class);
            assertThat(ex.rawArgs()[1]).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("requestedBytes=0, availableBytes=0 — boundary values accepted")
        void zeroBoundaryValues() {
            MemoryExhaustedException ex = new MemoryExhaustedException(0L, 0L, null);
            assertThat(ex.rawArgs()[0]).isEqualTo(0L);
            assertThat(ex.rawArgs()[1]).isEqualTo(0L);
        }

        @Test
        @DisplayName("errorCode() == KernelErrorCodes.EX_MEM_1001")
        void errorCodePinned() {
            MemoryExhaustedException ex = new MemoryExhaustedException(1L, 0L, null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_MEM_1001);
        }

        @Test
        @DisplayName("getMessage() is a static string — same reference on every call")
        void messageIsStaticString() {
            MemoryExhaustedException ex = new MemoryExhaustedException(4096L, 512L, null);
            assertThat(ex.getMessage()).isSameAs(ex.getMessage());
        }

        @Test
        @DisplayName("Cause is preserved by reference")
        void causePreserved() {
            OutOfMemoryError oom = new OutOfMemoryError("native");
            MemoryExhaustedException ex = new MemoryExhaustedException(8192L, 0L, oom);
            assertThat(ex.getCause()).isSameAs(oom);
        }

        @Test
        @DisplayName("Is unchecked (RuntimeException)")
        void isUnchecked() {
            assertThat(new MemoryExhaustedException(1L, 0L, null))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // -----------------------------------------------------------------------
    // MemoryBootstrapException — EX-BOOT-0004
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("MemoryBootstrapException (EX-BOOT-0004)")
    class MemoryBootstrapContract {

        @Test
        @DisplayName("rawArgs[0]=providerName (String), rawArgs[1]=requestedBytes (long)")
        void rawArgsFullConstructor() {
            MemoryBootstrapException ex = new MemoryBootstrapException(
                    "ExerisEnterprise/GlobalArbiter", 1_073_741_824L, null);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("ExerisEnterprise/GlobalArbiter");
            assertThat(raw[1]).isEqualTo(1_073_741_824L);
        }

        @Test
        @DisplayName("Short ctor (providerName, cause) uses sentinel -1L for requestedBytes")
        void shortCtorUsesSentinel() {
            MemoryBootstrapException ex = new MemoryBootstrapException("CommunityMemoryProvider", null);
            assertThat(ex.rawArgs()).hasSize(2);
            assertThat(ex.rawArgs()[0]).isEqualTo("CommunityMemoryProvider");
            assertThat(ex.rawArgs()[1]).isEqualTo(-1L);
        }

        @Test
        @DisplayName("Message-only ctor produces non-null message and rawArgs is non-null")
        void messageOnlyConstructor() {
            MemoryBootstrapException ex = new MemoryBootstrapException("No off-heap memory available.");
            assertThat(ex.getMessage()).isNotNull();
            assertThat(ex.rawArgs()).isNotNull();
        }

        @Test
        @DisplayName("errorCode() == KernelErrorCodes.EX_BOOT_0004")
        void errorCodePinned() {
            MemoryBootstrapException ex = new MemoryBootstrapException("P", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_BOOT_0004);
        }

        @Test
        @DisplayName("errorCode() starts with 'EX-BOOT-'")
        void errorCodeFormat() {
            MemoryBootstrapException ex = new MemoryBootstrapException("P", null);
            assertThat(ex.errorCode()).startsWith("EX-BOOT-");
        }

        @Test
        @DisplayName("Cause is preserved in full-args constructor")
        void causePreserved() {
            RuntimeException root = new RuntimeException("mmap failed");
            MemoryBootstrapException ex = new MemoryBootstrapException(
                    "ExerisEnterprise/GlobalArbiter", 512L, root);
            assertThat(ex.getCause()).isSameAs(root);
        }
    }
}

