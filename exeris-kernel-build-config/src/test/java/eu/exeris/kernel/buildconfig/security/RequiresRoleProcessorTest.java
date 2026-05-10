/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.buildconfig.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    @DisplayName("Generates RoleCheckRegistry with deterministic method IDs and ANY/ALL masks")
    void generatesRegistry(@TempDir Path workDir) throws IOException {
        String fixture = """
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

    private static ProcessorRun compileWithProcessor(Path workDir, List<String> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("system Java compiler must be available").isNotNull();

        Path classes = workDir.resolve("classes");
        Files.createDirectories(classes);

        List<JavaFileObject> units = sources.stream()
                .map(InMemorySource::new)
                .map(JavaFileObject.class::cast)
                .toList();

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
                    List.of("-proc:full", "--release", "26"),
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
