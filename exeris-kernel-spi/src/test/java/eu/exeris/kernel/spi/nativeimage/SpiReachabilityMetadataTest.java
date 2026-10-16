/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.nativeimage;

import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native-image reachability metadata guard for the SPI module.
 *
 * <p>The SPI is the implementation-blind foundation ("The Wall"). It must contain zero reflection,
 * dynamic resource lookups, foreign linkage, or GraalVM references.
 *
 * <p>This test is intentionally self-contained: the SPI cannot depend on the TCK because that would invert
 * the reactor dependency direction and create a cycle. The scanner and flagged API list are duplicated here
 * for that architectural reason.
 */
@DisplayName("SPI reachability metadata and reflection-free guarantee")
class SpiReachabilityMetadataTest {

    private static final String CLASS_SUFFIX = ".class";
    private static final String MODULE_INFO = "module-info.class";
    private static final String GRAALVM_PREFIX_SLASH = "org/graalvm/";
    private static final String GRAALVM_PREFIX_DOT = "org.graalvm.";

    @Test
    @DisplayName("SPI classes declare zero reflection, dynamic resource, foreign call, or GraalVM sites")
    void spiContainsZeroDeclarationSites() throws IOException {
        Path classesDir = spiClassesDirectory();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(classesDir)) {
            List<Path> classFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(CLASS_SUFFIX))
                    .filter(path -> !path.getFileName().toString().equals(MODULE_INFO))
                    .toList();

