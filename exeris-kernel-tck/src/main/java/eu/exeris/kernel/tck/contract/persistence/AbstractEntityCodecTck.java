/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import eu.exeris.kernel.spi.persistence.codec.EntityDecoder;
import eu.exeris.kernel.spi.persistence.codec.EntityEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TCK: Abstract contract for persistence entity codecs.
 *
 * <p>MVP scope validates a raw payload round-trip and strict malformed/bounds
 * behaviour expected from {@link EntityEncoder} and {@link EntityDecoder}.
 *
 * @since 0.5
 */
public abstract class AbstractEntityCodecTck {

    protected abstract PersistenceProvider createProvider();

    protected final EntityEncoder<MemorySegment> createEncoder() {
        return createProvider().rawEntityEncoder()
                .orElseThrow(() -> new AssertionError("Provider must expose rawEntityEncoder()"));
    }

    protected final EntityDecoder<MemorySegment> createDecoder() {
        return createProvider().rawEntityDecoder()
                .orElseThrow(() -> new AssertionError("Provider must expose rawEntityDecoder()"));
    }

    @Test
    @DisplayName("provider exposes both raw codec optionals")
    void providerExposesRawCodecOptionals() {
        PersistenceProvider provider = createProvider();
        assertThat(provider.rawEntityEncoder()).isPresent();
        assertThat(provider.rawEntityDecoder()).isPresent();
    }

    @Nested
    @DisplayName("Round-trip contract")
    class RoundTripContract {

        @Test
        @DisplayName("encode + decode preserves raw payload")
        void roundTripPreservesPayload() {
            EntityEncoder<MemorySegment> encoder = createEncoder();
            EntityDecoder<MemorySegment> decoder = createDecoder();

            byte[] expected = "{\"kind\":\"edge\",\"weight\":7}".getBytes(StandardCharsets.UTF_8);
            MemorySegment entity = MemorySegment.ofArray(expected);
            MemorySegment backing = MemorySegment.ofArray(new byte[512]);

            LoanedBuffer target = mock(LoanedBuffer.class);
            when(target.capacity()).thenReturn(512L);
            when(target.segment()).thenReturn(backing);

            int written = encoder.encode(entity, target);
            verify(target).setSize(written);
            assertThat(written).isEqualTo(expected.length);

            LoanedBuffer source = mock(LoanedBuffer.class);
            when(source.capacity()).thenReturn((long) written);
            when(source.segment()).thenReturn(backing);

            MemorySegment decoded = decoder.decode(source, 0, written);
            assertThat(decoded.toArray(ValueLayout.JAVA_BYTE)).containsExactly(expected);
            assertThat(decoded.isReadOnly()).isTrue();
        }
    }

    @Nested
    @DisplayName("Malformed and bounds contract")
    class MalformedAndBoundsContract {

        @Test
        @DisplayName("encode throws PersistenceProviderException when target capacity is too small")
        void encodeFailsOnOverflow() {
            EntityEncoder<MemorySegment> encoder = createEncoder();
            MemorySegment entity = MemorySegment.ofArray(new byte[8]);

            LoanedBuffer tooSmall = mock(LoanedBuffer.class);
            when(tooSmall.capacity()).thenReturn(4L);
            when(tooSmall.segment()).thenReturn(MemorySegment.ofArray(new byte[4]));

            assertThatThrownBy(() -> encoder.encode(entity, tooSmall))
                    .isInstanceOf(PersistenceProviderException.class);
        }

        @Test
        @DisplayName("encode rejects empty entity payload")
        void encodeRejectsEmptyEntityPayload() {
            EntityEncoder<MemorySegment> encoder = createEncoder();
            MemorySegment empty = MemorySegment.ofArray(new byte[0]);

            LoanedBuffer target = mock(LoanedBuffer.class);
            when(target.capacity()).thenReturn(16L);
            when(target.segment()).thenReturn(MemorySegment.ofArray(new byte[16]));

            assertThatThrownBy(() -> encoder.encode(empty, target))
                    .isInstanceOf(PersistenceProviderException.class);
        }

        @Test
        @DisplayName("encode rejects null entity")
        void encodeRejectsNullEntity() {
            EntityEncoder<MemorySegment> encoder = createEncoder();

            LoanedBuffer target = mock(LoanedBuffer.class);
            when(target.capacity()).thenReturn(16L);
            when(target.segment()).thenReturn(MemorySegment.ofArray(new byte[16]));

            assertThatThrownBy(() -> encoder.encode(null, target))
                    .isInstanceOf(PersistenceProviderException.class);
        }

        @Test
        @DisplayName("encode rejects null target")
        void encodeRejectsNullTarget() {
            EntityEncoder<MemorySegment> encoder = createEncoder();
            MemorySegment entity = MemorySegment.ofArray(new byte[8]);

            assertThatThrownBy(() -> encoder.encode(entity, null))
                    .isInstanceOf(PersistenceProviderException.class);
        }

        @Test
        @DisplayName("decode rejects null source")
        void decodeRejectsNullSource() {
            EntityDecoder<MemorySegment> decoder = createDecoder();

            assertThatThrownBy(() -> decoder.decode(null, 0, 1))
                    .isInstanceOf(PersistenceProviderException.class);
        }

        @Test
        @DisplayName("decode rejects negative offset")
        void decodeRejectsNegativeOffset() {
            EntityDecoder<MemorySegment> decoder = createDecoder();

            LoanedBuffer source = mock(LoanedBuffer.class);
            when(source.capacity()).thenReturn(16L);
            when(source.segment()).thenReturn(MemorySegment.ofArray(new byte[16]));

            assertThatThrownBy(() -> decoder.decode(source, -1, 8))
                    .isInstanceOf(PersistenceProviderException.class);
        }

        @Test
        @DisplayName("decode rejects non-positive length")
        void decodeRejectsNonPositiveLength() {
            EntityDecoder<MemorySegment> decoder = createDecoder();

            LoanedBuffer source = mock(LoanedBuffer.class);
            when(source.capacity()).thenReturn(16L);
            when(source.segment()).thenReturn(MemorySegment.ofArray(new byte[16]));

            assertThatThrownBy(() -> decoder.decode(source, 0, 0))
                    .isInstanceOf(PersistenceProviderException.class);
        }

        @Test
        @DisplayName("decode rejects bounds overflow")
        void decodeRejectsBoundsOverflow() {
            EntityDecoder<MemorySegment> decoder = createDecoder();

            LoanedBuffer source = mock(LoanedBuffer.class);
            when(source.capacity()).thenReturn(16L);
            when(source.segment()).thenReturn(MemorySegment.ofArray(new byte[16]));

            assertThatThrownBy(() -> decoder.decode(source, 8, 9))
                    .isInstanceOf(PersistenceProviderException.class);
        }
    }
}