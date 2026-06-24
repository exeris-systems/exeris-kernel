/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests pinning {@link KafkaEventProvider}'s discovery priority.
 *
 * <p>{@link KafkaEventProvider#PRIORITY} is a semantically significant constant: Kafka must
 * outrank the in-memory Community {@code EventProvider} ({@code 0}) on {@code ServiceLoader}
 * resolution, yet stay below the Enterprise tier slot ({@code 100}). That makes {@code 50}
 * intra-Community precedence, not the open-core tier convention. This test catches a silent
 * drift of that value (the config provider side has equivalent {@code isEqualTo(0)} pins).
 */
@DisplayName("KafkaEventProvider — discovery priority")
class KafkaEventProviderTest {

    @Test
    @DisplayName("priority() is 50 — intra-Community precedence, below the Enterprise slot (100)")
    void priorityIsIntraCommunitySlot() {
        assertThat(new KafkaEventProvider().priority())
                .as("priority() must return the PRIORITY constant")
                .isEqualTo(KafkaEventProvider.PRIORITY);
        assertThat(KafkaEventProvider.PRIORITY)
                .as("Kafka outranks in-memory Community (0) but stays below Enterprise (100)")
                .isEqualTo(50);
    }
}
