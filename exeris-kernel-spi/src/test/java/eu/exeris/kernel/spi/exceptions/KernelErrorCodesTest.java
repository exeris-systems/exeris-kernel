/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: {@link KernelErrorCodes} — stable binary constants registry.
 *
 * <h2>What This Proves</h2>
 * <p>These tests pin the exact string values of all error code constants.
 * Any rename, reformatting, or domain-tag change will break this test, triggering
 * a mandatory review of all dependent BinaryBlackBoxSink decoders in the Enterprise tier.
 *
 * <h2>Format</h2>
 * <p>Every constant MUST match {@code EX-[A-Z]+-\d+} (uppercase domain, numeric suffix).
 *
 * @see KernelErrorCodes
 * @since 0.5.0
 */
@DisplayName("L0: KernelErrorCodes — Stable Constant Registry")
class KernelErrorCodesTest {

    // -----------------------------------------------------------------------
    // EX-MEM domain — off-heap memory
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EX-MEM domain (Memory / Off-Heap)")
    class MemDomain {

        @Test
        @DisplayName("EX_MEM_1001 == 'EX-MEM-1001' (allocator exhausted)")
        void exMem1001() {
            assertThat(KernelErrorCodes.EX_MEM_1001).isEqualTo("EX-MEM-1001");
        }

        @Test
        @DisplayName("EX_MEM_1002 == 'EX-MEM-1002' (arena leak detected)")
        void exMem1002() {
            assertThat(KernelErrorCodes.EX_MEM_1002).isEqualTo("EX-MEM-1002");
        }

        @Test
        @DisplayName("EX_MEM_1003 == 'EX-MEM-1003' (allocation hint conflict)")
        void exMem1003() {
            assertThat(KernelErrorCodes.EX_MEM_1003).isEqualTo("EX-MEM-1003");
        }

        @Test
        @DisplayName("All EX-MEM codes match the pattern EX-MEM-\\d{4}")
        void allMemCodesMatchPattern() {
            assertThat(KernelErrorCodes.EX_MEM_1001).matches("EX-MEM-\\d{4}");
            assertThat(KernelErrorCodes.EX_MEM_1002).matches("EX-MEM-\\d{4}");
            assertThat(KernelErrorCodes.EX_MEM_1003).matches("EX-MEM-\\d{4}");
        }
    }

    // -----------------------------------------------------------------------
    // EX-BOOT domain — bootstrap / lifecycle
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EX-BOOT domain (Bootstrap / Lifecycle)")
    class BootDomain {

        @Test
        @DisplayName("EX_BOOT_0002 == 'EX-BOOT-0002' (subsystem lifecycle failure)")
        void exBoot0002() {
            assertThat(KernelErrorCodes.EX_BOOT_0002).isEqualTo("EX-BOOT-0002");
        }

        @Test
        @DisplayName("EX_BOOT_0003 == 'EX-BOOT-0003' (bootstrap deadline exceeded)")
        void exBoot0003() {
            assertThat(KernelErrorCodes.EX_BOOT_0003).isEqualTo("EX-BOOT-0003");
        }

        @Test
        @DisplayName("EX_BOOT_0004 == 'EX-BOOT-0004' (memory provider bootstrap failure)")
        void exBoot0004() {
            assertThat(KernelErrorCodes.EX_BOOT_0004).isEqualTo("EX-BOOT-0004");
        }

        @Test
        @DisplayName("EX_BOOT_3001 == 'EX-BOOT-3001' (telemetry bootstrap failure)")
        void exBoot3001() {
            assertThat(KernelErrorCodes.EX_BOOT_3001).isEqualTo("EX-BOOT-3001");
        }
    }

    // -----------------------------------------------------------------------
    // EX-NET domain — transport / network
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EX-NET domain (Transport / Network)")
    class NetDomain {

        @Test
        @DisplayName("EX_NET_2001 == 'EX-NET-2001' (TLS/Crypto failure)")
        void exNet2001() {
            assertThat(KernelErrorCodes.EX_NET_2001).isEqualTo("EX-NET-2001");
        }