            for (Path classFile : classFiles) {
                scanClass(classFile, violations);
            }
        }

        assertThat(violations)
                .as("SPI must be reflection-free and contain zero native-image declaration sites")
                .isEmpty();
    }

    @Test
    @DisplayName("SPI reachability-metadata.json exists and contains exactly '{}' with newline")
    void committedMetadataIsEmptyObject() throws IOException {
        String resourcePath = "/META-INF/native-image/eu.exeris/exeris-kernel-spi/reachability-metadata.json";
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            assertThat(in)
                    .as("Committed reachability-metadata.json resource must exist in SPI")
                    .isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content)
                    .as("SPI reachability-metadata.json must contain exactly empty JSON object and newline")
                    .isEqualTo("{}\n");
        }
    }

    @Test
    @DisplayName("GraalVM SDK classes are absent from the test classpath")
    void noGraalVmOnTestClasspath() {
        assertThat(getClass().getClassLoader().getResource("org/graalvm/nativeimage/ImageInfo.class"))
                .as("org.graalvm types must not be present on the classpath")
                .isNull();
    }

    private static Path spiClassesDirectory() {
        CodeSource source = MemoryAllocator.class.getProtectionDomain().getCodeSource();
        assertThat(source)
                .as("MemoryAllocator must have a CodeSource")
                .isNotNull();
        try {
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Failed to resolve CodeSource URI for MemoryAllocator", e);
        }
    }

    private static void scanClass(Path classFile, List<String> violations) throws IOException {
        ClassModel classModel = ClassFile.of().parse(classFile);
        String className = classModel.thisClass().asInternalName().replace('/', '.');

        for (PoolEntry poolEntry : classModel.constantPool()) {
            if (poolEntry instanceof Utf8Entry utf8) {
                String val = utf8.stringValue();
                if (val.contains(GRAALVM_PREFIX_SLASH) || val.contains(GRAALVM_PREFIX_DOT)) {
                    violations.add(className + " references " + val + " in constant pool");
                    break;
                }
            }
        }

        for (MethodModel methodModel : classModel.methods()) {
            String methodName = methodModel.methodName().stringValue();
            methodModel.code().ifPresent(codeModel -> {
                for (CodeElement element : codeModel) {
                    if (element instanceof Instruction instruction) {
                        String flaggedApi = checkInstruction(instruction);
                        if (flaggedApi != null) {
                            violations.add(className + "." + methodName + " → " + flaggedApi);
                        }
                    }
                }
            });
        }
    }

    // Keep in sync with eu.exeris.kernel.tck.contract.nativeimage.DeclarationSiteDetector
    private static String checkInstruction(Instruction instruction) {
        if (instruction instanceof InvokeInstruction inv) {
            String owner = inv.owner().asInternalName();
            String name = inv.name().stringValue();
            return checkInvokeOrRef(owner, name);
        }
        if (instruction instanceof InvokeDynamicInstruction indy) {
            for (LoadableConstantEntry arg : indy.invokedynamic().bootstrap().arguments()) {
                if (arg instanceof MethodHandleEntry mhe) {
                    MemberRefEntry ref = mhe.reference();
                    String owner = ref.owner().asInternalName();
                    String name = ref.name().stringValue();
                    String flagged = checkInvokeOrRef(owner, name);
                    if (flagged != null) {
                        return flagged;
                    }
                }
            }
        }
        if (instruction instanceof ConstantInstruction.LoadConstantInstruction ldc
                && ldc.constantEntry() instanceof MethodHandleEntry mhe) {
            MemberRefEntry ref = mhe.reference();
            String owner = ref.owner().asInternalName();
            String name = ref.name().stringValue();
            String flagged = checkInvokeOrRef(owner, name);
            if (flagged != null) {
                return flagged;
            }
        }
        return null;
    }

    private static String checkInvokeOrRef(String owner, String name) {
        if ("java/lang/Class".equals(owner)) {
            return checkClassApi(name);
        }
        if (isClassLoader(owner)) {
            return checkClassLoaderApi(name);
        }
        if ("java/lang/Module".equals(owner) && "getResourceAsStream".equals(name)) {
            return "Module.getResourceAsStream";
        }
        if ("java/lang/invoke/MethodHandles$Lookup".equals(owner)) {
            return checkLookupApi(name);
        }
        if ("java/lang/reflect/Array".equals(owner) && "newInstance".equals(name)) {
            return "Array.newInstance";
        }
        if ("java/lang/reflect/Proxy".equals(owner) && "newProxyInstance".equals(name)) {
            return "Proxy.newProxyInstance";
        }
        if ("java/lang/foreign/Linker".equals(owner)
                && ("downcallHandle".equals(name) || "upcallStub".equals(name))) {
            return "Linker." + name;
        }
        if ("java/lang/foreign/FunctionDescriptor".equals(owner)) {
            return checkFunctionDescriptorApi(name);
        }
        if ("java/lang/foreign/Linker$Option".equals(owner)) {
            return "Linker$Option." + name;
        }
        if ("java/lang/foreign/Arena".equals(owner) && "ofShared".equals(name)) {
            return "Arena.ofShared";
        }
        return null;
    }

    @SuppressWarnings("PMD.UseProperClassLoader")
    private static boolean isClassLoader(String owner) {
        if ("java/lang/ClassLoader".equals(owner)) {
            return true;
        }
        String binaryName = owner.replace('/', '.');
        try {
            Class<?> clazz = Class.forName(binaryName, false, ClassLoader.getPlatformClassLoader());
            return ClassLoader.class.isAssignableFrom(clazz);
        } catch (ClassNotFoundException | LinkageError e) {
            try {
                Class<?> clazz = Class.forName(binaryName, false, SpiReachabilityMetadataTest.class.getClassLoader());
                return ClassLoader.class.isAssignableFrom(clazz);
            } catch (ClassNotFoundException | LinkageError ignored) {
                return false;
            }
        }
    }

    private static String checkClassApi(String name) {
        if ("forName".equals(name)) {
            return "Class.forName";
        }
        if (name.startsWith("getDeclared")
                || name.startsWith("getMethod")
                || "getMethods".equals(name)
                || name.startsWith("getField")
                || "getFields".equals(name)
                || name.startsWith("getConstructor")
                || "getConstructors".equals(name)
                || name.startsWith("getResource")) {
            return "Class." + name;
        }
        return null;
    }

    private static String checkClassLoaderApi(String name) {
        if ("loadClass".equals(name)
                || name.startsWith("getResource")
                || name.startsWith("getSystemResource")
                || "getResources".equals(name)) {
            return "ClassLoader." + name;
        }
        return null;
    }

    private static String checkLookupApi(String name) {
        if (name.startsWith("find") || name.startsWith("unreflect")) {
            return "MethodHandles$Lookup." + name;
        }
        return null;
    }

    private static String checkFunctionDescriptorApi(String name) {
        if ("of".equals(name)
                || "ofVoid".equals(name)
                || "dropReturnLayout".equals(name)
                || name.startsWith("append")
                || name.startsWith("insert")
                || name.startsWith("change")) {
            return "FunctionDescriptor." + name;
        }
        return null;
    }
}
