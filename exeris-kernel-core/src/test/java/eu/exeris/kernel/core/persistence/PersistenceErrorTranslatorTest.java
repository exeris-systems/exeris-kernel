/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit Tests: {@link PersistenceErrorTranslator} — SQLSTATE → Exeris error code mapping.
 *
 * <h2>Coverage Strategy</h2>
 * <p>The {@link PersistenceProviderException} message is a static constant ("Persistence query
 * execution failure"). For SQLSTATE-driven failures created via the {@code queryFailed(...)}
 * factory, the semantic context lives in {@code rawArgs}: {@code rawArgs[0]} is the SQLSTATE
 * code, {@code rawArgs[1]} is the formatted detail hint string. Other factory methods define
 * different {@code rawArgs} layouts (for example {@code transportFailure(...)} uses
 * {@code [transportName, fileDescriptor, errno]}). This test class focuses on the SQLSTATE
 * mapping contract and only assumes the SQLSTATE-oriented {@code rawArgs} shape where
 * explicitly noted.
 *
 * @since 0.5.0
 */
@DisplayName("L1: PersistenceErrorTranslator — SQLSTATE mapping")
class PersistenceErrorTranslatorTest {

    // Helper: extract detail string from rawArgs[1]
    private static String detail(PersistenceProviderException ex) {
        Object[] args = ex.rawArgs();
        return args.length >= 2 ? String.valueOf(args[1]) : "";
    }

    // =========================================================================
    // Nested: Specific SQLSTATE codes
    // =========================================================================

    @Nested
    @DisplayName("Specific SQLSTATE Codes")
    class SpecificCodes {

        @Test
        @DisplayName("23505 UNIQUE_VIOLATION → EX_PERS_5003, rawArgs[0]=sqlState, detail contains 'unique constraint'")
        void uniqueViolation() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("23505", "users_email_key", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(ex.rawArgs()[0]).isEqualTo("23505");
            assertThat(detail(ex)).contains("unique constraint violated");
        }

        @Test
        @DisplayName("23503 FK_VIOLATION → EX_PERS_5003, detail contains 'foreign key constraint'")
        void foreignKeyViolation() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("23503", "orders_user_id_fkey", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("foreign key constraint violated");
        }

        @Test
        @DisplayName("23502 NOT_NULL_VIOLATION → EX_PERS_5003, detail contains 'not-null constraint'")
        void notNullViolation() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("23502", "column name", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("not-null constraint violated");
        }

        @Test
        @DisplayName("23514 CHECK_VIOLATION → EX_PERS_5003, detail contains 'check constraint'")
        void checkViolation() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("23514", "age_check", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("check constraint violated");
        }

        @Test
        @DisplayName("23P01 EXCLUSION_VIOLATION → EX_PERS_5003, detail contains 'exclusion constraint'")
        void exclusionViolation() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("23P01", "room_overlap_excl", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("exclusion constraint violated");
        }

        @Test
        @DisplayName("40001 SERIALIZATION_FAILURE → EX_PERS_5003, detail contains '[RETRYABLE]'")
        void serializationFailure() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("40001", "could not serialize", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("[RETRYABLE]").contains("serialization failure");
        }

        @Test
        @DisplayName("40P01 DEADLOCK_DETECTED → EX_PERS_5003, detail contains '[RETRYABLE]'")
        void deadlockDetected() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("40P01", "process deadlock", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("[RETRYABLE]").contains("deadlock detected");
        }

        @Test
        @DisplayName("57014 QUERY_CANCELLED → EX_PERS_5003, detail contains 'cancelled'")
        void queryCancelled() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("57014", "statement timeout", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("cancelled");
        }

        @Test
        @DisplayName("42P01 UNDEFINED_TABLE → EX_PERS_5003, detail contains 'undefined table'")
        void undefinedTable() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("42P01", "events", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("undefined table");
        }

        @Test
        @DisplayName("42703 UNDEFINED_COLUMN → EX_PERS_5003, detail contains 'undefined column'")
        void undefinedColumn() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("42703", "user_id", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("undefined column");
        }

        @Test
        @DisplayName("53300 TOO_MANY_CONNECTIONS → EX_PERS_5003 (queryFailed) with cause preserved")
        void tooManyConnections() {
            RuntimeException cause = new RuntimeException("pool exhausted");
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("53300", "all slots in use", cause);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(ex.getCause()).isSameAs(cause);
            assertThat(detail(ex)).contains("too many connections");
        }

        @Test
        @DisplayName("53100 DISK_FULL → EX_PERS_5003, detail contains 'disk full'")
        void diskFull() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("53100", "/var/data", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("disk full");
        }

        @Test
        @DisplayName("53200 OUT_OF_MEMORY → EX_PERS_5003, detail contains 'out of memory'")
        void outOfMemory() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("53200", "sort buffer", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("out of memory");
        }

        @Test
        @DisplayName("08006 CONN_FAILURE → EX_PERS_5003, rawArgs[0]=sqlState, detail contains 'connection failure'")
        void connectionFailure() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("08006", "connection dropped", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(ex.rawArgs()[0]).isEqualTo("08006");
            assertThat(detail(ex)).contains("connection failure");
        }

        @Test
        @DisplayName("08001 CONN_REFUSED → EX_PERS_5003, rawArgs[0]=sqlState, detail contains 'connection failure'")
        void connectionRefused() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("08001", "connection refused", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(ex.rawArgs()[0]).isEqualTo("08001");
            assertThat(detail(ex)).contains("connection failure");
        }

        @Test
        @DisplayName("28P01 INVALID_PASSWORD → EX_PERS_5004, rawArgs include SQLSTATE")
        void invalidPasswordMapsToAuthFailed() {
            PersistenceProviderException ex =
                PersistenceErrorTranslator.translate("28P01", "password authentication failed", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5004);
            assertThat(ex.rawArgs()[0]).isEqualTo("28P01");
            assertThat(ex.rawArgs()[1]).isEqualTo("password authentication failed");
        }
    }

    // =========================================================================
    // Nested: Class-Level Fallback
    // =========================================================================

    @Nested
    @DisplayName("Class-Level SQLSTATE Fallback")
    class ClassLevelFallback {

        @Test
        @DisplayName("Class 08 (unknown subcode) → EX_PERS_5003, rawArgs[0]=sqlState, detail contains 'connection failure'")
        void class08Unknown() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("08999", "network error", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(ex.rawArgs()[0]).isEqualTo("08999");
            assertThat(detail(ex)).contains("connection failure");
        }

        @Test
        @DisplayName("Class 23 (unknown subcode) → EX_PERS_5003, detail contains 'constraint violated'")
        void class23Unknown() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("23999", "constraint", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("constraint violated");
        }

        @Test
        @DisplayName("Class 25 (invalid tx state) → EX_PERS_5003, detail contains 'invalid transaction state'")
        void class25() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("25001", "SET TRANSACTION", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("invalid transaction state");
        }

        @Test
        @DisplayName("Class 28 (auth exception) → EX_PERS_5004")
        void class28() {
            PersistenceProviderException ex =
                PersistenceErrorTranslator.translate("28000", "invalid authorization specification", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5004);
            assertThat(ex.rawArgs()[0]).isEqualTo("28000");
            assertThat(ex.rawArgs()[1]).isEqualTo("invalid authorization specification");
        }

        @Test
        @DisplayName("Class 40 (unknown subcode) → EX_PERS_5003, detail contains '[RETRYABLE]'")
        void class40Unknown() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("40XXX", "tx aborted", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("[RETRYABLE]");
        }

        @Test
        @DisplayName("Class 42 (syntax error) → EX_PERS_5003, detail contains 'syntax error'")
        void class42() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("42601", "syntax error at position", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("syntax error");
        }

        @Test
        @DisplayName("Class 53 (unknown subcode) → EX_PERS_5003, detail contains 'insufficient'")
        void class53Unknown() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("53XXX", "no resources", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("insufficient server resources");
        }

        @Test
        @DisplayName("Class 57 (operator intervention) → EX_PERS_5003, detail contains 'operator'")
        void class57() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("57000", "admin cancelled", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("operator intervention");
        }

        @Test
        @DisplayName("Class 58 (system error) → EX_PERS_5003, rawArgs[0]=sqlState, detail contains 'system I/O error'")
        void class58() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("58000", "I/O error", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(ex.rawArgs()[0]).isEqualTo("58000");
            assertThat(detail(ex)).contains("system I/O error");
        }

        @Test
        @DisplayName("Class P0 (PL/pgSQL error) → EX_PERS_5003, detail contains 'PL/pgSQL error'")
        void classP0() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("P0001", "raise exception", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(detail(ex)).contains("PL/pgSQL error");
        }

        @Test
        @DisplayName("Completely unknown class → EX_PERS_5003 (default fallback)")
        void completelyUnknown() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("99999", "unknown error", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
        }
    }

    // =========================================================================
    // Nested: isRetryable predicate
    // =========================================================================

    @Nested
    @DisplayName("isRetryable() predicate")
    class IsRetryable {

        @ParameterizedTest(name = "isRetryable({0}) = true")
        @ValueSource(strings = {"40001", "40P01"})
        @DisplayName("40001 and 40P01 are retryable")
        void retryableCodes(String code) {
            assertThat(PersistenceErrorTranslator.isRetryable(code)).isTrue();
        }

        @ParameterizedTest(name = "isRetryable({0}) = false")
        @ValueSource(strings = {"23505", "42P01", "08006", "53300", "25001", ""})
        @DisplayName("Non-retryable codes return false")
        void nonRetryableCodes(String code) {
            assertThat(PersistenceErrorTranslator.isRetryable(code)).isFalse();
        }

        @Test
        @DisplayName("isRetryable(null) returns false — no NPE")
        void nullIsNotRetryable() {
            assertThat(PersistenceErrorTranslator.isRetryable(null)).isFalse();
        }
    }

    // =========================================================================
    // Nested: isConstraintViolation predicate
    // =========================================================================

    @Nested
    @DisplayName("isConstraintViolation() predicate")
    class IsConstraintViolation {

        @ParameterizedTest(name = "isConstraintViolation({0}) = true")
        @ValueSource(strings = {"23505", "23503", "23502", "23514", "23P01", "23000"})
        @DisplayName("Class 23 codes are constraint violations")
        void class23IsConstraint(String code) {
            assertThat(PersistenceErrorTranslator.isConstraintViolation(code)).isTrue();
        }

        @ParameterizedTest(name = "isConstraintViolation({0}) = false")
        @ValueSource(strings = {"40001", "42P01", "08006", "25001"})
        @DisplayName("Non-class-23 codes are not constraint violations")
        void notConstraint(String code) {
            assertThat(PersistenceErrorTranslator.isConstraintViolation(code)).isFalse();
        }

        @Test
        @DisplayName("isConstraintViolation(null) returns false — no NPE")
        void nullIsNotConstraint() {
            assertThat(PersistenceErrorTranslator.isConstraintViolation(null)).isFalse();
        }
    }

    // =========================================================================
    // Nested: Defensive null/short input handling
    // =========================================================================

    @Nested
    @DisplayName("Null / Short Input Safety")
    class NullShortInputSafety {

        @Test
        @DisplayName("translate(null, ...) returns EX_PERS_5003, rawArgs[0]='[unknown]' — no NPE")
        void nullSqlState() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate(null, "some detail", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
            assertThat(ex.rawArgs()).isNotEmpty();
            assertThat(ex.rawArgs()[0]).isEqualTo("[unknown]");
        }

        @Test
        @DisplayName("translate(\"X\", ...) (1-char) returns EX_PERS_5003 — no NPE")
        void singleCharSqlState() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("X", "short code", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5003);
        }

        @Test
        @DisplayName("translate with non-null cause preserves cause in exception")
        void causeIsPreserved() {
            RuntimeException cause = new RuntimeException("root cause");
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("40001", "details", cause);
            assertThat(ex.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("rawArgs[0] carries the sqlState — zero-alloc telemetry contract")
        void rawArgsCarrySqlState() {
            PersistenceProviderException ex =
                    PersistenceErrorTranslator.translate("23505", "email_unique", null);
            Object[] args = ex.rawArgs();
            assertThat(args).hasSizeGreaterThanOrEqualTo(2);
            assertThat(args[0]).isEqualTo("23505");
        }
    }
}