        @Test
        @DisplayName("EX_NET_2002 == 'EX-NET-2002' (Crypto provider bootstrap failure)")
        void exNet2002() {
            assertThat(KernelErrorCodes.EX_NET_2002).isEqualTo("EX-NET-2002");
        }

        @Test
        @DisplayName("EX_NET_4001 == 'EX-NET-4001' (transport bind failure)")
        void exNet4001() {
            assertThat(KernelErrorCodes.EX_NET_4001).isEqualTo("EX-NET-4001");
        }

        @Test
        @DisplayName("EX_NET_4002 == 'EX-NET-4002' (transport send failure)")
        void exNet4002() {
            assertThat(KernelErrorCodes.EX_NET_4002).isEqualTo("EX-NET-4002");
        }

        @Test
        @DisplayName("EX_NET_4003 == 'EX-NET-4003' (transport receive timeout)")
        void exNet4003() {
            assertThat(KernelErrorCodes.EX_NET_4003).isEqualTo("EX-NET-4003");
        }

        @Test
        @DisplayName("EX_NET_4004 == 'EX-NET-4004' (transport engine bootstrap failure)")
        void exNet4004() {
            assertThat(KernelErrorCodes.EX_NET_4004).isEqualTo("EX-NET-4004");
        }

        @Test
        @DisplayName("EX_NET_4005 == 'EX-NET-4005' (transport engine start failure)")
        void exNet4005() {
            assertThat(KernelErrorCodes.EX_NET_4005).isEqualTo("EX-NET-4005");
        }
    }

    // -----------------------------------------------------------------------
    // EX-PERS domain — persistence
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EX-PERS domain (Persistence)")
    class PersDomain {

        @Test
        @DisplayName("EX_PERS_5001 == 'EX-PERS-5001' (bootstrap failure)")
        void exPers5001() {
            assertThat(KernelErrorCodes.EX_PERS_5001).isEqualTo("EX-PERS-5001");
        }

        @Test
        @DisplayName("EX_PERS_5002 == 'EX-PERS-5002' (connection exhausted)")
        void exPers5002() {
            assertThat(KernelErrorCodes.EX_PERS_5002).isEqualTo("EX-PERS-5002");
        }

        @Test
        @DisplayName("EX_PERS_5003 == 'EX-PERS-5003' (query failed)")
        void exPers5003() {
            assertThat(KernelErrorCodes.EX_PERS_5003).isEqualTo("EX-PERS-5003");
        }

        @Test
        @DisplayName("EX_PERS_5004 == 'EX-PERS-5004' (auth failed)")
        void exPers5004() {
            assertThat(KernelErrorCodes.EX_PERS_5004).isEqualTo("EX-PERS-5004");
        }

        @Test
        @DisplayName("EX_PERS_5005 == 'EX-PERS-5005' (transport failure)")
        void exPers5005() {
            assertThat(KernelErrorCodes.EX_PERS_5005).isEqualTo("EX-PERS-5005");
        }

        @Test
        @DisplayName("EX_PERS_5006 == 'EX-PERS-5006' (interceptor init failed)")
        void exPers5006() {
            assertThat(KernelErrorCodes.EX_PERS_5006).isEqualTo("EX-PERS-5006");
        }

        @Test
        @DisplayName("EX_PERS_5007 == 'EX-PERS-5007' (no provider available)")
        void exPers5007() {
            assertThat(KernelErrorCodes.EX_PERS_5007).isEqualTo("EX-PERS-5007");
        }
    }

    // -----------------------------------------------------------------------
    // EX-SEC domain — security
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EX-SEC domain (Security)")
    class SecDomain {

        @Test
        @DisplayName("EX_SEC_2001 == 'EX-SEC-2001' (PrincipalContext missing)")
        void exSec2001() {
            assertThat(KernelErrorCodes.EX_SEC_2001).isEqualTo("EX-SEC-2001");
        }

