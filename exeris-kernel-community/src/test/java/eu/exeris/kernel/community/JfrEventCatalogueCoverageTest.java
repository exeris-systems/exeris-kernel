/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import eu.exeris.kernel.community.telemetry.CommunityJfrEventCatalogue;
import eu.exeris.kernel.core.telemetry.jfr.CoreJfrEventCatalogue;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.tck.contract.JfrEventCatalogueCoverage;
import jdk.jfr.Event;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one property that keeps the warm-up catalogues honest: every JFR event class in the
 * kernel is in exactly one bucket — warmed when its subsystem starts, or deliberately left cold.
 *
 * <h2>Why a guard and not a review habit</h2>
 * <p>A catalogue of class <em>names</em> cannot be checked by the compiler: two thirds of this
 * kernel's event classes are package-private, so no single class can name them with a literal, and a
 * name that no longer resolves fails silently at runtime — {@code JfrEventWarmup} logs it and carries
 * on, which is the right behaviour there and useless as enforcement. Nothing else would notice a new
 * event class that nobody classified, and the failure it causes is the one this whole line of work
 * started from: a cold class initialising on a virtual thread, pinning a carrier, and surfacing as a
 * carrier-pinning regression somewhere else entirely.
 *
 * <p>So the check runs both ways. Every name in a catalogue must resolve to a real
 * {@link Event} subclass in the module it claims, and every {@link Event} subclass the module
 * actually declares must appear in one of the two buckets.
 *
 * <p>Two seams carry the warm-up and each has a guard here: every Community subsystem warms its own
 * group from its {@code start()}, and {@code SubsystemOrchestrator.doStart} warms the Core group of
 * a subsystem that reports {@code isRunning()}. The second was added after the first shipped
 * without it — the Core half ran above every subsystem's guard, so the rule this file enforces held
 * for one half of the warm-up and not the other.
 *
 * <p>This module is the first point in the reactor where Core and Community are both on one
 * classpath, which is why the guard lives here — the same reasoning, and the same placement, as
 * {@code KernelTierDirectionArchitectureTest}. Test classes are excluded: a fixture subsystem in a
 * test has no hot path to warm, and judging one would only teach the next author to work around
 * this file.
 */
@AnalyzeClasses(packages = "eu.exeris.kernel", importOptions = ImportOption.DoNotIncludeTests.class)
class JfrEventCatalogueCoverageTest {

    @ArchTest
    static void coreEventClassesAreAllClassified(JavaClasses classes) {
        assertClassified(classes, "eu.exeris.kernel.core.",
                CoreJfrEventCatalogue.catalogue().allHotPath(),
                CoreJfrEventCatalogue.catalogue().deliberatelyCold());
    }

    @ArchTest
    static void communityEventClassesAreAllClassified(JavaClasses classes) {
        assertClassified(classes, "eu.exeris.kernel.community.",
                CommunityJfrEventCatalogue.catalogue().allHotPath(),
                CommunityJfrEventCatalogue.catalogue().deliberatelyCold());
    }

    @ArchTest
    static void everyCatalogueKeyIsASubsystemThatCanWarmIt(JavaClasses classes) {
        // The direction that matters. A group keyed by a name no subsystem reports is never warmed,
        // and nothing else would say so — its entries would simply sit there looking classified.
        // This check earns its place: it is what a `telemetry` group, keyed on a subsystem this
        // kernel does not have, was caught by.
        Set<String> subsystemNames = communitySubsystemNames(classes);

        assertThat(subsystemNames)
                .withFailMessage("no Community subsystem classes were found; every check below would pass vacuously")
                .isNotEmpty();

        Set<String> keys = new TreeSet<>(CoreJfrEventCatalogue.catalogue().warmedSubsystems());
        keys.addAll(CommunityJfrEventCatalogue.catalogue().warmedSubsystems());

        assertThat(difference(keys, subsystemNames))
                .withFailMessage("catalogue group(s) keyed on a name no Subsystem reports, so nothing warms them: "
                        + "%s — either the key is wrong, or those events belong in deliberatelyCold()",
                        difference(keys, subsystemNames))
                .isEmpty();
    }

    @ArchTest
    static void everySubsystemWarmsItsOwnCatalogueGroup(JavaClasses classes) {
        // The other direction, and the one a single shared hook got wrong: three subsystems
        // implemented Subsystem directly and never reached the base class the warm-up hung off, so
        // their groups — memory's allocation events among them — were never warmed by anything.
        // The call now sits in each start(); this is what keeps a thirteenth subsystem from
        // forgetting it.
        List<JavaClass> subsystems = concreteSubsystems(classes);

        assertThat(subsystems)
                .withFailMessage("no concrete Community Subsystem classes were found; this check would pass vacuously")
                .isNotEmpty();

        for (JavaClass subsystem : subsystems) {
            assertThat(warmsItsGroup(subsystem))
                    .withFailMessage("%s never calls CommunityJfrEventCatalogue.warmHotPath from start() — its own "
                            + "or an inherited one — so its hot-path event classes initialise on whichever virtual "
                            + "thread emits one first", subsystem.getSimpleName())
                    .isTrue();
        }
    }

