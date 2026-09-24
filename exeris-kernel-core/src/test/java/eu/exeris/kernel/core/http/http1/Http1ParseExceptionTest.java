/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http1;

import eu.exeris.kernel.spi.exceptions.FaultOrigin;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Core: Http1ParseException Contract & Glass-Box Test")
class Http1ParseExceptionTest {

    @Test
    @DisplayName("Http1ParseException carries EX-HTTP-4004 error code and classifies as SYSTEM fault by default (ADR-083)")
    void carriesErrorCodeAndClassifiesAsSystemFaultByDefault() {
        Http1ParseException exception = new Http1ParseException("Test violation", 42L);

        assertThat(exception.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
        assertThat(exception.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
        assertThat(FaultOrigin.classify(exception)).isEqualTo(FaultOrigin.SYSTEM);
        assertThat(exception.rawArgs()).containsExactly(42L);
        assertThat(exception.getMessage()).contains("Test violation");
    }

    @Test
    @DisplayName("Http1ParseException with cause chains properly and preserves SYSTEM fault origin by default")
    void constructorWithCausePreservesSystemFaultByDefault() {
        IllegalStateException cause = new IllegalStateException("Underlying error");
        Http1ParseException exception = new Http1ParseException("Malformed framing", cause, 100L, 200L);

        assertThat(exception.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
        assertThat(exception.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.rawArgs()).containsExactly(100L, 200L);
    }

    @Test
    @DisplayName("Http1RequestParseException fixes CALLER fault at the type (ADR-083)")
    void requestParseExceptionFixesCallerFault() {
        Http1RequestParseException inbound = new Http1RequestParseException(
                "Request line too long", 1024L, 8192L);

        assertThat(inbound.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
        assertThat(inbound.faultOrigin()).isEqualTo(FaultOrigin.CALLER);
        assertThat(FaultOrigin.classify(inbound)).isEqualTo(FaultOrigin.CALLER);
        assertThat(inbound.rawArgs()).containsExactly(1024L, 8192L);
    }

    @Test
    @DisplayName("Http1RequestParseException fixes CALLER fault when chaining a cause too")
    void requestParseExceptionFixesCallerFaultWithCause() {
        NumberFormatException cause = new NumberFormatException("For input string: \"x\"");
        Http1RequestParseException inbound = new Http1RequestParseException(
                "Malformed Content-Length", cause, 7L);

        assertThat(inbound.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
        assertThat(inbound.faultOrigin()).isEqualTo(FaultOrigin.CALLER);
        assertThat(FaultOrigin.classify(inbound)).isEqualTo(FaultOrigin.CALLER);
        assertThat(inbound.rawArgs()).containsExactly(7L);
        assertThat(inbound).hasCause(cause);
    }

    @Test
    @DisplayName("Http1ParseException with explicit FaultOrigin.CALLER classifies as CALLER fault")
    void explicitCallerFaultOriginPreserved() {
        Http1ParseException exception = new Http1ParseException(
                FaultOrigin.CALLER, "Malformed client request line", 42L);

        assertThat(exception.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
        assertThat(exception.faultOrigin()).isEqualTo(FaultOrigin.CALLER);
        assertThat(FaultOrigin.classify(exception)).isEqualTo(FaultOrigin.CALLER);
        assertThat(exception.rawArgs()).containsExactly(42L);
    }

    @Test
    @DisplayName("Http1ParseException with explicit FaultOrigin.SYSTEM classifies as SYSTEM fault (ADR-083)")
    void explicitSystemFaultOriginPreserved() {
        Http1ParseException exception = new Http1ParseException(
                FaultOrigin.SYSTEM, "Conflicting Content-Length from upstream", 10L, 20L);

        assertThat(exception.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
        assertThat(exception.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
        assertThat(FaultOrigin.classify(exception)).isEqualTo(FaultOrigin.SYSTEM);
        assertThat(exception.rawArgs()).containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("Http1ParseException with explicit FaultOrigin.SYSTEM and cause preserves cause and SYSTEM origin")
    void explicitSystemFaultOriginWithCausePreserved() {
        IllegalStateException cause = new IllegalStateException("Framing corrupted");
        Http1ParseException exception = new Http1ParseException(
                FaultOrigin.SYSTEM, "Upstream framing failure", cause, 100L);

        assertThat(exception.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
        assertThat(exception.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.rawArgs()).containsExactly(100L);
    }

    @Test
    @DisplayName("Http1ParseException carries single fieldSize rawArgs layout for malformed header lines")
    void carriesSingleFieldSizeRawArgsLayout() {
        Http1ParseException exception = new Http1ParseException(
                FaultOrigin.SYSTEM, "Malformed header line", 64L);

        assertThat(exception.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
        assertThat(exception.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
        assertThat(exception.rawArgs()).containsExactly(64L);
    }

    @Test
    @DisplayName("Http1ParseException carries triple rawArgs layout for buffer range limit violations")
    void carriesTripleRawArgsLayoutForBufferRangeLimits() {
        Http1ParseException exception = new Http1ParseException(
                FaultOrigin.SYSTEM, "Buffer range out of bounds", 0L, 1024L, 512L);

        assertThat(exception.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
        assertThat(exception.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
        assertThat(exception.rawArgs()).containsExactly(0L, 1024L, 512L);
    }

    @Test
    @DisplayName("Http1ParseException carries single rejected header name String rawArgs layout")
    void carriesSingleRejectedHeaderNameStringRawArgsLayout() {
        Http1ParseException exception = new Http1ParseException(
                FaultOrigin.CALLER, "Invalid header name token: {0}", "X-Invalid@Name");

        assertThat(exception.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
        assertThat(exception.faultOrigin()).isEqualTo(FaultOrigin.CALLER);
        assertThat(exception.rawArgs()).containsExactly("X-Invalid@Name");
    }
}
