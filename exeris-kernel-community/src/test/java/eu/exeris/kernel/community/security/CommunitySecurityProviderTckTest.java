/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.KernelIsolationClaims;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.tck.contract.security.AbstractSecurityProviderTck;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.MemorySegment;

@DisplayName("Community: SecurityProvider TCK")
class CommunitySecurityProviderTckTest extends AbstractSecurityProviderTck {

    @Override
    protected SecurityProvider createProvider() {
        return new CommunitySecurityProvider(TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);
    }

    @Override
    protected LoanedBuffer createValidTokenBuffer() {
        return TestJwt.builder().toBuffer();
    }

    @Override
    protected LoanedBuffer createInvalidTokenBuffer() {
        return TestJwt.bufferOf("not-a-jwt");
    }

    @Override
    protected LoanedBuffer createIndeterminateTokenBuffer() {
        return new NegativeSizeLoanedBuffer();
    }

    @Override
    protected LoanedBuffer createTokenWithKnownKid() {
        return TestJwt.builder().kid(TestJwt.TEST_KID).toBuffer();
    }

    @Override
    protected LoanedBuffer createTokenWithUnknownKid() {
        return TestJwt.builder().kid(TestJwt.UNKNOWN_KID).toBuffer();
    }

    @Override
    protected LoanedBuffer createTokenWithMissingKid() {
        return TestJwt.builder().noKid().toBuffer();
    }

    @Override
    protected LoanedBuffer createExpiredTokenBuffer() {
        return TestJwt.builder().expired().toBuffer();
    }

    @Override
    protected LoanedBuffer createWrongIssuerTokenBuffer() {
        return TestJwt.builder().issuer(TestJwt.EXPECTED_ISSUER + "/wrong").toBuffer();
    }

    @Override
    protected LoanedBuffer createWrongAudienceTokenBuffer() {
        return TestJwt.builder().audience(TestJwt.EXPECTED_AUDIENCE + "-wrong").toBuffer();
    }

    @Override
    protected LoanedBuffer createTamperedSignatureBuffer() {
        return TestJwt.builder().tamperedSignature().toBuffer();
    }

    @Override
    protected LoanedBuffer createTokenWithSeparatedSchemaStrategy() {
        return TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "SEPARATED_SCHEMA")
                .claim(KernelIsolationClaims.SCHEMA_NAME, "tenant_acme")
                .toBuffer();
    }

    @Override
    protected String expectedSeparatedSchemaName() {
        return "tenant_acme";
    }

    @Override
    protected LoanedBuffer createTokenWithDedicatedStrategy() {
        return TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "DEDICATED")
                .claim(KernelIsolationClaims.DATASOURCE_KEY, "ds-primary")
                .toBuffer();
    }

    @Override
    protected String expectedDedicatedDataSourceKey() {
        return "ds-primary";
    }

    @Override
    protected LoanedBuffer createTokenWithSeparatedSchemaMissingSchemaName() {
        return TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "SEPARATED_SCHEMA")
                .toBuffer();
    }

    @Override
    protected LoanedBuffer createTokenWithDedicatedMissingDataSourceKey() {
        return TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "DEDICATED")
                .toBuffer();
    }

    @Override
    protected LoanedBuffer createTokenWithUnrecognizedStrategy() {
        return TestJwt.builder()
                .claim(KernelIsolationClaims.ISOLATION_STRATEGY, "UNKNOWN_STRATEGY_XYZ")
                .toBuffer();
    }

    private static final class NegativeSizeLoanedBuffer implements LoanedBuffer {

        @Override
        public MemorySegment segment() {
            return MemorySegment.NULL;
        }

        @Override
        public long size() {
            return -1L;
        }

        @Override
        public long capacity() {
            return 0L;
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            return this;
        }

        @Override
        public LoanedBuffer view() {
            return this;
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            return this;
        }

        @Override
        public void retain() {
            // No-op.
        }

        @Override
        public void close() {
            // No-op.
        }

        @Override
        public int refCount() {
            return 1;
        }

        @Override
        public void setSize(long newSize) {
            // No-op.
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public void addCloseAction(Runnable action) {
            // No-op.
        }
    }
}
