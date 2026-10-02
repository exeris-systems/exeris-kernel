/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.transport.TransportConfig;

/**
 * What the owner of a {@code CLIENT} transport requires of its outbound connections, stated when
 * the transport is built.
 *
 * <p>Community-internal. It is on no SPI type: an owner whose engine dials peers of one known
 * scheme passes it to
 * {@link NativeTcpTransportProvider#createEngine(TransportConfig, CommunityOutboundTls)},
 * or to the Community HTTP overloads that reach that method. {@code TransportProvider#createEngine}
 * passes {@link #AMBIENT}.
 *
 * @since 0.12
 */
public enum CommunityOutboundTls {

    /**
     * The carrier decides where it is built: TLS that verifies the server where a crypto provider
     * is bound and {@code exeris.transport.tls} is not {@code false}, plaintext otherwise.
     */
    AMBIENT,

    /**
     * Plaintext, whatever crypto provider is bound and whatever {@code exeris.transport.tls} says.
     */
    PLAINTEXT,

    /**
     * TLS that verifies the server, or no carrier: {@code exeris.transport.tls=false}, no bound
     * crypto provider and a provider that cannot verify an outbound peer each fail the carrier's
     * construction.
     */
    VERIFIED
}