    @ArchTest
    static void everySubsystemAnswersIsRunningItself(JavaClasses classes) {
        // The precondition for the guard below it. The orchestrator warms a subsystem's Core group
        // only when it reports isRunning(), so a subsystem that never overrides the method inherits
        // the interface default false and silently loses that half of its warm-up — and, for the
        // same reason, is never stopped, because shutdown() reads the same answer.
        //
        // CommunityMemorySubsystem was exactly that, and nothing said so: the lifecycle TCK's own
        // javadoc recorded the hole (it asserted false-after-stop with no true-after-start to pair
        // it with) and only three subsystems bind that TCK at all. This is structural and covers
        // all of them: the declaration has to exist somewhere in the class's own hierarchy.
        List<JavaClass> subsystems = concreteSubsystems(classes);

        assertThat(subsystems)
                .withFailMessage("no concrete Community Subsystem classes were found; this check would pass vacuously")
                .isNotEmpty();

        for (JavaClass subsystem : subsystems) {
            assertThat(declaresIsRunning(subsystem))
                    .withFailMessage("%s neither declares isRunning() nor inherits a declaration, so it answers the "
                            + "Subsystem default false — the orchestrator will never stop it and never warm its Core "
                            + "event group. Extend AbstractCommunitySubsystem and call markRunning, or override it",
                            subsystem.getSimpleName())
                    .isTrue();
        }
    }

    /**
     * Whether this class or one of its superclasses declares {@code isRunning()} — as opposed to
     * inheriting the {@link Subsystem} interface default.
     *
     * @param subsystem a concrete Community subsystem
     * @return whether a class in its hierarchy declares the method
     */
    private static boolean declaresIsRunning(JavaClass subsystem) {
        for (JavaClass c = subsystem; c != null; c = c.getRawSuperclass().orElse(null)) {
            if (c.tryGetMethod("isRunning").isPresent()) {
                return true;
            }
        }
        return false;
    }

    @ArchTest
    static void theOrchestratorWarmsACoreGroupOnlyForASubsystemThatIsRunning(JavaClasses classes) {
        // The Core half of the warm-up, which had no check of its own: it ran above every
        // subsystem's guard, so the rule the other three tests here enforce — a subsystem that
        // found no provider warms nothing — held only for the Community half, and a kernel with
        // http.mode=DISABLED still loaded the Core HTTP event group on its way to returning.
        //
        // What this proves, and what it does not: the warm-up call and the check it sits behind
        // both live in doStart. ArchUnit reads the call graph, not the control flow, so it cannot
        // say the call is inside the if. Observing the initialisation itself would mean reading
        // FlightRecorder.getEventTypes(), which answers vacuously in a shared surefire JVM where
        // another test may already have initialised the group — so the two halves are guarded
        // structurally and the limit is written down rather than implied.
        JavaMethod doStart = orchestratorDoStart(classes);

        assertThat(callsFrom(doStart, CoreJfrEventCatalogue.class.getName(), "warmHotPath"))
                .withFailMessage("SubsystemOrchestrator.doStart never calls CoreJfrEventCatalogue.warmHotPath, so a "
                        + "subsystem's Core event classes initialise on whichever virtual thread emits one first")
                .isTrue();

        assertThat(callsFrom(doStart, Subsystem.class.getName(), "isRunning"))
                .withFailMessage("SubsystemOrchestrator.doStart never calls Subsystem.isRunning, so the Core warm-up "
                        + "is not behind the check that says this subsystem found a provider — a disabled one pays "
                        + "the class loads of events it cannot emit")
                .isTrue();
    }

