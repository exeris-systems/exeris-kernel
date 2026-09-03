/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.http2.Http2FrameParser;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.ValueLayout;

/**
 * Package-private pad/priority field extraction for HEADERS and DATA frame
 * payloads used by {@link CommunityHttp2SessionProcessor}.
 *
 * <p>Extracted from {@link CommunityHttp2SessionProcessor} in v0.8 Sprint 3
 * (QA-018a) as one of four seams of the processor's God-class decomposition.
 * RFC 7540 §6.2 (HEADERS) and §6.1 (DATA) allow padding and (for HEADERS)
 * an optional 5-byte priority section; the extracted helpers strip these
 * envelope fields and return the inner header-block / data fragment.
 */
final class CommunityHttp2FrameFragments {

    private static final int HTTP2_FLAG_PADDED = 0x08;
    private static final int HTTP2_PADDED_LENGTH_FIELD_BYTES = 1;
    private static final int HTTP2_PRIORITY_FIELD_BYTES = 5;

    private CommunityHttp2FrameFragments() {
        // package-private static utility — never instantiated.
    }

    /**
     * Inner payload after stripping the optional pad-length byte, optional
     * 5-byte priority section, and the trailing padding bytes.
     */
    /* default */ record Fragment(long offset, int length) {
    }

    /* default */ static Fragment extractHeadersFragment(LoanedBuffer aggregate,
                                                         long payloadOffset,
                                                         Http2FrameParser.FrameHeader header) {
        long fragmentOffset = payloadOffset;
        int fragmentLength = header.length();
        int padLength = 0;

        if (header.isPadded()) {
            if (fragmentLength < HTTP2_PADDED_LENGTH_FIELD_BYTES) {
                throw new IllegalStateException("Invalid PADDED HEADERS frame");
            }
            padLength = aggregate.segment().get(ValueLayout.JAVA_BYTE, fragmentOffset) & 0xFF;
            fragmentOffset += HTTP2_PADDED_LENGTH_FIELD_BYTES;
            fragmentLength -= HTTP2_PADDED_LENGTH_FIELD_BYTES;
        }

        if (header.isPriority()) {
            if (fragmentLength < HTTP2_PRIORITY_FIELD_BYTES) {
                throw new IllegalStateException("Invalid PRIORITY section in HEADERS frame");
            }
            fragmentOffset += HTTP2_PRIORITY_FIELD_BYTES;
            fragmentLength -= HTTP2_PRIORITY_FIELD_BYTES;
        }

        if (padLength > fragmentLength) {
            throw new IllegalStateException("Invalid HEADERS padding");
        }
        fragmentLength -= padLength;
        return new Fragment(fragmentOffset, fragmentLength);
    }

    /* default */ static Fragment extractDataFragment(LoanedBuffer aggregate,
                                                      long payloadOffset,
                                                      Http2FrameParser.FrameHeader header) {
        long fragmentOffset = payloadOffset;
        int fragmentLength = header.length();
        int padLength = 0;

        if ((header.flags() & HTTP2_FLAG_PADDED) != 0) {
            if (fragmentLength < HTTP2_PADDED_LENGTH_FIELD_BYTES) {
                throw new IllegalStateException("Invalid PADDED DATA frame");
            }
            padLength = aggregate.segment().get(ValueLayout.JAVA_BYTE, fragmentOffset) & 0xFF;
            fragmentOffset += HTTP2_PADDED_LENGTH_FIELD_BYTES;
            fragmentLength -= HTTP2_PADDED_LENGTH_FIELD_BYTES;
        }

        if (padLength > fragmentLength) {
            throw new IllegalStateException("Invalid DATA padding");
        }
        fragmentLength -= padLength;
        return new Fragment(fragmentOffset, fragmentLength);
    }
}
