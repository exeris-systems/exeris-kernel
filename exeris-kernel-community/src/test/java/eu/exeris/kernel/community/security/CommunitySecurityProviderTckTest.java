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
import eu.exeris.kernel.spi.security.identity.KeyRotationPolicy;
import eu.exeris.kernel.tck.contract.security.AbstractSecurityProviderTck;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.MemorySegment;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

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
    protected LoanedBuffer createAlgNoneTokenBuffer() {
        return TestJwt.builder().kid(TestJwt.TEST_KID).algNone().toBuffer();
    }

    @Override
    protected LoanedBuffer createAlgConfusionTokenBuffer() {
        return TestJwt.builder().kid(TestJwt.TEST_KID).hmacConfusion().toBuffer();
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

    @Override
    protected LoanedBuffer createTokenWithWrongTypedStrategy() {
        // A JSON number where a strategy string belongs. Discharged by the driver's own token
        // validation — the kernel mapping cannot see this case (see the TCK factory's Javadoc).
        return TestJwt.builder()
                .claimRaw(KernelIsolationClaims.ISOLATION_STRATEGY, 42)
                .toBuffer();
    }

    @Override
    protected LoanedBuffer createTokenWithSharedScopeClaim() {
        return TestJwt.builder()
                .claim(KernelIsolationClaims.SHARED_SCOPE_KEY, "world-alpha")
                .toBuffer();
    }

    @Override
    protected RotationHarness createRotationHarness() {
        return new CommunityRotationHarness();
    }

    /**
     * Drives {@link CommunityRotatingKeySet} with a controllable clock and a fail-injectable
     * {@link KeySetSource}. The "new" generation uses a distinct kid so the old-kid token
     * falls to the retiring (previous) generation after rotation.
     */
    private static final class CommunityRotationHarness implements RotationHarness {

        private static final String ROTATED_KID = "rotated-key-2";

        private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        private final SecurityProvider provider;
        private boolean newKeySetArmed;
        private boolean failArmed;

        private CommunityRotationHarness() {
            Map<String, RSAPublicKey> initial = TestJwt.keySet();
            KeySetSource source = () -> {
                if (failArmed) {
                    throw new KeySetRefreshException("refresh-failed");
                }
                if (newKeySetArmed) {
                    return Map.of(ROTATED_KID, TestJwt.testPublicKey());
                }
                return initial;
            };
            CommunityRotatingKeySet keySet = new CommunityRotatingKeySet(
                    initial, source, KeyRotationPolicy.defaults(), clock);
            this.provider = CommunitySecurityProvider.withKeyResolver(
                    keySet, TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);
        }

        @Override
        public SecurityProvider provider() {
            return provider;
        }

        @Override
        public void advanceClock(Duration amount) {
            clock.advance(amount);
        }

        @Override
        public void installNewKeySet() {
            newKeySetArmed = true;
        }

        @Override
        public void failNextRefresh() {
            failArmed = true;
        }

        @Override
        public LoanedBuffer tokenUnderOriginalKid() {
            return TestJwt.builder().kid(TestJwt.TEST_KID).expiresInSeconds(86_400L).toBuffer();
        }

        @Override
        public void close() {
            // No native resources to release.
        }
    }

    /** Test-only mutable {@link Clock} for deterministic time travel in rotation cases. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant start) {
            this.instant = start;
        }

        private void advance(Duration amount) {
            this.instant = this.instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
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
