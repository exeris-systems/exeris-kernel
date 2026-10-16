/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.nativeimage;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Bytecode scanner inspecting compiled class files for declaration sites touching reflection,
 * dynamic resources, GraalVM types, or foreign function and memory APIs.
 *
 * <p>Uses the standard JDK {@link java.lang.classfile} API to scan classes deterministically
 * without requiring additional runtime dependencies or preview flags.
 *
 * @since 0.13
 */
public final class DeclarationSiteScanner {

    private static final String CLASS_SUFFIX = ".class";
    private static final String MODULE_INFO = "module-info.class";
    private static final String GRAALVM_PREFIX_SLASH = "org/graalvm/";
    private static final String GRAALVM_PREFIX_DOT = "org.graalvm.";

    private DeclarationSiteScanner() {
        // Static scanner utility.
    }

    /**
     * An allowlist entry specifying an allowed declaration site and its disposition.
     *
     * @param ownerClass  binary name of the class containing the declaration site
     * @param methodName  optional method name containing the site, or {@code null} to match any method in the class
     * @param api         canonical name of the allowed API
     * @param disposition explanation of why this site is permitted or how it is covered
     * @since 0.13
     */
    public record AllowlistEntry(
            String ownerClass,
            String methodName,
            String api,
            String disposition
    ) {
        /**
         * Constructs an allowlist entry with non-null components.
         *
         * @param ownerClass  binary name of the class
         * @param methodName  optional method name containing the site, or {@code null} to match any method
         * @param api         canonical name of the allowed API
         * @param disposition explanation of the site's disposition
         */
        public AllowlistEntry {
            Objects.requireNonNull(ownerClass, "ownerClass");
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(disposition, "disposition");
        }

        /**
         * Constructs an allowlist entry matching any method in the class.
         *
         * @param ownerClass  binary name of the class
         * @param api         canonical name of the allowed API
         * @param disposition explanation of the site's disposition
         */
        public AllowlistEntry(String ownerClass, String api, String disposition) {
            this(ownerClass, null, api, disposition);
        }
    }

    /**
     * Combined result of a single-pass scan containing discovered declaration sites and extracted VarHandle targets.
     *
     * @param sites      sorted list of discovered declaration sites
     * @param varHandles sorted list of extracted VarHandle targets
     * @since 0.13
     */
    public record ScanResult(
            List<DeclarationSite> sites,
            List<VarHandleTarget> varHandles
    ) {
        /**
         * Constructs an immutable scan result with non-null lists.
         *
         * @param sites      discovered declaration sites
         * @param varHandles extracted VarHandle targets
         */
        public ScanResult {
            Objects.requireNonNull(sites, "sites");
            Objects.requireNonNull(varHandles, "varHandles");
            sites = List.copyOf(sites);
            varHandles = List.copyOf(varHandles);
        }
    }

    /**
     * An extracted target from a {@code MethodHandles.Lookup.findVarHandle} invocation.
     *
     * @param declaringClass binary name of the class calling {@code findVarHandle}
     * @param methodName     name of the method containing the call
     * @param receiverClass  binary name of the target class containing the field
     * @param fieldName      name of the accessed field
     * @param fieldType      type of the accessed field (canonical primitive name or binary class name)
     * @since 0.13
     */
    public record VarHandleTarget(
            String declaringClass,
            String methodName,
            String receiverClass,
            String fieldName,
            String fieldType
    ) implements Comparable<VarHandleTarget> {
        /**
         * Constructs a {@link VarHandleTarget} with non-null components.
         *
         * @param declaringClass binary name of the class calling {@code findVarHandle}
         * @param methodName     name of the method containing the call
         * @param receiverClass  binary name of the target class
         * @param fieldName      name of the field
         * @param fieldType      type of the field
         */
        public VarHandleTarget {
            Objects.requireNonNull(declaringClass, "declaringClass");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(receiverClass, "receiverClass");
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(fieldType, "fieldType");
        }

        @Override
        public int compareTo(VarHandleTarget other) {
            int cmp = receiverClass.compareTo(other.receiverClass);
            if (cmp != 0) {
                return cmp;
            }
            cmp = fieldName.compareTo(other.fieldName);
            if (cmp != 0) {
                return cmp;
            }
            cmp = fieldType.compareTo(other.fieldType);
            if (cmp != 0) {
                return cmp;
            }
            cmp = declaringClass.compareTo(other.declaringClass);
            if (cmp != 0) {
                return cmp;
            }
            return methodName.compareTo(other.methodName);
        }
    }