        @Test
        @DisplayName("EX_SEC_2002 == 'EX-SEC-2002' (token validation failed)")
        void exSec2002() {
            assertThat(KernelErrorCodes.EX_SEC_2002).isEqualTo("EX-SEC-2002");
        }

        @Test
        @DisplayName("EX_SEC_2003 == 'EX-SEC-2003' (insufficient privileges)")
        void exSec2003() {
            assertThat(KernelErrorCodes.EX_SEC_2003).isEqualTo("EX-SEC-2003");
        }

        @Test
        @DisplayName("EX_SEC_2004 == 'EX-SEC-2004' (StorageContext missing)")
        void exSec2004() {
            assertThat(KernelErrorCodes.EX_SEC_2004).isEqualTo("EX-SEC-2004");
        }
    }

    // -----------------------------------------------------------------------
    // EX-GRPH domain — graph engine
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EX-GRPH domain (Graph Engine)")
    class GrphDomain {

        @Test
        @DisplayName("EX_GRPH_5001 == 'EX-GRPH-5001' (graph bootstrap failure)")
        void exGrph5001() {
            assertThat(KernelErrorCodes.EX_GRPH_5001).isEqualTo("EX-GRPH-5001");
        }

        @Test
        @DisplayName("EX_GRPH_5002 == 'EX-GRPH-5002' (graph query failure)")
        void exGrph5002() {
            assertThat(KernelErrorCodes.EX_GRPH_5002).isEqualTo("EX-GRPH-5002");
        }

        @Test
        @DisplayName("EX_GRPH_5003 == 'EX-GRPH-5003' (graph sync failure)")
        void exGrph5003() {
            assertThat(KernelErrorCodes.EX_GRPH_5003).isEqualTo("EX-GRPH-5003");
        }

        @Test
        @DisplayName("EX_GRPH_5004 == 'EX-GRPH-5004' (path not found)")
        void exGrph5004() {
            assertThat(KernelErrorCodes.EX_GRPH_5004).isEqualTo("EX-GRPH-5004");
        }

        @Test
        @DisplayName("EX_GRPH_5005 == 'EX-GRPH-5005' (excessive allocation)")
        void exGrph5005() {
            assertThat(KernelErrorCodes.EX_GRPH_5005).isEqualTo("EX-GRPH-5005");
        }
    }

    // -----------------------------------------------------------------------
    // EX-EVENT domain — event engine
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EX-EVENT domain (Event Engine)")
    class EventDomain {

        @Test
        @DisplayName("EX_EVENT_6001 == 'EX-EVENT-6001' (generic event engine failure)")
        void exEvent6001() {
            assertThat(KernelErrorCodes.EX_EVENT_6001).isEqualTo("EX-EVENT-6001");
        }

        @Test
        @DisplayName("EX_EVENT_6002 == 'EX-EVENT-6002' (event bus overflow)")
        void exEvent6002() {
            assertThat(KernelErrorCodes.EX_EVENT_6002).isEqualTo("EX-EVENT-6002");
        }

        @Test
        @DisplayName("EX_EVENT_6003 == 'EX-EVENT-6003' (event registry conflict)")
        void exEvent6003() {
            assertThat(KernelErrorCodes.EX_EVENT_6003).isEqualTo("EX-EVENT-6003");
        }

        @Test
        @DisplayName("EX_EVENT_6004 == 'EX-EVENT-6004' (event provider creation failure)")
        void exEvent6004() {
            assertThat(KernelErrorCodes.EX_EVENT_6004).isEqualTo("EX-EVENT-6004");
        }
    }

    // -----------------------------------------------------------------------
    // EX-FLOW domain — flow engine
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EX-FLOW domain (Flow Engine / Saga Orchestration)")
    class FlowDomain {

        @Test
        @DisplayName("EX_FLOW_7001 == 'EX-FLOW-7001' (flow provider creation failure)")
        void exFlow7001() {
            assertThat(KernelErrorCodes.EX_FLOW_7001).isEqualTo("EX-FLOW-7001");
        }

