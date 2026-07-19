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
 * Application-supplied hook for customizing the {@code tools.jackson} JSON mapper that the
 * Community JSON codecs use, discovered via {@link java.util.ServiceLoader}.
 *
 * <h2>Why this exists</h2>
 * <p>The Community codec drivers ({@code JsonBodyEncoder},
 * {@code CommunityJsonRequestBodyEncoder}, {@code CommunityJsonResponseBodyDecoder},
 * {@code CommunityJsonRequestBodyDecoder}, {@code CommunityJsonEventPayloadCodec}) already accept
 * an injected {@link tools.jackson.databind.ObjectMapper}, but the providers assembling them
 * previously hardcoded {@code new ObjectMapper()} — a bare mapper with default {@code MethodHandle}
 * property accessors. Registering a customizer lets an application tune that mapper per
 * {@link JsonMapperScope}, for example adding the Blackbird module
 * ({@code jackson-module-blackbird}) so property access compiles to monomorphic
 * {@code invokedynamic} call-sites instead of the shared, megamorphic {@code MethodHandle}
 * accessor on the response-encode hot path (ADR-052).
 *
 * <h2>Registration</h2>
 * <p>Provide an implementation on the application classpath and declare it in
 * {@code META-INF/services/eu.exeris.kernel.community.json.JsonMapperCustomizer}. Community ships
 * no customizer and pulls no optional Jackson module dependency: with none registered, every
 * mapper is byte-for-byte the pre-0.10.1 default (see
 * {@link CommunityJsonMappers#forScope(JsonMapperScope)}).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #customize(JsonMapperScope, JsonMapper.Builder)} operates on a Jackson-3
 *       {@link JsonMapper.Builder} — the mapper is immutable once built, so all configuration
 *       (modules, features) must happen on the builder.</li>
 *   <li>{@link #appliesTo(JsonMapperScope)} narrows a customizer to specific scopes; scopes no
 *       customizer applies to keep the exact default mapper.</li>
 *   <li>{@link #order()} orders customizers ascending when several apply to one scope; a
 *       later customizer observes and may override an earlier one's builder state. This makes
 *       resolution deterministic despite the unspecified {@code ServiceLoader} iteration order.</li>
 *   <li>Applied once per scope at provider construction (bootstrap), never on the request path.</li>
 * </ul>
 *
 * @since 0.10.1
 */
@FunctionalInterface
public interface JsonMapperCustomizer {

    /**
     * Whether this customizer participates in mapper assembly for the given scope.
     *
     * @param scope the codec scope the mapper is being built for; never {@code null}
     * @return {@code true} to have {@link #customize(JsonMapperScope, JsonMapper.Builder)} invoked
     *         for this scope; the default applies the customizer to every scope
     */
    default boolean appliesTo(JsonMapperScope scope) {
        return true;
    }

    /**
     * Configures the mapper builder for the given scope.
     *
     * <p>Invoked only when {@link #appliesTo(JsonMapperScope)} returned {@code true} for
     * {@code scope}. Implementations that target several scopes may branch on {@code scope} to
     * configure each differently.
     *
     * @param scope   the codec scope being assembled; never {@code null}
     * @param builder the Jackson-3 mapper builder to configure; never {@code null}
     */
    void customize(JsonMapperScope scope, JsonMapper.Builder builder);

    /**
     * Application order relative to other customizers applied to the same scope; lower runs first.
     *
     * @return the order value (default {@code 0})
     */
    default int order() {
        return 0;
    }
}
