/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

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
}
