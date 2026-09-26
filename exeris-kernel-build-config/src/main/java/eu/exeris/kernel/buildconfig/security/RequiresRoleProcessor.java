/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.buildconfig.security;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compile-time annotation processor for {@code @RequiresRole} per ADR-014.
 *
 * <h2>What it produces</h2>
 * <p>For every method or type annotated with
 * {@code eu.exeris.kernel.spi.security.RequiresRole} across a compilation unit,
 * the processor emits a single Java source file:
 *
 * <pre>{@code
 * eu.exeris.kernel.security.generated.RoleCheckRegistry
 * }</pre>
 *
 * <p>The generated class is final, has primitive-only static fields, and exposes
 * O(1) lookup methods so the runtime decision is a single bitmask AND/EQ:
 *
 * <ul>
 *   <li>{@code requiredAny(int methodId)} — used when {@code RoleMatch.ANY}.</li>
 *   <li>{@code requiredAll(int methodId)} — used when {@code RoleMatch.ALL}.</li>
 *   <li>{@code matchIsAll(int methodId)} — boolean discriminator so the runtime
 *       picks the right primitive comparison.</li>
 *   <li>{@code methodCount()} — number of annotated entry points compiled.</li>
 *   <li>{@code methodNames()} — ordered list of fully-qualified method
 *       identifiers (for diagnostics; never on the hot path).</li>
 *   <li>{@code roleNameToBit(String)} — build-time role-name → bit lookup so
 *       the runtime can resolve principal claim strings against the same
 *       canonical assignment used at compile time.</li>
 * </ul>
 *
 * <h2>Determinism</h2>
 * <p>Method IDs are assigned in alphabetical order of the fully-qualified
 * declaring type + method signature so the generated artifact is identical
 * across rebuilds. Application-defined role names get bits assigned
 * alphabetically starting at {@link #SYSTEM_ROLE_BIT_LIMIT}; system roles
 * (per {@code KernelRoles}) keep their fixed canonical bits.
 *
 * <h2>SPI coupling</h2>
 * <p>This processor reads the annotation by FQN string; it does not declare a
 * compile-time dependency on {@code exeris-kernel-spi}. That keeps build-config
 * out of the SPI reactor cycle (SPI consumes build-config's checkstyle/PMD
 * rulesets via plugin dependencies). System role bit assignments are duplicated
 * here as constants — they MUST stay in sync with
 * {@code eu.exeris.kernel.spi.security.KernelRoles}.
 */
@SupportedAnnotationTypes(RequiresRoleProcessor.REQUIRES_ROLE_FQN)
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class RequiresRoleProcessor extends AbstractProcessor {

    /** Fully-qualified name of the SPI annotation processed by this implementation. */
    public static final String REQUIRES_ROLE_FQN = "eu.exeris.kernel.spi.security.RequiresRole";

    /** Fully-qualified name of the generated registry class. */
    public static final String GENERATED_CLASS_FQN = "eu.exeris.kernel.security.generated.RoleCheckRegistry";

    /** Reserved range for kernel system roles (bits {@code [0, 8)}). Mirrors {@code KernelRoles}. */
    public static final int SYSTEM_ROLE_BIT_LIMIT = 8;

    private static final String GENERATED_PACKAGE = "eu.exeris.kernel.security.generated";
    private static final String GENERATED_SIMPLE_NAME = "RoleCheckRegistry";
    private static final long ROLE_MASK_WIDTH = 64L;

    // Repeated literals extracted to keep generated source layout consistent and to
    // satisfy SonarQube S1192 (duplicated literal warnings).
    private static final String FOUR_SPACES = "    ";
    private static final String EIGHT_SPACES = "        ";
    private static final String CLOSE_BLOCK = FOUR_SPACES + "}";
    private static final String CLOSE_ARRAY_LITERAL = FOUR_SPACES + "};";

    /**
     * Canonical kernel-owned role-name → bit-position mapping. Mirrors
     * {@code eu.exeris.kernel.spi.security.KernelRoles}. Application-defined
     * roles outside this map receive bits {@code [SYSTEM_ROLE_BIT_LIMIT, 64)}
     * in alphabetical order at build time.
     */
    private static final Map<String, Integer> SYSTEM_ROLE_BITS = Map.of(
            "ROLE_SYSTEM",   0,
            "ROLE_ADMIN",    1,
            "ROLE_OPERATOR", 2,
            "ROLE_USER",     3
    );

    private final List<MethodDescriptor> methods = new ArrayList<>();
    private final TreeMap<String, Integer> applicationRoleBits = new TreeMap<>();
    private boolean generated;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty() && roundEnv.errorRaised()) {
            return false;
        }
        for (TypeElement annotation : annotations) {
            if (REQUIRES_ROLE_FQN.equals(annotation.getQualifiedName().toString())) {
                collect(roundEnv.getElementsAnnotatedWith(annotation));
            }
        }
        if (roundEnv.processingOver() && !generated && !methods.isEmpty()) {
            try {
                emit();
            } catch (IOException ex) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "RequiresRoleProcessor: failed to write " + GENERATED_CLASS_FQN
                                + " — " + ex.getMessage());
            }
            generated = true;
        }
        return true;
    }

    private void collect(Set<? extends Element> elements) {
        for (Element element : elements) {
            collectOne(element);
        }
    }

    private void collectOne(Element element) {
        Messager messager = processingEnv.getMessager();
        ElementKind kind = element.getKind();
        if (kind != ElementKind.METHOD && kind != ElementKind.CLASS && kind != ElementKind.INTERFACE) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    "@RequiresRole on unsupported element kind " + kind + " — ignored", element);
            return;
        }
        AnnotationMirror mirror = findMirror(element);
        if (mirror == null) {
            return;
        }
        List<String> roles = readStringArray(mirror, "value");
        if (roles.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@RequiresRole.value() must declare at least one role", element);
            return;
        }
        boolean matchAll = "ALL".equals(readEnumValue(mirror, "match", "ANY"));
        String methodKey = methodKey(element);
        methods.add(new MethodDescriptor(methodKey, List.copyOf(roles), matchAll));
        for (String role : roles) {
            if (!SYSTEM_ROLE_BITS.containsKey(role)) {
                applicationRoleBits.putIfAbsent(role, -1);
            }
        }
    }

    private static AnnotationMirror findMirror(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (REQUIRES_ROLE_FQN.equals(mirror.getAnnotationType().toString())) {
                return mirror;
            }
        }
        return null;
    }

    private static List<String> readStringArray(AnnotationMirror mirror, String name) {
        AnnotationValue value = findValue(mirror, name);
        if (value == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<? extends AnnotationValue> entries = (List<? extends AnnotationValue>) value.getValue();
        List<String> out = new ArrayList<>(entries.size());
        for (AnnotationValue entry : entries) {
            Object raw = entry.getValue();
            if (raw != null) {
                out.add(raw.toString());
            }
        }
        return out;
    }

    private static String readEnumValue(AnnotationMirror mirror, String name, String fallback) {
        AnnotationValue value = findValue(mirror, name);
        if (value == null) {
            return fallback;
        }
        Object raw = value.getValue();
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof VariableElement enumConstant) {
            return enumConstant.getSimpleName().toString();
        }
        return raw.toString();
    }

    private static AnnotationValue findValue(AnnotationMirror mirror, String name) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : mirror.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String methodKey(Element element) {
        Element parent = element.getEnclosingElement();
        String prefix = parent == null ? "" : parent + ".";
        return prefix + element;
    }

    private void emit() throws IOException {
        methods.sort((left, right) -> left.methodKey.compareTo(right.methodKey));
        assignApplicationBits();
        Filer filer = processingEnv.getFiler();
        JavaFileObject source = filer.createSourceFile(GENERATED_CLASS_FQN);
        try (PrintWriter writer = new PrintWriter(source.openWriter())) {
            writeRegistry(writer);
        }
    }

    private void assignApplicationBits() {
        long nextBit = SYSTEM_ROLE_BIT_LIMIT;
        for (Map.Entry<String, Integer> entry : applicationRoleBits.entrySet()) {
            if (nextBit >= ROLE_MASK_WIDTH) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@RequiresRole role budget exceeded (max " + ROLE_MASK_WIDTH
                                + " roles per long mask). Offending role: " + entry.getKey());
                return;
            }
            entry.setValue((int) nextBit);
            nextBit++;
        }
    }

    private long bitFor(String role) {
        Integer system = SYSTEM_ROLE_BITS.get(role);
        if (system != null) {
            return 1L << system;
        }
        Integer app = applicationRoleBits.get(role);
        return app == null ? 0L : 1L << app;
    }

    private long mask(List<String> roles) {
        long mask = 0L;
        for (String role : roles) {
            mask |= bitFor(role);
        }
        return mask;
    }

    private void writeRegistry(PrintWriter writer) {
        writer.println("// Generated by " + getClass().getName() + " — do not edit.");
        writer.println("package " + GENERATED_PACKAGE + ";");
        writer.println();
        writer.println("public final class " + GENERATED_SIMPLE_NAME + " {");
        writer.println();
        writeMethodIdConstants(writer);
        writer.println();
        writeAnyMaskArray(writer);
        writer.println();
        writeAllMaskArray(writer);
        writer.println();
        writeMatchDiscriminatorArray(writer);
        writer.println();
        writeMethodNamesArray(writer);
        writer.println();
        writeRoleNameMap(writer);
        writer.println();
        writeAccessors(writer);
        writer.println();
        writer.println(FOUR_SPACES + "private " + GENERATED_SIMPLE_NAME + "() {");
        writer.println(EIGHT_SPACES + "// generated registry — not instantiable");
        writer.println(CLOSE_BLOCK);
        writer.println("}");
    }

    private void writeMethodIdConstants(PrintWriter writer) {
        for (int index = 0; index < methods.size(); index++) {
            writer.println(FOUR_SPACES + "/** " + escapeJavadoc(methods.get(index).methodKey) + " */");
            writer.println(FOUR_SPACES + "public static final int M_" + index + " = " + index + ";");
        }
    }

    private void writeAnyMaskArray(PrintWriter writer) {
        writer.println(FOUR_SPACES + "private static final long[] REQUIRED_ANY = {");
        for (int index = 0; index < methods.size(); index++) {
            MethodDescriptor descriptor = methods.get(index);
            long maskValue = descriptor.matchAll ? 0L : mask(descriptor.roles);
            writer.println(EIGHT_SPACES + maskLiteral(maskValue) + entrySeparator(index));
        }
        writer.println(CLOSE_ARRAY_LITERAL);
    }

    private void writeAllMaskArray(PrintWriter writer) {
        writer.println(FOUR_SPACES + "private static final long[] REQUIRED_ALL = {");
        for (int index = 0; index < methods.size(); index++) {
            MethodDescriptor descriptor = methods.get(index);
            long maskValue = descriptor.matchAll ? mask(descriptor.roles) : 0L;
            writer.println(EIGHT_SPACES + maskLiteral(maskValue) + entrySeparator(index));
        }
        writer.println(CLOSE_ARRAY_LITERAL);
    }

    private void writeMatchDiscriminatorArray(PrintWriter writer) {
        writer.println(FOUR_SPACES + "private static final boolean[] MATCH_IS_ALL = {");
        for (int index = 0; index < methods.size(); index++) {
            writer.println(EIGHT_SPACES + methods.get(index).matchAll + entrySeparator(index));
        }
        writer.println(CLOSE_ARRAY_LITERAL);
    }

    private void writeMethodNamesArray(PrintWriter writer) {
        writer.println(FOUR_SPACES + "private static final String[] METHOD_NAMES = {");
        for (int index = 0; index < methods.size(); index++) {
            writer.println(EIGHT_SPACES + "\"" + escapeString(methods.get(index).methodKey) + "\""
                    + entrySeparator(index));
        }
        writer.println(CLOSE_ARRAY_LITERAL);
    }

    private String entrySeparator(int index) {
        return index < methods.size() - 1 ? "," : "";
    }

    private void writeRoleNameMap(PrintWriter writer) {
        writer.println(FOUR_SPACES + "private static final java.util.Map<String, Integer> ROLE_BITS;");
        writer.println(FOUR_SPACES + "static {");
        writer.println(EIGHT_SPACES + "java.util.HashMap<String, Integer> bits = new java.util.HashMap<>();");
        for (Map.Entry<String, Integer> entry : new TreeMap<>(SYSTEM_ROLE_BITS).entrySet()) {
            writer.println(EIGHT_SPACES + "bits.put(\"" + escapeString(entry.getKey()) + "\", "
                    + entry.getValue() + ");");
        }
        for (Map.Entry<String, Integer> entry : applicationRoleBits.entrySet()) {
            writer.println(EIGHT_SPACES + "bits.put(\"" + escapeString(entry.getKey()) + "\", "
                    + entry.getValue() + ");");
        }
        writer.println(EIGHT_SPACES + "ROLE_BITS = java.util.Map.copyOf(bits);");
        writer.println(CLOSE_BLOCK);
    }

    private void writeAccessors(PrintWriter writer) {
        writer.println(FOUR_SPACES + "public static int methodCount() { return " + methods.size() + "; }");
        writer.println(FOUR_SPACES + "public static long requiredAny(int methodId) "
                + "{ return REQUIRED_ANY[methodId]; }");
        writer.println(FOUR_SPACES + "public static long requiredAll(int methodId) "
                + "{ return REQUIRED_ALL[methodId]; }");
        writer.println(FOUR_SPACES + "public static boolean matchIsAll(int methodId) "
                + "{ return MATCH_IS_ALL[methodId]; }");
        writer.println(FOUR_SPACES + "public static String methodName(int methodId) "
                + "{ return METHOD_NAMES[methodId]; }");
        writer.println(FOUR_SPACES + "public static java.util.Set<String> roleNames() "
                + "{ return ROLE_BITS.keySet(); }");
        writer.println(FOUR_SPACES + "public static int roleNameToBit(String role) {");
        writer.println(EIGHT_SPACES + "Integer bit = ROLE_BITS.get(role);");
        writer.println(EIGHT_SPACES + "return bit == null ? -1 : bit;");
        writer.println(CLOSE_BLOCK);
    }

    private static String maskLiteral(long mask) {
        return "0x" + Long.toHexString(mask) + "L";
    }

    private static String escapeJavadoc(String value) {
        return value.replace("*/", "* /");
    }

    private static String escapeString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Snapshot of one annotated method's binding intent. */
    private record MethodDescriptor(String methodKey, List<String> roles, boolean matchAll) { }
}
