/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Platform.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions.events;

/**
 * Root exception for all event engine failures.
 *
 * @since 0.5.0
 */
public class EventEngineException extends RuntimeException {

    public EventEngineException(String message) {
        super(message);
    }

    public EventEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}

