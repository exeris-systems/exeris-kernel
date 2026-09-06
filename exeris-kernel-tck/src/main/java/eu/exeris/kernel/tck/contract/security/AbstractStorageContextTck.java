/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.security;

import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.security.StorageContext.IsolationStrategy;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Config;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link StorageContext} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link StorageContext#isolationKey()} returns a correct isolation key</li>
 *   <li>{@link StorageContext#attributes()} returns an unmodifiable map</li>
 *   <li>Each strategy exposes exactly the fields that apply to it — {@code schemaName()}
 *       and {@code dataSourceKey()} are empty except under the one strategy that uses
 *       them</li>
 *   <li><b>JFR Zero-Allocation:</b> 1M calls to {@code createGlobal()} must produce
 *       zero {@code eu.exeris.*} heap allocations in the steady-state phase
 *       (verifies "No Waste Compute" for the GLOBAL singleton path)</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * {@snippet lang="java" :
 * class MyStorageContextTest extends AbstractStorageContextTck {
 *     @Override protected StorageContext createShared(String key) {
 *         return new ImmutableStorageContext(Optional.of(key), SHARED, ...);
 *     }
 * }
 * }
 *
 * @since 0.5
 */
public abstract class AbstractStorageContextTck {

    /**
     * Creates the contract; subclasses supply the per-strategy bindings via
     * {@link #createShared(String)}, {@link #createSeparatedSchema(String, String)},
     * {@link #createDedicated(String, String)} and {@link #createGlobal()}.
     */
    public AbstractStorageContextTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Creates a SHARED-strategy context with the given isolation key.
     *
     * @param isolationKey tenant isolation key
     * @return storage context instance
     */
    protected abstract StorageContext createShared(String isolationKey);

    /**
     * Creates a SEPARATED_SCHEMA context.
     *
     * @param isolationKey tenant isolation key
     * @param schemaName   schema name
     * @return storage context instance
     */
    protected abstract StorageContext createSeparatedSchema(String isolationKey, String schemaName);

    /**
     * Creates a DEDICATED context.
     *
     * @param isolationKey  tenant isolation key
     * @param dataSourceKey datasource routing key
     * @return storage context instance
     */
    protected abstract StorageContext createDedicated(String isolationKey, String dataSourceKey);

    /**
     * Creates a global (system) context with no tenant isolation.
     *
     * @return global storage context
     */
    protected abstract StorageContext createGlobal();

    // =========================================================================
    // Isolation Key
    // =========================================================================

    @Nested
    @DisplayName("isolationKey() contract")
    class IsolationKeyContract {

        @Test
        @DisplayName("SHARED context returns correct isolation key")
        void sharedReturnsIsolationKey() {
            StorageContext ctx = createShared("tenant-42");
            assertThat(ctx.isolationKey()).isPresent().contains("tenant-42");
        }

        @Test
        @DisplayName("SEPARATED_SCHEMA context returns correct isolation key")
        void separatedSchemaReturnsIsolationKey() {
            StorageContext ctx = createSeparatedSchema("tenant-99", "schema_99");
            assertThat(ctx.isolationKey()).isPresent().contains("tenant-99");
        }

        @Test
        @DisplayName("DEDICATED context returns correct isolation key")
        void dedicatedReturnsIsolationKey() {
            StorageContext ctx = createDedicated("tenant-enterprise", "ds-enterprise");
            assertThat(ctx.isolationKey()).isPresent().contains("tenant-enterprise");
        }

        @Test
        @DisplayName("Global context returns empty isolation key")
        void globalReturnsEmptyIsolationKey() {
            StorageContext ctx = createGlobal();
            assertThat(ctx.isolationKey()).isEmpty();
        }
    }

    // =========================================================================
    // Strategy
    // =========================================================================

    @Nested
    @DisplayName("strategy() contract")
    class StrategyContract {

        @Test
        @DisplayName("SHARED context reports SHARED strategy")
        void sharedStrategy() {
            assertThat(createShared("t").strategy()).isEqualTo(IsolationStrategy.SHARED);
        }

        @Test
        @DisplayName("SEPARATED_SCHEMA context reports correct strategy")
        void separatedSchemaStrategy() {
            assertThat(createSeparatedSchema("t", "s").strategy())
                    .isEqualTo(IsolationStrategy.SEPARATED_SCHEMA);
        }

        @Test
        @DisplayName("DEDICATED context reports correct strategy")
        void dedicatedStrategy() {
            assertThat(createDedicated("t", "ds").strategy())
                    .isEqualTo(IsolationStrategy.DEDICATED);
        }
    }

    // =========================================================================
    // Attributes immutability
    // =========================================================================

    @Nested
    @DisplayName("attributes() immutability contract")
    class AttributesContract {

        @Test
        @DisplayName("attributes() returns an unmodifiable map")
        void attributesAreUnmodifiable() {
            StorageContext ctx = createShared("tenant-1");
            Map<String, String> attrs = ctx.attributes();
            assertThatThrownBy(() -> attrs.put("rogue", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("attributes() never returns null")
        void attributesNeverNull() {
            assertThat(createShared("t").attributes()).isNotNull();
            assertThat(createGlobal().attributes()).isNotNull();
        }
    }

    // =========================================================================
    // Schema / DataSource field exclusivity
    // =========================================================================

    @Nested
    @DisplayName("Strategy-exclusive field rules")
    class FieldExclusivity {

        @Test
        @DisplayName("SHARED context has empty schemaName and dataSourceKey")
        void sharedFieldsEmpty() {
            StorageContext ctx = createShared("t");
            assertThat(ctx.schemaName()).isEmpty();
            assertThat(ctx.dataSourceKey()).isEmpty();
        }

        @Test
        @DisplayName("SEPARATED_SCHEMA has present schemaName, empty dataSourceKey")
        void separatedSchemaFields() {
            StorageContext ctx = createSeparatedSchema("t", "my_schema");
            assertThat(ctx.schemaName()).isPresent().contains("my_schema");
            assertThat(ctx.dataSourceKey()).isEmpty();
        }

        @Test
        @DisplayName("DEDICATED has present dataSourceKey, empty schemaName")
        void dedicatedFields() {
            StorageContext ctx = createDedicated("t", "ds-main");
            assertThat(ctx.dataSourceKey()).isPresent().contains("ds-main");
            assertThat(ctx.schemaName()).isEmpty();
        }
    }

    // =========================================================================
    // ImmutableStorageContext GLOBAL singleton
    // =========================================================================

    @Nested
    @DisplayName("GLOBAL singleton contract")
    class GlobalSingleton {

        @Test
        @DisplayName("GLOBAL is a valid global context")
        void globalIsValid() {
            StorageContext global = ImmutableStorageContext.GLOBAL;
            assertThat(global.isolationKey()).isEmpty();
            assertThat(global.strategy()).isEqualTo(IsolationStrategy.SHARED);
            assertThat(global.schemaName()).isEmpty();
            assertThat(global.dataSourceKey()).isEmpty();
            assertThat(global.attributes()).isEmpty();
        }
    }

    // =========================================================================
    // JFR: Zero-Allocation Monitor (StorageContext.global() path)
    // =========================================================================

    @Nested
    @DisplayName("JFR: StorageContext zero-allocation contract")
    class ZeroAllocationContract {

        @Test
        @DisplayName("1M calls to createGlobal(): zero eu.exeris.* heap allocations")
        void globalPathIsAllocationFree() throws IOException {
            Config config = Config.ofHighDensity("StorageContext", getClass().getSimpleName());

            Result result = JfrAllocationMonitor.measure(config, iterations -> {
                for (int i = 0; i < iterations; i++) {
                    StorageContext ctx = createGlobal();
                    // Force the JIT to treat ctx as live (prevent dead-code elimination)
                    if (ctx.strategy() == null) {
                        throw new AssertionError("unreachable");
                    }
                }
            });

            JfrAllocationMonitor.assertZeroExerisAllocations(result,
                    "StorageContext.global() — GLOBAL singleton must be allocation-free");
        }
    }
}






