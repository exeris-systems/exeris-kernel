/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.nativeimage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Package-private helper validating discovered declaration sites against allowlists.
 */
final class AllowlistMatcher {

    private AllowlistMatcher() {
    }

    static void assertMatches(List<DeclarationSite> sites,
                              Collection<DeclarationSiteScanner.AllowlistEntry> allowlist) {
        List<String> errors = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        Map<DeclarationSiteScanner.AllowlistEntry, Integer> matchCounts = new LinkedHashMap<>();

        for (DeclarationSiteScanner.AllowlistEntry entry : allowlist) {
            if (entry.disposition().isBlank()) {
                errors.add("blank disposition for allowlist entry: " + formatEntry(entry));
            }
            String key = entry.ownerClass() + "#"
                    + (entry.methodName() != null ? entry.methodName() : "*") + "#" + entry.api();
            if (!seenKeys.add(key)) {
                errors.add("duplicate allowlist entry for " + formatEntry(entry));
            }
            matchCounts.put(entry, 0);
        }

        for (DeclarationSite site : sites) {
            boolean matched = false;
            for (DeclarationSiteScanner.AllowlistEntry entry : allowlist) {
                if (entry.ownerClass().equals(site.declaringClass())
                        && entry.api().equals(site.api())
                        && (entry.methodName() == null || entry.methodName().equals(site.methodName()))) {
                    matchCounts.put(entry, matchCounts.get(entry) + 1);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add("new declaration site " + site.declaringClass() + "." + site.methodName()
                        + " → " + site.api() + ": add metadata coverage or a disposition");
            }
        }

        for (Map.Entry<DeclarationSiteScanner.AllowlistEntry, Integer> entry : matchCounts.entrySet()) {
            if (entry.getValue() == 0 && !entry.getKey().disposition().isBlank()) {
                errors.add("stale allowlist entry for " + formatEntry(entry.getKey())
                        + ": no matching declaration sites found");
            }
        }

        if (!errors.isEmpty()) {
            throw new AssertionError(String.join("\n", errors));
        }
    }

    private static String formatEntry(DeclarationSiteScanner.AllowlistEntry entry) {
        String target = entry.methodName() != null
                ? entry.ownerClass() + "." + entry.methodName()
                : entry.ownerClass();
        return target + " → " + entry.api();
    }
}
