/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TlsPeerIdentity#of} classifies an authority host without a lookup: brackets mean IPv6, a
 * JDK IP literal means an address, and anything else must be a DNS name in canonical form.
 */
@DisplayName("L0: TlsPeerIdentity.of — authority host classification")
class TlsPeerIdentityTest {

    @Test
    @DisplayName("localhost is a DNS name")
    void plainNameIsDns() {
        assertThat(TlsPeerIdentity.of("localhost")).isEqualTo(new TlsPeerIdentity.DnsName("localhost"));
    }

    @Test
    @DisplayName("a name is lower-cased")
    void nameIsLowerCased() {
        assertThat(TlsPeerIdentity.of("LOCALHOST")).isEqualTo(new TlsPeerIdentity.DnsName("localhost"));
        assertThat(((TlsPeerIdentity.DnsName) TlsPeerIdentity.of("Api.Example.TEST")).name())
                .isEqualTo("api.example.test");
    }

    @Test
    @DisplayName("one trailing dot is removed")
    void trailingDotIsRemoved() {
        assertThat(TlsPeerIdentity.of("localhost.")).isEqualTo(new TlsPeerIdentity.DnsName("localhost"));
    }

    @Test
    @DisplayName("an IPv4 literal is an address")
    void ipv4IsAddress() {
        assertThat(octets("127.0.0.1")).containsExactly(127, 0, 0, 1);
    }

    @Test
    @DisplayName("a short IPv4 form is the address the dial connects to")
    void shortIpv4IsTheDialledAddress() {
        assertThat(octets("127.1")).containsExactly(127, 0, 0, 1);
    }

    @Test
    @DisplayName("a bracketed IPv6 literal is a 16-byte address")
    void bracketedIpv6IsAddress() {
        byte[] loopback = new byte[16];
        loopback[15] = 1;
        assertThat(octets("[::1]")).containsExactly(loopback);
    }

    @Test
    @DisplayName("an IPv4-mapped IPv6 literal is the 4-byte IPv4 address")
    void mappedIpv6IsIpv4() {
        assertThat(octets("[::ffff:127.0.0.1]")).containsExactly(127, 0, 0, 1);
    }

    @Test
    @DisplayName("an IPv6 scope is dropped")
    void scopeIsDropped() {
        TlsPeerIdentity.IpAddress address = (TlsPeerIdentity.IpAddress) TlsPeerIdentity.of("[fe80::1%1]");

        assertThat(address.octets()).hasSize(16);
        assertThat(address.address().getHostAddress()).doesNotContain("%");
    }

    @Test
    @DisplayName("an IPv4 literal with a trailing dot is classified before the dot is removed: a DNS name")
    void dottedIpv4IsADnsName() {
        assertThat(TlsPeerIdentity.of("127.0.0.1.")).isEqualTo(new TlsPeerIdentity.DnsName("127.0.0.1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"my_service", "xn--bcher-kva.de", "a-b.c-d", "x"})
    @DisplayName("underscores, hyphens and A-labels are accepted")
    void validNamesAreAccepted(String host) {
        assertThat(TlsPeerIdentity.of(host)).isInstanceOf(TlsPeerIdentity.DnsName.class);
    }

    @Test
    @DisplayName("a 253-character name and a 63-character label are accepted")
    void longestNameAndLabelAreAccepted() {
        String label = "a".repeat(63);
        String name = label + "." + label + "." + label + "." + "b".repeat(61);

        assertThat(name).hasSize(253);
        assertThat(TlsPeerIdentity.of(name)).isInstanceOf(TlsPeerIdentity.DnsName.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "[127.0.0.1]", "[localhost]", "[::1", ".", "..", "a..", "a..b", ".a", "bücher.de",
            "*.x", "a b", "host:80", "nul\u0000byte", "crlf\r\nInjected"})
    @DisplayName("a host that is neither an IP literal nor a DNS name is refused")
    void invalidHostsAreRefused(String host) {
        assertRefused(host);
    }

    @Test
    @DisplayName("a 254-character name is refused")
    void overlongNameIsRefused() {
        String label = "a".repeat(63);
        assertRefused(label + "." + label + "." + label + "." + "b".repeat(62));
    }

    @Test
    @DisplayName("a 64-character label is refused")
    void overlongLabelIsRefused() {
        assertRefused("a".repeat(64) + ".example");
    }

    @Test
    @DisplayName("null is refused")
    void nullIsRefused() {
        assertRefused(null);
    }

    private static void assertRefused(String host) {
        assertThatThrownBy(() -> TlsPeerIdentity.of(host))
                .isInstanceOf(TlsHandshakeException.class)
                .satisfies(e -> assertThat(((TlsHandshakeException) e).rawArgs())
                        .containsExactly(-1, TlsFailureDetail.INVALID_PEER_NAME));
    }

    private static byte[] octets(String host) {
        TlsPeerIdentity identity = TlsPeerIdentity.of(host);
        assertThat(identity).isInstanceOf(TlsPeerIdentity.IpAddress.class);
        return ((TlsPeerIdentity.IpAddress) identity).octets();
    }
}
