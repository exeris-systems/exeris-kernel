/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.buildconfig.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compiles a small fixture source tree with the {@link RequiresRoleProcessor} on
 * the annotation processor path and asserts the generated
 * {@code RoleCheckRegistry} source carries the expected method-id, mask, and
 * role-bit assignments.
 *
 * <p>The synthetic {@code RequiresRole} + {@code RoleMatch} sources live alongside
 * the fixture so the test does not need a project-scope dependency on
 * {@code exeris-kernel-spi} (that would create a reactor cycle —
 * see {@code build-config/pom.xml}).
 */
@DisplayName("RequiresRoleProcessor")
class RequiresRoleProcessorTest {

    private static final String REQUIRES_ROLE_SOURCE = """
            package eu.exeris.kernel.spi.security;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.SOURCE)
            @Target({ElementType.METHOD, ElementType.TYPE})
            public @interface RequiresRole {
                String[] value();
                RoleMatch match() default RoleMatch.ANY;
            }
            """;

    private static final String ROLE_MATCH_SOURCE = """
            package eu.exeris.kernel.spi.security;
            public enum RoleMatch { ANY, ALL }
            """;

    private static final String ADMIN_API_FIXTURE = """
            package fixtures;
            import eu.exeris.kernel.spi.security.RequiresRole;
            import eu.exeris.kernel.spi.security.RoleMatch;
            public class AdminApi {
                @RequiresRole({"ROLE_ADMIN"})
                public void purgeCache() {}

                @RequiresRole(value = {"ROLE_ADMIN", "ROLE_AUDITOR"}, match = RoleMatch.ALL)
                public void replayLog() {}

                @RequiresRole({"ROLE_OPERATOR", "tenant.metrics.read"})
                public void readMetrics() {}
            }
            """;

    private static final String EXPECTED_METADATA_JSON = """
            {
              "reflection": [
                {
                  "type": "eu.exeris.kernel.security.generated.RoleCheckRegistry",
                  "methods": [
                    { "name": "requiredAny", "parameterTypes": ["int"] },
                    { "name": "requiredAll", "parameterTypes": ["int"] },
                    { "name": "matchIsAll", "parameterTypes": ["int"] },
                    { "name": "methodCount", "parameterTypes": [] },
                    { "name": "roleNameToBit", "parameterTypes": ["java.lang.String"] }
                  ]
                }
              ]
            }
            """;

    @Test
    @DisplayName("Generates RoleCheckRegistry with deterministic method IDs and ANY/ALL masks")
    void generatesRegistry(@TempDir Path workDir) throws IOException {
        String fixture = ADMIN_API_FIXTURE;

        compileWithProcessor(workDir, List.of(REQUIRES_ROLE_SOURCE, ROLE_MATCH_SOURCE, fixture));

        Path registry = workDir.resolve(
                "eu/exeris/kernel/security/generated/RoleCheckRegistry.java");
        assertThat(registry).exists();
        String content = Files.readString(registry);

        // Method IDs are alphabetical by enclosing type + member, so:
        //   M_0 = AdminApi.purgeCache(), M_1 = AdminApi.readMetrics(), M_2 = AdminApi.replayLog()
        assertThat(content)
                .contains("public static final int M_0 = 0;")
                .contains("public static final int M_1 = 1;")
                .contains("public static final int M_2 = 2;")
                .contains("purgeCache()")
                .contains("replayLog()")
                .contains("readMetrics()");

        // Bit assignments:
        //   ROLE_ADMIN = bit 1 (system) → 0x2L
        //   ROLE_OPERATOR = bit 2 (system) → 0x4L
        //   tenant.metrics.read = bit 8 (first app role)  → 0x100L
        //   ROLE_AUDITOR = bit 9 (second app role alphabetically after tenant.metrics.read?)
        //     alphabetical sort over app roles: "ROLE_AUDITOR" < "tenant.metrics.read"
        //     so ROLE_AUDITOR = bit 8, tenant.metrics.read = bit 9.
        assertThat(content)
                .contains("bits.put(\"ROLE_ADMIN\", 1)")
                .contains("bits.put(\"ROLE_OPERATOR\", 2)")
                .contains("bits.put(\"ROLE_AUDITOR\", 8)")
                .contains("bits.put(\"tenant.metrics.read\", 9)");

        // ANY mask for purgeCache (M_0) = ROLE_ADMIN bit 1 → 0x2L
        // ALL mask for replayLog (M_2) = ROLE_ADMIN | ROLE_AUDITOR = 0x2L | 0x100L = 0x102L
        // ANY mask for readMetrics (M_1) = ROLE_OPERATOR | tenant.metrics.read = 0x4L | 0x200L = 0x204L
        assertThat(content)
                .contains("MATCH_IS_ALL = {")
                .contains("MATCH_IS_ALL")
                .contains("0x2L")     // purgeCache ANY mask
                .contains("0x204L")   // readMetrics ANY mask
                .contains("0x102L");  // replayLog ALL mask
    }