        @Test
        @DisplayName("EX_FLOW_7002 == 'EX-FLOW-7002' (flow engine lifecycle failure)")
        void exFlow7002() {
            assertThat(KernelErrorCodes.EX_FLOW_7002).isEqualTo("EX-FLOW-7002");
        }

        @Test
        @DisplayName("EX_FLOW_7003 == 'EX-FLOW-7003' (flow step execution failure)")
        void exFlow7003() {
            assertThat(KernelErrorCodes.EX_FLOW_7003).isEqualTo("EX-FLOW-7003");
        }

        @Test
        @DisplayName("EX_FLOW_7004 == 'EX-FLOW-7004' (flow registry conflict)")
        void exFlow7004() {
            assertThat(KernelErrorCodes.EX_FLOW_7004).isEqualTo("EX-FLOW-7004");
        }
    }

    // -----------------------------------------------------------------------
    // EX-RUN domain — runtime / scheduler
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EX-RUN domain (Runtime / Scheduler)")
    class RunDomain {

        @Test
        @DisplayName("EX_RUN_3002 == 'EX-RUN-3002' (virtual thread pinned carrier)")
        void exRun3002() {
            assertThat(KernelErrorCodes.EX_RUN_3002).isEqualTo("EX-RUN-3002");
        }
    }

    // -----------------------------------------------------------------------
    // Cross-cutting: all codes are non-blank and match the universal pattern
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Cross-cutting: all codes are non-blank and structurally valid")
    class CrossCutting {

        @Test
        @DisplayName("Every known code is non-blank")
        void allCodesNonBlank() {
            List<String> allCodes = List.of(
                    KernelErrorCodes.EX_MEM_1001, KernelErrorCodes.EX_MEM_1002, KernelErrorCodes.EX_MEM_1003,
                    KernelErrorCodes.EX_BOOT_0002, KernelErrorCodes.EX_BOOT_0003,
                    KernelErrorCodes.EX_BOOT_0004, KernelErrorCodes.EX_BOOT_3001,
                    KernelErrorCodes.EX_NET_2001, KernelErrorCodes.EX_NET_2002,
                    KernelErrorCodes.EX_NET_4001, KernelErrorCodes.EX_NET_4002,
                    KernelErrorCodes.EX_NET_4003, KernelErrorCodes.EX_NET_4004, KernelErrorCodes.EX_NET_4005,
                    KernelErrorCodes.EX_PERS_5001, KernelErrorCodes.EX_PERS_5002,
                    KernelErrorCodes.EX_PERS_5003, KernelErrorCodes.EX_PERS_5004,
                    KernelErrorCodes.EX_PERS_5005, KernelErrorCodes.EX_PERS_5006, KernelErrorCodes.EX_PERS_5007,
                    KernelErrorCodes.EX_SEC_2001, KernelErrorCodes.EX_SEC_2002,
                    KernelErrorCodes.EX_SEC_2003, KernelErrorCodes.EX_SEC_2004,
                    KernelErrorCodes.EX_GRPH_5001, KernelErrorCodes.EX_GRPH_5002,
                    KernelErrorCodes.EX_GRPH_5003, KernelErrorCodes.EX_GRPH_5004, KernelErrorCodes.EX_GRPH_5005,
                    KernelErrorCodes.EX_EVENT_6001, KernelErrorCodes.EX_EVENT_6002,
                    KernelErrorCodes.EX_EVENT_6003, KernelErrorCodes.EX_EVENT_6004,
                    KernelErrorCodes.EX_FLOW_7001, KernelErrorCodes.EX_FLOW_7002,
                    KernelErrorCodes.EX_FLOW_7003, KernelErrorCodes.EX_FLOW_7004,
                    KernelErrorCodes.EX_RUN_3002
            );
            assertThat(allCodes).isNotEmpty().doesNotContain("").allSatisfy(code ->
                    assertThat(code).isNotBlank()
            );
        }
    }
}

