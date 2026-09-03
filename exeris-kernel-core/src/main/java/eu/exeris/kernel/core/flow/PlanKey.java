/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

/**
 * Core: what identifies a compiled plan in the catalog — a definition name <em>and</em> a version.
 *
 * <p>Until 0.11 the catalog was keyed by name alone, so registering a changed definition evicted the
 * one every in-flight saga had parked under, and resume rebound those sagas to whatever was newest.
 * ADR-062 made that rebinding fail closed; ADR-064 makes it unnecessary by letting versions coexist.
 *
 * @param name    the definition name; never {@code null}
 * @param version the declared definition version
 * @since 0.11.0
 */
/* default */ record PlanKey(String name, int version) {
}
