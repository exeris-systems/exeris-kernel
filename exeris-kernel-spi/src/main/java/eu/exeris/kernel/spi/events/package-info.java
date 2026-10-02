/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * {@link eu.exeris.kernel.spi.context.KernelProviders#EVENT_ENGINE} once during bootstrap, for the
 * kernel's lifetime. A {@link java.lang.ScopedValue} binding reaches the thread that established it
 * and the subtasks forked inside its scope, not a thread started any other way, so code the kernel
 * runs on a thread it starts (a request or stream handler, for example) receives the engine or its
 * bus through its constructor.
 *
 * <h2>Where ordering lives</h2>
 * <p>{@link eu.exeris.kernel.spi.events.EventBus} is unordered by design; per-stream total
 * ordering and append-with-expected-version optimistic concurrency belong to the durable-log
 * surface, {@link eu.exeris.kernel.spi.events.EventStreamAppender} and
 * {@link eu.exeris.kernel.spi.events.EventStreamReader}. Persistence owns neither.
 *
 * @since 0.5
 */
package eu.exeris.kernel.spi.events;

