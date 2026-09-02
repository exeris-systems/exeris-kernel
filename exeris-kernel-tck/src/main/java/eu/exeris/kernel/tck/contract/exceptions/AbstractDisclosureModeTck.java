/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.exceptions;

import eu.exeris.kernel.spi.config.KernelProfile;
import eu.exeris.kernel.spi.exceptions.ExceptionDisclosure;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Profile-aware disclosure contract for {@link ExceptionDisclosure}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link KernelProfile#DEV} surfaces the original message and rawArgs.</li>
 *   <li>{@link KernelProfile#TEST} and {@link KernelProfile#PROD} return an opaque
 *       {@code "<errorCode> [traceId=<uuid>]"} envelope and an empty rawArgs array.</li>
 *   <li>Stack-trace disclosure tracks {@link KernelProfile#enablesFullErrorDisclosure()}.</li>
 *   <li>Opaque envelopes are correlatable: they always contain the {@code errorCode}
 *       and {@code traceId} of the source exception.</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class CommunityDisclosureModeTckTest extends AbstractDisclosureModeTck {
 *     // No overrides required — the contract is profile-driven and binding-agnostic.
 * }
 * }</pre>
 *
 * <p>Bindings that wrap {@code ExceptionDisclosure} in a sink (e.g.,
 * {@code Slf4jTelemetrySink}) should add their own concrete tests asserting the
 * rendered artifact respects the disclosure decision — this abstract verifies
 * only the SPI helper itself.
 *
 * @since 0.7.0
 */
@DisplayName("ExceptionDisclosure — profile-aware redaction contract")
public abstract class AbstractDisclosureModeTck {

    private static final long REQUESTED_BYTES = 1024L;
    private static final long AVAILABLE_BYTES = 0L;

    /**
     * Returns a fixture exception with non-empty {@code rawArgs}. Concrete bindings may
     * override to substitute an SPI exception they actually throw; the default uses
     * {@link MemoryExhaustedException} which is part of the SPI baseline.
     *
     * @return non-null kernel exception
     */
    protected ExerisKernelException fixture() {
        return new MemoryExhaustedException(REQUESTED_BYTES, AVAILABLE_BYTES);
    }

    @Test
    @DisplayName("DEV profile surfaces the original message verbatim")
    void devProfileSurfacesFullMessage() {
        ExerisKernelException ex = fixture();
        String disclosed = ExceptionDisclosure.discloseMessage(ex, KernelProfile.DEV);
        assertThat(disclosed).isEqualTo(ex.getMessage());
    }

    @Test
    @DisplayName("PROD profile redacts message to opaque code+traceId envelope")
    void prodProfileRedactsMessage() {
        ExerisKernelException ex = fixture();
        String disclosed = ExceptionDisclosure.discloseMessage(ex, KernelProfile.PROD);
        assertThat(disclosed)
                .doesNotContain(ex.getMessage())
                .contains(ex.errorCode())
                .contains(ex.traceId().toString());
    }

    @Test
    @DisplayName("TEST profile redacts message identically to PROD")
    void testProfileRedactsMessage() {
        ExerisKernelException ex = fixture();
        String disclosed = ExceptionDisclosure.discloseMessage(ex, KernelProfile.TEST);
        assertThat(disclosed)
                .doesNotContain(ex.getMessage())
                .contains(ex.errorCode())
                .contains(ex.traceId().toString());
    }

    @Test
    @DisplayName("DEV profile returns the original rawArgs reference")
    void devProfileSurfacesRawArgs() {
        ExerisKernelException ex = fixture();
        Object[] disclosed = ExceptionDisclosure.discloseRawArgs(ex, KernelProfile.DEV);
        assertThat(disclosed).isSameAs(ex.rawArgs());
    }

    @Test
    @DisplayName("PROD profile returns the empty rawArgs sentinel")
    void prodProfileRedactsRawArgs() {
        ExerisKernelException ex = fixture();
        Object[] disclosed = ExceptionDisclosure.discloseRawArgs(ex, KernelProfile.PROD);
        assertThat(disclosed)
                .as("PROD must not surface operational primitives")
                .isEmpty();
        assertThat(disclosed).isSameAs(ExceptionDisclosure.EMPTY_ARGS);
    }

    @Test
    @DisplayName("TEST profile returns the empty rawArgs sentinel")
    void testProfileRedactsRawArgs() {
        ExerisKernelException ex = fixture();
        Object[] disclosed = ExceptionDisclosure.discloseRawArgs(ex, KernelProfile.TEST);
        assertThat(disclosed).isEmpty();
        assertThat(disclosed).isSameAs(ExceptionDisclosure.EMPTY_ARGS);
    }

    @Test
    @DisplayName("Stack-trace disclosure tracks profile.enablesFullErrorDisclosure()")
    void stackTraceDisclosureFollowsProfile() {
        assertThat(ExceptionDisclosure.discloseStackTrace(KernelProfile.DEV)).isTrue();
        assertThat(ExceptionDisclosure.discloseStackTrace(KernelProfile.TEST)).isFalse();
        assertThat(ExceptionDisclosure.discloseStackTrace(KernelProfile.PROD)).isFalse();
    }

    @Test
    @DisplayName("Opaque envelope keeps errorCode and traceId for log correlation")
    void opaqueEnvelopeIsCorrelatable() {
        ExerisKernelException ex = fixture();
        String prod = ExceptionDisclosure.discloseMessage(ex, KernelProfile.PROD);
        String test = ExceptionDisclosure.discloseMessage(ex, KernelProfile.TEST);
        for (String envelope : new String[] {prod, test}) {
            assertThat(envelope)
                    .as("redacted envelope must remain correlatable via errorCode + traceId")
                    .contains(ex.errorCode())
                    .contains(ex.traceId().toString());
        }
    }

    @Test
    @DisplayName("activeProfile() returns PROD when CURRENT_CONFIG is unbound (safe default)")
    void activeProfileFallsBackToProdWhenUnbound() {
        // Test runs outside a KernelBootstrap scope; CURRENT_CONFIG must be unbound here.
        assertThat(ExceptionDisclosure.activeProfile())
                .as("activeProfile() must fall back to PROD when no config is bound")
                .isEqualTo(KernelProfile.PROD);
    }
}
