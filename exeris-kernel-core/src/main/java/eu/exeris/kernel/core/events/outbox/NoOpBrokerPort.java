/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.outbox;

import java.util.List;

/**
 * Core: No-op {@link OutboxBrokerPort} that acknowledges all events without delivery.
 *
 * <h2>Usage</h2>
 * <p>Used as the default broker port when no physical broker is configured
 * (standalone mode, integration tests, local development). All events polled
 * from the outbox are immediately marked as delivered — effectively draining
 * the outbox without publishing anywhere.
 *
 * @since 0.5.0
 */
public final class NoOpBrokerPort implements OutboxBrokerPort {

    /** Shared singleton — stateless, safe for all scopes. */
    public static final NoOpBrokerPort INSTANCE = new NoOpBrokerPort();

    private NoOpBrokerPort() {}

    @Override
    public int publish(List<OutboxEntry> batch) {
        return batch.size();
    }

    @Override
    public String brokerId() {
        return "noop";
    }
}
