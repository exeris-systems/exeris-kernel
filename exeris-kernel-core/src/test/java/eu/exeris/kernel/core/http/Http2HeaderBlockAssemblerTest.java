/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http;

import eu.exeris.kernel.core.http.http2.Http2FrameParser;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.core.http.http2.Http2HeaderBlockAssembler;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Http2HeaderBlockAssemblerTest {

    @Test
    void beginHeadersFailureLeavesAssemblerResetAndReusable() {
        try (Arena arena = Arena.ofConfined()) {
            Http2HeaderBlockAssembler assembler =
                    new Http2HeaderBlockAssembler(new StubAllocator(arena));
            Http2FrameParser.FrameHeader header =
                    new Http2FrameParser.FrameHeader(2, Http2FrameType.HEADERS.code(), 0, 3);
            MemorySegment payload = arena.allocate(2);
            payload.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
            payload.set(ValueLayout.JAVA_BYTE, 1, (byte) 2);

            assertThatThrownBy(() -> assembler.beginHeaders(header, payload, -1, 2))
                    .isInstanceOf(Http2HeaderBlockAssembler.ContinuationViolationException.class);

            assertThat(assembler.isAwaitingContinuation()).isFalse();
            assertThat(assembler.currentStreamId()).isZero();
            assertThat(assembler.isComplete()).isFalse();
            assertThatThrownBy(assembler::completeBlock)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("HPACK header block is not yet complete");

            Http2FrameParser.FrameHeader endHeaders =
                    new Http2FrameParser.FrameHeader(2, Http2FrameType.HEADERS.code(), 0x04, 5);
            assembler.beginHeaders(endHeaders, payload, 0, 2);

            assertThat(assembler.isAwaitingContinuation()).isFalse();
            assertThat(assembler.currentStreamId()).isEqualTo(5);
            assertThat(assembler.isComplete()).isTrue();
            assertThat(assembler.completeBlock().toArray(ValueLayout.JAVA_BYTE)).containsExactly((byte) 1, (byte) 2);
        }
    }

        private static final class StubAllocator implements MemoryAllocator {
                private final Arena arena;

                private StubAllocator(Arena arena) {
                        this.arena = arena;
                }

                @Override
                public LoanedBuffer allocate(AllocationHint hint) {
                        return new StubLoanedBuffer(arena.allocate(hint.sizeBytes(), 8L));
                }

                @Override
                public LoanedBuffer allocateNetwork(int estimatedBytes) {
                        return new StubLoanedBuffer(arena.allocate(estimatedBytes, 8L));
                }

                @Override
                public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
                        return allocate(AllocationHint.SMALL);
                }

                @Override
                public LoanedBuffer allocateInfrastructure(long sizeBytes) {
                        return new StubLoanedBuffer(arena.allocate(sizeBytes, 8L));
                }

                @Override
                public MemoryStats stats() {
                        return MemoryStats.zero();
                }

                @Override
                public void close() {
                }
        }

        private static final class StubLoanedBuffer implements LoanedBuffer {
                private final MemorySegment segment;
                private long size;

                private StubLoanedBuffer(MemorySegment segment) {
                        this.segment = segment;
                        this.size = segment.byteSize();
                }

                @Override
                public MemorySegment segment() {
                        return segment;
                }

                @Override
                public long size() {
                        return size;
                }

                @Override
                public long capacity() {
                        return segment.byteSize();
                }

                @Override
                public LoanedBuffer slice(long offset, long length) {
                        return new StubLoanedBuffer(segment.asSlice(offset, length));
                }

                @Override
                public LoanedBuffer view() {
                        return this;
                }

                @Override
                public LoanedBuffer peek(long offset, long length) {
                        return new StubLoanedBuffer(segment.asSlice(offset, length));
                }

                @Override
                public void retain() {
                }

                @Override
                public void close() {
                }

                @Override
                public int refCount() {
                        return 1;
                }

                @Override
                public void setSize(long newSize) {
                        size = newSize;
                }

                @Override
                public boolean isAlive() {
                        return true;
                }

                @Override
                public void addCloseAction(Runnable action) {
                }
        }
}