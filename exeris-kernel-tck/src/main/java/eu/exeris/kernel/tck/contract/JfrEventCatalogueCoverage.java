/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assertion a JFR event-class warm-up catalogue is checked with: its two buckets and the event
 * classes a module actually declares are the same set.
 *
 * <p>An instrument, like {@link JfrPinningMonitor} beside it, and not a contract any binding
 * implements. It is here because two modules need it and neither can see the other's tests: the
 * Community guard enumerates through ArchUnit, the Kafka driver's through its own
 * {@code CodeSource}, and the driver is the module that depends on Community rather than the other
 * way round. Each module keeps its own enumeration — those are different mechanisms, not copies —
 * and shares the judgement made about the result, which is identical either way.
 *
 * <p>Plain collections rather than a catalogue type: this module sees the SPI and nothing below it,
 * and a warm-up catalogue is a Core concern.
 *
 * @since 0.12
 * @see JfrPinningMonitor
 */
public final class JfrEventCatalogueCoverage {

    private JfrEventCatalogueCoverage() {
        // Static instrument — no instances.
    }

    /**
     * Fails unless every declared event class is in exactly one bucket, and every name in a bucket
     * is a declared event class.
     *
     * <p>Both directions, and both matter. A declared class in neither bucket is one nobody
     * classified — it initialises on whichever virtual thread emits it first, which is the defect
     * the catalogues exist to remove. A bucket name that is not declared is a class renamed, moved
     * or deleted, which fails silently at runtime because a warm-up logs an unresolvable name and
     * carries on.
     *
     * <p>The empty-set check comes first: an enumeration that found nothing would satisfy every
     * assertion below it.
     *
     * @param what     what was enumerated, named in the failure messages — a module prefix, or a
     *                 phrase like {@code "this module"}; must not be {@code null}
     * @param declared the event classes the module declares, by binary name; must not be
     *                 {@code null}
     * @param hotPath  the names the catalogue warms; must not be {@code null}
     * @param cold     the names the catalogue deliberately leaves cold; must not be {@code null}
     */
    public static void assertBucketsMatchDeclared(String what, Set<String> declared,
                                                  List<String> hotPath, List<String> cold) {
        assertThat(declared)
                .withFailMessage("no JFR event classes were found for %s — the layout changed and every "
                        + "assertion below would pass on the empty set", what)
                .isNotEmpty();

        // Each subject computed once. AssertJ's withFailMessage(String, Object...) is eager, so an
        // expression passed as both the subject and a message argument is computed twice on every
        // run, failing or not.
        Set<String> inBothBuckets = intersection(hotPath, cold);
        assertThat(inBothBuckets)
                .withFailMessage("class(es) in both catalogue buckets at once for %s: %s", what, inBothBuckets)
                .isEmpty();

        Set<String> warmedTwice = duplicatesWithin(hotPath);
        assertThat(warmedTwice)
                .withFailMessage("name(s) listed twice in the hot-path map for %s: %s", what, warmedTwice)
                .isEmpty();

        Set<String> coldTwice = duplicatesWithin(cold);
        assertThat(coldTwice)
                .withFailMessage("name(s) listed twice in deliberatelyCold() for %s: %s", what, coldTwice)
                .isEmpty();

        Set<String> classified = new TreeSet<>(hotPath);
        classified.addAll(cold);

        Set<String> unclassified = difference(declared, classified);
        assertThat(unclassified)
                .withFailMessage("JFR event class(es) for %s are in neither catalogue bucket. Add each to its "
                        + "hot-path group, or to deliberatelyCold() with the reason it can wait: %s",
                        what, unclassified)
                .isEmpty();

        Set<String> unresolved = difference(classified, declared);
        assertThat(unresolved)
                .withFailMessage("catalogue name(s) for %s do not resolve to a JFR event class there — renamed, "
                        + "moved, or deleted: %s", what, unresolved)
                .isEmpty();
    }

    private static Set<String> difference(Set<String> from, Set<String> remove) {
        Set<String> out = new TreeSet<>(from);
        out.removeAll(remove);
        return out;
    }

    private static Set<String> duplicatesWithin(List<String> names) {
        Set<String> seen = new TreeSet<>();
        Set<String> twice = new TreeSet<>();
        for (String name : names) {
            if (!seen.add(name)) {
                twice.add(name);
            }
        }
        return twice;
    }

    private static Set<String> intersection(List<String> left, List<String> right) {
        Set<String> out = new TreeSet<>(left);
        out.retainAll(right);
        return out;
    }
}
