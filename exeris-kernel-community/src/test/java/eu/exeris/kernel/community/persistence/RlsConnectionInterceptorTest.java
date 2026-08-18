/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RlsConnectionInterceptor")
class RlsConnectionInterceptorTest {

    @Mock
    private PersistenceConnection connection;

    @Mock
    private StorageContext storageContext;

    @Mock
    private PersistenceStatement statement;

    private static final String SQL_TENANT_AND_SCOPE =
            "SELECT set_config('exeris.tenant_id', ?, false), set_config('exeris.shared_scope', ?, false)";
    /**
     * The retired scope-only statement. SEPARATED_SCHEMA and DEDICATED used to issue this, which is
     * exactly how they left {@code exeris.tenant_id} at the previous borrower's value. Kept here so
     * {@link #noStrategyPublishesScopeWithoutTenant} can assert nothing brings it back.
     */
    private static final String SQL_SCOPE_ONLY =
            "SELECT set_config('exeris.shared_scope', ?, false)";

    /** Stubs the prepare/bind/execute chain for {@code sql}, returning the shared statement mock. */
    private void stubStatement(String sql) {
        when(connection.prepare(sql)).thenReturn(statement);
        when(statement.bindString(anyInt(), anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(mock(QueryResult.class));
    }

    @Test
    @DisplayName("separatedSchema valid schema executes exact search_path SQL")
    void separatedSchemaValidSchemaExecutesExactSql() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
        when(storageContext.schemaName()).thenReturn(Optional.of("tenant_01"));
        stubStatement(SQL_TENANT_AND_SCOPE);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(connection).executeUpdate("SET search_path TO tenant_01, public");
    }

    @Test
    @DisplayName("separatedSchema blank schema throws EX-PERS-5006")
    void separatedSchemaBlankSchemaThrowsPersistenceProviderException() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
        when(storageContext.schemaName()).thenReturn(Optional.of("   "));
        when(storageContext.isolationKey()).thenReturn(Optional.of("tenant-01"));

        assertThatThrownBy(() -> RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext))
                .isInstanceOf(PersistenceProviderException.class)
                .satisfies(ex -> {
                    PersistenceProviderException ppe = (PersistenceProviderException) ex;
                    assertThat(ppe.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5006);
                });
    }

    @Test
    @DisplayName("separatedSchema invalid identifier throws EX-PERS-5006")
    void separatedSchemaInvalidIdentifierThrowsPersistenceProviderException() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
        when(storageContext.schemaName()).thenReturn(Optional.of("1bad"));
        when(storageContext.isolationKey()).thenReturn(Optional.of("tenant-01"));

        assertThatThrownBy(() -> RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext))
                .isInstanceOf(PersistenceProviderException.class)
                .satisfies(ex -> {
                    PersistenceProviderException ppe = (PersistenceProviderException) ex;
                    assertThat(ppe.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5006);
                });
    }

    @Test
    @DisplayName("shared strategy sets tenant key via set_config prepare/bind path")
    void sharedStrategyUsesPrepareAndBindString() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.SHARED);
        when(storageContext.isolationKey()).thenReturn(Optional.of("tenant-key-01"));
        stubStatement(SQL_TENANT_AND_SCOPE);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(connection).prepare(SQL_TENANT_AND_SCOPE);
        verify(statement).bindString(0, "tenant-key-01");
    }

    // =========================================================================
    // Tenant key — published on every strategy, not just the one that reads it
    // =========================================================================

    @ParameterizedTest
    @EnumSource(StorageContext.IsolationStrategy.class)
    @DisplayName("every strategy republishes exeris.tenant_id, whatever its own isolation rests on")
    void everyStrategyRepublishesTenantId(StorageContext.IsolationStrategy strategy) {
        when(storageContext.strategy()).thenReturn(strategy);
        when(storageContext.isolationKey()).thenReturn(Optional.of("tenant-key-01"));
        if (strategy == StorageContext.IsolationStrategy.SEPARATED_SCHEMA) {
            when(storageContext.schemaName()).thenReturn(Optional.of("tenant_01"));
        }
        stubStatement(SQL_TENANT_AND_SCOPE);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        // SEPARATED_SCHEMA and DEDICATED do not rely on exeris.tenant_id for their own isolation, and
        // that is precisely why leaving it unpublished is unsafe: with perTenantPooling at its default
        // of false they are served from the pool a SHARED request last used, and set_config(..., false)
        // is session-scoped. An RLS policy on any table they touch would read the previous tenant's key.
        verify(statement).bindString(0, "tenant-key-01");
    }

    @Test
    @DisplayName("the published session keys are the SPI constants, not a parallel spelling")
    void publishedKeysAreTheSpiConstants() {
        // The policy that reads these keys lives in the deployment's own migrations, which the kernel
        // does not ship and cannot introspect -- so the name is a contract with no compiler across it.
        // SQL_TENANT_AND_SCOPE above pins the exact statement; this pins that the statement is built
        // from the constants a generator or migration tool would reference. Drift either way fails one
        // of the two, which is the point of having both.
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.SHARED);
        when(storageContext.isolationKey()).thenReturn(Optional.of("tenant-key-01"));
        // Deliberately NOT stubbed on the expected SQL: every other test here pins the statement by
        // matching it, so a drifted key fails them as a Mockito stubbing error. This one accepts any
        // statement and then reads it, so the same drift fails here as an assertion that names the key.
        when(connection.prepare(anyString())).thenReturn(statement);
        when(statement.bindString(anyInt(), anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(mock(QueryResult.class));

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        ArgumentCaptor<String> issued = ArgumentCaptor.forClass(String.class);
        verify(connection).prepare(issued.capture());
        assertThat(issued.getValue())
                .as("the tenant key the interceptor actually issues must be the SPI constant")
                .contains("set_config('" + ConnectionInterceptor.SESSION_KEY_TENANT_ID + "'")
                .as("and so must the shared scope")
                .contains("set_config('" + ConnectionInterceptor.SESSION_KEY_SHARED_SCOPE + "'");
    }

    @ParameterizedTest
    @EnumSource(StorageContext.IsolationStrategy.class)
    @DisplayName("no strategy publishes the shared scope without the tenant key")
    void noStrategyPublishesScopeWithoutTenant(StorageContext.IsolationStrategy strategy) {
        when(storageContext.strategy()).thenReturn(strategy);
        if (strategy == StorageContext.IsolationStrategy.SEPARATED_SCHEMA) {
            when(storageContext.schemaName()).thenReturn(Optional.of("tenant_01"));
        }
        stubStatement(SQL_TENANT_AND_SCOPE);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        // The two settings travel in one statement. A branch that reaches for the scope-only statement
        // is a branch that decided the tenant key was somebody else's problem.
        verify(connection, never()).prepare(SQL_SCOPE_ONLY);
    }

    // =========================================================================
    // Shared scope — ADR-012 §4b row visibility
    // =========================================================================

    @Test
    @DisplayName("shared strategy publishes a declared shared scope alongside the tenant key")
    void sharedStrategyPublishesDeclaredSharedScope() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.SHARED);
        when(storageContext.isolationKey()).thenReturn(Optional.of("tenant-key-01"));
        when(storageContext.sharedScopeKey()).thenReturn(Optional.of("world-alpha"));
        stubStatement(SQL_TENANT_AND_SCOPE);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(statement).bindString(0, "tenant-key-01");
        verify(statement).bindString(1, "world-alpha");
    }

    @Test
    @DisplayName("absent shared scope is published as \"\" — a stale one must not survive pool recycle")
    void absentSharedScopeIsClearedNotSkipped() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.SHARED);
        when(storageContext.isolationKey()).thenReturn(Optional.of("tenant-key-01"));
        stubStatement(SQL_TENANT_AND_SCOPE);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(statement)
                .bindString(1, "");
    }

    // =========================================================================
    // search_path — pool-recycle isolation (ADR-012)
    // =========================================================================

    @Test
    @DisplayName("shared strategy resets search_path — a SEPARATED_SCHEMA request may have set it")
    void sharedStrategyResetsSearchPath() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.SHARED);
        when(storageContext.isolationKey()).thenReturn(Optional.of("tenant-key-01"));
        stubStatement(SQL_TENANT_AND_SCOPE);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        // Unconditional: with persistence.perTenantPooling defaulting to false, SHARED and
        // SEPARATED_SCHEMA are served from one pool, and SET search_path is session-scoped — so
        // skipping this when the context declares no schema inherits the previous tenant's.
        verify(connection).executeUpdate("RESET search_path");
    }

    @Test
    @DisplayName("dedicated strategy resets search_path for the same pool-recycle reason")
    void dedicatedStrategyResetsSearchPath() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.DEDICATED);
        stubStatement(SQL_TENANT_AND_SCOPE);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(connection).executeUpdate("RESET search_path");
    }

    @Test
    @DisplayName("separatedSchema does not reset — its own SET overwrites whatever was there")
    void separatedSchemaDoesNotResetSearchPath() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
        when(storageContext.schemaName()).thenReturn(Optional.of("tenant_01"));
        stubStatement(SQL_TENANT_AND_SCOPE);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(connection, never()).executeUpdate("RESET search_path");
        verify(connection).executeUpdate("SET search_path TO tenant_01, public");
    }

    @Test
    @DisplayName("dedicated strategy publishes the shared scope — orthogonal to pool-level routing")
    void dedicatedStrategyPublishesSharedScope() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.DEDICATED);
        when(storageContext.sharedScopeKey()).thenReturn(Optional.of("world-alpha"));
        stubStatement(SQL_TENANT_AND_SCOPE);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(connection).prepare(SQL_TENANT_AND_SCOPE);
        verify(statement).bindString(1, "world-alpha");
    }

    @Test
    @DisplayName("dedicated strategy clears the shared scope when none is declared")
    void dedicatedStrategyClearsSharedScopeWhenAbsent() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.DEDICATED);
        stubStatement(SQL_TENANT_AND_SCOPE);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(statement)
                .bindString(1, "");
    }

    @Test
    @DisplayName("separatedSchema publishes the shared scope after the search_path switch")
    void separatedSchemaPublishesSharedScope() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
        when(storageContext.schemaName()).thenReturn(Optional.of("tenant_01"));
        when(storageContext.sharedScopeKey()).thenReturn(Optional.of("world-alpha"));
        stubStatement(SQL_TENANT_AND_SCOPE);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(connection).executeUpdate("SET search_path TO tenant_01, public");
        verify(statement).bindString(1, "world-alpha");
    }
}
