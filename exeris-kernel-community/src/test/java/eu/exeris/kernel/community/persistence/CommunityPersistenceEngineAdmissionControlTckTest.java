/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.tck.contract.persistence.AbstractPersistenceEngineAdmissionControlTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community binding for abstract admission-control TCK.
 */
@DisplayName("L1 Binding: CommunityPersistenceEngine#canServiceRequest()")
class CommunityPersistenceEngineAdmissionControlTckTest extends AbstractPersistenceEngineAdmissionControlTck {

    @Override
    protected PersistenceEngine createEngine() {
        PersistenceConfig config = PersistenceConfig.production(
                "jdbc:h2:mem:exeris_tck_admission_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,
                1,
                1
        );
        return new CommunityPersistenceProvider().createEngine(config);
    }
}
