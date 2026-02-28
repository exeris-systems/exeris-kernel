/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions.persistence;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: {@link PersistenceProviderException} rawArgs binary layouts.
 *
 * <h2>rawArgs Layouts Verified</h2>
 * <pre>
 * EX_PERS_5001 (bootstrapFailure):      [String providerName, String sanitizedUrl]
 * EX_PERS_5002 (connectionExhausted):   [String providerName, long timeoutMs, int activeConnections]
 * EX_PERS_5003 (queryFailed):           [String sqlState, String detail]
 * EX_PERS_5004 (authFailed):            [String mechanism, String serverMessage]
 * EX_PERS_5005 (transportFailure):      [String transportName, long fileDescriptor, int errno]
 * EX_PERS_5006 (interceptorInitFailed): [String interceptorClass, String isolationKey]
 * EX_PERS_5007 (noProviderAvailable):   [String message]
 * </pre>
 *
 * <h2>Security: URL Sanitization</h2>
 * <p>The bootstrapFailure factory MUST strip embedded {@code user:password@} userinfo
 * from connection URLs to prevent credential leakage into telemetry dumps.
 *
 * @since 0.5.0
 */
@DisplayName("L0: PersistenceProviderException rawArgs Binary Layouts")
class PersistenceProviderExceptionLayoutTest {

    // -----------------------------------------------------------------------
    // EX_PERS_5001 — bootstrapFailure (with security: URL sanitization)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("bootstrapFailure (EX_PERS_5001) — rawArgs[0]=providerName, [1]=sanitizedUrl")
    class BootstrapFailure {

        @Test
        @DisplayName("rawArgs[0]=providerName (String), rawArgs[1]=sanitizedUrl (String)")
        void rawArgsLayout() {
            PersistenceProviderException ex = PersistenceProviderException.bootstrapFailure(
                    "ExerisCommunity/JDBC", "postgresql://localhost:5432/exeris", null);

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5001);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("ExerisCommunity/JDBC");
            assertThat(raw[1]).isEqualTo("postgresql://localhost:5432/exeris");
        }

        @Test
        @DisplayName("Cause is preserved by reference")
        void causePreserved() {
            RuntimeException root = new RuntimeException("connection refused");
            PersistenceProviderException ex = PersistenceProviderException.bootstrapFailure(
                    "ExerisEnterprise/PgNative", "postgresql://localhost:5432/db", root);
            assertThat(ex.getCause()).isSameAs(root);
        }

        // ---- Credential sanitization contract ----

        @ParameterizedTest(name = "strips userinfo from: {0}")
        @DisplayName("rawArgs[1] MUST NOT contain embedded credentials (userinfo stripped)")
        @ValueSource(strings = {
                "postgresql://alice:s3cr3t@localhost:5432/exeris",
                "postgresql://admin:p%40ss%21@db.prod.example.com:5432/kernel",
                "jdbc:postgresql://svc_user:hunter2@10.0.0.1:5432/app",
                "postgresql://root:@localhost/db",
                "postgresql://user:pass@host:5432/db?ssl=true"
        })
        void credentialsAreStrippedFromRawArgs(String urlWithCredentials) {
            PersistenceProviderException ex = PersistenceProviderException.bootstrapFailure(
                    "TestProvider", urlWithCredentials, null);
            String sanitized = (String) ex.rawArgs()[1];
            assertThat(sanitized)
                    .as("rawArgs[1] must not contain 'user:pass@' pattern")
                    .doesNotMatch(".*://[^@]*:[^@]*@.*");
        }

        @Test
        @DisplayName("sanitized URL preserves host/port/database path")
        void sanitizedUrlPreservesStructure() {
            PersistenceProviderException ex = PersistenceProviderException.bootstrapFailure(
                    "ExerisEnterprise/PgNative",
                    "postgresql://svc_user:s3cr3t@db.internal:5432/exeris_prod",
                    null);
            assertThat((String) ex.rawArgs()[1]).isEqualTo("postgresql://db.internal:5432/exeris_prod");
        }

