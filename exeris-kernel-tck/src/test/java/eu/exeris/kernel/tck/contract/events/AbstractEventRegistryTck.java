/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.events.EventRegistryException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link EventRegistry} contract verification.
 *
 * <h2>EVENT-205 — Closing the Registry TCK Gap</h2>
 * <p>The {@code events.md} subsystem doc has long flagged
 * {@code EX-EVENT-6003} (registry conflict) as a TCK gap; this suite is the binding-agnostic
 * contract that every {@link EventRegistry} implementation must satisfy.
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>Registering a type with a fresh name + fresh ordinal succeeds and is observable
 *       via {@link EventRegistry#resolve}, {@link EventRegistry#ordinalOf} and
 *       {@link EventRegistry#size}.</li>
 *   <li>Re-registering the <em>identical</em> {@link EventTypeSpec} is idempotent
 *       (no-op; no exception).</li>
 *   <li>Registering a different type name with an ordinal already in use throws
 *       {@link EventRegistryException} carrying error code
 *       {@value KernelErrorCodes#EX_EVENT_6003} and {@code rawArgs == [conflictingName, ordinal]}.</li>
 *   <li>Registering the same name with a different ordinal throws
 *       {@link EventRegistryException} with the same error code; rawArgs carry the
 *       conflicting registration shape.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityEventRegistryTckTest extends AbstractEventRegistryTck {
 *     \@Override protected EventEngine createEngine() {
 *         return new CommunityEventProvider().createEngine(EventEngineConfig.defaults());
 *     }
 * }
 * }</pre>
 *
 * @since 0.7.0
 */
@DisplayName("EventRegistry TCK — EVENT-205 ordinal conflict contract")
public abstract class AbstractEventRegistryTck {

    /** Creates a fresh, not-yet-started {@link EventEngine}. The TCK does not call {@code start()}. */
    protected abstract EventEngine createEngine();

    private static final int    ORDINAL_USER_CREATED  = 1_001;
    private static final int    ORDINAL_ORDER_PLACED  = 1_002;
    private static final String TYPE_USER_CREATED     = "RegistryTckUserCreated";
    private static final String TYPE_ORDER_PLACED     = "RegistryTckOrderPlaced";
    private static final String TYPE_DIFFERENT        = "RegistryTckDifferent";

    private EventEngine engine;
    private EventRegistry registry;

    @BeforeEach
    final void setUp() {
        engine = createEngine();
        registry = engine.registry();
    }

    @AfterEach
    final void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Nested
    @DisplayName("Successful registration")
    class SuccessfulRegistration {

        @Test
        @DisplayName("register() with a fresh name and ordinal is observable via resolve/ordinalOf/size")
        void registerFreshTypeIsObservable() {
            int sizeBefore = registry.size();

            registry.register(EventTypeSpec.of(TYPE_USER_CREATED, ORDINAL_USER_CREATED));

            assertThat(registry.resolve(TYPE_USER_CREATED))
                    .as("resolve() MUST return the registered spec")
                    .isNotNull();
            assertThat(registry.ordinalOf(TYPE_USER_CREATED))
                    .as("ordinalOf() MUST return the registered ordinal")
                    .isEqualTo(ORDINAL_USER_CREATED);
            assertThat(registry.size())
                    .as("size() MUST grow by 1 after a fresh registration")
                    .isEqualTo(sizeBefore + 1);
        }

        @Test
        @DisplayName("register() is idempotent for the IDENTICAL EventTypeSpec — no exception, size unchanged")
        void registerSameSpecTwiceIsIdempotent() {
            EventTypeSpec spec = EventTypeSpec.of(TYPE_ORDER_PLACED, ORDINAL_ORDER_PLACED);
            registry.register(spec);
            int sizeAfterFirst = registry.size();

            assertThatCode(() -> registry.register(spec))
                    .as("Re-registering the identical EventTypeSpec MUST NOT throw")
                    .doesNotThrowAnyException();

            assertThat(registry.size())
                    .as("size() MUST be unchanged after idempotent re-registration")
                    .isEqualTo(sizeAfterFirst);
        }
    }

    @Nested
    @DisplayName("Ordinal conflict — EX-EVENT-6003")
    class OrdinalConflict {

        @Test
        @DisplayName("Different name with an ordinal already in use throws EX-EVENT-6003 with rawArgs [name, ordinal]")
        void differentNameSameOrdinalThrowsConflict() {
            registry.register(EventTypeSpec.of(TYPE_USER_CREATED, ORDINAL_USER_CREATED));

            assertThatThrownBy(() -> registry.register(EventTypeSpec.of(TYPE_DIFFERENT, ORDINAL_USER_CREATED)))
                    .as("EventRegistry MUST raise EX-EVENT-6003 on duplicate-ordinal registration")
                    .isInstanceOf(EventRegistryException.class)
                    .satisfies(ex -> {
                        EventRegistryException registryEx = (EventRegistryException) ex;
                        assertThat(registryEx.errorCode())
                                .as("errorCode MUST be %s", KernelErrorCodes.EX_EVENT_6003)
                                .isEqualTo(KernelErrorCodes.EX_EVENT_6003);
                        assertThat(registryEx.rawArgs())
                                .as("rawArgs MUST follow the [String eventType, int ordinal] layout")
                                .containsExactly(TYPE_DIFFERENT, ORDINAL_USER_CREATED);
                    });
        }

        @Test
        @DisplayName("Same name with a different ordinal throws EX-EVENT-6003")
        void sameNameDifferentOrdinalThrowsConflict() {
            registry.register(EventTypeSpec.of(TYPE_USER_CREATED, ORDINAL_USER_CREATED));

            assertThatThrownBy(() -> registry.register(EventTypeSpec.of(TYPE_USER_CREATED, ORDINAL_ORDER_PLACED)))
                    .as("Re-registering the same name with a different ordinal MUST raise EX-EVENT-6003")
                    .isInstanceOf(EventRegistryException.class)
                    .satisfies(ex -> {
                        EventRegistryException registryEx = (EventRegistryException) ex;
                        assertThat(registryEx.errorCode())
                                .isEqualTo(KernelErrorCodes.EX_EVENT_6003);
                        assertThat(registryEx.rawArgs())
                                .as("rawArgs index 0 MUST be the conflicting type name")
                                .isNotEmpty();
                        assertThat(registryEx.rawArgs()[0])
                                .isEqualTo(TYPE_USER_CREATED);
                    });
        }
    }
}