    @Test
    @DisplayName("Empty value() raises a compile error")
    void emptyValueIsCompileError(@TempDir Path workDir) throws IOException {
        String fixture = """
                package fixtures;
                import eu.exeris.kernel.spi.security.RequiresRole;
                public class BadApi {
                    @RequiresRole({})
                    public void noRoles() {}
                }
                """;
        ProcessorRun run = compileWithProcessor(workDir,
                List.of(REQUIRES_ROLE_SOURCE, ROLE_MATCH_SOURCE, fixture));

        assertThat(run.diagnostics)
                .as("the processor must report an error for empty @RequiresRole.value()")
                .contains("@RequiresRole.value() must declare at least one role");
    }

    @Test
    @DisplayName("No @RequiresRole declarations produces no registry file")
    void noAnnotationsProducesNoRegistry(@TempDir Path workDir) throws IOException {
        String fixture = """
                package fixtures;
                public class Plain { public void hi() {} }
                """;
        compileWithProcessor(workDir, List.of(REQUIRES_ROLE_SOURCE, ROLE_MATCH_SOURCE, fixture));

        Path registry = workDir.resolve(
                "eu/exeris/kernel/security/generated/RoleCheckRegistry.java");
        assertThat(registry).doesNotExist();
    }

    @Test
    @DisplayName("No @RequiresRole declarations produces no reachability metadata file")
    void noAnnotationsProducesNoMetadata(@TempDir Path workDir) throws IOException {
        String fixture = """
                package fixtures;
                public class Plain { public void hi() {} }
                """;
        compileWithProcessor(workDir, List.of(REQUIRES_ROLE_SOURCE, ROLE_MATCH_SOURCE, fixture));

        Path classesDir = workDir.resolve("classes");
        if (Files.exists(classesDir)) {
            try (Stream<Path> stream = Files.walk(classesDir)) {
                assertThat(stream.filter(Files::isRegularFile))
                        .noneMatch(path -> path.getFileName().toString()
                                .equals(RequiresRoleProcessor.NATIVE_IMAGE_METADATA_FILE));
            }
        }
    }

    @Test
    @DisplayName("Emits reachability metadata at default path")
    void emitsReachabilityMetadataAtDefaultPath(@TempDir Path workDir) throws IOException {
        compileWithProcessor(workDir, List.of(REQUIRES_ROLE_SOURCE, ROLE_MATCH_SOURCE, ADMIN_API_FIXTURE));

        Path metadataFile = workDir.resolve("classes")
                .resolve(RequiresRoleProcessor.DEFAULT_NATIVE_IMAGE_METADATA_DIR)
                .resolve(RequiresRoleProcessor.NATIVE_IMAGE_METADATA_FILE);
        assertThat(metadataFile).exists();
        String content = Files.readString(metadataFile, StandardCharsets.UTF_8);
        assertThat(content).isEqualTo(EXPECTED_METADATA_JSON);
    }

    @Test
    @DisplayName("Metadata names members the generated class declares with expected signatures")
    void metadataNamesMembersTheGeneratedClassDeclares(@TempDir Path workDir) throws Exception {
        compileWithProcessor(workDir, List.of(REQUIRES_ROLE_SOURCE, ROLE_MATCH_SOURCE, ADMIN_API_FIXTURE));

        Path metadataFile = workDir.resolve("classes")
                .resolve(RequiresRoleProcessor.DEFAULT_NATIVE_IMAGE_METADATA_DIR)
                .resolve(RequiresRoleProcessor.NATIVE_IMAGE_METADATA_FILE);
        assertThat(metadataFile).exists();
        String json = Files.readString(metadataFile, StandardCharsets.UTF_8);

        Pattern methodPattern = Pattern.compile(
                "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"parameterTypes\"\\s*:\\s*\\[([^\\]]*)\\]\\s*\\}");
        Matcher matcher = methodPattern.matcher(json);
        record RegisteredMethod(String name, Class<?>[] paramTypes) {}
        List<RegisteredMethod> methods = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            String rawParams = matcher.group(2).trim();
            Class<?>[] params;
            if (rawParams.isEmpty()) {
                params = new Class<?>[0];
            } else {
                String[] split = rawParams.split("\\s*,\\s*");
                params = new Class<?>[split.length];
                for (int i = 0; i < split.length; i++) {
                    String clean = split[i].replace("\"", "").trim();
                    params[i] = switch (clean) {
                        case "int" -> int.class;
                        case "java.lang.String" -> String.class;
                        default -> throw new AssertionError("Unexpected parameter type: " + clean);
                    };
                }
            }
            methods.add(new RegisteredMethod(name, params));
        }

