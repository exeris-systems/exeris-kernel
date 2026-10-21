/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.nativeimage;

import eu.exeris.kernel.core.telemetry.jfr.CoreJfrEventCatalogue;
import eu.exeris.kernel.tck.contract.nativeimage.DeclarationSite;
import eu.exeris.kernel.tck.contract.nativeimage.DeclarationSiteScanner;
import eu.exeris.kernel.tck.contract.nativeimage.DeclarationSiteScanner.AllowlistEntry;
import eu.exeris.kernel.tck.contract.nativeimage.ReachabilityMetadata;
import eu.exeris.kernel.tck.contract.nativeimage.ReachabilityMetadataGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ClassDesc;
import java.lang.foreign.FunctionDescriptor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static eu.exeris.kernel.tck.contract.nativeimage.DeclarationSiteScanner.allow;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reachability metadata verification guard for {@code exeris-kernel-core}.
 *
 * <p>Enforces complete declaration-site allowlisting, centralized FFM descriptor table usage,
 * and exact byte-for-byte matching between the committed {@code reachability-metadata.json}
 * golden file and the synthesized runtime model.
 *
 * @since 0.13
 */
@DisplayName("Core reachability metadata and declaration-site guard")
class CoreReachabilityMetadataTest {

    private static final String KERNEL_VERSION_CLASS = "eu.exeris.kernel.core.bootstrap.KernelVersion";
    private static final String MANIFEST_LOCATOR_CLASS = "eu.exeris.kernel.core.contract.ManifestLocator";
    private static final String OPENSSL_DOWNCALL_ENUM = "eu.exeris.kernel.core.crypto.openssl.OpenSslDowncall";
    private static final String SYSCALL_DOWNCALL_ENUM = "eu.exeris.kernel.core.transport.syscall.SyscallDowncall";

