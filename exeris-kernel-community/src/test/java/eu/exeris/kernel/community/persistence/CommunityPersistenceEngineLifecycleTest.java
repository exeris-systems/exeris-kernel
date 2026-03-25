/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommunityPersistenceEngine lifecycle hardening")
class CommunityPersistenceEngineLifecycleTest {

    @Test
    @DisplayName("DEDICATED strategy is explicitly rejected in Community provider")
    void dedicatedStrategyIsRejected() {
        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(testConfig(true))) {
            assertThatThrownBy(() -> engine.openConnection(ImmutableStorageContext.dedicated("tenant-a", "ds-a")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DEDICATED strategy is unsupported in Community provider")
                    .hasMessageContaining("dedicated datasource routing is unsupported in Community provider");
        }
    }

    @Test
    @DisplayName("close() is idempotent and blocks subsequent opens")
    void closeIsIdempotentAndPreventsFurtherOpen() {
        CommunityPersistenceEngine engine = new CommunityPersistenceEngine(testConfig(true));
        engine.close();

        assertThatCode(engine::close).doesNotThrowAnyException();
        assertThatThrownBy(engine::openConnection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CommunityPersistenceEngine is closed");
        assertThatThrownBy(() -> engine.openConnection(ImmutableStorageContext.separatedSchema("tenant-a", "tenant_a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CommunityPersistenceEngine is closed");
    }

    @Test
    @DisplayName("open/close race allows only success-before-close or closed-state rejection")
    void openCloseRaceIsDeterministicAtContractLevel() throws Exception {
        for (int i = 0; i < 40; i++) {
            int iteration = i;
            CommunityPersistenceEngine engine = new CommunityPersistenceEngine(testConfig(true));
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Thread opener = Thread.ofVirtual().start(() -> {
                await(start);
                try (PersistenceConnection ignored =
                             engine.openConnection(ImmutableStorageContext.separatedSchema(
                                     "tenant-" + iteration,
                                     "tenant_" + iteration))) {
                    // success path: open won the race before close transition
                } catch (IllegalStateException ex) {
                    assertThat(ex.getMessage()).contains("CommunityPersistenceEngine is closed");
                } catch (Throwable ex) {
                    unexpected.compareAndSet(null, ex);
                }
            });

            Thread closer = Thread.ofVirtual().start(() -> {
                await(start);
                engine.close();
            });

            start.countDown();
            opener.join();
            closer.join();
            assertThat(unexpected.get()).isNull();
            engine.close();
        }
    }

    private static PersistenceConfig testConfig(boolean perTenantPooling) {
        return new PersistenceConfig(
                "jdbc:h2:mem:community_lifecycle_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,
                1,
                5_000L,
                60_000L,
                600_000L,
                false,
                perTenantPooling,
                false,
                16,
                Map.of()
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting test latch", ex);
        }
    }
}
