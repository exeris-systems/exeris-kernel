/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-100 §3 — a {@code stable} signature names no {@code preview} or {@code experimental} type.
 *
 * <p>A public or protected signature of a class that {@code tools/spi-api-diff/stability-surfaces.conf}
 * labels {@code stable} makes every change to the type it names a change to a {@code stable}
 * signature, which the compatibility gate labels by declaring class and so never sees. The rule reads
 * the labels from that file, so it cannot drift from the gate's own classification.
 *
 * <p>Signature means the field type, return type, parameter types, declared exceptions and the
 * type arguments of each, plus the supertypes of the class itself.
 */
@AnalyzeClasses(packages = "eu.exeris.kernel.spi")
public class StableSignatureArchitectureTest {

    private static final String CONF = "tools/spi-api-diff/stability-surfaces.conf";

    /**
     * Deprecated-for-removal bridges, by {@code Class#member}. They are the same {@code ScopedValue}
     * instances as the holders in the owning packages and are removed in the first 1.0 release
     * candidate, which is when this list is deleted.
     */
    private static final Set<String> REMOVED_IN_FIRST_RELEASE_CANDIDATE = Set.of(
            "KernelProviders#GRAPH_PROVIDER",
            "KernelProviders#GRAPH_ENGINE",
            "KernelProviders#graphEngine",
            "KernelProviders#TIME_SOURCE",
            "KernelProviders#timeSource");

    /**
     * Packages that are {@code preview} today and that ADR-100 §1 promotes to {@code stable} in the
     * single promotion commit (§5). A stable signature may name them only until that commit relabels
     * them; the commit deletes the entry together with the relabel. {@code spi.graph},
     * {@code spi.contract}, {@code spi.time} and {@code spi.websocket} stay {@code preview} at 1.0 and
     * are deliberately absent: no stable signature may name them.
     */
    private static final Set<String> PROMOTED_BY_THE_PROMOTION_COMMIT = Set.of(
            "eu.exeris.kernel.spi.security.",
            "eu.exeris.kernel.spi.events.",
            "eu.exeris.kernel.spi.crypto.",
            "eu.exeris.kernel.spi.scheduling.",
            "eu.exeris.kernel.spi.storage.",
            "eu.exeris.kernel.spi.http.");

    @ArchTest
    static void stableSignaturesNameNoPreviewType(JavaClasses classes) {
        Map<String, String> labels = readLabels(repositoryRoot().resolve(CONF));
        assertThat(labels).as("labels read from " + CONF).isNotEmpty();

        List<String> violations = new ArrayList<>();
        int stableClasses = 0;
        for (JavaClass owner : classes) {
            if (!"stable".equals(labelOf(owner, labels)) || !isExposed(owner)) {
                continue;
            }
            stableClasses++;
            collect(owner, labels, violations);
        }

        assertThat(stableClasses).as("stable classes inspected").isGreaterThan(0);
        assertThat(violations)
                .as("ADR-100 §3: a stable signature names no preview or experimental type")
                .isEmpty();
    }

    private static void collect(JavaClass owner, Map<String, String> labels, List<String> violations) {
        Set<JavaClass> supertypes = new LinkedHashSet<>(owner.getRawInterfaces());
        owner.getRawSuperclass().ifPresent(supertypes::add);
        for (JavaClass supertype : supertypes) {
            report(owner, "<supertype>", supertype, labels, violations);
        }
        for (JavaField field : owner.getFields()) {
            if (isExposed(field.getModifiers())) {
                for (JavaClass type : field.getType().getAllInvolvedRawTypes()) {
                    report(owner, field.getName(), type, labels, violations);
                }
            }
        }
        for (JavaCodeUnit unit : owner.getCodeUnits()) {
            if (!isExposed(unit.getModifiers())) {
                continue;
            }
            Set<JavaClass> types = new LinkedHashSet<>(unit.getRawReturnType().getAllInvolvedRawTypes());
            unit.getParameterTypes().forEach(p -> types.addAll(p.getAllInvolvedRawTypes()));
            unit.getExceptionTypes().forEach(e -> types.addAll(e.getAllInvolvedRawTypes()));
            for (JavaClass type : types) {
                report(owner, unit.getName(), type, labels, violations);
            }
        }
    }

    private static void report(JavaClass owner, String member, JavaClass named,
                               Map<String, String> labels, List<String> violations) {
        String level = labelOf(named, labels);
        if (!"preview".equals(level) && !"experimental".equals(level)) {
            return;
        }
        if (REMOVED_IN_FIRST_RELEASE_CANDIDATE.contains(topLevel(owner).getSimpleName() + "#" + member)
                || PROMOTED_BY_THE_PROMOTION_COMMIT.stream().anyMatch(named.getName()::startsWith)) {
            return;
        }
        violations.add(owner.getName() + "#" + member + " names " + level + " type " + named.getName());
    }

    private static boolean isExposed(JavaClass type) {
        return type.getModifiers().contains(JavaModifier.PUBLIC)
                || type.getModifiers().contains(JavaModifier.PROTECTED);
    }

    private static boolean isExposed(Set<JavaModifier> modifiers) {
        return modifiers.contains(JavaModifier.PUBLIC) || modifiers.contains(JavaModifier.PROTECTED);
    }

    private static JavaClass topLevel(JavaClass type) {
        JavaClass current = type;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }

    /** The label of a class: an exact class entry wins over a package entry. */
    private static String labelOf(JavaClass type, Map<String, String> labels) {
        String name = topLevel(type).getName();
        String exact = labels.get(name);
        if (exact != null) {
            return exact;
        }
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            String key = entry.getKey();
            if (name.startsWith(key + ".") && key.length() > bestLength) {
                best = entry.getValue();
                bestLength = key.length();
            }
        }
        return best;
    }

    /** Parses {@code level=entry,entry,...} blocks, honouring trailing-backslash continuations. */
    static Map<String, String> readLabels(Path conf) {
        List<String> lines;
        try {
            lines = Files.readAllLines(conf);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + conf, e);
        }
        Map<String, String> labels = new LinkedHashMap<>();
        StringBuilder logical = new StringBuilder();
        for (String raw : lines) {
            String line = raw.strip();
            if (logical.isEmpty() && (line.isEmpty() || line.startsWith("#"))) {
                continue;
            }
            if (line.endsWith("\\")) {
                logical.append(line, 0, line.length() - 1);
                continue;
            }
            logical.append(line);
            int eq = logical.indexOf("=");
            String level = logical.substring(0, eq).strip();
            for (String entry : logical.substring(eq + 1).split(",")) {
                if (!entry.isBlank()) {
                    labels.put(entry.strip(), level);
                }
            }
            logical.setLength(0);
        }
        return labels;
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(CONF))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root holding " + CONF).isNotNull();
        return dir;
    }
}