    private static final List<AllowlistEntry> ALLOWLIST = List.of(
            // Resources
            allow("eu.exeris.kernel.core.bootstrap.KernelVersion", "load", "Class.getResourceAsStream",
                    "covered-by-metadata: exeris-kernel.properties resource registered in reachability-metadata.json"),
            allow("eu.exeris.kernel.core.contract.ManifestLocator", "classpathManifests",
                    "ClassLoader.getResources",
                    "covered-by-metadata: consumer-provided license-manifest.json fallback "
                            + "registered in reachability-metadata.json"),

            // Centralized foreign descriptor tables
            allow("eu.exeris.kernel.core.crypto.openssl.OpenSslDowncall", "<clinit>", "FunctionDescriptor.of",
                    "covered-by-metadata: OpenSSL downcall descriptors declared in centralized enum table"),
            allow("eu.exeris.kernel.core.crypto.openssl.OpenSslDowncall", "<clinit>", "FunctionDescriptor.ofVoid",
                    "covered-by-metadata: OpenSSL void downcall descriptors declared in centralized enum table"),
            allow("eu.exeris.kernel.core.transport.syscall.SyscallDowncall", "<clinit>", "FunctionDescriptor.of",
                    "covered-by-metadata: syscall downcall descriptors declared in centralized enum table"),

            // Downcall linkage sites
            allow("eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader", "link", "Linker.downcallHandle",
                    "covered-by-metadata: required and optional OpenSSL downcalls defined in OpenSslDowncall enum"),
            allow("eu.exeris.kernel.core.crypto.openssl.CoreOpenSslRuntime", "requiredDowncall",
                    "Linker.downcallHandle",
                    "covered-by-caller: the descriptor is supplied by the consuming module, "
                            + "which registers it in its own metadata"),
            allow("eu.exeris.kernel.core.crypto.openssl.CoreOpenSslRuntime", "lambda$optionalDowncall$0",
                    "Linker.downcallHandle",
                    "covered-by-caller: the descriptor is supplied by the consuming module, "
                            + "which registers it in its own metadata"),
            allow("eu.exeris.kernel.core.transport.syscall.CoreSyscallLoader", "req", "Linker.downcallHandle",
                    "covered-by-metadata: socket syscall downcalls defined in SyscallDowncall enum"),
            allow("eu.exeris.kernel.core.transport.syscall.SyscallErrno", "lambda$resolveErrnoLocation$0",
                    "Linker.downcallHandle",
                    "covered-by-metadata: errno location downcall defined in SyscallDowncall enum"),

            // VarHandle reflection targets (23 sites)
            allow("eu.exeris.kernel.core.config.KernelConfigRegistry", "<clinit>", "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: sealed field registered via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.crypto.openssl.NativeCipherContext", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: refCount field registered via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.crypto.tls.OffHeapTlsEngine", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: closedFlag field registered via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.crypto.tls.TlsStateMachine", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: phaseOrdinal field registered via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.events.outbox.OutboxStateMachine", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: state field registered via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.memory.AbstractLoanedBuffer", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: refCount field registered via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.memory.LeakTracker$LeakAction", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: firedState field registered via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.memory.MemoryMaintenanceTask", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: loopActive and maintenanceThread fields registered "
                            + "via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.memory.ResourceArbiter", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: arbiter decision and action fields registered "
                            + "via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.memory.WatermarkManager", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: watermark level and byte counter fields registered "
                            + "via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.security.CitadelGuard", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: sealedFlag field registered via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.transport.scheduler.AdmissionController", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: activeStreamCount field registered via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.transport.scheduler.DrainCoordinator", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: busyAtSeal, busyStreams, and draining fields registered "
                            + "via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.transport.scheduler.DrainCoordinator$StreamWork", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: busy field registered via VarHandle literal extraction"),
            allow("eu.exeris.kernel.core.transport.scheduler.StreamLoadShedder", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: shedCountField field registered via VarHandle literal extraction"),

            // Processor-covered reflection
            allow("eu.exeris.kernel.core.security.GeneratedRoleRegistryLoader", "tryResolve", "Class.forName",
                    "covered-by-processor: annotation-processor-generated role registry loaded by convention"),
            allow("eu.exeris.kernel.core.security.GeneratedRoleRegistryLoader$ReflectiveRoleRegistry", "bind",
                    "MethodHandles$Lookup.findStatic",
                    "covered-by-processor: annotation-processor-generated role registry methods bound reflectively"),

            // JFR catalogue reflection
            allow("eu.exeris.kernel.core.telemetry.jfr.JfrEventWarmup", "ensureInitialised", "Class.forName",
                    "JFR-catalogue rule: all catalogued event classes registered in reachability-metadata.json"),
            allow("eu.exeris.kernel.core.telemetry.jfr.JfrEventWarmup", "initialiseWithEnclosing", "Class.forName",
                    "JFR-catalogue rule: enclosing classes of catalogued events registered in reachability-metadata.json"),

            // Consumer-type reflection (ADR-103 scope exclusion)
            allow("eu.exeris.kernel.core.graph.GraphMetadataEngine", "extractFieldNames", "Class.getDeclaredFields",
                    "consumer-type: client-supplied graph entity field introspection (ADR-103 scope exclusion)"),
            allow("eu.exeris.kernel.core.graph.GraphMetadataEngine", "resolveEdgeTable", "Class.getDeclaredFields",
                    "consumer-type: client-supplied graph entity field introspection (ADR-103 scope exclusion)"),
            allow("eu.exeris.kernel.core.http.client.KernelWebClient", "arrayTypeOf", "Array.newInstance",
                    "consumer-type: dynamic array allocation for client-specified HTTP body types "
                            + "(ADR-103 scope exclusion)")
    );

    @Test
    @DisplayName("GraalVM SDK classes are absent from the test classpath")
    void noGraalVmOnTestClasspath() {
        assertThat(getClass().getClassLoader().getResource("org/graalvm/nativeimage/ImageInfo.class"))
                .as("org.graalvm types must not be present on the classpath")
                .isNull();
    }

    @Test
    @DisplayName("FunctionDescriptor factory methods are called only from descriptor enum static initializers")
    void functionDescriptorsOnlyInEnums() throws IOException {
        Class<?> anchor = resolveClass(KERNEL_VERSION_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        Set<String> allowedClasses = Set.of(OPENSSL_DOWNCALL_ENUM, SYSCALL_DOWNCALL_ENUM);

        for (DeclarationSite site : result.sites()) {
            if (site.api().startsWith("FunctionDescriptor.")) {
                assertThat(allowedClasses)
                        .as("FunctionDescriptor calls must reside only in centralized descriptor enums")
                        .contains(site.declaringClass());
                assertThat(site.methodName())
                        .as("FunctionDescriptor calls must be in <clinit>")
                        .isEqualTo("<clinit>");
            }
        }
    }

    @Test
    @DisplayName("Linker.downcallHandle is called only from designated loader and runtime classes")
    void downcallsOnlyInDesignatedClasses() throws IOException {
        Class<?> anchor = resolveClass(KERNEL_VERSION_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        Set<String> allowedClasses = Set.of(
                "eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader",
                "eu.exeris.kernel.core.crypto.openssl.CoreOpenSslRuntime",
                "eu.exeris.kernel.core.transport.syscall.CoreSyscallLoader",
                "eu.exeris.kernel.core.transport.syscall.SyscallErrno"
        );

        for (DeclarationSite site : result.sites()) {
            if ("Linker.downcallHandle".equals(site.api())) {
                assertThat(allowedClasses)
                        .as("downcallHandle calls must reside only in designated loader/runtime classes")
                        .contains(site.declaringClass());
            }
        }
    }

    @Test
    @DisplayName("Linker.upcallStub is not called anywhere in Core")
    void noUpcallStubsInCore() throws IOException {
        Class<?> anchor = resolveClass(KERNEL_VERSION_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        for (DeclarationSite site : result.sites()) {
            assertThat(site.api())
                    .as("Core must not create upcall stubs")
                    .isNotEqualTo("Linker.upcallStub");
        }
    }

    @Test
    @DisplayName("Arena.ofShared is not called anywhere in Core")
    void noSharedArenasInCore() throws IOException {
        Class<?> anchor = resolveClass(KERNEL_VERSION_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        for (DeclarationSite site : result.sites()) {
            assertThat(site.api())
                    .as("Core must not allocate shared arenas")
                    .isNotEqualTo("Arena.ofShared");
        }
    }

    @Test
    @DisplayName("findVarHandle sites match exactly the 23 extractable targets")
    void findVarHandleSitesMatchExtractableTargets() throws IOException {
        Class<?> anchor = resolveClass(KERNEL_VERSION_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        assertThat(result.varHandles())
                .as("findVarHandle sites in Core must match exactly 23 extractable targets")
                .hasSize(23);
    }

    @Test
    @DisplayName("All declaration sites in Core match allowlist dispositions with no stale entries")
    void allowlistMatchesAllSites() throws IOException {
        Class<?> anchor = resolveClass(KERNEL_VERSION_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        DeclarationSiteScanner.assertAllowlistMatches(result.sites(), ALLOWLIST);
    }

    @Test
    @EnabledOnOs(value = {OS.LINUX, OS.MAC}, disabledReason = "Windows is not a verified image target (ADR-103 §2)")
    @DisplayName("Committed reachability-metadata.json matches expected model generated from declaration sites")
    void committedMetadataMatchesGeneratedModel() throws IOException, ReflectiveOperationException {
        Class<?> anchor = resolveClass(KERNEL_VERSION_CLASS);
        ReachabilityMetadata model = buildExpectedModel(anchor);
        ReachabilityMetadataGuard.assertCommittedMatches(anchor, "exeris-kernel-core", model);
    }

    static ReachabilityMetadata buildExpectedModel(Class<?> anchor)
            throws IOException, ReflectiveOperationException {
        ReachabilityMetadata.Builder builder = ReachabilityMetadata.builder();

        // 1. Foreign downcalls: OpenSslDowncall and SyscallDowncall enum tables
        Class<?> openSslEnum = resolveClass(OPENSSL_DOWNCALL_ENUM);
        for (Object downcall : openSslEnum.getEnumConstants()) {
            builder.registerDowncall(readDescriptor(downcall));
        }

        Class<?> syscallEnum = resolveClass(SYSCALL_DOWNCALL_ENUM);
        for (Object downcall : syscallEnum.getEnumConstants()) {
            builder.registerDowncall(readDescriptor(downcall));
        }

        // 2. JFR events closure
        List<String> eventNames = new ArrayList<>();
        eventNames.addAll(CoreJfrEventCatalogue.catalogue().allHotPath());
        eventNames.addAll(CoreJfrEventCatalogue.catalogue().deliberatelyCold());
        List<String> jfrClosure = ReachabilityMetadataGuard.jfrClosure(
                eventNames, CoreReachabilityMetadataTest.class.getClassLoader());
        for (String typeName : jfrClosure) {
            builder.registerType(typeName);
        }

        // 3. VarHandle reflection targets (23 sites)
        Path classesDir = DeclarationSiteScanner.classesDirectory(anchor);
        DeclarationSiteScanner.ScanResult scan = DeclarationSiteScanner.scanAll(classesDir);
        for (DeclarationSiteScanner.VarHandleTarget target : scan.varHandles()) {
            builder.registerField(target.receiverClass(), target.fieldName());
        }

        // 4. Resources
        String kernelProps = ReachabilityMetadataGuard.readStaticField(anchor, "RESOURCE", String.class);
        if (kernelProps.startsWith("/")) {
            kernelProps = kernelProps.substring(1);
        }
        builder.registerResource(kernelProps);

        Class<?> manifestLocatorClass = resolveClass(MANIFEST_LOCATOR_CLASS);
        String licenseManifest = ReachabilityMetadataGuard.readStaticField(
                manifestLocatorClass, "DEFAULT_MANIFEST_FILENAME", String.class);
        builder.registerResource(licenseManifest);

        // 5. jctools field offsets across instantiated queue superclass chains
        registerJcToolsFieldOffsets(builder, classesDir);

        return builder.build();
    }

    private static void registerJcToolsFieldOffsets(ReachabilityMetadata.Builder builder, Path classesDir)
            throws IOException, ClassNotFoundException {
        Set<String> queueClasses = discoverInstantiatedQueueClasses(classesDir);
        if (!queueClasses.isEmpty()) {
            builder.registerMethod("sun.misc.Unsafe", "getAndSetObject",
                    "java.lang.Object", "long", "java.lang.Object");
            builder.registerMethod("sun.misc.Unsafe", "getAndAddLong",
                    "java.lang.Object", "long", "long");
        }
        ClassLoader loader = CoreReachabilityMetadataTest.class.getClassLoader();

        for (String queueClass : queueClasses) {
            Class<?> curr = Class.forName(queueClass, false, loader);
            while (curr != null && curr != Object.class) {
                String resourceName = curr.getName().replace('.', '/') + ".class";
                try (InputStream in = loader.getResourceAsStream(resourceName)) {
                    if (in != null) {
                        extractJcToolsOffsets(builder, in.readAllBytes());
                    }
                }
                curr = curr.getSuperclass();
            }
        }
    }

    private static Set<String> discoverInstantiatedQueueClasses(Path classesDir) throws IOException {
        Set<String> queueClasses = new TreeSet<>();
        try (Stream<Path> stream = Files.walk(classesDir)) {
            List<Path> classFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .toList();

            for (Path classFile : classFiles) {
                ClassModel cm = ClassFile.of().parse(classFile);
                for (MethodModel mm : cm.methods()) {
                    mm.code().ifPresent(code -> {
                        for (CodeElement ce : code) {
                            if (ce instanceof NewObjectInstruction noi) {
                                String internalName = noi.className().asInternalName();
                                if (internalName.startsWith("org/jctools/queues/")) {
                                    queueClasses.add(internalName.replace('/', '.'));
                                }
                            }
                        }
                    });
                }
            }
        }
        return queueClasses;
    }

    private static void extractJcToolsOffsets(ReachabilityMetadata.Builder builder, byte[] classBytes) {
        ClassModel cm = ClassFile.of().parse(classBytes);
        for (MethodModel mm : cm.methods()) {
            mm.code().ifPresent(code -> {
                List<Instruction> instructions = collectInstructions(code);
                for (int i = 0; i < instructions.size(); i++) {
                    Instruction inst = instructions.get(i);
                    if (inst instanceof InvokeInstruction inv
                            && "org/jctools/util/UnsafeAccess".equals(inv.owner().asInternalName())
                            && "fieldOffset".equals(inv.name().stringValue())
                            && i >= 2) {
                        Instruction arg1 = instructions.get(i - 2);
                        Instruction arg2 = instructions.get(i - 1);
                        if (arg1 instanceof ConstantInstruction ci1
                                && ci1.constantValue() instanceof ClassDesc cd
                                && arg2 instanceof ConstantInstruction ci2
                                && ci2.constantValue() instanceof String fn) {
                            String targetName = toClassName(cd);
                            builder.registerField(targetName, fn);
                        }
                    }
                }
            });
        }
    }

    private static String toClassName(ClassDesc classDesc) {
        if (classDesc.isPrimitive()) {
            return classDesc.displayName();
        }
        String desc = classDesc.descriptorString();
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return desc.substring(1, desc.length() - 1).replace('/', '.');
        }
        return desc;
    }

    private static List<Instruction> collectInstructions(CodeModel codeModel) {
        List<Instruction> instructions = new ArrayList<>();
        for (CodeElement element : codeModel) {
            if (element instanceof Instruction instruction) {
                instructions.add(instruction);
            }
        }
        return instructions;
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static FunctionDescriptor readDescriptor(Object enumConstant) throws ReflectiveOperationException {
        Method method = enumConstant.getClass().getDeclaredMethod("descriptor");
        method.setAccessible(true);
        return (FunctionDescriptor) method.invoke(enumConstant);
    }

    private static Class<?> resolveClass(String binaryName) {
        try {
            return Class.forName(binaryName, false, CoreReachabilityMetadataTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Required class not found on classpath: " + binaryName, e);
        }
    }
}
