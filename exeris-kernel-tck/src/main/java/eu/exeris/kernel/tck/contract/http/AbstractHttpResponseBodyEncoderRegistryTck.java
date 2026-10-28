/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpResponseBodyEncoder;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK: Abstract base for {@link HttpResponseBodyEncoderRegistry} contract verification (ADR-034 §3).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code resolve(Class<?>)} answers the encoder to use for one payload type, and that
 *       encoder supports the type it was resolved for.</li>
 *   <li>When nothing supports the payload type the answer is {@code null}, never a fallback
 *       encoder: the caller turns an unencodable payload into a server-side error, and an encoder
 *       of the wrong media type would put the wrong body on the wire. The binding declares such a
 *       type when its registry has one.</li>
 *   <li>{@link HttpResponseBodyEncoderRegistry#empty()} resolves no encoder for any type.</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * {@snippet lang="java" :
 * class CommunityResponseBodyEncoderRegistryTckTest extends AbstractHttpResponseBodyEncoderRegistryTck {
 *     @Override
 *     protected HttpResponseBodyEncoderRegistry createRegistry() {
 *         return new CommunityHttpProvider().responseBodyEncoderRegistry();
 *     }
 *
 *     @Override
 *     protected Class<?> supportedPayloadType() {
 *         return Map.class;
 *     }
 * }
 * }
 *
 * <h2>The Wall (SPI compliance)</h2>
 * <p>This TCK imports only from {@code exeris-kernel-spi}.
 *
 * @since 0.13
 */
public abstract class AbstractHttpResponseBodyEncoderRegistryTck {

    /**
     * Creates the contract; subclasses supply the registry under test via {@link #createRegistry()}.
     */
    public AbstractHttpResponseBodyEncoderRegistryTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Creates the registry under test.
     *
     * @return non-null registry
     */
    protected abstract HttpResponseBodyEncoderRegistry createRegistry();

    /**
     * Returns a payload type the registry holds an encoder for.
     *
     * @return non-null payload type
     */
    protected abstract Class<?> supportedPayloadType();

    /**
     * Returns a payload type no encoder in the registry supports, or empty when the registry
     * accepts every type. The null-for-unsupported case is skipped, not passed, when empty.
     *
     * @return an unsupported payload type, or empty
     */
    protected Optional<Class<?>> unsupportedPayloadType() {
        return Optional.empty();
    }

    @Nested
    @DisplayName("resolve(Class<?>)")
    class Resolve {

        @Test
        @DisplayName("resolve answers an encoder that supports the resolved type")
        void resolveAnswersEncoderForSupportedType() {
            Class<?> type = supportedPayloadType();
            HttpResponseBodyEncoder encoder = createRegistry().resolve(type);
            assertThat(encoder)
                    .as("resolve(%s) for a type the registry holds an encoder for", type.getName())
                    .isNotNull();
            assertThat(encoder.supports(type))
                    .as("the resolved encoder supports the type it was resolved for")
                    .isTrue();
        }

        @Test
        @DisplayName("resolve answers null, not a fallback encoder, for a type nothing supports")
        void resolveAnswersNullForUnsupportedType() {
            Optional<Class<?>> unsupported = unsupportedPayloadType();
            assumeTrue(unsupported.isPresent(), "binding declares no unsupported payload type");
            Class<?> type = unsupported.get();
            assertThat(createRegistry().resolve(type))
                    .as("resolve(%s) for a type no encoder supports", type.getName())
                    .isNull();
        }
    }

    @Nested
    @DisplayName("empty()")
    class Empty {

        @Test
        @DisplayName("empty registry resolves no encoder for any type")
        void emptyRegistryResolvesNothing() {
            HttpResponseBodyEncoderRegistry empty = HttpResponseBodyEncoderRegistry.empty();
            assertThat(empty.resolve(supportedPayloadType())).isNull();
            assertThat(empty.resolve(Object.class)).isNull();
        }
    }
}
