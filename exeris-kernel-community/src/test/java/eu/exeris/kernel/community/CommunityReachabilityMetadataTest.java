/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community;

import eu.exeris.kernel.community.telemetry.CommunityJfrEventCatalogue;
import eu.exeris.kernel.tck.contract.nativeimage.DeclarationSite;
import eu.exeris.kernel.tck.contract.nativeimage.DeclarationSiteScanner;
import eu.exeris.kernel.tck.contract.nativeimage.DeclarationSiteScanner.AllowlistEntry;
import eu.exeris.kernel.tck.contract.nativeimage.ReachabilityMetadata;
import eu.exeris.kernel.tck.contract.nativeimage.ReachabilityMetadataGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.FileDescriptor;
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
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static eu.exeris.kernel.tck.contract.nativeimage.DeclarationSiteScanner.allow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Reachability metadata verification guard for {@code exeris-kernel-community}.
 *
 * <p>Enforces complete declaration-site allowlisting, centralized foreign descriptor table usage,
 * valid native-image arguments, and exact byte-for-byte matching between the committed
 * {@code reachability-metadata.json} golden file and the synthesized runtime model.
 *
 * @since 0.13
 */
@DisplayName("Community reachability metadata and declaration-site guard")
class CommunityReachabilityMetadataTest {

    private static final String COMMUNITY_CRYPTO_PROVIDER_CLASS =
            "eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider";
    private static final String COMMUNITY_FOREIGN_CALL_ENUM =
            "eu.exeris.kernel.community.crypto.CommunityForeignCall";
    private static final String PERSISTENCE_ENGINE_CLASS =
            "eu.exeris.kernel.community.persistence.CommunityPersistenceEngine";

    private static final Set<String> BANNED_HIKARI_SETTERS = Set.of(
            "setDriverClassName",
            "setDataSourceClassName",
            "setExceptionOverrideClassName",
            "setHealthCheckRegistry",
            "setMetricRegistry"
    );

    private static final List<String> HIKARI_BY_NAME = List.of(
            // HikariPool$HouseKeeper.<init>:
            // AtomicReferenceFieldUpdater.newUpdater(PoolBase.class, String.class, "catalog")
            "com.zaxxer.hikari.pool.PoolBase",
            // PoolEntry.<clinit>: AtomicIntegerFieldUpdater.newUpdater(PoolEntry.class, "state")
            "com.zaxxer.hikari.pool.PoolEntry",
            // ConcurrentBag.lambda$new$0 (threadList supplier, useWeakThreadLocals=false):
            // new FastList(IConcurrentBagEntry.class, 16) -> Array.newInstance
            "com.zaxxer.hikari.util.ConcurrentBag$IConcurrentBagEntry[]",
            // PoolEntry.<init>: new FastList(Statement.class, 16) -> Array.newInstance;
            // FastList.add regrows via Array.newInstance
            "java.sql.Statement[]"
    );

    private static final List<String> JDK_BY_NAME_ON_HIKARI_PATH = List.of(
            // JDK SQLException.<clinit>:
            // AtomicReferenceFieldUpdater.newUpdater(SQLException.class, SQLException.class, "next");
            // first SQLException Hikari constructs
            "java.sql.SQLException"
    );

