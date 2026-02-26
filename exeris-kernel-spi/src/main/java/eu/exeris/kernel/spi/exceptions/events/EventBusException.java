/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Platform.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions.events;

import eu.exeris.kernel.spi.events.EventBus;

/**
 * Thrown when an {@link EventBus} operation fails (publish overflow, subscription error).
 *
 * @since 0.5.0
 */
public class EventBusException extends EventEngineException {

    public EventBusException(String message) {
        super(message);
    }

    public EventBusException(String message, Throwable cause) {
        super(message, cause);
    }
}

