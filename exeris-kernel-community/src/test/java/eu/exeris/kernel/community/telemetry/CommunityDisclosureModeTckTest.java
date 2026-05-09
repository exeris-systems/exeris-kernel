/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