    private static final List<AllowlistEntry> ALLOWLIST = List.of(
            // FFM foreign calls
            allow("eu.exeris.kernel.community.crypto.CommunityForeignCall", "<clinit>", "FunctionDescriptor.of",
                    "covered-by-metadata: Community foreign descriptors declared in centralized enum table"),
            allow("eu.exeris.kernel.community.crypto.CommunityAlpnSelector", "resolveStub", "Linker.upcallStub",
                    "covered-by-metadata: ALPN selector upcall stub declared in CommunityForeignCall"),
            allow("eu.exeris.kernel.community.crypto.CommunityAlpnSelector", "resolveStub",
                    "MethodHandles$Lookup.findStatic",
                    "covered-by-metadata: ALPN selector callback target method registered in "
                            + "reachability-metadata.json"),

            // VarHandle reflection targets
            allow("eu.exeris.kernel.community.crypto.CommunityTlsClientTrust", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: refCount field registered via VarHandle literal extraction"),
            allow("eu.exeris.kernel.community.persistence.FairnessTracker$BucketMetrics", "<clinit>",
                    "MethodHandles$Lookup.findVarHandle",
                    "covered-by-metadata: accepted, queueDepthSamples, queueWaitSamples, and rejected fields "
                            + "registered via VarHandle literal extraction"),

            // SocketChannel and FileDescriptor reflection
            allow("eu.exeris.kernel.community.crypto.SocketChannelFdReflectionResolver$FieldAccessor",
                    "resolveDeclaredField", "Class.getDeclaredField",
                    "covered-by-metadata: SocketChannel fd fields probed and registered in "
                            + "reachability-metadata.json"),
            allow("eu.exeris.kernel.community.crypto.SocketChannelFdReflectionResolver$FileDescriptorFallback",
                    "resolveChannelFdField", "Class.getDeclaredField",
                    "covered-by-metadata: SocketChannel fd field probed and registered in reachability-metadata.json"),
            allow("eu.exeris.kernel.community.crypto.SocketChannelFdReflectionResolver$FileDescriptorFallback",
                    "resolveFileDescriptorFdField", "Class.getDeclaredField",
                    "covered-by-metadata: FileDescriptor fd field probed and registered in reachability-metadata.json"),
            allow("eu.exeris.kernel.community.crypto.SocketChannelFdReflectionResolver$MethodAccessor",
                    "resolveDeclaredMethod", "Class.getDeclaredMethod",
                    "covered-by-metadata: SocketChannel getFDVal method probed and registered in "
                            + "reachability-metadata.json"),

            // Shared arenas
            allow("eu.exeris.kernel.community.memory.CommunityArenaShardPool", "getOrCreateShardArena", "Arena.ofShared",
                    "covered-by-runtime-flag: -H:+SharedArenaSupport configured in native-image.properties"),

            // Resources
            allow("eu.exeris.kernel.community.persistence.CommunityPersistenceMigrationRunner",
                    "readMigrationResource", "ClassLoader.getResourceAsStream",
                    "covered-by-metadata: migration SQL resources registered in reachability-metadata.json")
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
        Class<?> anchor = resolveClass(COMMUNITY_CRYPTO_PROVIDER_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        Set<String> allowedClasses = Set.of(COMMUNITY_FOREIGN_CALL_ENUM);

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
    @DisplayName("Linker.downcallHandle is not called anywhere in Community")
    void noDowncallHandlesInCommunity() throws IOException {
        Class<?> anchor = resolveClass(COMMUNITY_CRYPTO_PROVIDER_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        for (DeclarationSite site : result.sites()) {
            assertThat(site.api())
                    .as("Community must not create downcall handles directly; use CoreOpenSslRuntime")
                    .isNotEqualTo("Linker.downcallHandle");
        }
    }

    @Test
    @DisplayName("Linker.upcallStub is called only from designated selector class")
    void upcallsOnlyInAlpnSelector() throws IOException {
        Class<?> anchor = resolveClass(COMMUNITY_CRYPTO_PROVIDER_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        for (DeclarationSite site : result.sites()) {
            if ("Linker.upcallStub".equals(site.api())) {
                assertThat(site.declaringClass())
                        .as("upcallStub must be called only in CommunityAlpnSelector")
                        .isEqualTo("eu.exeris.kernel.community.crypto.CommunityAlpnSelector");
            }
        }
    }

    @Test
    @DisplayName("Arena.ofShared is called only in CommunityArenaShardPool")
    void sharedArenasOnlyInShardPool() throws IOException {
        Class<?> anchor = resolveClass(COMMUNITY_CRYPTO_PROVIDER_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        for (DeclarationSite site : result.sites()) {
            if ("Arena.ofShared".equals(site.api())) {
                assertThat(site.declaringClass())
                        .as("Arena.ofShared must be called only in CommunityArenaShardPool")
                        .isEqualTo("eu.exeris.kernel.community.memory.CommunityArenaShardPool");
            }
        }
    }

    @Test
    @DisplayName("findVarHandle sites match exactly the 5 extractable targets")
    void findVarHandleSitesMatchExtractableTargets() throws IOException {
        Class<?> anchor = resolveClass(COMMUNITY_CRYPTO_PROVIDER_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        assertThat(result.varHandles())
                .as("findVarHandle sites in Community must match exactly 5 extractable targets")
                .hasSize(5);
    }

    @Test
    @DisplayName("All declaration sites in Community match allowlist dispositions with no stale entries")
    void allowlistMatchesAllSites() throws IOException {
        Class<?> anchor = resolveClass(COMMUNITY_CRYPTO_PROVIDER_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        DeclarationSiteScanner.assertAllowlistMatches(result.sites(), ALLOWLIST);
    }

    @Test
    @DisplayName("native-image.properties contains required arguments and conditional shared-arena flag")
    void nativeImagePropertiesValid() throws IOException {
        Class<?> anchor = resolveClass(COMMUNITY_CRYPTO_PROVIDER_CLASS);
        String propertiesPath = "/META-INF/native-image/eu.exeris/exeris-kernel-community/native-image.properties";
        Properties props = new Properties();
        try (InputStream in = anchor.getResourceAsStream(propertiesPath)) {
            assertThat(in)
                    .as("native-image.properties must exist under META-INF/native-image/eu.exeris/exeris-kernel-community/")
                    .isNotNull();
            props.load(in);
        }

        String argsValue = props.getProperty("Args");
        assertThat(argsValue)
                .as("native-image.properties must define 'Args'")
                .isNotNull();

        List<String> args = Arrays.stream(argsValue.split("\\s+"))
                .filter(s -> !s.isBlank())
                .toList();

        DeclarationSiteScanner.ScanResult scan = DeclarationSiteScanner.scanAll(anchor);
        boolean hasSharedArena = scan.sites().stream().anyMatch(s -> "Arena.ofShared".equals(s.api()));

        Set<String> expectedArgs = new TreeSet<>(Set.of(
                "--enable-monitoring=jfr",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED"
        ));
        if (hasSharedArena) {
            expectedArgs.add("-H:+SharedArenaSupport");
        }

        assertThat(Set.copyOf(args))
                .as("native-image.properties arguments must match expected configuration")
                .isEqualTo(expectedArgs);
    }

    @Test
    @DisplayName("HikariDataSource is instantiated and no banned reflective HikariConfig setters are invoked")
    void hikariDeclarationSitesAndBannedSetters() throws IOException {
        Class<?> anchor = resolveClass(COMMUNITY_CRYPTO_PROVIDER_CLASS);
        Path classesDir = DeclarationSiteScanner.classesDirectory(anchor);
        assertThatCode(() -> verifyHikariDeclarationSites(classesDir)).doesNotThrowAnyException();
    }

    @Test
    @EnabledOnOs(value = {OS.LINUX, OS.MAC}, disabledReason = "Windows is not a verified image target (ADR-103 §2)")
    @DisplayName("Committed reachability-metadata.json matches expected model generated from declaration sites")
    void committedMetadataMatchesGeneratedModel() throws IOException, ReflectiveOperationException {
        Class<?> anchor = resolveClass(COMMUNITY_CRYPTO_PROVIDER_CLASS);
        ReachabilityMetadata model = buildExpectedModel(anchor);
        ReachabilityMetadataGuard.assertCommittedMatches(anchor, "exeris-kernel-community", model);
    }

    static ReachabilityMetadata buildExpectedModel(Class<?> anchor)
            throws IOException, ReflectiveOperationException {
        ReachabilityMetadata.Builder builder = ReachabilityMetadata.builder();

        // 1. Foreign calls from CommunityForeignCall enum table
        Class<?> foreignCallEnum = resolveClass(COMMUNITY_FOREIGN_CALL_ENUM);
        for (Object constant : foreignCallEnum.getEnumConstants()) {
            FunctionDescriptor descriptor = readDescriptor(constant);
            boolean isUpcall = readIsUpcall(constant);
            if (isUpcall) {
                Class<?> targetClass = readTargetClass(constant);
                String targetMethod = readTargetMethod(constant);
                builder.registerDirectUpcall(targetClass.getName(), targetMethod, descriptor);
                MethodType methodType = descriptor.toMethodType();
                targetClass.getDeclaredMethod(targetMethod, methodType.parameterArray());
                List<String> paramTypes = methodType.parameterList().stream()
                        .map(Class::getName)
                        .toList();
                builder.registerMethod(targetClass.getName(), targetMethod, paramTypes);
            } else {
                builder.registerDowncall(descriptor);
            }
        }

        // 2. JFR events closure from CommunityJfrEventCatalogue
        List<String> eventNames = new ArrayList<>();
        eventNames.addAll(CommunityJfrEventCatalogue.catalogue().allHotPath());
        eventNames.addAll(CommunityJfrEventCatalogue.catalogue().deliberatelyCold());
        List<String> jfrClosure = ReachabilityMetadataGuard.jfrClosure(
                eventNames, CommunityReachabilityMetadataTest.class.getClassLoader());
        for (String typeName : jfrClosure) {
            builder.registerType(typeName);
        }

        // 3. VarHandle reflection targets
        Path classesDir = DeclarationSiteScanner.classesDirectory(anchor);
        DeclarationSiteScanner.ScanResult scan = DeclarationSiteScanner.scanAll(classesDir);
        for (DeclarationSiteScanner.VarHandleTarget target : scan.varHandles()) {
            builder.registerField(target.receiverClass(), target.fieldName());
        }

        // 4. SocketChannelFdReflectionResolver members probed on current JDK
        registerSocketChannelFdResolverMembers(builder);

        // 5. Database migration resources
        Class<?> engineClass = resolveClass(PERSISTENCE_ENGINE_CLASS);
        @SuppressWarnings("unchecked")
        List<String> migrations = (List<String>) ReachabilityMetadataGuard.readStaticField(
                engineClass, "MIGRATION_RESOURCES", List.class);
        for (String migration : migrations) {
            builder.registerResource(migration);
        }

        // 6. Hikari metadata
        verifyHikariDeclarationSites(classesDir);
        builder.registerAllDeclaredFields("com.zaxxer.hikari.HikariConfig");
        for (String typeName : HIKARI_BY_NAME) {
            builder.registerType(typeName);
        }
        for (String typeName : JDK_BY_NAME_ON_HIKARI_PATH) {
            builder.registerType(typeName);
        }
        builder.registerField("com.zaxxer.hikari.pool.PoolBase", "catalog");
        builder.registerField("com.zaxxer.hikari.pool.PoolEntry", "state");
        builder.registerField("java.sql.SQLException", "next");
        // ConcurrentBag.resolveThreadIsVirtual (<clinit>):
        // publicLookup().findVirtual(Thread.class, "isVirtual", ...); catches only
        // NoSuchMethodException/IllegalAccessException, so a missing registration fails ConcurrentBag.<clinit>
        builder.registerMethod("java.lang.Thread", "isVirtual");

        // 7. jctools field offsets across instantiated queue superclass chains
        registerJcToolsFieldOffsets(builder, classesDir);

        return builder.build();
    }

    private static void registerSocketChannelFdResolverMembers(ReachabilityMetadata.Builder builder)
            throws IOException {
        Class<?> methodAccessorClass = resolveClass(
                "eu.exeris.kernel.community.crypto.SocketChannelFdReflectionResolver$MethodAccessor");
        String[] methodNames = (String[]) ReachabilityMetadataGuard.readStaticField(
                methodAccessorClass, "METHOD_NAMES", String[].class);

        Class<?> fieldAccessorClass = resolveClass(
                "eu.exeris.kernel.community.crypto.SocketChannelFdReflectionResolver$FieldAccessor");
        String[] fieldNames = (String[]) ReachabilityMetadataGuard.readStaticField(
                fieldAccessorClass, "FIELD_NAMES", String[].class);

        try (SocketChannel channel = SocketChannel.open()) {
            Class<?> current = channel.getClass();
            while (current != null && current != Object.class) {
                for (String methodName : methodNames) {
                    try {
                        Method method = current.getDeclaredMethod(methodName);
                        if (method.getParameterCount() == 0
                                && (method.getReturnType() == int.class
                                || Number.class.isAssignableFrom(method.getReturnType()))) {
                            builder.registerMethod(current.getName(), method.getName(), List.of());
                        }
                    } catch (NoSuchMethodException _) {
                        // Candidate method not declared on this class.
                    }
                }
                for (String fieldName : fieldNames) {
                    try {
                        Field field = current.getDeclaredField(fieldName);
                        builder.registerField(current.getName(), field.getName());
                    } catch (NoSuchFieldException _) {
                        // Candidate field not declared on this class.
                    }
                }
                current = current.getSuperclass();
            }
        }
        try {
            Field fdField = FileDescriptor.class.getDeclaredField("fd");
            builder.registerField(FileDescriptor.class.getName(), fdField.getName());
        } catch (NoSuchFieldException _) {
            // FileDescriptor.fd absent on this platform.
        }
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
        ClassLoader loader = CommunityReachabilityMetadataTest.class.getClassLoader();

        for (String queueClass : queueClasses) {
            Class<?> curr = Class.forName(queueClass, false, loader);
            while (curr != null && !curr.getName().equals("java.lang.Object")) {
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

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static boolean readIsUpcall(Object enumConstant) throws ReflectiveOperationException {
        Method method = enumConstant.getClass().getDeclaredMethod("isUpcall");
        method.setAccessible(true);
        return (boolean) method.invoke(enumConstant);
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Class<?> readTargetClass(Object enumConstant) throws ReflectiveOperationException {
        Method method = enumConstant.getClass().getDeclaredMethod("targetClass");
        method.setAccessible(true);
        return (Class<?>) method.invoke(enumConstant);
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static String readTargetMethod(Object enumConstant) throws ReflectiveOperationException {
        Method method = enumConstant.getClass().getDeclaredMethod("targetMethod");
        method.setAccessible(true);
        return (String) method.invoke(enumConstant);
    }

    private static void verifyHikariDeclarationSites(Path classesDir) throws IOException {
        AtomicBoolean hasHikariDataSource = new AtomicBoolean();
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
                            if (ce instanceof NewObjectInstruction noi
                                    && "com/zaxxer/hikari/HikariDataSource".equals(noi.className().asInternalName())) {
                                hasHikariDataSource.set(true);
                            }
                            if (ce instanceof InvokeInstruction inv) {
                                String owner = inv.owner().asInternalName();
                                if (("com/zaxxer/hikari/HikariConfig".equals(owner)
                                        || "com/zaxxer/hikari/HikariDataSource".equals(owner))
                                        && BANNED_HIKARI_SETTERS.contains(inv.name().stringValue())) {
                                    throw new IllegalStateException(
                                            "Banned HikariConfig setter invoked: " + inv.name().stringValue()
                                                    + " in " + cm.thisClass().asInternalName());
                                }
                            }
                        }
                    });
                }
            }
        }
        if (!hasHikariDataSource.get()) {
            throw new IllegalStateException("Expected HikariDataSource instantiation site in Community");
        }
    }

    private static Class<?> resolveClass(String binaryName) {
        try {
            return Class.forName(binaryName, false, CommunityReachabilityMetadataTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Required class not found on classpath: " + binaryName, e);
        }
    }
}
