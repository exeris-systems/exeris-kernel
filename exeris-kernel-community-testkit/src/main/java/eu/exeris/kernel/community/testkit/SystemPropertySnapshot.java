/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.testkit;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures system properties a fixture is about to overwrite, and puts them back afterwards.
 *
 * <p>Kernel configuration is read from system properties at boot, so a fixture has to set them before
 * booting. Without a restore, the first fixture in a JVM silently reconfigures every suite that runs
 * after it — a failure that surfaces as an unrelated test failing only when run in a particular order.
 *
 * <p>Testkit plumbing, not fixture API. Public only so the fixture subpackages can reach it.
 *
 * @param keys   the property names captured
 * @param values the values at capture time, {@code null} where the property was unset
 * @since 0.11.0
 */
public record SystemPropertySnapshot(List<String> keys, List<String> values) {

    /**
     * Records the current value of each property.
     *
     * @param propertyKeys the property names to capture
     * @return a snapshot that {@link #restore()} can replay
     */
    public static SystemPropertySnapshot capture(String... propertyKeys) {
        List<String> capturedKeys = List.of(propertyKeys);
        List<String> snapshotValues = new ArrayList<>(capturedKeys.size());
        for (String key : capturedKeys) {
            snapshotValues.add(System.getProperty(key));
        }
        return new SystemPropertySnapshot(capturedKeys, snapshotValues);
    }

    /** Restores every captured property, clearing the ones that were unset at capture time. */
    public void restore() {
        for (int index = 0; index < keys.size(); index++) {
            String value = values.get(index);
            if (value == null) {
                System.clearProperty(keys.get(index));
            } else {
                System.setProperty(keys.get(index), value);
            }
        }
    }
}
