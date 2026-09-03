/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.config;

import eu.exeris.kernel.tck.contract.bootstrap.AbstractDynamicConfigRegistryTck;
import org.junit.jupiter.api.DisplayName;

/** Core binding for {@link AbstractDynamicConfigRegistryTck}. */
@DisplayName("Core: KernelConfigRegistry TCK")
class CoreDynamicConfigRegistryTckTest extends AbstractDynamicConfigRegistryTck {

    @Override
    protected DynamicConfigRegistryAdapter createRegistry() {
        KernelConfigRegistry registry = new KernelConfigRegistry();
        return new DynamicConfigRegistryAdapter() {
            @Override
            public void register(String file, String key, java.util.function.Consumer<String> callback) {
                registry.register(file, key, callback);
            }

            @Override
            public void seal() {
                registry.seal();
            }

            @Override
            public void fireReload(String file, String key, String value) {
                registry.fireReload(file, key, value);
            }
        };
    }
}

