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
import static org.mockito.Mockito.mock;
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

    @Test
    @DisplayName("separatedSchema valid schema executes exact search_path SQL")
    void separatedSchemaValidSchemaExecutesExactSql() {
        when(storageContext.strategy()).thenReturn(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
        when(storageContext.schemaName()).thenReturn(Optional.of("tenant_01"));

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
        when(connection.prepare("SELECT set_config('exeris.tenant_id', ?, false)")).thenReturn(statement);
        when(statement.bindString(0, "tenant-key-01")).thenReturn(statement);
        QueryResult queryResult = mock(QueryResult.class);
        when(statement.executeQuery()).thenReturn(queryResult);

        RlsConnectionInterceptor.INSTANCE.onConnectionAcquired(connection, storageContext);

        verify(connection).prepare("SELECT set_config('exeris.tenant_id', ?, false)");
        verify(statement).bindString(0, "tenant-key-01");
    }
}
