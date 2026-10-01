/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * TCK: an {@link HttpClientEngine} that carries a request over TLS verifies the server before any
 * request byte is sent ({@link HttpClientEngine#send}, ADR-074 §4).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>The certificate chain is verified against the engine's trust: a leaf from an issuer the
 *       engine does not trust is refused, and so is a leaf from a private authority when the engine
 *       uses its default trust.</li>
 *   <li>The certificate's subject alternative names are matched against the host of the effective
 *       authority — {@link HttpRequest#authority()}, else {@link HttpClientEngine#defaultAuthority()}.
 *       A DNS host matches DNS entries only and an IP literal IP entries only; the subject common
 *       name is never consulted.</li>
 *   <li>A refusal is {@link TlsHandshakeException} ({@code EX-NET-2001}) thrown from {@code send},
 *       and the server reads no request.</li>
 *   <li>A DNS host is sent as the server name indication; an IP literal sends none.</li>
 * </ul>
 *
 * <p>It is a class of its own, apart from {@link AbstractHttpClientEngineTck}, so that a provider
 * binds it when its client verifies, without the general client contract going red first.
 *
 * <h2>Binding</h2>
 * <p>A binding supplies the fixtures ({@link #fixtures}) and a started client that carries requests
 * over TLS with a given trust ({@link #startClient}). The servers are the JDK's own TLS stack
 * ({@link #startTlsServer}) and a ClientHello probe, so the server side of every case is independent
 * of the provider under test. The DNS cases dial {@code localhost} and bind their servers on the
 * address it resolves to; the address cases dial {@code 127.0.0.1}. A binding with a numeric code
 * for each reason a server is refused asserts it in {@link #assertRefusalDetail}.
 *
 * @since 0.12
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public abstract class AbstractHttpClientTlsPeerVerificationTck {

    private static final String LOCALHOST = "localhost";
    private static final String LOOPBACK = "127.0.0.1";
    private static final Duration QUIET_LIMIT = Duration.ofSeconds(15);
    private static final Duration PROBE_LIMIT = Duration.ofSeconds(20);
    private static final int STATUS_OK = 200;

    private final Deque<AutoCloseable> opened = new ArrayDeque<>();
    private TlsPeerFixtures material;
    private InetAddress localhostAddress;
    private InetAddress loopbackAddress;

    /**
     * Creates the contract; subclasses supply the fixtures and the client under test.
     */
    public AbstractHttpClientTlsPeerVerificationTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Why a server is refused, for {@link #assertRefusalDetail}.
     *
     * @since 0.12
     */
    public enum PeerRefusal {
        /** The server's chain does not lead to a certificate the client trusts. */
        UNTRUSTED_ISSUER,
        /** No DNS subject alternative name matches the authority's DNS host. */
        NAME_MISMATCH,
        /** No IP subject alternative name matches the authority's IP literal. */
        ADDRESS_MISMATCH
    }

    /**
     * Writes the fixtures into {@code directory}, fresh for each case.
     *
     * @param directory an empty directory the case owns
     * @return the material, with the shapes {@link TlsPeerFixtures} describes
     */
    protected abstract TlsPeerFixtures fixtures(Path directory);

    /**
     * Creates and starts a client engine that carries every request over TLS.
     *
     * @param trustAnchor      a PEM file holding the only certificate the engine trusts, or
     *                         {@code null} for the provider's default trust
     * @param defaultAuthority the engine's {@link HttpClientEngine#defaultAuthority()}, or
     *                         {@code null} for none
     * @return a started engine; the suite closes it
     */
    protected abstract HttpClientEngine startClient(Path trustAnchor, String defaultAuthority);

    /**
     * Starts a TLS server presenting {@code leaf} on an ephemeral port of {@code bindAddress}. The
     * default is the JDK's TLS stack; a binding overrides it only to serve through something that
     * behaves the same way.
     *
     * @param leaf        the certificate and key to present
     * @param bindAddress the address to accept on
     * @return the started server; the suite closes it
     */
    protected TlsPeerServer startTlsServer(TlsPeerFixtures.Leaf leaf, InetAddress bindAddress) {
        return JsseTlsPeerServer.start(leaf, bindAddress);
    }

    /**
     * Asserts the provider-specific part of a refusal, beyond the {@code EX-NET-2001} every provider
     * reports. The default asserts nothing more.
     *
     * @param refusal the exception {@code send} threw
     * @param reason  why the server should have been refused
     */
    protected void assertRefusalDetail(TlsHandshakeException refusal, PeerRefusal reason) {
        // Optional: TlsHandshakeException leaves rawArgs[0] to the implementation tier.
    }

    @BeforeEach
    final void writeFixtures(@TempDir Path directory) throws IOException {
        material = fixtures(directory);
        localhostAddress = InetAddress.getByName(LOCALHOST);
        loopbackAddress = InetAddress.getByName(LOOPBACK);
    }

    @AfterEach
    final void closeEverything() throws Exception {
        while (!opened.isEmpty()) {
            opened.pop().close();
        }
    }

    // =========================================================================
    // The fixtures are what the cases rely on
    // =========================================================================

    @Test
    @DisplayName("fixtures: each leaf differs from an accepted one in exactly the respect its case relies on")
    void fixturesHaveTheShapesTheCasesRelyOn() {
        TlsPeerFixtureShapes.assertShapes(material);
    }

    // =========================================================================
    // Trust
    // =========================================================================

    @Test
    @DisplayName("a trusted leaf naming the authority's DNS host is accepted, and the request is served")
    void trustedLeafForTheAuthorityNameIsAccepted() {
        TlsPeerServer server = serve(material.localhostLeaf(), localhostAddress);
        HttpClientEngine client = client(material.trustedRoot(), null);

        assertThat(statusOf(client, get(LOCALHOST + ":" + server.port()))).isEqualTo(STATUS_OK);
        assertThat(server.requestsServed()).isEqualTo(1);
    }

    @Test
    @DisplayName("a leaf from an issuer the client does not trust is refused, and the server reads no request")
    void leafFromAnUntrustedIssuerIsRefusedBeforeAnyRequestByte() throws InterruptedException {
        TlsPeerServer server = serve(material.untrustedIssuerLeaf(), localhostAddress);
        HttpClientEngine client = client(material.trustedRoot(), null);

        assertRefused(client, get(LOCALHOST + ":" + server.port()), PeerRefusal.UNTRUSTED_ISSUER);
        server.awaitQuiet(QUIET_LIMIT);
        assertThat(server.requestsServed()).as("request heads the server read from a refused client").isZero();
    }

    @Test
    @DisplayName("default trust: a leaf from a private authority is refused")
    void defaultTrustRefusesAPrivateAuthority() throws InterruptedException {
        TlsPeerServer server = serve(material.localhostLeaf(), localhostAddress);
        HttpClientEngine client = client(null, null);

        assertRefused(client, get(LOCALHOST + ":" + server.port()), PeerRefusal.UNTRUSTED_ISSUER);
        server.awaitQuiet(QUIET_LIMIT);
        assertThat(server.requestsServed()).isZero();
    }

    // =========================================================================
    // Identity: DNS hosts
    // =========================================================================

    @Test
    @DisplayName("a trusted leaf naming another DNS host is refused")
    void trustedLeafForAnotherNameIsRefused() {
        TlsPeerServer server = serve(material.otherNameLeaf(), localhostAddress);
        HttpClientEngine client = client(material.trustedRoot(), null);

        assertRefused(client, get(LOCALHOST + ":" + server.port()), PeerRefusal.NAME_MISMATCH);
    }

    @Test
    @DisplayName("the subject common name is never consulted: CN=localhost with no SAN is refused for localhost")
    void commonNameIsNeverConsulted() {
        TlsPeerServer server = serve(material.commonNameOnlyLeaf(), localhostAddress);
        HttpClientEngine client = client(material.trustedRoot(), null);

        assertRefused(client, get(LOCALHOST + ":" + server.port()), PeerRefusal.NAME_MISMATCH);
    }

    @Test
    @DisplayName("a DNS host is not matched against IP entries: an address-only leaf is refused for localhost")
    void addressLeafDoesNotMatchANameAuthority() {
        TlsPeerServer server = serve(material.loopbackAddressLeaf(), localhostAddress);
        HttpClientEngine client = client(material.trustedRoot(), null);

        assertRefused(client, get(LOCALHOST + ":" + server.port()), PeerRefusal.NAME_MISMATCH);
    }

    // =========================================================================
    // Identity: IP literals
    // =========================================================================

    @Test
    @DisplayName("a trusted leaf naming the authority's IP literal as an IP entry is accepted")
    void trustedLeafForTheAuthorityAddressIsAccepted() {
        TlsPeerServer server = serve(material.loopbackAddressLeaf(), loopbackAddress);
        HttpClientEngine client = client(material.trustedRoot(), null);

        assertThat(statusOf(client, get(LOOPBACK + ":" + server.port()))).isEqualTo(STATUS_OK);
        assertThat(server.requestsServed()).isEqualTo(1);
    }

    @Test
    @DisplayName("an IP literal is not matched against DNS entries: DNS:127.0.0.1 is refused for 127.0.0.1")
    void dnsEntrySpellingTheAddressDoesNotMatchAnAddressAuthority() {
        TlsPeerServer server = serve(material.loopbackAsDnsNameLeaf(), loopbackAddress);
        HttpClientEngine client = client(material.trustedRoot(), null);

        assertRefused(client, get(LOOPBACK + ":" + server.port()), PeerRefusal.ADDRESS_MISMATCH);
    }

    @Test
    @DisplayName("an IP literal is not resolved to a name: DNS:localhost is refused for 127.0.0.1")
    void nameLeafDoesNotMatchAnAddressAuthority() {
        TlsPeerServer server = serve(material.localhostLeaf(), loopbackAddress);
        HttpClientEngine client = client(material.trustedRoot(), null);

        assertRefused(client, get(LOOPBACK + ":" + server.port()), PeerRefusal.ADDRESS_MISMATCH);
    }

    // =========================================================================
    // The effective authority
    // =========================================================================

    @Test
    @DisplayName("an unaddressed request is verified against the default authority, in both directions")
    void unaddressedRequestIsVerifiedAgainstTheDefaultAuthority() {
        TlsPeerServer byName = serve(material.localhostLeaf(), localhostAddress);
        HttpClientEngine named = client(material.trustedRoot(), LOCALHOST + ":" + byName.port());
        assertThat(statusOf(named, unaddressed()))
                .as("default authority localhost, leaf DNS:localhost").isEqualTo(STATUS_OK);

        TlsPeerServer byAddress = serve(material.localhostLeaf(), loopbackAddress);
        HttpClientEngine addressed = client(material.trustedRoot(), LOOPBACK + ":" + byAddress.port());
        assertRefused(addressed, unaddressed(), PeerRefusal.ADDRESS_MISMATCH);
    }

    @Test
    @DisplayName("the request's authority, not the engine's default, is what the server must match")
    void requestAuthorityIsVerifiedOverTheDefault() {
        TlsPeerServer server = serve(material.localhostLeaf(), loopbackAddress);
        HttpClientEngine client = client(material.trustedRoot(), LOCALHOST + ":" + server.port());

        assertRefused(client, get(LOOPBACK + ":" + server.port()), PeerRefusal.ADDRESS_MISMATCH);
    }

    // =========================================================================
    // Server name indication
    // =========================================================================

    @Test
    @DisplayName("a DNS host is sent as the server name, whatever its case")
    void serverNameIsTheAuthorityHost() {
        HttpClientEngine client = client(material.trustedRoot(), null);

        for (String host : List.of(LOCALHOST, "LOCALHOST")) {
            ClientHelloSniProbe probe = probe(localhostAddress);
            sendToProbe(client, get(host + ":" + probe.port()));
            assertThat(probe.awaitClientHello(PROBE_LIMIT).serverName())
                    .as("server_name sent for authority host %s", host)
                    .isEqualToIgnoringCase(LOCALHOST);
        }
    }

    @Test
    @DisplayName("an IP literal sends no server name")
    void noServerNameForAnAddressAuthority() {
        HttpClientEngine client = client(material.trustedRoot(), null);
        ClientHelloSniProbe probe = probe(loopbackAddress);

        sendToProbe(client, get(LOOPBACK + ":" + probe.port()));

        assertThat(probe.awaitClientHello(PROBE_LIMIT).serverName())
                .as("server_name in a complete ClientHello for an IP-literal authority")
                .isNull();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private TlsPeerServer serve(TlsPeerFixtures.Leaf leaf, InetAddress bindAddress) {
        TlsPeerServer server = startTlsServer(leaf, bindAddress);
        opened.push(server);
        return server;
    }

    private ClientHelloSniProbe probe(InetAddress bindAddress) {
        ClientHelloSniProbe probe = ClientHelloSniProbe.listenOn(bindAddress);
        opened.push(probe);
        return probe;
    }

    private HttpClientEngine client(Path trustAnchor, String defaultAuthority) {
        HttpClientEngine client = startClient(trustAnchor, defaultAuthority);
        opened.push(client);
        assertThat(client.isRunning()).as("startClient returns a started engine").isTrue();
        return client;
    }

    private void assertRefused(HttpClientEngine client, HttpRequest request, PeerRefusal reason) {
        Throwable thrown = catchThrowable(() -> statusOf(client, request));
        assertThat(thrown)
                .as("send to a server that fails verification (%s) must throw TlsHandshakeException", reason)
                .isInstanceOf(TlsHandshakeException.class);
        TlsHandshakeException refusal = (TlsHandshakeException) thrown;
        assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_2001);
        assertRefusalDetail(refusal, reason);
    }

    /** Sends to a probe, which reads the ClientHello and closes the connection without answering. */
    private static void sendToProbe(HttpClientEngine client, HttpRequest request) {
        assertThat(catchThrowable(() -> statusOf(client, request)))
                .as("a probe answers no ClientHello, so the send fails")
                .isNotNull();
    }

    private static int statusOf(HttpClientEngine client, HttpRequest request) {
        HttpResponse response = client.send(request);
        try {
            return response.status().code();
        } finally {
            if (response.body() != null) {
                response.body().close();
            }
        }
    }

    private static HttpRequest get(String authority) {
        return unaddressed().withAuthority(authority);
    }

    private static HttpRequest unaddressed() {
        return HttpRequest.noBody(HttpMethod.GET, "/tck/tls-peer", HttpVersion.HTTP_1_1, List.of());
    }
}
