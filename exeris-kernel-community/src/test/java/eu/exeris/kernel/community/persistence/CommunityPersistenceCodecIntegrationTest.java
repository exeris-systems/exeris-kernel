/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.RowCursor;
import eu.exeris.kernel.spi.persistence.codec.EntityDecoder;
import eu.exeris.kernel.spi.persistence.codec.EntityEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Community: Persistence codec integration")
class CommunityPersistenceCodecIntegrationTest {

    @Test
    @DisplayName("raw codec round-trip works through real H2 persistence path")
    void rawCodecRoundTripThroughPersistencePath() {
        PersistenceProvider provider = new CommunityPersistenceProvider();
        EntityEncoder<MemorySegment> encoder = provider.rawEntityEncoder().orElseThrow();
        EntityDecoder<MemorySegment> decoder = provider.rawEntityDecoder().orElseThrow();

        String jdbcUrl = "jdbc:h2:mem:community_codec_it_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        PersistenceConfig config = PersistenceConfig.defaults(jdbcUrl, "sa", "");

        byte[] expectedPayload = "{\"kind\":\"vertex\",\"id\":42,\"active\":true}"
                .getBytes(StandardCharsets.UTF_8);
        MemorySegment entity = MemorySegment.ofArray(expectedPayload);

        try (PersistenceEngine engine = provider.createEngine(config);
             MemoryAllocator allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
             LoanedBuffer encoded = allocator.allocate(AllocationHint.MEDIUM);
             PersistenceConnection connection = engine.openConnection()) {

            connection.executeUpdate("CREATE TABLE codec_payloads (id BIGINT PRIMARY KEY, payload BINARY VARYING)");

            int written = encoder.encode(entity, encoded);
            byte[] encodedBytes = encoded.segment().asSlice(0, written).toArray(ValueLayout.JAVA_BYTE);

            try (PersistenceStatement insert = connection.prepare("INSERT INTO codec_payloads(id, payload) VALUES ($1, $2)")) {
                long rows = insert
                        .bindLong(0, 1L)
                        .bindBytes(1, encodedBytes)
                        .executeUpdate();
                assertThat(rows).isEqualTo(1L);
            }

            try (PersistenceStatement query = connection.prepare("SELECT payload FROM codec_payloads WHERE id = $1");
                 QueryResult result = query.bindLong(0, 1L).executeQuery()) {

                assertThat(result.next()).isTrue();
                RowCursor row = result.row();
                int payloadLength = row.getLength(0);
                assertThat(payloadLength).isEqualTo(expectedPayload.length);

                MemorySegment payloadSegment = row.getSegment(0);
                try (LoanedBuffer source = allocator.allocateNetwork(payloadLength)) {
                    source.segment().asSlice(0, payloadLength).copyFrom(payloadSegment.asSlice(0, payloadLength));
                    source.setSize(payloadLength);

                    MemorySegment decoded = decoder.decode(source, 0, payloadLength);
                    assertThat(decoded.toArray(ValueLayout.JAVA_BYTE)).containsExactly(expectedPayload);
                    assertThat(decoded.isReadOnly()).isTrue();
                }

                assertThat(result.next()).isFalse();
            }
        }
    }
}
