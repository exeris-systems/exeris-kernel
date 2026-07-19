/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.json;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Assembles the {@code tools.jackson} JSON mapper for a given {@link JsonMapperScope}, applying any
 * {@link JsonMapperCustomizer}s discovered via {@link ServiceLoader} (ADR-052).
 *
 * <p>The concrete Community codec drivers accept an injected {@link ObjectMapper} in their
 * constructors; the providers call {@link #forScope(JsonMapperScope)} to obtain that mapper instead
 * of hardcoding {@code new ObjectMapper()}, giving applications a customization seam without any
 * change to SPI, the driver classes, or generated code.
 *
 * <h2>Default preservation</h2>
 * <p>When no discovered customizer {@linkplain JsonMapperCustomizer#appliesTo(JsonMapperScope)
 * applies} to a scope, this returns a plain {@code new ObjectMapper()} — byte-for-byte the
 * pre-0.10.1 default, with no builder round-trip that could drift from the bare-constructor
 * defaults. The customizing {@link JsonMapper#builder() builder} path is taken only when at least
 * one customizer opts in.
 *
 * @since 0.10.1
 */
public final class CommunityJsonMappers {

    /** Discovered once at class-init; immutable thereafter (single-writer JLS static-init guarantee). */
    private static final List<JsonMapperCustomizer> CUSTOMIZERS = discover();

    private CommunityJsonMappers() {
    }

    /**
     * Builds the JSON mapper for {@code scope}, applying any registered customizers.
     *
     * @param scope the codec scope to assemble a mapper for; never {@code null}
     * @return a configured mapper; a bare default when no customizer applies to {@code scope}
     */
    public static ObjectMapper forScope(JsonMapperScope scope) {
        return apply(scope, CUSTOMIZERS);
    }

    /**
     * Core assembly logic against an explicit customizer list (package-private for testability —
     * bypasses {@link ServiceLoader}).
     *
     * @param scope       the codec scope; never {@code null}
     * @param customizers the candidate customizers; never {@code null}
     * @return the assembled mapper
     */
    /* default */ static ObjectMapper apply(JsonMapperScope scope, List<JsonMapperCustomizer> customizers) {
        Objects.requireNonNull(scope, "scope must not be null");
        List<JsonMapperCustomizer> applicable = customizers.stream()
                .filter(customizer -> customizer.appliesTo(scope))
                .sorted(Comparator.comparingInt(JsonMapperCustomizer::order))
                .toList();
        if (applicable.isEmpty()) {
            // No opt-in for this scope: preserve the exact pre-0.10.1 default mapper.
            return new ObjectMapper();
        }
        // A misbehaving customizer fails fast at bootstrap; the customizer class shows in the trace.
        JsonMapper.Builder builder = JsonMapper.builder();
        for (JsonMapperCustomizer customizer : applicable) {
            customizer.customize(scope, builder);
        }
        return builder.build();
    }

    /**
     * The customizers discovered via {@link ServiceLoader} (package-private for testability).
     *
     * @return an immutable snapshot of the discovered customizers
     */
    /* default */ static List<JsonMapperCustomizer> discovered() {
        return CUSTOMIZERS;
    }

    private static List<JsonMapperCustomizer> discover() {
        List<JsonMapperCustomizer> found = new ArrayList<>();
        for (JsonMapperCustomizer customizer : ServiceLoader.load(JsonMapperCustomizer.class)) {
            found.add(customizer);
        }
        return List.copyOf(found);
    }
}