        assertThat(methods)
                .as("Metadata must register exactly five methods")
                .hasSize(5);

        Map<String, Class<?>> expectedReturns = Map.of(
                "requiredAny", long.class,
                "requiredAll", long.class,
                "matchIsAll", boolean.class,
                "methodCount", int.class,
                "roleNameToBit", int.class
        );

        Path classesDir = workDir.resolve("classes");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classesDir.toUri().toURL()})) {
            Class<?> clazz = Class.forName(RequiresRoleProcessor.GENERATED_CLASS_FQN, true, loader);
            for (RegisteredMethod rm : methods) {
                Method method = clazz.getDeclaredMethod(rm.name(), rm.paramTypes());
                assertThat(Modifier.isPublic(method.getModifiers()))
                        .as("method %s must be public", rm.name())
                        .isTrue();
                assertThat(Modifier.isStatic(method.getModifiers()))
                        .as("method %s must be static", rm.name())
                        .isTrue();
                assertThat(method.getReturnType())
                        .as("method %s return type", rm.name())
                        .isEqualTo(expectedReturns.get(rm.name()));
            }
        }
    }

    @Test
    @DisplayName("Option exeris.requiresrole.nativeImageMetadataDir overrides reachability metadata path")
    void metadataDirOptionOverridesPath(@TempDir Path workDir) throws IOException {
        String customDir = "META-INF/native-image/com.acme/app";
        List<String> options = List.of(
                "-A" + RequiresRoleProcessor.NATIVE_IMAGE_METADATA_DIR_OPTION + "=" + customDir);

        ProcessorRun run = compileWithProcessor(workDir,
                List.of(REQUIRES_ROLE_SOURCE, ROLE_MATCH_SOURCE, ADMIN_API_FIXTURE),
                options);
        assertThat(run.ok()).isTrue();

        Path classesDir = workDir.resolve("classes");
        Path overriddenFile = classesDir.resolve(customDir)
                .resolve(RequiresRoleProcessor.NATIVE_IMAGE_METADATA_FILE);
        Path defaultFile = classesDir.resolve(RequiresRoleProcessor.DEFAULT_NATIVE_IMAGE_METADATA_DIR)
                .resolve(RequiresRoleProcessor.NATIVE_IMAGE_METADATA_FILE);

        assertThat(overriddenFile).exists();
        assertThat(Files.readString(overriddenFile, StandardCharsets.UTF_8)).isEqualTo(EXPECTED_METADATA_JSON);
        assertThat(defaultFile).doesNotExist();
    }

    @Test
    @DisplayName("Option with trailing slash strips slash and writes to normalized path")
    void metadataDirOptionStripsTrailingSlash(@TempDir Path workDir) throws IOException {
        String customDir = "META-INF/native-image/com.acme/app";
        List<String> options = List.of(
                "-A" + RequiresRoleProcessor.NATIVE_IMAGE_METADATA_DIR_OPTION + "=" + customDir + "/");

        ProcessorRun run = compileWithProcessor(workDir,
                List.of(REQUIRES_ROLE_SOURCE, ROLE_MATCH_SOURCE, ADMIN_API_FIXTURE),
                options);
        assertThat(run.ok()).isTrue();

        Path classesDir = workDir.resolve("classes");
        Path overriddenFile = classesDir.resolve(customDir)
                .resolve(RequiresRoleProcessor.NATIVE_IMAGE_METADATA_FILE);
        assertThat(overriddenFile).exists();
    }

    @Test
    @DisplayName("Option specifying path outside META-INF/native-image/ causes compile error and emits no metadata")
    void metadataDirOutsideNativeImageRootIsCompileError(@TempDir Path workDir) throws IOException {
        List<String> options = List.of(
                "-A" + RequiresRoleProcessor.NATIVE_IMAGE_METADATA_DIR_OPTION + "=resources/x");

        ProcessorRun run = compileWithProcessor(workDir,
                List.of(REQUIRES_ROLE_SOURCE, ROLE_MATCH_SOURCE, ADMIN_API_FIXTURE),
                options);
        assertThat(run.ok()).isFalse();
        assertThat(run.diagnostics())
                .contains(RequiresRoleProcessor.NATIVE_IMAGE_METADATA_DIR_OPTION)
                .contains("META-INF/native-image/");

        Path classesDir = workDir.resolve("classes");
        if (Files.exists(classesDir)) {
            try (Stream<Path> stream = Files.walk(classesDir)) {
                assertThat(stream.filter(Files::isRegularFile))
                        .noneMatch(path -> path.getFileName().toString()
                                .equals(RequiresRoleProcessor.NATIVE_IMAGE_METADATA_FILE));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "META-INF/native-image/../../../escaped",
            "META-INF/native-image/a/../b",
            "META-INF/native-image/./a",
            "META-INF/native-image//a",
            "META-INF/native-image/",
            "META-INF/native-image/a\\..\\b"
    })
    @DisplayName("Option with empty, '.', or '..' segments causes compile error and emits no metadata")
    void metadataDirWithInvalidSegmentsIsCompileError(String invalidDir, @TempDir Path workDir) throws IOException {
        List<String> options = List.of(
                "-A" + RequiresRoleProcessor.NATIVE_IMAGE_METADATA_DIR_OPTION + "=" + invalidDir);

        ProcessorRun run = compileWithProcessor(workDir,
                List.of(REQUIRES_ROLE_SOURCE, ROLE_MATCH_SOURCE, ADMIN_API_FIXTURE),
                options);
        assertThat(run.ok()).isFalse();
        assertThat(run.diagnostics())
                .contains(RequiresRoleProcessor.NATIVE_IMAGE_METADATA_DIR_OPTION)
                .contains("META-INF/native-image/");

        if (Files.exists(workDir)) {
            try (Stream<Path> stream = Files.walk(workDir)) {
                assertThat(stream.filter(Files::isRegularFile))
                        .noneMatch(path -> path.getFileName().toString()
                                .equals(RequiresRoleProcessor.NATIVE_IMAGE_METADATA_FILE));
            }
        }
    }

    private static ProcessorRun compileWithProcessor(Path workDir, List<String> sources) throws IOException {
        return compileWithProcessor(workDir, sources, List.of());
    }

    private static ProcessorRun compileWithProcessor(Path workDir,
                                                     List<String> sources,
                                                     List<String> extraOptions) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("system Java compiler must be available").isNotNull();

        Path classes = workDir.resolve("classes");
        Files.createDirectories(classes);

        List<JavaFileObject> units = sources.stream()
                .map(InMemorySource::new)
                .map(JavaFileObject.class::cast)
                .toList();

        List<String> options = new ArrayList<>(List.of("-proc:full", "--release", "25"));
        options.addAll(extraOptions);

        StringBuilder diagnostics = new StringBuilder();
        boolean ok;
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(workDir.toFile()));

            ok = compiler.getTask(
                    null,
                    fileManager,
                    diagnostic -> diagnostics.append(diagnostic.getKind())
                            .append(": ").append(diagnostic.getMessage(null)).append('\n'),
                    options,
                    null,
                    units
            ).call();
        }
        return new ProcessorRun(ok, diagnostics.toString());
    }

    private record ProcessorRun(boolean ok, String diagnostics) { }

    private static final class InMemorySource extends SimpleJavaFileObject {

        private final String content;

        InMemorySource(String content) {
            super(URI.create("memory:///" + extractName(content) + ".java"), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }

        private static final String URI_SEPARATOR = "/";

        private static String extractName(String source) {
            // Pull "package foo.bar; ... class|interface|@interface|enum Name" → foo/bar/Name
            String pkg = lineMatching(source, "package ");
            String pkgPath = pkg == null ? "" : pkg.substring("package ".length(), pkg.indexOf(';'))
                    .trim().replace(".", URI_SEPARATOR) + URI_SEPARATOR;
            String typeLine = Stream.of("public class ", "class ", "interface ",
                            "public interface ", "public @interface ", "@interface ",
                            "public enum ", "enum ")
                    .map(prefix -> matchAfter(source, prefix))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "fixture source missing top-level type declaration"));
            return pkgPath + typeLine;
        }

        private static String matchAfter(String source, String prefix) {
            int idx = source.indexOf(prefix);
            if (idx < 0) {
                return null;
            }
            int start = idx + prefix.length();
            int end = start;
            while (end < source.length()) {
                char current = source.charAt(end);
                if (Character.isJavaIdentifierPart(current)) {
                    end++;
                } else {
                    break;
                }
            }
            return source.substring(start, end);
        }

        private static String lineMatching(String source, String prefix) {
            for (String line : source.split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.startsWith(prefix)) {
                    return trimmed;
                }
            }
            return null;
        }
    }
}
