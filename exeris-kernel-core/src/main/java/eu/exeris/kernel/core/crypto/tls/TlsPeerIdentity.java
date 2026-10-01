/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

/**
 * The identity a TLS client requires of the server's certificate: a DNS name, matched against the
 * certificate's DNS subject alternative names, or an IP address, matched against its IP entries.
 *
 * <p>The subject common name is never consulted, and an identity of one kind never matches an
 * entry of the other.
 *
 * @since 0.12
 */
public sealed interface TlsPeerIdentity permits TlsPeerIdentity.DnsName, TlsPeerIdentity.IpAddress {

    /** The longest DNS name, in characters, without a trailing dot. */
    int MAX_NAME_LENGTH = 253;

    /** The longest DNS label, in characters. */
    int MAX_LABEL_LENGTH = 63;

    /**
     * Classifies an authority host as the identity a server certificate must carry, without a DNS
     * lookup.
     *
     * <p>A host in brackets is an IPv6 literal. A host that {@link InetAddress#ofLiteral} accepts is
     * an IP address: the same bytes the dial connects to, with any IPv6 scope dropped, because a
     * scope is a routing hint and not part of the identity. Anything else is a DNS name: one
     * trailing dot removed, then lower-cased. It must be ASCII (an internationalised name is given
     * in its A-label form), 1–253 characters long, in labels of 1–63 letters, digits, hyphens or
     * underscores.
     *
     * @param host the host part of an authority, as dialled
     * @return the identity the server must present
     * @throws TlsHandshakeException ({@code EX-NET-2001}, detail
     *         {@link TlsFailureDetail#INVALID_PEER_NAME}) when the host is none of these
     */
    // 'of' is the standard Java factory idiom (cf. List.of, Path.of, StreamId.of)
    @SuppressWarnings("PMD.ShortMethodName")
    static TlsPeerIdentity of(String host) {
        return TlsPeerIdentityParser.classify(host);
    }

    /**
     * A DNS name, in the lower-case, dot-free-at-the-end form the certificate check and the server
     * name indication both take.
     *
     * @param name the name; lower-cased on construction
     * @since 0.12
     */
    record DnsName(String name) implements TlsPeerIdentity {

        private static final char LABEL_SEPARATOR = '.';
        private static final char ASCII_LIMIT = 0x80;

        /**
         * Validates and lower-cases {@code name}.
         *
         * @throws IllegalArgumentException if {@code name} is not an ASCII DNS name of 1–253
         *         characters in labels of 1–63 letters, digits, hyphens or underscores
         */
        public DnsName {
            Objects.requireNonNull(name, "name must not be null");
            if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException("DNS name must be 1-" + MAX_NAME_LENGTH + " characters");
            }
            int labelLength = 0;
            for (int index = 0; index < name.length(); index++) {
                labelLength = labelLengthAfter(name.charAt(index), labelLength);
            }
            requireNonEmptyLabel(labelLength);
            name = name.toLowerCase(Locale.ROOT);
        }

        /**
         * The length of the current label once {@code character} is read: {@code 0} after a
         * separator that ends a non-empty label, one more after a label character.
         */
        private static int labelLengthAfter(char character, int labelLength) {
            if (character == LABEL_SEPARATOR) {
                requireNonEmptyLabel(labelLength);
                return 0;
            }
            if (!isLabelCharacter(character)) {
                throw new IllegalArgumentException("DNS name holds a character outside [A-Za-z0-9_-]");
            }
            if (labelLength == MAX_LABEL_LENGTH) {
                throw new IllegalArgumentException("DNS label exceeds " + MAX_LABEL_LENGTH + " characters");
            }
            return labelLength + 1;
        }

        private static void requireNonEmptyLabel(int labelLength) {
            if (labelLength == 0) {
                throw new IllegalArgumentException("DNS name has an empty label");
            }
        }

        /**
         * An ASCII letter or digit, a hyphen or an underscore. {@link Character#isLetterOrDigit} is
         * asked only below {@link #ASCII_LIMIT}, where it means exactly {@code [A-Za-z0-9]}.
         */
        private static boolean isLabelCharacter(char character) {
            return character < ASCII_LIMIT
                    && (Character.isLetterOrDigit(character) || character == '-' || character == '_');
        }
    }

    /**
     * An IPv4 or IPv6 address, without an IPv6 scope.
     *
     * @param address the address; any scope is dropped on construction
     * @since 0.12
     */
    record IpAddress(InetAddress address) implements TlsPeerIdentity {

        /**
         * Drops any IPv6 scope from {@code address}.
         */
        public IpAddress {
            Objects.requireNonNull(address, "address must not be null");
            try {
                address = InetAddress.getByAddress(address.getAddress());
            } catch (UnknownHostException impossible) {
                throw new IllegalArgumentException("address has neither 4 nor 16 bytes", impossible);
            }
        }

        /**
         * The address in network byte order.
         *
         * @return a fresh array of 4 or 16 bytes
         */
        public byte[] octets() {
            return address.getAddress();
        }
    }
}
