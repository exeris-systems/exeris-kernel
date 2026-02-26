/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Platform.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions.events;

import eu.exeris.kernel.spi.events.EventRegistry;

/**
 * Thrown when an {@link EventRegistry} operation fails (duplicate registration with conflict,
 * registration after engine start in Enterprise mode).
 *
 * @since 0.5.0
 */
public class EventRegistryException extends EventEngineException {

    public EventRegistryException(String message) {
        super(message);
    }

    public EventRegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}

