/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.buildconfig.config;

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
 * Compiles small fixture trees with the {@link ImmutableConfigProcessor} on the annotation
 * processor path and asserts the contract violations are surfaced as compile errors.
 *
 * <p>The synthetic {@code Immutable} + {@code Dynamic} annotation sources live alongside the
 * fixtures so the test does not need a project-scope dependency on {@code exeris-kernel-spi}
 * (that would create a reactor cycle — see {@code build-config/pom.xml}). The processor reads
 * the real annotation FQNs, which the synthetic sources reproduce exactly.
 */
@DisplayName("ImmutableConfigProcessor")
class ImmutableConfigProcessorTest {

    private static final String IMMUTABLE_SOURCE = """
            package eu.exeris.kernel.spi.config;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.FIELD)
            public @interface Immutable {
                String file();
                String key();
                String reason() default "";
            }
            """;

    private static final String DYNAMIC_SOURCE = """
            package eu.exeris.kernel.spi.config;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.FIELD)
            public @interface Dynamic {
                String file();
                String key();
            }
            """;

    @Test
    @DisplayName("A field carrying both @Immutable and @Dynamic is a compile error")
    void bothAnnotationsIsCompileError(@TempDir Path workDir) throws IOException {
        String fixture = """
                package fixtures;
                import eu.exeris.kernel.spi.config.Immutable;
                import eu.exeris.kernel.spi.config.Dynamic;
                public final class Contradiction {
                    @Immutable(file = "security.properties", key = "security.jwks.uri")
                    @Dynamic(file = "security.properties", key = "security.jwks.uri")
                    public static final String JWKS_URI = "https://issuer/.well-known/jwks.json";
                }
                """;
        ProcessorRun run = compileWithProcessor(workDir,
                List.of(IMMUTABLE_SOURCE, DYNAMIC_SOURCE, fixture));

        assertThat(run.ok)
                .as("compilation must fail when a key is both @Immutable and @Dynamic")
                .isFalse();
        assertThat(run.diagnostics)
                .contains("cannot be both @Immutable and @Dynamic");
    }

    @Test
    @DisplayName("A non-final @Immutable field is a compile error")
    void nonFinalImmutableIsCompileError(@TempDir Path workDir) throws IOException {
        String fixture = """
                package fixtures;
                import eu.exeris.kernel.spi.config.Immutable;
                public final class Loose {
                    @Immutable(file = "security.properties", key = "security.issuer")
                    public static String ISSUER = "https://issuer";
                }
                """;
        ProcessorRun run = compileWithProcessor(workDir, List.of(IMMUTABLE_SOURCE, fixture));

        assertThat(run.ok)
                .as("compilation must fail when an @Immutable field is not static final")
                .isFalse();
        assertThat(run.diagnostics)
                .contains("must be declared 'static final'");
    }

    @Test
    @DisplayName("A static final @Immutable field compiles cleanly")
    void validImmutableCompiles(@TempDir Path workDir) throws IOException {
        String fixture = """
                package fixtures;
                import eu.exeris.kernel.spi.config.Immutable;
                public final class TrustAnchors {
                    @Immutable(file = "security.properties", key = "security.jwks.uri",
                               reason = "rotating at runtime would bypass issuer validation")
                    public static final String JWKS_URI = "https://issuer/.well-known/jwks.json";
                }
                """;
        ProcessorRun run = compileWithProcessor(workDir, List.of(IMMUTABLE_SOURCE, fixture));

        assertThat(run.ok)
                .as("a static final @Immutable field must compile without error")
                .isTrue();
        assertThat(run.diagnostics)
                .doesNotContain("ERROR");
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
                    List.of("-proc:full", "--release", "25"),
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
            String pkg = lineMatching(source, "package ");
            String pkgPath = pkg == null ? "" : pkg.substring("package ".length(), pkg.indexOf(';'))
                    .trim().replace(".", URI_SEPARATOR) + URI_SEPARATOR;
            String typeLine = Stream.of("public final class ", "public class ", "class ",
                            "interface ", "public interface ", "public @interface ", "@interface ",
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
            int idx = source.indexOf(prefix);
            if (idx < 0) {
                return null;
            }
            int end = source.indexOf('\n', idx);
            return end < 0 ? source.substring(idx) : source.substring(idx, end);
        }
    }
}
