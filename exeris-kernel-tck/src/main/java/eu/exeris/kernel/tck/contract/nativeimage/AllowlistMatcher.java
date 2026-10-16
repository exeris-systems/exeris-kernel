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

        Map<String, DeclarationSiteScanner.AllowlistEntry> wildcards = new LinkedHashMap<>();
        Map<String, List<DeclarationSiteScanner.AllowlistEntry>> specifics = new LinkedHashMap<>();

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

            String groupKey = entry.ownerClass() + "#" + entry.api();
            if (entry.methodName() == null) {
                wildcards.put(groupKey, entry);
            } else {
                specifics.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(entry);
            }
        }

        for (Map.Entry<String, DeclarationSiteScanner.AllowlistEntry> wildEntry : wildcards.entrySet()) {
            if (specifics.containsKey(wildEntry.getKey())) {
                errors.add("overlapping wildcard and method-specific allowlist entry for "
                        + formatEntry(wildEntry.getValue()));
            }
        }

        for (DeclarationSite site : sites) {
            DeclarationSiteScanner.AllowlistEntry matchedEntry = null;
            for (DeclarationSiteScanner.AllowlistEntry entry : allowlist) {
                if (entry.ownerClass().equals(site.declaringClass())
                        && entry.api().equals(site.api())
                        && (entry.methodName() == null || entry.methodName().equals(site.methodName()))) {
                    matchedEntry = entry;
                    break;
                }
            }
            if (matchedEntry != null) {
                matchCounts.put(matchedEntry, matchCounts.get(matchedEntry) + 1);
            } else {
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
