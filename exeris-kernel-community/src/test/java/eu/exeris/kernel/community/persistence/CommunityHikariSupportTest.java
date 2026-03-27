/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommunityHikariSupport TLS/data-source properties")
class CommunityHikariSupportTest {

    @Test
    @DisplayName("useTls=true adds ssl=true and sslmode=require when not explicitly configured")
    void useTlsAddsSslDefaultsWhenUnset() {
        PersistenceConfig config = config(true, Map.of("applicationName", "exeris-test"));

        try (HikariDataSource pool = CommunityHikariSupport.buildPool(config, null)) {
            Properties properties = pool.getDataSourceProperties();
            assertThat(properties.getProperty("applicationName")).isEqualTo("exeris-test");
            assertThat(properties.getProperty("ssl")).isEqualTo("true");
            assertThat(properties.getProperty("sslmode")).isEqualTo("require");
        }
    }

    @Test
    @DisplayName("explicit ssl/sslmode are preserved when useTls=true")
    void explicitSslSettingsAreNotOverridden() {
        PersistenceConfig config = config(true, Map.of(
                "ssl", "false",
                "sslmode", "disable"
        ));

        try (HikariDataSource pool = CommunityHikariSupport.buildPool(config, null)) {
            Properties properties = pool.getDataSourceProperties();
            assertThat(properties.getProperty("ssl")).isEqualTo("false");
            assertThat(properties.getProperty("sslmode")).isEqualTo("disable");
        }
    }

    @Test
    @DisplayName("tenant pools use minimumIdle=0 to reduce retention")
    void tenantPoolsUseMinimumIdleZero() {
        PersistenceConfig config = config(false, Map.of());

        try (HikariDataSource sharedPool = CommunityHikariSupport.buildPool(config, null);
             HikariDataSource tenantPool = CommunityHikariSupport.buildPool(config, "tenant-a")) {
            assertThat(sharedPool.getMinimumIdle()).isEqualTo(config.minIdleConnections());
            assertThat(tenantPool.getMinimumIdle()).isZero();
        }
    }

    @Test
    @DisplayName("pool baseline uses autoCommit=true for read scopes")
    void poolBaselineUsesAutoCommitTrue() {
        PersistenceConfig config = config(false, Map.of());

        try (HikariDataSource pool = CommunityHikariSupport.buildPool(config, null)) {
            assertThat(pool.isAutoCommit()).isTrue();
        }
    }

    @Test
    @DisplayName("PostgreSQL sets defaultRowFetchSize=50 when absent")
    void postgresAddsDefaultRowFetchSizeWhenUnset() {
        PersistenceConfig config = postgresConfig(Map.of());
        HikariConfig hikariConfig = new HikariConfig();

        CommunityHikariSupport.applyDataSourceProperties(hikariConfig, config);

        assertThat(hikariConfig.getDataSourceProperties().getProperty("defaultRowFetchSize"))
                .isEqualTo("50");
    }

    @Test
    @DisplayName("PostgreSQL preserves explicit defaultRowFetchSize value")
    void postgresDoesNotOverrideExplicitDefaultRowFetchSize() {
        PersistenceConfig config = postgresConfig(Map.of("defaultRowFetchSize", "500"));
        HikariConfig hikariConfig = new HikariConfig();

        CommunityHikariSupport.applyDataSourceProperties(hikariConfig, config);

        assertThat(hikariConfig.getDataSourceProperties().getProperty("defaultRowFetchSize"))
                .isEqualTo("500");
    }

    @Test
    @DisplayName("Non-PostgreSQL URLs do not get defaultRowFetchSize")
    void nonPostgresDoesNotAddDefaultRowFetchSize() {
        PersistenceConfig config = config(false, Map.of());
        HikariConfig hikariConfig = new HikariConfig();

        CommunityHikariSupport.applyDataSourceProperties(hikariConfig, config);

        assertThat(hikariConfig.getDataSourceProperties().containsKey("defaultRowFetchSize"))
                .isFalse();
    }

    @Test
    @DisplayName("PostgreSQL check is case-insensitive")
    void postgresUrlCheckIsCaseInsensitive() {
        PersistenceConfig config = new PersistenceConfig(
                "JDBC:POSTGRESQL://localhost:5432/exeris",
                "sa",
                "",
                4,
                1,
                5_000L,
                60_000L,
                600_000L,
                false,
                false,
                false,
                0,
                Map.of()
        );
        HikariConfig hikariConfig = new HikariConfig();

        CommunityHikariSupport.applyDataSourceProperties(hikariConfig, config);

        assertThat(hikariConfig.getDataSourceProperties().getProperty("defaultRowFetchSize"))
                .isEqualTo("50");
    }

    private static PersistenceConfig config(boolean useTls, Map<String, String> properties) {
        return new PersistenceConfig(
                "jdbc:h2:mem:community_hikari_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,
                1,
                5_000L,
                60_000L,
                600_000L,
                false,
                false,
                useTls,
                0,
                properties
        );
    }

    private static PersistenceConfig postgresConfig(Map<String, String> properties) {
        return new PersistenceConfig(
                "jdbc:postgresql://localhost:5432/exeris",
                "sa",
                "",
                4,
                1,
                5_000L,
                60_000L,
                600_000L,
                false,
                false,
                false,
                0,
                properties
        );
    }
}
