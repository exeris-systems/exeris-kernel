/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.ArenaLoanedBuffer;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every fatal outcome of {@link OffHeapTlsEngine} empties the calling thread's OpenSSL error
 * queue exactly once, after reading {@code SSL_get_error}; a retry and a successful record leave
 * it alone.
 *
 * <p>The queue is per OS thread and {@code SSL_get_error} reads any entry on it as
 * {@code SSL_ERROR_SSL}, so one session's leftover entry turns the next session's
 * {@code WANT_READ} on the same thread into a fatal error. Each site is asserted on its own, in
 * both directions: a clear missing from a fatal site, and a clear added to a retry or success site
 * that pays a downcall on the hot path, are both failures here.
 */
@DisplayName("L1: OffHeapTlsEngine — OpenSSL error-queue discipline")
class OffHeapTlsEngineErrorQueueTest {

    private static final String CLEAR = "ERR_clear_error";
    private static final String GET_ERROR = "SSL_get_error";

    private static final MemoryAllocator ALLOC = new MemoryAllocator() {
        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return ArenaLoanedBuffer.allocateOwning(hint, 64);
        }

        @Override public LoanedBuffer allocateNetwork(int bytes)         { return allocate(AllocationHint.MEDIUM); }
        @Override public LoanedBuffer allocateCarrierSlab(int index)     { return allocate(AllocationHint.MEDIUM); }
        @Override public LoanedBuffer allocateInfrastructure(long bytes) { return allocate(AllocationHint.MEDIUM); }
        @Override public MemoryStats stats()                             { return MemoryStats.zero(); }
        @Override public void close()                                    { /* stateless */ }
    };

    private RecordingOpenSsl openSsl;
    private LoanedBuffer scratch;
    private LoanedBuffer scratch2;

    @BeforeEach
    void setUp() {
        openSsl = new RecordingOpenSsl();
        scratch = ALLOC.allocate(AllocationHint.MEDIUM);
        scratch2 = ALLOC.allocate(AllocationHint.MEDIUM);
    }

    @AfterEach
    void tearDown() {
        scratch.close();
        scratch2.close();
    }

    private OffHeapTlsEngine boundClient() {
        OffHeapTlsEngine engine = new OffHeapTlsEngine(openSsl.handles(), 0x1234L, false, ALLOC);
        engine.notifyBound();
        return engine;
    }

    private OffHeapTlsEngine activeClient() {
        OffHeapTlsEngine engine = boundClient();
        openSsl.connectResult = 1;
        assertThat(engine.beginHandshake(scratch)).isEqualTo(TlsStatus.FINISHED);
        openSsl.forget();
        return engine;
    }

    private void assertClearedOnceAfterTheErrorWasRead() {
        assertThat(openSsl.count(CLEAR))
                .as("one clear for one fatal outcome; calls were %s", openSsl.calls())
                .isEqualTo(1);
        assertThat(openSsl.indexOf(CLEAR))
                .as("the clear follows SSL_get_error, whose answer it would otherwise change; calls were %s",
                        openSsl.calls())
                .isGreaterThan(openSsl.indexOf(GET_ERROR));
    }

    private void assertNotCleared() {
        assertThat(openSsl.count(CLEAR))
                .as("a retry or a successful record leaves the queue alone; calls were %s", openSsl.calls())
                .isZero();
    }

    @Nested
    @DisplayName("beginHandshake")
    class Handshake {

        @ParameterizedTest(name = "SSL_get_error = {0}")
        @ValueSource(ints = {
                CoreOpenSslLoader.SSL_ERROR_SSL,
                CoreOpenSslLoader.SSL_ERROR_SYSCALL,
                CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN})
        @DisplayName("a client step that fails clears once, after SSL_get_error")
        void fatalClientStepClears(int sslError) {
            try (OffHeapTlsEngine engine = boundClient()) {
                openSsl.connectResult = -1;
                openSsl.errorResult = sslError;

                assertThat(engine.beginHandshake(scratch)).isEqualTo(TlsStatus.CLOSED);

                assertClearedOnceAfterTheErrorWasRead();
            }
        }

        @Test
        @DisplayName("a server step that fails clears once, after SSL_get_error")
        void fatalServerStepClears() {
            try (OffHeapTlsEngine engine = new OffHeapTlsEngine(openSsl.handles(), 0x1234L, true, ALLOC)) {
                engine.notifyBound();
                openSsl.acceptResult = -1;
                openSsl.errorResult = CoreOpenSslLoader.SSL_ERROR_SSL;

                assertThat(engine.beginHandshake(scratch)).isEqualTo(TlsStatus.CLOSED);

                assertClearedOnceAfterTheErrorWasRead();
            }
        }

        @ParameterizedTest(name = "SSL_get_error = {0}")
        @ValueSource(ints = {CoreOpenSslLoader.SSL_ERROR_WANT_READ, CoreOpenSslLoader.SSL_ERROR_WANT_WRITE})
        @DisplayName("a step that asks for more I/O does not clear")
        void retryDoesNotClear(int sslError) {
            try (OffHeapTlsEngine engine = boundClient()) {
                openSsl.connectResult = -1;
                openSsl.errorResult = sslError;

                assertThat(engine.beginHandshake(scratch)).isNotEqualTo(TlsStatus.CLOSED);

                assertNotCleared();
            }
        }

        @Test
        @DisplayName("a completed handshake clears once")
        void completedHandshakeClears() {
            try (OffHeapTlsEngine engine = boundClient()) {
                openSsl.connectResult = 1;

                assertThat(engine.beginHandshake(scratch)).isEqualTo(TlsStatus.FINISHED);

                assertThat(openSsl.count(CLEAR)).as("calls were %s", openSsl.calls()).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("unwrap")
    class Unwrap {

        @ParameterizedTest(name = "SSL_get_error = {0}")
        @ValueSource(ints = {
                CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN,
                CoreOpenSslLoader.SSL_ERROR_SSL,
                CoreOpenSslLoader.SSL_ERROR_SYSCALL})
        @DisplayName("a read that ends the session clears once, after SSL_get_error")
        void terminalReadClears(int sslError) {
            try (OffHeapTlsEngine engine = activeClient()) {
                openSsl.readResult = -1;
                openSsl.errorResult = sslError;

                assertThat(engine.unwrap(scratch, scratch2)).isEqualTo(TlsStatus.CLOSED);

                assertClearedOnceAfterTheErrorWasRead();
            }
        }

        @Test
        @DisplayName("a read that would block does not clear")
        void wouldBlockReadDoesNotClear() {
            try (OffHeapTlsEngine engine = activeClient()) {
                openSsl.readResult = -1;
                openSsl.errorResult = CoreOpenSslLoader.SSL_ERROR_WANT_READ;

                assertThat(engine.unwrap(scratch, scratch2)).isEqualTo(TlsStatus.NEED_UNWRAP);

                assertNotCleared();
            }
        }

        @Test
        @DisplayName("a read that returns bytes does not clear")
        void successfulReadDoesNotClear() {
            try (OffHeapTlsEngine engine = activeClient()) {
                openSsl.readResult = 5;

                assertThat(engine.unwrap(scratch, scratch2)).isEqualTo(TlsStatus.OK);

                assertNotCleared();
            }
        }
    }

    @Nested
    @DisplayName("wrap")
    class Wrap {

        @Test
        @DisplayName("a write that fails clears once, after SSL_get_error")
        void fatalWriteClears() {
            try (OffHeapTlsEngine engine = activeClient()) {
                openSsl.writeResult = -1;
                openSsl.errorResult = CoreOpenSslLoader.SSL_ERROR_SSL;

                assertThat(engine.wrap(scratch, scratch2)).isEqualTo(TlsStatus.CLOSED);

                assertClearedOnceAfterTheErrorWasRead();
            }
        }

        @Test
        @DisplayName("a write that would block does not clear")
        void wouldBlockWriteDoesNotClear() {
            try (OffHeapTlsEngine engine = activeClient()) {
                openSsl.writeResult = -1;
                openSsl.errorResult = CoreOpenSslLoader.SSL_ERROR_WANT_WRITE;

                assertThat(engine.wrap(scratch, scratch2)).isEqualTo(TlsStatus.NEED_WRAP);

                assertNotCleared();
            }
        }

        @Test
        @DisplayName("a write that sends bytes does not clear")
        void successfulWriteDoesNotClear() {
            try (OffHeapTlsEngine engine = activeClient()) {
                openSsl.writeResult = 7;

                assertThat(engine.wrap(scratch, scratch2)).isEqualTo(TlsStatus.OK);

                assertNotCleared();
            }
        }
    }

    @Nested
    @DisplayName("initiateShutdown")
    class Shutdown {

        @Test
        @DisplayName("a shutdown that fails clears once")
        void failedShutdownClears() {
            try (OffHeapTlsEngine engine = activeClient()) {
                openSsl.shutdownResult = -1;

                engine.initiateShutdown(scratch);

                assertThat(openSsl.count(CLEAR)).as("calls were %s", openSsl.calls()).isEqualTo(1);
                assertThat(openSsl.indexOf(CLEAR)).isGreaterThan(openSsl.indexOf("SSL_shutdown"));
            }
        }

        @ParameterizedTest(name = "SSL_shutdown = {0}")
        @ValueSource(ints = {0, 1})
        @DisplayName("a shutdown that sent or completed close_notify does not clear")
        void shutdownProgressDoesNotClear(int result) {
            try (OffHeapTlsEngine engine = activeClient()) {
                openSsl.shutdownResult = result;

                engine.initiateShutdown(scratch);

                assertNotCleared();
            }
        }
    }

    @Nested
    @DisplayName("bindTransportFd")
    class Bind {

        @Test
        @DisplayName("an SSL_set_fd that fails clears once")
        void failedBindClears() {
            try (OffHeapTlsEngine engine = new OffHeapTlsEngine(openSsl.handles(), 0x1234L, false, ALLOC)) {
                openSsl.setFdResult = 0;

                assertThat(engine.bindTransportFd(openSsl.sslSetFd(), 7)).isZero();

                assertThat(openSsl.count(CLEAR)).as("calls were %s", openSsl.calls()).isEqualTo(1);
                assertThat(openSsl.indexOf(CLEAR)).isGreaterThan(openSsl.indexOf("SSL_set_fd"));
            }
        }

        @Test
        @DisplayName("an SSL_set_fd that succeeds does not clear")
        void successfulBindDoesNotClear() {
            try (OffHeapTlsEngine engine = new OffHeapTlsEngine(openSsl.handles(), 0x1234L, false, ALLOC)) {
                openSsl.setFdResult = 1;

                assertThat(engine.bindTransportFd(openSsl.sslSetFd(), 7)).isEqualTo(1);

                assertNotCleared();
            }
        }
    }
}
