/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence.codec;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.persistence.codec.EntityDecoder;
import eu.exeris.kernel.spi.persistence.codec.EntityEncoder;

import java.lang.foreign.MemorySegment;

/**
 * Persistence codec for raw JSONB payloads represented as {@link MemorySegment}.
 *
 * <p>This codec may still cross the JDBC heap boundary, but it keeps
 * the SPI contract explicit by encoding/decoding through {@link LoanedBuffer}.
 *
 * @since 0.5.0
 */
public final class CommunityRawJsonbCodec implements EntityEncoder<MemorySegment>, EntityDecoder<MemorySegment> {

    private static final String CODEC_STATE = "CODEC";
    private static final long ZERO_OFFSET = 0L;
    private static final long EMPTY_PAYLOAD_BYTES = 0L;

    @Override
    public int encode(MemorySegment entity, LoanedBuffer target) {
        validateEncodeInputs(entity, target);
        long bytes = entity.byteSize();
        validateEntityPayload(bytes);
        validateTargetCapacity(bytes, target.capacity());
        copyPayload(entity, target, bytes);
        target.setSize(bytes);
        return toEncodedSize(bytes);
    }

    @Override
    public MemorySegment decode(LoanedBuffer source, int offset, int length) {
        if (source == null) {
            throw codecFailure("decode: source must not be null", null);
        }
        if (offset < 0) {
            throw codecFailure("decode: offset must be >= 0", null);
        }
        if (length <= 0) {
            throw codecFailure("decode: length must be > 0", null);
        }

        long end = (long) offset + (long) length;
        if (end > source.capacity()) {
            throw codecFailure("decode: source bounds exceeded", null);
        }

        return source.segment().asSlice(offset, length).asReadOnly();
    }

    private static PersistenceProviderException codecFailure(String detail, Throwable cause) {
        return PersistenceProviderException.queryFailed(CODEC_STATE, detail, cause);
    }

    private static void validateEncodeInputs(MemorySegment entity, LoanedBuffer target) {
        if (entity == null) {
            throw codecFailure("encode: entity must not be null", null);
        }
        if (target == null) {
            throw codecFailure("encode: target must not be null", null);
        }
    }

    private static void validateEntityPayload(long bytes) {
        if (bytes <= EMPTY_PAYLOAD_BYTES) {
            throw codecFailure("encode: entity payload must not be empty", null);
        }
    }

    private static void validateTargetCapacity(long bytes, long capacity) {
        if (bytes > capacity) {
            throw codecFailure("encode: target capacity overflow", null);
        }
    }

    private static void copyPayload(MemorySegment entity, LoanedBuffer target, long bytes) {
        target.segment().asSlice(ZERO_OFFSET, bytes).copyFrom(entity.asSlice(ZERO_OFFSET, bytes));
    }

    private static int toEncodedSize(long bytes) {
        try {
            return Math.toIntExact(bytes);
        } catch (ArithmeticException e) {
            throw codecFailure("encode: entity size exceeds int range", e);
        }
    }
}