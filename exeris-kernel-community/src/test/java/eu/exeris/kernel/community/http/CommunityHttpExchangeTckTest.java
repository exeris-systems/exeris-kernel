/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.contract.http.AbstractHttpExchangeTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.MemorySegment;

@DisplayName("Community: HttpExchange TCK")
class CommunityHttpExchangeTckTest extends AbstractHttpExchangeTck {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Override
    protected HttpExchange createExchange(HttpRequest request) {
        return new CommunityHttpExchange(request, new RecordingStream(), ALLOCATOR, true, HttpResponseBodyEncoderRegistry.empty());
    }

    private static final class RecordingStream implements TransportStream {

        @Override
        public int read(MemorySegment target, int maxBytes) {
            return -1;
        }

        @Override
        public void write(MemorySegment source, int length) {
            // no-op
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
            buffer.close();
        }

        @Override
        public long streamId() {
            return 0;
        }

        @Override
        public boolean isBidirectional() {
            return true;
        }

        @Override
        public boolean isClientInitiated() {
            return true;
        }

        @Override
        public TransportConnection connection() {
            throw new UnsupportedOperationException("Not required by exchange TCK");
        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
