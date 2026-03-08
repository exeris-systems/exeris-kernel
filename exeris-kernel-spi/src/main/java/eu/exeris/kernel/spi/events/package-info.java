/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
/**
 * Exeris Kernel Events SPI — The "Invisible Wall" for event-driven subsystems.
 *
 * <h2>Purpose</h2>
 * <p>This package defines the <b>pure contracts</b> for the Exeris event engine.
 * Implementation-blind: SPI types and signatures must not depend on or reference specific
 * memory APIs, persistence implementations, or native transport mechanisms.
 *
 * <h2>SPI Hierarchy</h2>
 * <pre>
 * EventProvider        ← ServiceLoader entry point (META-INF/services)
 *   └─ EventEngine     ← Composite facade: bus + queue + loop + registry
 *       ├─ EventBus    ← Pub/sub: publish + subscribe + unsubscribe
 *       ├─ EventQueue  ← Durable queue: push + poll + drain
 *       ├─ EventLoop   ← Async processing: start + stop + registerProcessor
 *       └─ EventRegistry ← Type system: register + resolve
 * </pre>
 *
 * <h2>Data Carriers</h2>
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.events.EventDescriptor} — Valhalla-ready value record.</li>
 *   <li>{@link eu.exeris.kernel.spi.events.EventTypeSpec} — Immutable type metadata.</li>
 *   <li>{@link eu.exeris.kernel.spi.events.SubscriptionToken} — Opaque unsubscription handle.</li>
 * </ul>
 *
 * <h2>Propagation</h2>
 * <p>The resolved {@link eu.exeris.kernel.spi.events.EventEngine} is bound to
 * {@link eu.exeris.kernel.spi.context.KernelProviders#EVENT_ENGINE} during bootstrap
 * and inherited by all virtual threads via {@link java.lang.ScopedValue} semantics.
 *
 * @since 0.5.0
 */
package eu.exeris.kernel.spi.events;

