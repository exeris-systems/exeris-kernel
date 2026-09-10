/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

/**
 * Core: what identifies a compiled plan in the catalog — a definition name <em>and</em> a version.
 *
 * <p>Keying by both lets versions of one definition coexist in the plan catalog (ADR-064): a
 * redeployed definition is compiled and stored under its own version rather than overwriting the
 * plan an in-flight saga parked under, and each saga resumes against the version its own snapshot
 * names, never against whatever is newest.
 *
 * @param name    the definition name; never {@code null}
 * @param version the declared definition version
 * @since 0.11
 */
/* default */ record PlanKey(String name, int version) {
}
