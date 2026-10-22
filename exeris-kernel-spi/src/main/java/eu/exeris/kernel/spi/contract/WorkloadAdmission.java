/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.contract;

/**
 * Runtime half of the developer-licence workload envelope: the gate every server-side HTTP and
 * transport driver consults to admit a connection or a request, and the marker it stamps on every
 * response while the gate is active.
 *
 * <p>The kernel binds an instance in {@link ContractKernelProviders#WORKLOAD_ADMISSION}. A driver
 * reads it <b>once</b>, in {@code start()}, which runs inside the kernel scope, and keeps the
 * reference in a final field. Reading it later, on an I/O or request thread, is a defect: a thread
 * that is not a descendant of the kernel scope sees no binding, so the driver would silently fall back
 * to {@link #UNGATED} and the envelope would not be enforced.
 *
 * <h2>Driver obligations</h2>
 * <p>They apply only while {@link #enforced()} is {@code true}; under {@link #UNGATED} a driver makes no
 * admission call and writes no marker, so a kernel that is not under a developer licence pays one
 * boolean read at start and nothing afterwards.
 * <ol>
 *   <li><b>Connections.</b> Every inbound connection accepted on a kernel-owned listener counts: HTTP/1,
 *       cleartext HTTP/2, TLS, WebSocket (it shares the HTTP listener) and each QUIC connection.
 *       Outbound client connections do not count. When {@link #tryAdmitConnection()} returns
 *       {@code false} the driver closes the connection right after accepting it, before it writes a byte
 *       and before any TLS handshake starts. {@link #connectionClosed()} is called exactly once for
 *       every admitted connection, on every close path: error, idle timeout and drain included.</li>
 *   <li><b>Requests.</b> {@link #tryAdmitRequest()} is called once per HTTP request (one HTTP/1
 *       message, one HTTP/2 or HTTP/3 stream) before route lookup or any handler runs. A driver that answers the
 *       request of an h2c upgrade on stream 1 gates that request too; a driver that never serves it has
 *       nothing to gate, and only its {@code 101} carries the marker. A refused
 *       request is answered {@code 429 Too Many Requests} with {@code Retry-After: 1} and an empty body.
 *       HTTP/1 keeps the connection alive; HTTP/2 answers with HEADERS carrying END_STREAM, never
 *       RST_STREAM, so the client reads a response rather than a transport error.</li>
 *   <li><b>Marker.</b> Every final response (status 200 or above, including kernel-generated 4xx and
 *       5xx, the {@code 429} above and the head of a streaming or server-sent-events response) and every
 *       {@code 101 Switching Protocols} carries {@value #LICENCE_HEADER}: {@value #DEVELOPMENT_MARKER}.
 *       Interim 1xx responses other than 101 are exempt. The driver writes the header after the
 *       handler's own headers and drops any handler-supplied header of the same name, compared without
 *       regard to case and with surrounding whitespace stripped (an intermediary strips it, so a name
 *       padded with spaces reaches the client as the same field), so a handler can neither remove the
 *       marker nor change its value. No configuration key disables it.</li>
 * </ol>
 *
 * <p><b>Allocation:</b> an implementation allocates nothing on any admission call.
 * <p><b>Thread confinement:</b> any thread; every method is safe for concurrent use.
 * <p><b>Ownership:</b> the kernel bootstrapper owns the instance; a driver borrows it for the life of
 * its engine.
 *
 * @implSpec An implementation that refuses must make the refusal observable once through the
 *           contract's violation reporting rather than once per refused call.
 * @since 0.13
 */
public interface WorkloadAdmission {

    /** Name of the response header that marks a developer-licensed kernel. */
    String LICENCE_HEADER = "Exeris-License";

    /** Value of {@link #LICENCE_HEADER} under a developer licence. */
    String DEVELOPMENT_MARKER = "development";

    /**
     * The gate of a kernel that runs under no developer licence: it admits everything and is not
     * {@linkplain #enforced() enforced}.
     */
    WorkloadAdmission UNGATED = new WorkloadAdmission() {
        @Override
        public boolean enforced() {
            return false;
        }

        @Override
        public boolean tryAdmitConnection() {
            return true;
        }

        @Override
        public void connectionClosed() {
            // Nothing was counted, so nothing is released.
        }

        @Override
        public boolean tryAdmitRequest() {
            return true;
        }
    };

    /**
     * Whether the envelope is in force. A driver reads this once in {@code start()} and makes no other
     * call, and writes no marker, when it is {@code false}.
     *
     * @return {@code true} only under a developer-licence contract
     */
    boolean enforced();

    /**
     * Admits one inbound connection if the connection ceiling allows it. A {@code true} result must be
     * balanced by exactly one {@link #connectionClosed()}.
     *
     * @return {@code true} if the connection may proceed, {@code false} if the driver must close it
     */
    boolean tryAdmitConnection();

    /**
     * Releases one connection previously admitted by {@link #tryAdmitConnection()}.
     */
    void connectionClosed();

    /**
     * Admits one HTTP request if the throughput ceiling allows it.
     *
     * @return {@code true} if the request may be dispatched, {@code false} if it must be answered
     *         {@code 429}
     */
    boolean tryAdmitRequest();
}
