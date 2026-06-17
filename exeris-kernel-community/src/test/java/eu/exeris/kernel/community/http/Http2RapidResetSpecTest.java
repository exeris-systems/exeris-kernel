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
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable specification for HTTP/2 Rapid Reset (CVE-2023-44487) flood defense.
 *
 * <p>Asserts the per-connection net rapid-reset budget on {@link Http2SessionContext}: each
 * inbound {@code RST_STREAM} ({@link Http2SessionContext#recordInboundRstStream()}) increments the
 * net count, a dispatched request ({@link Http2SessionContext#recordDispatchedRequest()}) credits
 * it back, and crossing {@link Http2SessionContext#rapidResetBudget()} reports a flood — the signal
 * {@link CommunityHttp2SessionProcessor} uses to emit {@code GOAWAY(ENHANCE_YOUR_CALM)} and stop the
 * frame loop. The plain {@code SETTINGS_MAX_CONCURRENT_STREAMS} cap is no defense here: every reset
 * frees the slot, so the cap is never reached (the attack's whole point).
 *
 * <p><b>Layer note:</b> this is an HTTP/2 <i>codec</i> concern (inbound {@code RST_STREAM} frame
 * flood), distinct from the transport-stream abort SPI tracked as {@code TransportStream.reset(long)}
 * (downstream issue #23 / ROADMAP "Transport-Agnostic Stream Abort"). The two share the word
 * "reset" but not the layer or direction.
 *
 * @see <a href="https://www.cve.org/CVERecord?id=CVE-2023-44487">CVE-2023-44487</a>
 */
@DisplayName("HTTP/2: Rapid Reset flood defense (CVE-2023-44487)")
final class Http2RapidResetSpecTest {

    private MemoryAllocator allocator;
    private Http2SessionContext session;

    @BeforeEach
    void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        session = Http2SessionContext.create(allocator);
    }

    @AfterEach
    void tearDown() {
        session.close();
        allocator.close();
    }

    @Test
    @DisplayName("a peer that opens-then-resets streams without bound is throttled at the budget")
    void rapidResetFloodIsThrottled() {
        int budget = Http2SessionContext.rapidResetBudget();

        // Faithful CVE-2023-44487 pattern: open a stream, immediately reset it, repeatedly. Each
        // reset frees the concurrency slot, so the max-concurrent-streams cap never bites — the
        // net rapid-reset budget is the only thing that stops the flood.
        for (int i = 0; i < budget; i++) {
            int streamId = i * 2 + 1;
            session.admitClientStreamId(streamId);
            session.openRequestStream(new Http2DecodedRequest(streamId, null, "/", List.of(), true));
            session.resetRequestStream(streamId);
            assertFalse(session.recordInboundRstStream(),
                    "within-budget reset #" + (i + 1) + " must not yet be flagged as a flood");
        }

        // The reset that crosses the budget MUST be reported as a flood (caller → GOAWAY(0x0B)).
        int floodStreamId = budget * 2 + 1;
        session.admitClientStreamId(floodStreamId);
        session.openRequestStream(new Http2DecodedRequest(floodStreamId, null, "/", List.of(), true));
        session.resetRequestStream(floodStreamId);
        assertTrue(session.recordInboundRstStream(),
                "the reset crossing the per-connection budget MUST trip the flood defense");
        assertEquals(budget + 1, session.rapidResetCount(),
                "net reset count at trip time is the budget + the tripping frame");
    }

    @Test
    @DisplayName("legitimate cancels interleaved with real work stay under budget (no false trip)")
    void legitimateCancelsDoNotTrip() {
        // A well-behaved peer cancels far more streams than the budget over the connection's life,
        // but each cancel is paired with a dispatched request — the net count never climbs.
        for (int i = 0; i < Http2SessionContext.rapidResetBudget() * 5; i++) {
            session.recordDispatchedRequest();
            assertFalse(session.recordInboundRstStream(),
                    "a reset paired with real work must never be flagged as a flood");
        }
        assertTrue(session.rapidResetCount() <= 1,
                "net reset count stays floored (oscillates 0/1) when every reset is offset by a dispatch");
    }
}
