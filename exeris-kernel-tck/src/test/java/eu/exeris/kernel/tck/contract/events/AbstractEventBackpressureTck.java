/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for {@link EventBus} back-pressure contract verification.
 *
 * <h2>EVENT-205b — Closing the Backpressure TCK Gap</h2>
 * <p>{@code events.md} has long flagged {@code EX-EVENT-6002} as a TCK gap. This suite is the
 * binding-agnostic contract that every <em>queueing-bus</em> {@link EventEngine} implementation
 * built with a <em>fail-fast</em> {@link EventEngineConfig} must satisfy when the bus queue is at
 * capacity. Drivers that bypass the local queue entirely (e.g. the Kafka driver, where the broker
 * itself is the durable queue) are out of scope for this contract — overflow surfaces via
 * driver-native error paths there, not via {@code EX-EVENT-6002} on the local queue.
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>Publishing a persistent event past the queue capacity throws an
 *       {@link EventBusException} carrying error code
 *       {@value KernelErrorCodes#EX_EVENT_6002}.</li>
 *   <li>The exception's {@code rawArgs} follow the documented Glass-Box layout
 *       {@code [String eventType, long queueDepth, long queueCapacity]}.</li>
 *   <li>Reported {@code queueCapacity} matches the configured queue capacity. {@code queueDepth}
 *       is bounded by capacity (a strict equality is binding-defined: heap impls observe depth
 *       == capacity at the moment of overflow; broker impls may report depth slightly below
 *       capacity if they reject pre-emptively).</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityEventBackpressureTckTest extends AbstractEventBackpressureTck {
 *     \@Override protected EventEngine createEngine(EventEngineConfig failFastConfig) {
 *         return new CommunityEventProvider().createEngine(failFastConfig);
 *     }
 * }
 * }</pre>
 *
 * @since 0.7.0
 */
@DisplayName("EventBus backpressure TCK — EVENT-205b EX-EVENT-6002 contract")
public abstract class AbstractEventBackpressureTck {

    /**
     * Creates a fresh, not-yet-started {@link EventEngine} configured with the supplied
     * {@code failFastConfig} (which always carries {@code busPublishFailFast=true} and a tiny
     * {@code queueCapacity}). The TCK calls {@code start()}.
     */
    protected abstract EventEngine createEngine(EventEngineConfig failFastConfig);

    /**
     * Optional template hook — the binding-specific config builder that the suite uses to
     * fabricate a fail-fast config with the requested queue capacity. The default implementation
     * uses {@link EventEngineConfig}'s 11-arg backward-compat constructor and overrides
     * {@code busPublishFailFast} via the canonical 12-arg constructor.
     */
    protected EventEngineConfig failFastConfig(int queueCapacity) {
        EventEngineConfig defaults = EventEngineConfig.communityDefaults();
        return new EventEngineConfig(
                defaults.engineName(),
                queueCapacity,
                defaults.batchSize(),
                defaults.partitionName(),
                defaults.partitionBytes(),
                defaults.slabDescriptorCount(),
                defaults.slabPayloadSmall(),
                defaults.slabPayloadMedium(),
                defaults.slabPayloadLarge(),
                false,                            // outboxEnabled — disabled to avoid PersistenceEngine wiring
                defaults.outboxBatchSize(),
                true                              // busPublishFailFast — the contract under test
        );
    }

    private static final String TYPE_BACKPRESSURE = "BackpressureTckEvent";
    private static final int    ORDINAL_BACKPRESSURE = 9_001;
    private static final int    QUEUE_CAPACITY      = 4;

    private EventEngine engine;

    @BeforeEach
    final void setUp() {
        // The engine is intentionally NOT started in this suite: a running EventLoop would
        // continuously drain the queue, hiding the overflow we want to observe. Holding the
        // engine in CREATED state lets the persistent-publish path enqueue but never drain,
        // so the (capacity+1)-th publish is the deterministic moment of capacity overflow.
        engine = createEngine(failFastConfig(QUEUE_CAPACITY));
        engine.registry().register(EventTypeSpec.ofPersistent(TYPE_BACKPRESSURE, ORDINAL_BACKPRESSURE));
    }

    @AfterEach
    final void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Persistent publish past capacity throws EX-EVENT-6002 with rawArgs [eventType, queueDepth, queueCapacity]")
    void publishOverflowRaisesPublishOverflow() {
        EventBus bus = engine.bus();
        // Saturate the queue; the events stay in the queue because no subscriber drains them.
        // Capacity + 1 publishes guarantees the (capacity+1)-th hits the overflow condition.
        EventBusException overflow = null;
        for (int i = 0; i < QUEUE_CAPACITY + 1; i++) {
            try {
                bus.publish(persistentDescriptor(), EventPayload.empty());
            } catch (EventBusException ex) {
                overflow = ex;
                break;
            }
        }

        assertThat(overflow)
                .as("Persistent publish past queueCapacity=%d MUST raise EX-EVENT-6002 within "
                        + QUEUE_CAPACITY + "+1 attempts", QUEUE_CAPACITY)
                .isNotNull();
        assertThat(overflow.errorCode())
                .as("errorCode MUST be %s", KernelErrorCodes.EX_EVENT_6002)
                .isEqualTo(KernelErrorCodes.EX_EVENT_6002);

        Object[] rawArgs = overflow.rawArgs();
        assertThat(rawArgs)
                .as("rawArgs MUST follow the [String eventType, long queueDepth, long queueCapacity] layout")
                .hasSize(3);
        assertThat(rawArgs[0])
                .as("rawArgs[0] MUST be the conflicting event type name")
                .isEqualTo(TYPE_BACKPRESSURE);
        assertThat(((Number) rawArgs[1]).longValue())
                .as("rawArgs[1] queueDepth MUST be in [1, queueCapacity]")
                .isBetween(1L, (long) QUEUE_CAPACITY);
        assertThat(((Number) rawArgs[2]).longValue())
                .as("rawArgs[2] queueCapacity MUST match the configured EventEngineConfig.queueCapacity")
                .isEqualTo((long) QUEUE_CAPACITY);
    }

    private EventDescriptor persistentDescriptor() {
        UUID id = UUID.randomUUID();
        return new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                ORDINAL_BACKPRESSURE,
                EventDescriptor.FLAG_PERSISTENT,
                System.currentTimeMillis());
    }
}
