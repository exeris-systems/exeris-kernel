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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Executable specification for HTTP/2 Rapid Reset (CVE-2023-44487) flood defense.
 *
 * <p><b>Status: pending implementation.</b> This test is intentionally {@link Disabled} — it
 * encodes the attack sequence and the <i>expected</i> mitigated behavior so the contract is
 * committed before the implementation. There is currently no per-window {@code RST_STREAM}
 * budget in {@link CommunityHttp2SessionProcessor} / {@link Http2SessionContext}, so a peer can
 * open-then-immediately-reset streams without bound: each {@link Http2SessionContext#resetRequestStream(int)}
 * frees the concurrency slot, so the {@code SETTINGS_MAX_CONCURRENT_STREAMS} cap is never reached
 * and the server performs unbounded request setup/teardown work.
 *
 * <p><b>Layer note:</b> this is an HTTP/2 <i>codec</i> concern (inbound {@code RST_STREAM} frame
 * flood), distinct from the transport-stream abort SPI tracked as {@code TransportStream.reset(long)}
 * (downstream issue #23 / ROADMAP "Transport-Agnostic Stream Abort"). The two share the word
 * "reset" but not the layer or direction — see the ROADMAP entry for the full distinction.
 *
 * <p>When the mitigation lands (per-window reset counter + {@code GOAWAY(ENHANCE_YOUR_CALM)} once
 * the budget is exceeded, with a config knob and a JFR flood event), remove {@link Disabled} and
 * replace the {@link #rapidResetFloodIsThrottled()} body with an assertion that the session refuses
 * further admission / signals {@code GOAWAY} after the threshold.
 *
 * @see <a href="https://www.cve.org/CVERecord?id=CVE-2023-44487">CVE-2023-44487</a>
 */
@DisplayName("HTTP/2: Rapid Reset flood defense (CVE-2023-44487) — executable spec")
final class Http2RapidResetSpecTest {

    /** A flood well above any reasonable per-connection request rate. */
    private static final int FLOOD_CYCLES = 1_000;

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
    @Disabled("CVE-2023-44487 rapid-reset mitigation not yet implemented — see ROADMAP "
            + "'HTTP/2: Rapid Reset (CVE-2023-44487) Flood Defense'. Enable once the per-window "
            + "RST_STREAM budget + GOAWAY(ENHANCE_YOUR_CALM) seam exists and assert the flood is throttled.")
    @DisplayName("a peer that opens-then-resets streams without bound MUST be throttled")
    void rapidResetFloodIsThrottled() {
        // Faithful CVE-2023-44487 pattern: open a stream, then immediately reset it, repeatedly.
        // Each reset frees the concurrency slot, so the max-concurrent-streams cap never bites.
        for (int i = 0; i < FLOOD_CYCLES; i++) {
            int streamId = i * 2 + 1;
            session.admitClientStreamId(streamId);
            session.openRequestStream(new Http2DecodedRequest(streamId, null, "/", List.of(), true));
            session.resetRequestStream(streamId);
        }

        // EXPECTED (post-fix): exceeding the per-window reset budget MUST stop admitting new
        // streams / emit GOAWAY(ENHANCE_YOUR_CALM). No such seam exists yet, so the contract
        // is unsatisfied — this test stays @Disabled until the mitigation lands.
        fail("Rapid-reset mitigation unimplemented: session processed " + FLOOD_CYCLES
                + " open+reset cycles without throttling (CVE-2023-44487).");
    }
}
