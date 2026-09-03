/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence.jdbc;

import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SQL placeholder-translation cache bound is configuration, not a constant.
 *
 * <p>It was a private {@code 1024} with no way to address it, and the cache it bounds
 * <b>never evicts</b> — so an application with more distinct statements than the bound keeps the
 * earliest ones it happened to see, not the hottest, and re-translates everything else on every
 * call. Raising the bound is the only lever, and until v0.12 there was none.
 *
 * <p>These cases drive the resolver rather than {@code translateParams}, deliberately: translation
 * is deterministic, so a cached and an uncached result are the same string and the bound is
 * invisible from the output. A test that went through the output would assert nothing.
 */
@DisplayName("JdbcPersistenceConnection — the SQL translation cache bound is configurable")
class JdbcSqlTranslationCacheConfigTest {

    private static final String KEY = "persistence.sqlTranslationCacheMaxEntries";

    @Nested
    @DisplayName("resolved from configuration")
    class FromConfig {

        @Test
        @DisplayName("a bound provider supplies the limit")
        void providerSuppliesLimit() {
            int resolved = withConfig(Map.of(KEY, 8_192),
                    JdbcPersistenceConnection::resolveSqlTranslationCacheMaxEntries);
            assertThat(resolved).isEqualTo(8_192);
        }

        @Test
        @DisplayName("0 is honoured, because disabling a cache is a coherent setting")
        void zeroDisablesCaching() {
            int resolved = withConfig(Map.of(KEY, 0),
                    JdbcPersistenceConnection::resolveSqlTranslationCacheMaxEntries);
            assertThat(resolved)
                    .as("a workload with unbounded statement variety pays for a cache that never "
                            + "hits; 0 must mean off, not 'fall back to the default'")
                    .isZero();
        }

        @Test
        @DisplayName("a negative value is refused, not quietly replaced by the default")
        void negativeIsRefused() {
            assertThatThrownBy(() -> withConfig(Map.of(KEY, -1),
                    JdbcPersistenceConnection::resolveSqlTranslationCacheMaxEntries))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(KEY);
        }

        @Test
        @DisplayName("an absent key yields the documented default")
        void absentKeyYieldsDefault() {
            int resolved = withConfig(Map.of(),
                    JdbcPersistenceConnection::resolveSqlTranslationCacheMaxEntries);
            assertThat(resolved)
                    .isEqualTo(JdbcPersistenceConnection.DEFAULT_SQL_TRANSLATION_CACHE_MAX_ENTRIES);
        }
    }

    @Nested
    @DisplayName("with no provider bound")
    class WithoutConfig {

        @Test
        @DisplayName("the default applies — a driver built outside a boot must still translate")
        void defaultAppliesUnbound() {
            assertThat(JdbcPersistenceConnection.resolveSqlTranslationCacheMaxEntries())
                    .isEqualTo(JdbcPersistenceConnection.DEFAULT_SQL_TRANSLATION_CACHE_MAX_ENTRIES);
        }
    }

    private static <T> T withConfig(Map<String, Integer> ints, java.util.function.Supplier<T> body) {
        return ScopedValue.where(KernelProviders.CURRENT_CONFIG, MapConfigProvider.ofInts(ints))
                .call(body::get);
    }
}
