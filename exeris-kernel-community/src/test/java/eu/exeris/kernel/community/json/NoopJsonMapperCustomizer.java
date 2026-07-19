/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.json;

import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only {@link JsonMapperCustomizer} registered via a {@code META-INF/services} file to verify
 * {@link java.util.ServiceLoader} discovery (ADR-052). It intentionally applies to no scope, so its
 * presence on the module test classpath never alters any produced mapper.
 */
public final class NoopJsonMapperCustomizer implements JsonMapperCustomizer {

    @Override
    public boolean appliesTo(JsonMapperScope scope) {
        return false;
    }

    @Override
    public void customize(JsonMapperScope scope, JsonMapper.Builder builder) {
        // never invoked (appliesTo is always false)
    }
}
