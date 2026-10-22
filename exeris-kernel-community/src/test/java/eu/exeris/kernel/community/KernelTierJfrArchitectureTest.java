/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jdk.jfr.Event;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins single-phase JFR commit discipline across the Core and Community main code.
 *
 * <h2>The invariant</h2>
 * <p>A virtual thread that unmounts while a JFR event window is open straddles the carrier-bound
 * event writer, and the JVM can crash. The window is the span from {@link Event#begin()} to
 * {@link Event#commit()}, so nothing that can block, take a lock, park or perform I/O may run
 * inside it. Because that property cannot be read off a call's name, the rule is an allowlist: in a
 * window the only permitted calls are the methods of {@link Event} itself, a short list of
 * side-effect-free JDK calls, and the component accessors of two named SPI records,
 * {@code FlowEngineConfig} and {@code FlowEngineStats}, whose values are plain fields (see
 * {@link #isPermittedInWindow(JavaCall)}). A call into any other
 * Core, Community, SPI or library method is a violation even when it is harmless today, because the
 * callee may acquire a lock or do I/O tomorrow without touching the event class.
 *
 * <h2>What counts as a window</h2>
 * <ul>
 *   <li>A method that calls {@code begin()} directly opens a window at the first such call and
 *       closes it at its last {@code commit()}.</li>
 *   <li>A method whose declared result is an {@link Event} subtype and that begins an event, or
 *       calls such a method, <em>hands the open window to its caller</em>. That is a begin helper:
 *       the helper itself is checked from its begin point to its return, and every call to it is a
 *       begin point in the caller, so the caller's window is held to the same allowlist.</li>
 *   <li>Any other method that begins an event without committing it lets the window escape into
 *       code the rule cannot see, and is a violation.</li>
 * </ul>
 *
 * <h2>Reach and limits</h2>
 * <p>Main code of Core and Community only; test classes are not imported. Window bounds are source
 * line numbers, the only ordering bytecode metadata exposes, so two statements on one line are one
 * position. Lambda bodies are separate synthetic methods and are analysed as methods of their own
 * rather than as part of the enclosing window.
 */
@AnalyzeClasses(packages = "eu.exeris.kernel", importOptions = ImportOption.DoNotIncludeTests.class)
class KernelTierJfrArchitectureTest {

    private static final String CORE = "eu.exeris.kernel.core";
    private static final String COMMUNITY = "eu.exeris.kernel.community";

    /** Methods of {@link Event} that are allowed inside a window; they only touch the event itself. */
    private static final Set<String> EVENT_METHODS =
            Set.of("begin", "end", "commit", "shouldCommit", "isEnabled", "set");

    /**
     * Side-effect-free JDK calls, keyed {@code owner#name}. Every entry is needed by a call that
     * sits in a window today; none may block, lock or perform I/O.
     */
    private static final Map<String, String> JDK_ALLOWLIST = Map.of(
            "java.lang.Object#getClass", "reads the receiver's class pointer, no lookup",
            "java.lang.Class#getName", "returns the already-resolved class name string");

    /**
     * SPI records whose accessors may be read inside a window. They are immutable value carriers
     * whose accessors return a component field; a record outside this set must not be read there.
     */
    private static final Set<String> RECORD_ACCESSOR_OWNERS = Set.of(
            "eu.exeris.kernel.spi.flow.FlowEngineConfig",
            "eu.exeris.kernel.spi.flow.FlowEngineStats");

    @ArchTest
    static void coreAndCommunityClassesAreOnTheAnalysisClasspath(JavaClasses classes) {
        assertThat(countIn(classes, CORE))
                .as("Core classes must be analysable").isGreaterThan(0);
        assertThat(countIn(classes, COMMUNITY))
                .as("Community classes must be analysable").isGreaterThan(0);
    }

    @ArchTest
    static void theRuleInputHoldsBeginCommitWindows(JavaClasses classes) {
        // The rule below is "all methods should ...", which passes on an empty input. The
        // input that matters is the set of methods holding a window, so that set is asserted
        // non-empty and to contain a known two-phase site.
        Collection<JavaCodeUnit> inScope = classes.stream()
                .filter(KernelTierJfrArchitectureTest::isInScope)
                .flatMap(c -> c.getCodeUnits().stream())
                .toList();
        Windows windows = new Windows(inScope);
        Set<String> owners = windows.windowMethods().stream()
                .map(m -> m.getOwner().getName())
                .collect(Collectors.toSet());
        assertThat(windows.windowMethods())
                .as("no method holds a begin()/commit() window, so the allowlist rule checks nothing")
                .isNotEmpty();
        assertThat(owners)
                .as("the known two-phase site must be part of the rule's input")
                .contains("eu.exeris.kernel.core.persistence.TransactionLifecycleEvent");
    }

    @ArchTest
    static final ArchRule jfrWindowsHoldOnlyAllowlistedCalls = codeUnits()
            .that().areDeclaredInClassesThat().resideInAnyPackage(CORE + "..", COMMUNITY + "..")
            .should(holdOnlyAllowlistedCallsInJfrWindows())
            .because("A virtual thread that unmounts while a JFR event window is open straddles the "
                    + "carrier-bound event writer and the JVM can crash. Between begin() and commit() "
                    + "only field writes, Event methods and the side-effect-free JDK calls on the "
                    + "allowlist may run, and the window must close in the method that opened it "
                    + "unless that method hands the event to its caller.");

    private static ArchCondition<JavaCodeUnit> holdOnlyAllowlistedCallsInJfrWindows() {
        return new ArchCondition<>("hold only allowlisted calls between JFR begin() and commit()") {
            private Windows windows;

            @Override
            public void init(Collection<JavaCodeUnit> allObjectsToTest) {
                windows = new Windows(allObjectsToTest);
            }

            @Override
            public void check(JavaCodeUnit unit, ConditionEvents events) {
                List<JavaCall<?>> beginPoints = windows.beginPoints(unit);
                if (beginPoints.isEmpty()) {
                    return;
                }
                List<JavaCall<?>> commits = commitsOf(unit);
                boolean handsOver = windows.isBeginHelper(unit);
                if (commits.isEmpty() && !handsOver) {
                    events.add(SimpleConditionEvent.violated(unit, String.format(
                            "%s begins a JFR event at line %d and never commits it, so the window "
                                    + "escapes the method",
                            unit.getFullName(), firstLine(beginPoints))));
                    return;
                }
                int from = firstLine(beginPoints);
                int to = commits.isEmpty() ? Integer.MAX_VALUE : lastLine(commits);
                for (JavaCall<?> call : unit.getCallsFromSelf()) {
                    int line = call.getLineNumber();
                    if (line < from || line > to
                            || windows.isBeginPoint(call) || isPermittedInWindow(call)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(unit, String.format(
                            "%s calls %s at line %d between JFR begin() and commit(); only Event "
                                    + "methods and allowlisted side-effect-free calls are permitted there",
                            unit.getFullName(), call.getTarget().getFullName(), line)));
                }
            }
        };
    }

    /** Whether {@code call} may run while a JFR event window is open. */
    private static boolean isPermittedInWindow(JavaCall<?> call) {
        JavaClass owner = call.getTargetOwner();
        if (owner.isAssignableTo(Event.class)) {
            // A constructor of an event is allowed so that building the event as the argument of a
            // begin helper is not mistaken for work inside the window.
            return EVENT_METHODS.contains(call.getName()) || "<init>".equals(call.getName());
        }
        if (JDK_ALLOWLIST.containsKey(owner.getName() + "#" + call.getName())) {
            return true;
        }
        // An accessor of a named SPI record reads a component field.
        return RECORD_ACCESSOR_OWNERS.contains(owner.getName())
                && call.getTarget().getRawParameterTypes().isEmpty()
                && owner.getFields().stream().anyMatch(f -> f.getName().equals(call.getName()));
    }

    private static boolean isInScope(JavaClass c) {
        return c.getPackageName().startsWith(CORE) || c.getPackageName().startsWith(COMMUNITY);
    }

    private static boolean isEventCall(JavaCall<?> call, String name) {
        return name.equals(call.getName()) && call.getTargetOwner().isAssignableTo(Event.class);
    }

    private static List<JavaCall<?>> commitsOf(JavaCodeUnit unit) {
        return unit.getCallsFromSelf().stream()
                .filter(c -> isEventCall(c, "commit"))
                .<JavaCall<?>>map(c -> c)
                .toList();
    }

    private static int firstLine(List<JavaCall<?>> calls) {
        return calls.stream().mapToInt(JavaAccess::getLineNumber).min().orElse(0);
    }

    private static int lastLine(List<JavaCall<?>> calls) {
        return calls.stream().mapToInt(JavaAccess::getLineNumber).max().orElse(0);
    }

    private static long countIn(JavaClasses classes, String packagePrefix) {
        return classes.stream()
                .filter(c -> c.getPackageName().startsWith(packagePrefix))
                .count();
    }

    /** Begin points and begin helpers across a set of code units. */
    private static final class Windows {
        private final Map<String, JavaCodeUnit> helpers = new TreeMap<>();
        private final Collection<JavaCodeUnit> units;

        Windows(Collection<JavaCodeUnit> units) {
            this.units = units;
            boolean grew = true;
            while (grew) {
                grew = false;
                for (JavaCodeUnit unit : units) {
                    if (!helpers.containsKey(unit.getFullName())
                            && returnsEvent(unit) && !beginPoints(unit).isEmpty()) {
                        helpers.put(unit.getFullName(), unit);
                        grew = true;
                    }
                }
            }
        }

        boolean isBeginHelper(JavaCodeUnit unit) {
            return helpers.containsKey(unit.getFullName());
        }

        boolean isBeginPoint(JavaCall<?> call) {
            return isEventCall(call, "begin") || helpers.containsKey(call.getTarget().getFullName());
        }

        List<JavaCall<?>> beginPoints(JavaCodeUnit unit) {
            return unit.getCallsFromSelf().stream()
                    .filter(this::isBeginPoint)
                    .<JavaCall<?>>map(c -> c)
                    .toList();
        }

        /** Methods that hold a window: they have a begin point and a commit. */
        Set<JavaCodeUnit> windowMethods() {
            return units.stream()
                    .filter(u -> !beginPoints(u).isEmpty() && !commitsOf(u).isEmpty())
                    .collect(Collectors.toSet());
        }

        private static boolean returnsEvent(JavaCodeUnit unit) {
            return unit instanceof JavaMethod method
                    && method.getRawReturnType().isAssignableTo(Event.class);
        }
    }
}
