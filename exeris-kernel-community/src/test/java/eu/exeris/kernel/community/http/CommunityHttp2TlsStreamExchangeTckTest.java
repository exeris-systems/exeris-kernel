/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.transport.NativeTcpStreamTlsDrainHook;
import eu.exeris.kernel.community.transport.TlsTestCertificate;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.tck.contract.http.AbstractHttpStreamExchangeTck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community binding of {@link AbstractHttpStreamExchangeTck} over HTTP/2 on a TLS listener with ALPN
 * {@code h2}. It reuses the cleartext binding's scenario adapter and swaps the round trip for
 * {@link Http2StreamLoopback#startTls}, so every case runs against the TLS record layer. A client that
 * withholds flow-control credit parks the emitter on the stream window; with an 8 KiB send buffer the
 * socket fills as well, which is where a TLS write meets a full buffer, and the backpressure probe
 * fails unless a write stalled. Runs with the {@code stream-loopback} group.
 *
 * <p>Fails rather than skips when OpenSSL cannot be loaded, because a skipped case reports success.
 * Probes the cleartext binding inherits, the shed probe among them, run again over TLS.
 */
@Tag("stream-loopback")
@DisplayName("Community: HttpStreamExchange TCK over HTTP/2 with TLS and ALPN")
class CommunityHttp2TlsStreamExchangeTckTest extends CommunityHttp2cStreamExchangeTckTest {

    private static final long AUTH_EXPIRY_DELAY_MILLIS = 300L;

    @TempDir
    /* default */ static Path tlsMaterialDir;

    private static Http2StreamLoopback.Tls tls;

    @BeforeAll
    static void loadOpenSslAndMaterial() throws Exception {
        CommunityKernelCryptoProvider crypto = new CommunityKernelCryptoProvider();
        TlsTestCertificate certificate = TlsTestCertificate.generateInto(tlsMaterialDir);
        tls = new Http2StreamLoopback.Tls(crypto, certificate, Http2TestClient.trusting(certificate.certificate()));
    }

    @Override
    protected StreamScenario openStream(HttpStreamHandler handler) {
        return new StallCountingScenario(Http2StreamLoopback.startTls(handler, 0L, tls));
    }

    @Override
    protected StreamScenario openExpiringStream(HttpStreamHandler handler) {
        return new StallCountingScenario(Http2StreamLoopback.startTls(
                handler, System.currentTimeMillis() + AUTH_EXPIRY_DELAY_MILLIS, tls));
    }

    /**
     * Counts the TLS writes that stalled while the scenario was open. A scenario that withheld the
     * client's credit is the backpressure probe, and it fails on close unless at least one write stalled:
     * with an 8 KiB send buffer the probe's frames do not fit the socket, so a binding that never stalls
     * would be passing on the HTTP/2 window alone.
     */
    private static final class StallCountingScenario extends LoopbackScenario {

        private final AtomicInteger stalls = new AtomicInteger();
        private boolean clientStalled;

        private StallCountingScenario(Http2StreamLoopback loopback) {
            super(loopback);
            NativeTcpStreamTlsDrainHook.install(stalls::incrementAndGet);
        }

        @Override
        public void stallClient() {
            clientStalled = true;
            super.stallClient();
        }

        @Override
        public void close() {
            try {
                super.close();
            } finally {
                NativeTcpStreamTlsDrainHook.clear();
            }
            if (clientStalled) {
                assertThat(stalls.get()).as("TLS writes that stalled during the backpressure probe").isPositive();
            }
        }
    }
}