        @Test
        @DisplayName("null connectionUrl maps to sentinel '[null]' — no NullPointerException")
        void nullUrlDoesNotThrow() {
            PersistenceProviderException ex = PersistenceProviderException.bootstrapFailure(
                    "ExerisCommunity/JDBC", null, null);
            assertThat(ex.rawArgs()[1]).isEqualTo("[null]");
        }

        @Test
        @DisplayName("URL without userinfo is passed through unchanged")
        void cleanUrlPassedThrough() {
            String clean = "postgresql://localhost:5432/mydb";
            PersistenceProviderException ex = PersistenceProviderException.bootstrapFailure(
                    "ExerisCommunity/JDBC", clean, null);
            assertThat(ex.rawArgs()[1]).isEqualTo(clean);
        }
    }

    // -----------------------------------------------------------------------
    // EX_PERS_5002 — connectionExhausted
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("connectionExhausted (EX_PERS_5002)")
    class ConnectionExhausted {

        @Test
        @DisplayName("rawArgs[0]=providerName, [1]=timeoutMs (long), [2]=activeConnections (int)")
        void rawArgsLayout() {
            PersistenceProviderException ex = PersistenceProviderException.connectionExhausted(
                    "ExerisCommunity/JDBC", 30_000L, 20);

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5002);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(3);
            assertThat(raw[0]).isEqualTo("ExerisCommunity/JDBC");
            assertThat(raw[1]).isEqualTo(30_000L);
            assertThat(raw[2]).isEqualTo(20);
        }

        @Test
        @DisplayName("rawArgs[1] is Long type — binary serializer contract")
        void slot1IsLong() {
            PersistenceProviderException ex = PersistenceProviderException.connectionExhausted("P", 1000L, 5);
            assertThat(ex.rawArgs()[1]).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("rawArgs[2] is Integer type — binary serializer contract")
        void slot2IsInteger() {
            PersistenceProviderException ex = PersistenceProviderException.connectionExhausted("P", 1000L, 5);
            assertThat(ex.rawArgs()[2]).isInstanceOf(Integer.class);
        }

        @Test
        @DisplayName("getCause() is null — connectionExhausted has no cause by design")
        void noCause() {
            PersistenceProviderException ex = PersistenceProviderException.connectionExhausted("P", 1L, 1);
            assertThat(ex.getCause()).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // EX_PERS_5003 — queryFailed
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("queryFailed (EX_PERS_5003) — rawArgs[0]=sqlState, [1]=detail")
    class QueryFailed {

        @Test
        @DisplayName("rawArgs[0]=sqlState (String), rawArgs[1]=detail (String)")
        void rawArgsLayout() {
            PersistenceProviderException ex = PersistenceProviderException.queryFailed(
                    "42P01", "relation \"users\" does not exist", new RuntimeException("io"));

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("42P01");
            assertThat(raw[1]).isEqualTo("relation \"users\" does not exist");
        }

        @Test
        @DisplayName("Cause is preserved by reference")
        void causePreserved() {
            RuntimeException root = new RuntimeException("io error");
            PersistenceProviderException ex = PersistenceProviderException.queryFailed("00000", "ok", root);
            assertThat(ex.getCause()).isSameAs(root);
        }
    }

    // -----------------------------------------------------------------------
    // EX_PERS_5004 — authFailed
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("authFailed (EX_PERS_5004) — rawArgs[0]=mechanism, [1]=serverMessage")
    class AuthFailed {

        @Test
        @DisplayName("rawArgs[0]=mechanism (String), rawArgs[1]=serverMessage (String)")
        void rawArgsLayout() {
            PersistenceProviderException ex = PersistenceProviderException.authFailed(
                    "SCRAM-SHA-256", "password authentication failed for user \"svc\"");

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5004);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("SCRAM-SHA-256");
            assertThat(raw[1]).isEqualTo("password authentication failed for user \"svc\"");
        }

        @Test
        @DisplayName("getCause() is null — authFailed has no cause by design")
        void noCause() {
            PersistenceProviderException ex = PersistenceProviderException.authFailed("MD5", "bad password");
            assertThat(ex.getCause()).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // EX_PERS_5005 — transportFailure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("transportFailure (EX_PERS_5005)")
    class TransportFailure {

        @Test
        @DisplayName("rawArgs[0]=transportName (String), [1]=fileDescriptor (long), [2]=errno (int)")
        void rawArgsLayout() {
            RuntimeException root = new RuntimeException("broken pipe");
            PersistenceProviderException ex = PersistenceProviderException.transportFailure(
                    "NativeTransport", 42L, 32, root);

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5005);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(3);
            assertThat(raw[0]).isEqualTo("NativeTransport");
            assertThat(raw[1]).isEqualTo(42L);
            assertThat(raw[2]).isEqualTo(32);
            assertThat(ex.getCause()).isSameAs(root);
        }

        @Test
        @DisplayName("rawArgs[1] is Long, rawArgs[2] is Integer — type contract")
        void rawArgTypes() {
            PersistenceProviderException ex = PersistenceProviderException.transportFailure(
                    "T", 1L, 1, null);
            assertThat(ex.rawArgs()[1]).isInstanceOf(Long.class);
            assertThat(ex.rawArgs()[2]).isInstanceOf(Integer.class);
        }
    }

    // -----------------------------------------------------------------------
    // EX_PERS_5006 — interceptorInitFailed
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("interceptorInitFailed (EX_PERS_5006)")
    class InterceptorInitFailed {

        @Test
        @DisplayName("rawArgs[0]=interceptorClass (String), [1]=isolationKey (String)")
        void rawArgsLayout() {
            RuntimeException root = new RuntimeException("RLS setup failed");
            PersistenceProviderException ex = PersistenceProviderException.interceptorInitFailed(
                    "RlsInjectionInterceptor", "tenant-abc-123", root);

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5006);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("RlsInjectionInterceptor");
            assertThat(raw[1]).isEqualTo("tenant-abc-123");
            assertThat(ex.getCause()).isSameAs(root);
        }

        @Test
        @DisplayName("System-scope operations use '[none]' sentinel as isolationKey")
        void systemScopeSentinel() {
            PersistenceProviderException ex = PersistenceProviderException.interceptorInitFailed(
                    "AuditInterceptor", "[none]", null);
            assertThat(ex.rawArgs()[1]).isEqualTo("[none]");
        }
    }

    // -----------------------------------------------------------------------
    // EX_PERS_5007 — noProviderAvailable
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("noProviderAvailable (EX_PERS_5007) — rawArgs[0]=message")
    class NoProviderAvailable {

        @Test
        @DisplayName("rawArgs[0]=message (String), errorCode=EX_PERS_5007")
        void rawArgsLayout() {
            PersistenceProviderException ex = PersistenceProviderException.noProviderAvailable(
                    "Add exeris-kernel-community to runtime deps");

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5007);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(1);
            assertThat(raw[0]).isEqualTo("Add exeris-kernel-community to runtime deps");
        }
    }

    // -----------------------------------------------------------------------
    // Cross-cutting: always unchecked
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("All PersistenceProviderException variants are RuntimeException (unchecked)")
    void allVariantsUnchecked() {
        assertThat(PersistenceProviderException.bootstrapFailure("P", "url", null))
                .isInstanceOf(RuntimeException.class);
        assertThat(PersistenceProviderException.connectionExhausted("P", 1L, 1))
                .isInstanceOf(RuntimeException.class);
        assertThat(PersistenceProviderException.queryFailed("00000", "d", null))
                .isInstanceOf(RuntimeException.class);
        assertThat(PersistenceProviderException.authFailed("MD5", "bad"))
                .isInstanceOf(RuntimeException.class);
        assertThat(PersistenceProviderException.transportFailure("T", 1L, 1, null))
                .isInstanceOf(RuntimeException.class);
        assertThat(PersistenceProviderException.interceptorInitFailed("I", "[none]", null))
                .isInstanceOf(RuntimeException.class);
        assertThat(PersistenceProviderException.noProviderAvailable("no provider"))
                .isInstanceOf(RuntimeException.class);
    }
}

