/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.http.CommunityHttpProvider;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.tck.contract.http.AbstractHttpClientEngineTck;
import org.junit.jupiter.api.DisplayName;

/**
 * The client engine contract, bound to the engine a booted Community kernel publishes as
 * {@code HttpKernelProviders.HTTP_CLIENT_ENGINE}.
 *
 * <p>{@link CommunityHttpSubsystem} binds a {@link DeferredHttpClientEngine} over the discovered
 * {@code HttpProvider}, which on a Community classpath is {@link CommunityHttpProvider}. An
 * application reaches that wrapper, not the provider's engine, so the wrapper answers the contract
 * itself: in its CREATED state, where it has no delegate yet, as well as once {@code start()} has
 * built one.
 */
@DisplayName("Community: HttpClientEngine TCK (deferred bootstrap engine)")
class DeferredHttpClientEngineTckTest extends AbstractHttpClientEngineTck {

    @Override
    protected HttpClientEngine createEngine(HttpConfig config) {
        return new DeferredHttpClientEngine(new CommunityHttpProvider(), config);
    }
}
