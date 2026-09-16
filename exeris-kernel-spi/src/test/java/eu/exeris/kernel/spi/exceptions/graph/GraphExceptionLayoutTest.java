/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.graph;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: Graph exception rawArgs binary layouts.
 *
 * <h2>Layouts Verified</h2>
 * <pre>
 * GraphBootstrapException (EX-GRPH-5001):      [String providerName, String reason]
 * ExcessiveAllocationException (EX-GRPH-5005): [String driverName, long bytesAllocated, long bytesTransferred]
 * PathNotFoundException (EX-GRPH-5004):        [long sourceMost, long sourceLeast, long targetMost, long targetLeast]
 * </pre>
 *
 * @since 0.5.0
 */
@DisplayName("L0: Graph Exception rawArgs Binary Layouts")
class GraphExceptionLayoutTest {

    // -----------------------------------------------------------------------
    // GraphBootstrapException — EX-GRPH-5001
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("GraphBootstrapException (EX-GRPH-5001)")
    class GraphBootstrap {

        @Test
        @DisplayName("rawArgs[0]=providerName (String), [1]=reason (String)")
        void rawArgsLayout() {
            GraphBootstrapException ex = new GraphBootstrapException(
                    "ExerisCommunity/JdbcGraph", "Neo4j driver not on classpath");

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_GRPH_5001);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("ExerisCommunity/JdbcGraph");
            assertThat(raw[1]).isEqualTo("Neo4j driver not on classpath");
        }

        @Test
        @DisplayName("Chained constructor preserves cause")
        void causePreserved() {
            RuntimeException root = new RuntimeException("connection refused");
            GraphBootstrapException ex = new GraphBootstrapException(
                    "ExerisEnterprise/Neo4j", "cannot connect", root);
            assertThat(ex.getCause()).isSameAs(root);
            assertThat(ex.rawArgs()[0]).isEqualTo("ExerisEnterprise/Neo4j");
        }

        @Test
        @DisplayName("No-cause constructor sets getCause() to null")
        void noCauseIsNull() {
            GraphBootstrapException ex = new GraphBootstrapException("P", "reason");
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("Is unchecked (RuntimeException)")
        void isUnchecked() {
            assertThat(new GraphBootstrapException("P", "r"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // -----------------------------------------------------------------------
    // ExcessiveAllocationException — EX-GRPH-5005
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ExcessiveAllocationException (EX-GRPH-5005)")
    class ExcessiveAllocation {

        @Test
        @DisplayName("rawArgs[0]=driverName, [1]=bytesAllocated (long), [2]=bytesTransferred (long)")
        void rawArgsLayout() {
            ExcessiveAllocationException ex = new ExcessiveAllocationException(
                    "CypherGraphDriver", 10_485_760L, 1_024L);

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_GRPH_5005);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(3);
            assertThat(raw[0]).isEqualTo("CypherGraphDriver");
            assertThat(raw[1]).isEqualTo(10_485_760L);
            assertThat(raw[2]).isEqualTo(1_024L);
        }

        @Test
        @DisplayName("rawArgs[1] and [2] are Long type — binary serializer contract")
        void rawArgTypes() {
            ExcessiveAllocationException ex = new ExcessiveAllocationException("D", 1L, 0L);
            assertThat(ex.rawArgs()[1]).isInstanceOf(Long.class);
            assertThat(ex.rawArgs()[2]).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("getCause() is null — ExcessiveAllocationException has no cause")
        void noCause() {
            assertThat(new ExcessiveAllocationException("D", 1L, 0L).getCause()).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // PathNotFoundException — EX-GRPH-5004
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("PathNotFoundException (EX-GRPH-5004)")
    class PathNotFound {

        @Test
        @DisplayName("rawArgs[0..3] = sourceMost, sourceLeast, targetMost, targetLeast (4x long)")
        void rawArgsLayout() {
            UUID src = UUID.randomUUID();
            UUID tgt = UUID.randomUUID();
            PathNotFoundException ex = new PathNotFoundException(src, tgt);

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_GRPH_5004);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(4);
            assertThat(raw[0]).isEqualTo(src.getMostSignificantBits());
            assertThat(raw[1]).isEqualTo(src.getLeastSignificantBits());
            assertThat(raw[2]).isEqualTo(tgt.getMostSignificantBits());
            assertThat(raw[3]).isEqualTo(tgt.getLeastSignificantBits());
        }

        @Test
        @DisplayName("rawArgs[0..3] are all Long — no UUID object in payload")
        void rawArgTypesAreLong() {
            UUID src = UUID.randomUUID();
            UUID tgt = UUID.randomUUID();
            PathNotFoundException ex = new PathNotFoundException(src, tgt);
            assertThat(ex.rawArgs()[0]).isInstanceOf(Long.class);
            assertThat(ex.rawArgs()[1]).isInstanceOf(Long.class);
            assertThat(ex.rawArgs()[2]).isInstanceOf(Long.class);
            assertThat(ex.rawArgs()[3]).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("UUID round-trip: longs reconstruct original source UUID")
        void sourceUuidRoundTrip() {
            UUID src = UUID.randomUUID();
            UUID tgt = UUID.randomUUID();
            PathNotFoundException ex = new PathNotFoundException(src, tgt);
            UUID reconstructed = new UUID((long) ex.rawArgs()[0], (long) ex.rawArgs()[1]);
            assertThat(reconstructed).isEqualTo(src);
        }

        @Test
        @DisplayName("UUID round-trip: longs reconstruct original target UUID")
        void targetUuidRoundTrip() {
            UUID src = UUID.randomUUID();
            UUID tgt = UUID.randomUUID();
            PathNotFoundException ex = new PathNotFoundException(src, tgt);
            UUID reconstructed = new UUID((long) ex.rawArgs()[2], (long) ex.rawArgs()[3]);
            assertThat(reconstructed).isEqualTo(tgt);
        }

        @Test
        @DisplayName("null sourceNodeId throws NullPointerException")
        void nullSourceThrows() {
            UUID target = UUID.randomUUID();
            assertThatThrownBy(() -> new PathNotFoundException(null, target))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null targetNodeId throws NullPointerException")
        void nullTargetThrows() {
            UUID source = UUID.randomUUID();
            assertThatThrownBy(() -> new PathNotFoundException(source, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}