    /**
     * The {@code doStart} the orchestrator actually runs a subsystem through.
     *
     * @param classes the analysed classpath
     * @return that method
     */
    private static JavaMethod orchestratorDoStart(JavaClasses classes) {
        JavaClass orchestrator = classes.stream()
                .filter(c -> "eu.exeris.kernel.core.bootstrap.SubsystemOrchestrator".equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SubsystemOrchestrator is not on the analysed classpath, so "
                        + "the assertions that read it would pass vacuously"));
        return orchestrator.getMethods().stream()
                .filter(m -> "doStart".equals(m.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SubsystemOrchestrator declares no doStart — the Core warm-up "
                        + "seam moved and this guard no longer looks at it"));
    }

    /**
     * Whether {@code method}'s own body calls {@code target} on {@code ownerName}.
     *
     * @param method    the method whose body is read
     * @param ownerName the binary name of the call's target owner
     * @param target    the called method's name
     * @return whether that call originates in this method
     */
    private static boolean callsFrom(JavaMethod method, String ownerName, String target) {
        return method.getMethodCallsFromSelf().stream()
                .anyMatch(call -> target.equals(call.getTarget().getName())
                        && call.getTargetOwner().getName().equals(ownerName));
    }

    /**
     * Whether this class, or one it inherits {@code start()} from, warms its group <em>from
     * {@code start()}</em>.
     *
     * <p>The origin is the point. Asking only whether the class calls {@code warmHotPath} anywhere
     * accepts a call in {@code stop()}, in a dead private method, or on a branch nothing reaches —
     * the same shape of false pass as the guard this replaced, one level down: that one asked
     * whether a subsystem <em>had</em> a group rather than whether anything warmed it, and stayed
     * green while three subsystems warmed nothing at all.
     *
     * <p>The superclass walk stays, because {@code AbstractSingleProviderSubsystem} declares the
     * {@code start()} its two subclasses inherit unchanged — but it walks to find the class that
     * declares {@code start()}, not to accept a call from anywhere in the hierarchy.
     *
     * @param subsystem a concrete Community subsystem
     * @return whether its {@code start()} reaches the warm-up
     */
    private static boolean warmsItsGroup(JavaClass subsystem) {
        for (JavaClass c = subsystem; c != null; c = c.getRawSuperclass().orElse(null)) {
            Optional<JavaMethod> start = c.tryGetMethod("start");
            if (start.isEmpty()) {
                continue;
            }
            // The first start() up the chain is the one that runs; if it does not warm, an
            // inherited one further up is not what a caller of this subsystem would execute.
            return callsFrom(start.get(), CommunityJfrEventCatalogue.class.getName(), "warmHotPath");
        }
        return false;
    }

    /**
     * The names the subsystems actually report, read from {@code Subsystem.name()}.
     *
     * <p>Derived by instantiating each one and calling the method, not by lowercasing a class name:
     * the catalogue is keyed on what {@code name()} returns, and a check that reconstructs the key
     * from the class name instead never reads the property it claims to check. {@code name()} is a
     * constant on every implementation and needs no kernel context to call.
     */
    private static Set<String> communitySubsystemNames(JavaClasses classes) {
        Set<String> names = new TreeSet<>();
        for (JavaClass javaClass : concreteSubsystems(classes)) {
            try {
                Class<?> type = Class.forName(javaClass.getName());
                var constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                names.add(((Subsystem) constructor.newInstance()).name());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not read Subsystem.name() from " + javaClass.getName()
                        + " — the guard cannot check the catalogue's keys without it", e);
            }
        }
        return names;
    }

    private static List<JavaClass> concreteSubsystems(JavaClasses classes) {
        return classes.stream()
                .filter(c -> c.getPackageName().equals("eu.exeris.kernel.community.bootstrap"))
                .filter(c -> c.isAssignableTo(Subsystem.class))
                .filter(c -> !c.getModifiers().contains(JavaModifier.ABSTRACT))
                .toList();
    }

    /**
     * Enumerates one module's event classes off the analysed classpath and hands the judgement to
     * the shared instrument.
     *
     * <p>The enumeration is this module's own — ArchUnit, by name prefix — because the Kafka driver
     * cannot use it: that module has no ArchUnit dependency and walks its own {@code CodeSource}
     * instead. What the two shared, verbatim, was everything after the enumeration.
     *
     * @param classes      the analysed classpath
     * @param modulePrefix the package prefix identifying the module
     * @param hotPath      the names its catalogue warms
     * @param cold         the names its catalogue deliberately leaves cold
     */
    private static void assertClassified(JavaClasses classes, String modulePrefix,
                                         List<String> hotPath, List<String> cold) {
        Set<String> declared = classes.stream()
                .filter(c -> c.getName().startsWith(modulePrefix))
                .filter(c -> c.isAssignableTo(Event.class))
                .filter(c -> !c.getName().equals(Event.class.getName()))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        JfrEventCatalogueCoverage.assertBucketsMatchDeclared(modulePrefix, declared, hotPath, cold);
    }

    private static Set<String> difference(Set<String> from, Set<String> remove) {
        Set<String> out = new TreeSet<>(from);
        out.removeAll(remove);
        return out;
    }
}