    /**
     * Resolves the compiled classes directory for the module declaring the given anchor class.
     *
     * @param anchorClass an anchor class declared by the target module
     * @return path to the compiled classes directory
     */
    public static Path classesDirectory(Class<?> anchorClass) {
        Objects.requireNonNull(anchorClass, "anchorClass");
        CodeSource source = anchorClass.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("Class " + anchorClass.getName() + " has no accessible CodeSource");
        }
        Path path;
        try {
            path = Path.of(source.getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Failed to convert CodeSource URI for " + anchorClass.getName(), e);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("Classes location for " + anchorClass.getName()
                    + " is not a directory: " + path);
        }
        return path;
    }

    /**
     * Scans the module containing the anchor class in a single pass, returning both declaration sites
     * and VarHandle targets.
     *
     * @param anchorClass anchor class used to locate the module's classes directory
     * @return combined scan result
     * @throws IOException if scanning fails
     * @since 0.13
     */
    public static ScanResult scanAll(Class<?> anchorClass) throws IOException {
        return scanAll(classesDirectory(anchorClass));
    }

    /**
     * Scans the given classes directory in a single pass, returning both declaration sites
     * and VarHandle targets.
     *
     * @param classesDir directory containing compiled {@code .class} files
     * @return combined scan result
     * @throws IOException if reading class files fails
     * @since 0.13
     */
    public static ScanResult scanAll(Path classesDir) throws IOException {
        if (!Files.isDirectory(classesDir)) {
            throw new IllegalStateException("Classes location is not a directory: " + classesDir);
        }
        List<DeclarationSite> sites = new ArrayList<>();
        List<VarHandleTarget> varHandles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(classesDir)) {
            List<Path> classFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(CLASS_SUFFIX))
                    .filter(path -> !MODULE_INFO.equals(path.getFileName().toString()))
                    .toList();
            if (classFiles.isEmpty()) {
                throw new IllegalStateException("No .class files found in classes directory: " + classesDir);
            }
            for (Path classFile : classFiles) {
                scanClassInto(classFile, sites, varHandles);
            }
        }
        Collections.sort(sites);
        Collections.sort(varHandles);
        return new ScanResult(sites, varHandles);
    }

    /**
     * Scans a single class file in a single pass, returning both declaration sites and VarHandle targets.
     *
     * @param classFile path to the {@code .class} file
     * @return combined scan result for the class
     * @throws IOException if reading the class file fails
     * @since 0.13
     */
    public static ScanResult scanClassAll(Path classFile) throws IOException {
        List<DeclarationSite> sites = new ArrayList<>();
        List<VarHandleTarget> varHandles = new ArrayList<>();
        scanClassInto(classFile, sites, varHandles);
        Collections.sort(sites);
        Collections.sort(varHandles);
        return new ScanResult(sites, varHandles);
    }

    /**
     * Scans the module containing the anchor class for declaration sites.
     *
     * @param anchorClass anchor class used to locate the module's classes directory
     * @return sorted list of discovered declaration sites
     * @throws IOException if directory traversal fails
     */
    public static List<DeclarationSite> scan(Class<?> anchorClass) throws IOException {
        return scanAll(anchorClass).sites();
    }

    /**
     * Scans the given classes directory recursively for declaration sites.
     *
     * @param classesDir directory containing compiled {@code .class} files
     * @return sorted list of discovered declaration sites
     * @throws IOException if reading class files fails
     */
    public static List<DeclarationSite> scan(Path classesDir) throws IOException {
        return scanAll(classesDir).sites();
    }

    /**
     * Scans a single class file for declaration sites.
     *
     * @param classFile path to the {@code .class} file
     * @return list of declaration sites found in the class
     * @throws IOException if reading the class file fails
     */
    public static List<DeclarationSite> scanClass(Path classFile) throws IOException {
        return scanClassAll(classFile).sites();
    }

    /**
     * Scans all {@code MethodHandles.Lookup.findVarHandle} invocations in the module of the anchor class,
     * extracting their target receiver, field name, and field type literals.
     *
     * @param anchorClass anchor class used to locate the module's classes directory
     * @return sorted list of extracted {@link VarHandleTarget} objects
     * @throws IOException if scanning fails
     */
    public static List<VarHandleTarget> scanVarHandles(Class<?> anchorClass) throws IOException {
        return scanAll(anchorClass).varHandles();
    }

    /**
     * Scans all {@code MethodHandles.Lookup.findVarHandle} invocations in the given classes directory,
     * extracting their target receiver, field name, and field type literals.
     *
     * @param classesDir directory containing compiled {@code .class} files
     * @return sorted list of extracted {@link VarHandleTarget} objects
     * @throws IOException if reading class files fails
     */
    public static List<VarHandleTarget> scanVarHandles(Path classesDir) throws IOException {
        return scanAll(classesDir).varHandles();
    }

    /**
     * Scans a single class file for {@code MethodHandles.Lookup.findVarHandle} invocations and extracts their targets.
     *
     * @param classFile path to the {@code .class} file
     * @return list of extracted {@link VarHandleTarget} objects
     * @throws IOException if reading the class file fails
     */
    public static List<VarHandleTarget> scanVarHandlesInClass(Path classFile) throws IOException {
        return scanClassAll(classFile).varHandles();
    }

    private static void scanClassInto(Path classFile,
                                      List<DeclarationSite> sites,
                                      List<VarHandleTarget> varHandles) throws IOException {
        ClassModel classModel = ClassFile.of().parse(classFile);
        String className = classModel.thisClass().asInternalName().replace('/', '.');

        scanConstantPool(classModel, className, sites);

        for (MethodModel methodModel : classModel.methods()) {
            String methodName = methodModel.methodName().stringValue();
            methodModel.code().ifPresent(codeModel -> {
                List<Instruction> instructions = collectInstructions(codeModel);
                for (int index = 0; index < instructions.size(); index++) {
                    Instruction instruction = instructions.get(index);
                    String api = DeclarationSiteDetector.detectFlaggedApi(
                            instruction, instructions, index, className, methodName);
                    if (api != null) {
                        sites.add(new DeclarationSite(className, methodName, api));
                    }
                    if (instruction instanceof InvokeInstruction inv
                            && "java/lang/invoke/MethodHandles$Lookup".equals(inv.owner().asInternalName())
                            && "findVarHandle".equals(inv.name().stringValue())) {
                        varHandles.add(VarHandleTargetExtractor.extract(className, methodName, instructions, index));
                    }
                }
            });
        }
    }

    /**
     * Asserts that all discovered declaration sites match entries in the allowlist and that no allowlist
     * entry is stale.
     *
     * @param sites     discovered declaration sites
     * @param allowlist expected allowlist entries with dispositions
     */
    public static void assertAllowlistMatches(List<DeclarationSite> sites, Collection<AllowlistEntry> allowlist) {
        AllowlistMatcher.assertMatches(sites, allowlist);
    }

    /**
     * Creates an allowlist entry for a binary class name, API identifier, and disposition.
     *
     * @param ownerClass  binary name of the class
     * @param api         canonical name of the allowed API
     * @param disposition rationale for allowing this site
     * @return constructed {@link AllowlistEntry}
     */
    public static AllowlistEntry allow(String ownerClass, String api, String disposition) {
        return new AllowlistEntry(ownerClass, null, api, disposition);
    }

    /**
     * Creates an allowlist entry for a class object, API identifier, and disposition.
     *
     * @param ownerClass  class containing the site
     * @param api         canonical name of the allowed API
     * @param disposition rationale for allowing this site
     * @return constructed {@link AllowlistEntry}
     */
    public static AllowlistEntry allow(Class<?> ownerClass, String api, String disposition) {
        return new AllowlistEntry(ownerClass.getName(), null, api, disposition);
    }

    /**
     * Creates an allowlist entry for a binary class name, method name, API identifier, and disposition.
     *
     * @param ownerClass  binary name of the class
     * @param methodName  name of the method containing the site
     * @param api         canonical name of the allowed API
     * @param disposition rationale for allowing this site
     * @return constructed {@link AllowlistEntry}
     * @since 0.13
     */
    public static AllowlistEntry allow(String ownerClass, String methodName, String api, String disposition) {
        return new AllowlistEntry(ownerClass, methodName, api, disposition);
    }

    /**
     * Creates an allowlist entry for a class object, method name, API identifier, and disposition.
     *
     * @param ownerClass  class containing the site
     * @param methodName  name of the method containing the site
     * @param api         canonical name of the allowed API
     * @param disposition rationale for allowing this site
     * @return constructed {@link AllowlistEntry}
     * @since 0.13
     */
    public static AllowlistEntry allow(Class<?> ownerClass, String methodName, String api, String disposition) {
        return new AllowlistEntry(ownerClass.getName(), methodName, api, disposition);
    }

    private static List<Instruction> collectInstructions(Iterable<CodeElement> codeModel) {
        List<Instruction> instructions = new ArrayList<>();
        for (CodeElement element : codeModel) {
            if (element instanceof Instruction instruction) {
                instructions.add(instruction);
            }
        }
        return instructions;
    }

    private static void scanConstantPool(ClassModel classModel, String className, List<DeclarationSite> sites) {
        boolean graalRefFound = false;
        for (PoolEntry poolEntry : classModel.constantPool()) {
            if (poolEntry instanceof Utf8Entry utf8) {
                String val = utf8.stringValue();
                if (val.contains(GRAALVM_PREFIX_SLASH) || val.contains(GRAALVM_PREFIX_DOT)) {
                    graalRefFound = true;
                    break;
                }
            }
        }
        if (graalRefFound) {
            sites.add(new DeclarationSite(className, "<constant-pool>", GRAALVM_PREFIX_SLASH));
        }
    }
}
