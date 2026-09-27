/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A listener that reads the first TLS ClientHello it is sent, reports whether it carried a
 * {@code server_name} extension and with what host, and closes the connection without answering.
 *
 * <p>An absent {@code server_name} means something only if the whole message was read, so the probe
 * parses every extension and fails unless the extension block ends exactly where the message does
 * and holds {@code signature_algorithms}, which a TLS 1.2 or 1.3 client offering certificate
 * authentication sends. Plain {@code java.net}: it knows nothing about the provider under test.
 */
final class ClientHelloSniProbe implements AutoCloseable {

    private static final int RECORD_HANDSHAKE = 22;
    private static final int HANDSHAKE_CLIENT_HELLO = 1;
    private static final int EXTENSION_SERVER_NAME = 0;
    private static final int EXTENSION_SIGNATURE_ALGORITHMS = 13;
    private static final int NAME_TYPE_HOST_NAME = 0;
    private static final int RANDOM_BYTES = 32;
    private static final int HANDSHAKE_HEADER_BYTES = 4;
    private static final int MAX_CLIENT_HELLO_BYTES = 64 * 1024;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    private final ServerSocket listener;
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile ClientHello observed;
    private volatile Exception failure;

    private ClientHelloSniProbe(ServerSocket listener) {
        this.listener = listener;
    }

    /**
     * What the probe read.
     *
     * @param serverName the {@code host_name} in the {@code server_name} extension, or {@code null}
     *                   when the ClientHello carried none
     */
    record ClientHello(String serverName) {
    }

    /**
     * Starts listening on an ephemeral port of {@code address}.
     *
     * @param address the address to accept on
     * @return the listening probe
     */
    static ClientHelloSniProbe listenOn(InetAddress address) {
        try {
            ClientHelloSniProbe probe = new ClientHelloSniProbe(new ServerSocket(0, 1, address));
            Thread.ofPlatform().daemon().name("tck-client-hello-probe").start(probe::readOne);
            return probe;
        } catch (IOException e) {
            throw new UncheckedIOException("the ClientHello probe could not listen", e);
        }
    }

    /**
     * The port the probe accepts on.
     *
     * @return the bound port
     */
    int port() {
        return listener.getLocalPort();
    }

    /**
     * The ClientHello the first connection sent, parsed in full.
     *
     * @param limit how long to wait for it
     * @return what it carried
     * @throws AssertionError if no connection arrived, or what arrived was not one complete
     *                        ClientHello
     */
    ClientHello awaitClientHello(Duration limit) {
        try {
            if (!done.await(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("no ClientHello reached the probe within " + limit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the ClientHello", e);
        }
        if (failure != null) {
            throw new AssertionError("the probe did not read one complete ClientHello: " + failure, failure);
        }
        return observed;
    }

    @Override
    public void close() {
        try {
            listener.close();
        } catch (IOException ignored) {
            // Best effort: the reading thread ends on the resulting SocketException.
        }
    }

    private void readOne() {
        try (Socket socket = listener.accept()) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            observed = parse(readClientHello(new DataInputStream(socket.getInputStream())));
        } catch (IOException | BufferUnderflowException | IllegalArgumentException e) {
            failure = e;
        } finally {
            close();
            done.countDown();
        }
    }

    /** Reassembles the first handshake message from as many handshake records as it spans. */
    private static byte[] readClientHello(DataInputStream in) throws IOException {
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        while (true) {
            int type = in.readUnsignedByte();
            if (type != RECORD_HANDSHAKE) {
                throw new ProtocolException("record type " + type + " arrived before the ClientHello was complete");
            }
            in.readUnsignedShort();
            byte[] fragment = new byte[in.readUnsignedShort()];
            in.readFully(fragment);
            handshake.write(fragment);
            byte[] bytes = handshake.toByteArray();
            if (bytes.length >= HANDSHAKE_HEADER_BYTES) {
                int length = (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | bytes[3] & 0xFF;
                if (length > MAX_CLIENT_HELLO_BYTES) {
                    throw new ProtocolException("handshake message declares " + length + " bytes");
                }
                if (bytes.length >= HANDSHAKE_HEADER_BYTES + length) {
                    if (bytes[0] != HANDSHAKE_CLIENT_HELLO) {
                        throw new ProtocolException("first handshake message is type " + bytes[0] + ", not ClientHello");
                    }
                    return Arrays.copyOfRange(bytes, HANDSHAKE_HEADER_BYTES, HANDSHAKE_HEADER_BYTES + length);
                }
            }
        }
    }

    /** Walks the ClientHello body to its last extension. */
    private static ClientHello parse(byte[] body) throws ProtocolException {
        ByteBuffer hello = ByteBuffer.wrap(body);
        hello.getShort();
        skip(hello, RANDOM_BYTES);
        skip(hello, Byte.toUnsignedInt(hello.get()));
        skip(hello, Short.toUnsignedInt(hello.getShort()));
        skip(hello, Byte.toUnsignedInt(hello.get()));
        int extensionsEnd = Short.toUnsignedInt(hello.getShort()) + hello.position();
        if (extensionsEnd != body.length) {
            throw new ProtocolException("extensions end at " + extensionsEnd + ", the message at " + body.length);
        }
        String serverName = null;
        boolean signatureAlgorithms = false;
        while (hello.position() < extensionsEnd) {
            int type = Short.toUnsignedInt(hello.getShort());
            ByteBuffer data = slice(hello, Short.toUnsignedInt(hello.getShort()));
            if (type == EXTENSION_SERVER_NAME) {
                serverName = hostName(data);
            } else if (type == EXTENSION_SIGNATURE_ALGORITHMS) {
                signatureAlgorithms = true;
            }
        }
        if (hello.position() != extensionsEnd) {
            throw new ProtocolException("the last extension overruns the extension block");
        }
        if (!signatureAlgorithms) {
            throw new ProtocolException("no signature_algorithms extension: not a certificate-authenticating ClientHello");
        }
        return new ClientHello(serverName);
    }

    private static String hostName(ByteBuffer extension) throws ProtocolException {
        ByteBuffer list = slice(extension, Short.toUnsignedInt(extension.getShort()));
        while (list.hasRemaining()) {
            int nameType = Byte.toUnsignedInt(list.get());
            ByteBuffer name = slice(list, Short.toUnsignedInt(list.getShort()));
            if (nameType == NAME_TYPE_HOST_NAME) {
                byte[] bytes = new byte[name.remaining()];
                name.get(bytes);
                return new String(bytes, StandardCharsets.US_ASCII);
            }
        }
        throw new ProtocolException("server_name extension carries no host_name entry");
    }

    private static ByteBuffer slice(ByteBuffer source, int length) {
        int start = source.position();
        skip(source, length);
        return source.slice(start, length);
    }

    private static void skip(ByteBuffer source, int length) {
        if (length > source.remaining()) {
            throw new BufferUnderflowException();
        }
        source.position(source.position() + length);
    }
}
