/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingIdentityTest {

    @ParameterizedTest(name = "{0} -> subsystem={1} test={2}")
    @CsvSource({
            "CommunityEventBusZeroAllocTckTest-EventBus-20260805-120000.jfr, eventbus, CommunityEventBusZeroAllocTckTest",
            "ZeroAllocationContract-StorageContext-20260805-120000.jfr, storagecontext, ZeroAllocationContract",
            "AllocationContract-Security-20260830-091500, security, AllocationContract",
    })
    void tckShapeIsIdentified(String filename, String subsystem, String testClass) {
        RecordingIdentity id = RecordingIdentity.fromFilename(filename);
        assertThat(id.source()).isEqualTo(RecordingIdentity.Source.FILENAME);
        assertThat(id.subsystem()).isEqualTo(subsystem);
        assertThat(id.testClass()).isEqualTo(testClass);
    }

    @ParameterizedTest(name = "{0} is not a TCK recording")
    @CsvSource({
            "surefire.jfr",
            "jmh-benchmarks.jfr",
            "pin-flowengine-steady-20260805-120000.jfr",
            "pin-transport-steady-20260805-120000.jfr",
            "hs_err_pid685117.jfr",
            "storage-bootstrap.jfr",
    })
    void otherShapesAreNot(String filename) {
        RecordingIdentity id = RecordingIdentity.fromFilename(filename);
        assertThat(id.source()).isEqualTo(RecordingIdentity.Source.NONE);
        assertThat(id.identified()).isFalse();
        assertThat(id.subsystem()).isNull();
    }
}
