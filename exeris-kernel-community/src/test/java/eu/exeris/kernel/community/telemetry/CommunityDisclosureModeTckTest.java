/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.tck.contract.exceptions.AbstractDisclosureModeTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community binding for {@link AbstractDisclosureModeTck}.
 *
 * <p>The disclosure contract is profile-driven and binding-agnostic, so no overrides
 * are required. Sink-level enforcement (e.g., {@code Slf4jTelemetrySink}) is covered
 * separately by {@link Slf4jTelemetrySinkDisclosureTest}.
 */
@DisplayName("Community: ExceptionDisclosure TCK")
class CommunityDisclosureModeTckTest extends AbstractDisclosureModeTck {
}
