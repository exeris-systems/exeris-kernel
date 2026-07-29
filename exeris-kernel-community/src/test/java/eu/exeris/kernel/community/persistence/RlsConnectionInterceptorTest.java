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
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        stubStatement(SQL_SCOPE_ONLY);

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
        verify(connection, never()).prepare(SQL_SCOPE_ONLY);
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

    @Test
    @DisplayName("dedicated strategy publishes the shared scope — orthogonal to pool-level routing")
    void dedicatedStrategyPublishesSharedScope() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.DEDICATED);
        when(storageContext.sharedScopeKey()).thenReturn(Optional.of("world-alpha"));
        stubStatement(SQL_SCOPE_ONLY);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(connection).prepare(SQL_SCOPE_ONLY);
        verify(statement).bindString(0, "world-alpha");
    }

    @Test
    @DisplayName("dedicated strategy clears the shared scope when none is declared")
    void dedicatedStrategyClearsSharedScopeWhenAbsent() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.DEDICATED);
        stubStatement(SQL_SCOPE_ONLY);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(statement)
                .bindString(0, "");
    }

    @Test
    @DisplayName("separatedSchema publishes the shared scope after the search_path switch")
    void separatedSchemaPublishesSharedScope() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
        when(storageContext.schemaName()).thenReturn(Optional.of("tenant_01"));
        when(storageContext.sharedScopeKey()).thenReturn(Optional.of("world-alpha"));
        stubStatement(SQL_SCOPE_ONLY);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(connection).executeUpdate("SET search_path TO tenant_01, public");
        verify(statement).bindString(0, "world-alpha");
    }
}
