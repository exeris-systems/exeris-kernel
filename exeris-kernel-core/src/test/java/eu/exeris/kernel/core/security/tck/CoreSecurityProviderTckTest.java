/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.security.tck;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.tck.contract.security.AbstractSecurityProviderTck;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.MemorySegment;

@DisplayName("Core: SecurityProvider TCK")
class CoreSecurityProviderTckTest extends AbstractSecurityProviderTck {

    @Override
    protected SecurityProvider createProvider() {
        return new CoreSecurityProviderFixture();
    }

    @Override
    protected LoanedBuffer createValidTokenBuffer() {
        return new StubLoanedBuffer(1L);
    }

    @Override
    protected LoanedBuffer createInvalidTokenBuffer() {
        return new StubLoanedBuffer(0L);
    }

    @Override
    protected LoanedBuffer createIndeterminateTokenBuffer() {
        return new StubLoanedBuffer(-1L);
    }

    @Override
    protected LoanedBuffer createTokenWithSeparatedSchemaStrategy() {
        return new StubLoanedBuffer(2L);
    }

    @Override
    protected String expectedSeparatedSchemaName() {
        return CoreSecurityProviderFixture.SEPARATED_SCHEMA_NAME;
    }

    @Override
    protected LoanedBuffer createTokenWithDedicatedStrategy() {
        return new StubLoanedBuffer(3L);
    }

    @Override
    protected String expectedDedicatedDataSourceKey() {
        return CoreSecurityProviderFixture.DEDICATED_DS_KEY;
    }

    @Override
    protected LoanedBuffer createTokenWithSeparatedSchemaMissingSchemaName() {
        return new StubLoanedBuffer(10L); // fixture denies (ADR-012 §4a amended): incomplete sub-claim
    }

    @Override
    protected LoanedBuffer createTokenWithDedicatedMissingDataSourceKey() {
        return new StubLoanedBuffer(11L); // fixture denies: incomplete sub-claim
    }

    @Override
    protected LoanedBuffer createTokenWithUnrecognizedStrategy() {
        return new StubLoanedBuffer(12L); // fixture denies: unrecognized strategy value
    }

    @Override
    protected LoanedBuffer createTokenWithWrongTypedStrategy() {
        return new StubLoanedBuffer(13L); // fixture denies: wrong-typed strategy claim
    }

    @Override
    protected LoanedBuffer createTokenWithSharedScopeClaim() {
        return new StubLoanedBuffer(14L); // fixture denies: shared scope declared but unenforceable
    }

    @Override
    protected LoanedBuffer createTokenWithWrongTypedSharedScope() {
        return new StubLoanedBuffer(15L); // fixture denies: wrong-typed shared-scope claim
    }

    private static final class StubLoanedBuffer implements LoanedBuffer {
        private long size;

        private StubLoanedBuffer(long size) {
            this.size = size;
        }

        @Override
        public MemorySegment segment() {
            return MemorySegment.NULL;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long capacity() {
            return size;
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            return new StubLoanedBuffer(length);
        }

        @Override
        public LoanedBuffer view() {
            return new StubLoanedBuffer(size);
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            return new StubLoanedBuffer(length);
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
            this.size = newSize;
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