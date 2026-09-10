/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
    void headerBlockBoundIsTheConfiguredOneNotAConstant() {
        try (Arena arena = Arena.ofConfined()) {
            // ADR-071's tail: this bound was a hardcoded 65 536, so an operator raising the header
            // limits saw HTTP/1 honour them and HTTP/2 silently not. A tiny bound proves the value
            // is read rather than the constant — at 65 536 this frame would sail through.
            Http2HeaderBlockAssembler assembler =
                    new Http2HeaderBlockAssembler(new StubAllocator(arena), 4);
            Http2FrameParser.FrameHeader header =
                    new Http2FrameParser.FrameHeader(8, Http2FrameType.HEADERS.code(), 0x04, 1);
            MemorySegment payload = arena.allocate(8);

            assertThatThrownBy(() -> assembler.beginHeaders(header, payload, 0, 8))
                    .as("a block larger than the CONFIGURED bound must be refused")
                    .isInstanceOf(Http2HeaderBlockAssembler.ContinuationViolationException.class);

            Http2HeaderBlockAssembler roomy =
                    new Http2HeaderBlockAssembler(new StubAllocator(arena), 16);
            roomy.beginHeaders(header, payload, 0, 8);
            assertThat(roomy.isComplete())
                    .as("the same block is accepted once the bound admits it")
                    .isTrue();
        }
    }

    @Test
    void nonPositiveHeaderBlockBoundIsRefused() {
        try (Arena arena = Arena.ofConfined()) {
            // Protective, so ADR-071 says 0 is not "unlimited" — it is a bound that refuses
            // everything, and a protection must not be switchable off by an empty-looking value.
            assertThatThrownBy(() -> new Http2HeaderBlockAssembler(new StubAllocator(arena), 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Http2HeaderBlockAssembler(new StubAllocator(arena), -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

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
                    /* test stub — no resources to release */
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
                    /* test stub — ref-count not tracked in this fixture */
                }

                @Override
                public void close() {
                    /* test stub — no resource to release */
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
                    /* test stub — close actions not tracked */
                }
        }
}