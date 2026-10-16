/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.nativeimage;

import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReachabilityMetadata rendering and declaration-site scanner self-tests")
class ReachabilityMetadataRenderingTest {

    static class ValidFixture {
        static final VarHandle COUNTER_VH;
        static final VarHandle STRING_VAL_VH;

        static {
            try {
                COUNTER_VH = MethodHandles.lookup().findVarHandle(ValidFixture.class, "counter", int.class);
                STRING_VAL_VH = MethodHandles.lookup().findVarHandle(String.class, "value", byte[].class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        int counter;
    }

    static class NonLiteralFixture {
        static VarHandle dynamicVH(Class<?> target) {
            try {
                return MethodHandles.lookup().findVarHandle(target, "field", int.class);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static class PositiveFixture {
        static final String GRAAL_REF = "org.graalvm.nativeimage.some.Constant";

        @FunctionalInterface
        interface ThrowingFunction {
            Class<?> apply(String name) throws ClassNotFoundException;
        }

        static void staticForName() throws Exception {
            Class.forName("java.lang.String");
        }

        static void virtualGetDeclaredField(Class<?> clazz) throws Exception {
            clazz.getDeclaredField("value");
        }

        static void interfaceDowncallHandle(Linker linker, MemorySegment addr, FunctionDescriptor fd) {
            linker.downcallHandle(addr, fd);
        }

        static void interfaceStaticFunctionDescriptor() {
            FunctionDescriptor.ofVoid();
        }

        static void interfaceStaticFirstVariadicArg() {
            Linker.Option.firstVariadicArg(0);
        }

        static void interfaceStaticArenaOfShared() {
            Arena.ofShared();
        }

        static void lookupFindStatic(MethodHandles.Lookup lookup,
                                     Class<?> refc,
                                     String name,
                                     MethodType type) throws Exception {
            lookup.findStatic(refc, name, type);
        }

        static void lambdaSite() {
            Runnable r = () -> {
                try {
                    Class.forName("java.lang.Integer");
                } catch (ClassNotFoundException ignored) {
                }
            };
            r.run();
        }

        static void methodRefSite() {
            ThrowingFunction f = Class::forName;
            assertThat(f).isNotNull();
        }

        static void subclassClassLoaderSite(URLClassLoader ucl) {
            ucl.getResource("test.txt");
        }

        static void moduleGetResourceAsStream(Module module) throws Exception {
            module.getResourceAsStream("test.txt");
        }

        static void classLoaderLoadClass(ClassLoader cl) throws Exception {
            cl.loadClass("java.lang.String");
        }

        static class InnerPositive {
            static void innerSite() throws Exception {
                Class.forName("java.lang.Long");
            }
        }
    }

    static class NegativeFixture {
        static void safeMethods() {
            String name = String.class.getName();
            Class<?> decl = NegativeFixture.class.getDeclaringClass();
            Arena arena = Arena.ofConfined();
            arena.close();
            assertThat(name).isNotNull();
            assertThat(decl).isNull();
        }
    }

    static class OuterJfr {
        static class MiddleJfr {
            static class InnerEvent {
            }
        }
    }

    static class StaticFieldFixture {
        private static final String TEST_STR = "secret-token";
        private static final int TEST_INT = 12345;
    }

    @Test
    @DisplayName("Empty reachability metadata renders exactly '{}' with a trailing newline")
    void emptyModelRendersExactlyEmptyObjectWithNewline() {
        assertThat(ReachabilityMetadata.empty().render()).isEqualTo("{}\n");
    }

    @Test
    @DisplayName("Layout mapping handles primitives, AddressLayout, no-return, and rejects GroupLayout")
    void layoutMappingContract() {
        assertThat(ReachabilityMetadata.mapLayout(ValueLayout.JAVA_INT)).isEqualTo("jint");
        assertThat(ReachabilityMetadata.mapLayout(ValueLayout.JAVA_LONG)).isEqualTo("jlong");
        assertThat(ReachabilityMetadata.mapLayout(ValueLayout.ADDRESS)).isEqualTo("void*");
        assertThat(ReachabilityMetadata.mapLayout(ValueLayout.JAVA_BYTE)).isEqualTo("jbyte");
        assertThat(ReachabilityMetadata.mapLayout(ValueLayout.JAVA_SHORT)).isEqualTo("jshort");
        assertThat(ReachabilityMetadata.mapLayout(ValueLayout.JAVA_CHAR)).isEqualTo("jchar");
        assertThat(ReachabilityMetadata.mapLayout(ValueLayout.JAVA_FLOAT)).isEqualTo("jfloat");
        assertThat(ReachabilityMetadata.mapLayout(ValueLayout.JAVA_DOUBLE)).isEqualTo("jdouble");
        assertThat(ReachabilityMetadata.mapLayout(ValueLayout.JAVA_BOOLEAN)).isEqualTo("jboolean");

        FunctionDescriptor voidDesc = FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT);
        assertThat(ReachabilityMetadata.mapReturnLayout(voidDesc)).isEqualTo("void");
        assertThat(ReachabilityMetadata.mapArgumentLayouts(voidDesc)).containsExactly("jint");

        GroupLayout struct = MemoryLayout.structLayout(
                ValueLayout.JAVA_INT.withName("x"),
                ValueLayout.JAVA_INT.withName("y")
        );
        assertThatThrownBy(() -> ReachabilityMetadata.mapLayout(struct))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Group layout is not supported");
    }

    @Test
    @DisplayName("Deterministic canonical rendering orders keys, sections, and deduplicates downcalls")
    void deterministicOutput() {
        ReachabilityMetadata model = ReachabilityMetadata.builder()
                .registerResource("db/migration/V2.sql")
                .registerResource("db/migration/V1.sql")
                .registerField("zzz.Zebra", "stripeCount")
                .registerField("aaa.Apple", "color")
                .registerField("aaa.Apple", "variety")
                .registerMethod("aaa.Apple", "harvest", "java.lang.String", "int")
                .registerMethod("aaa.Apple", "consume")
                .registerAllDeclaredFields("aaa.Apple")
                .registerDowncall("jint", List.of("jlong", "jint"))
                .registerDowncall("jint", List.of("jlong", "jint")) // Duplicate to verify deduplication
                .registerDowncall("void", List.of())
                .registerDirectUpcall("eu.exeris.Callback", "onEvent", "jint", List.of("jlong"))
                .build();

        String json1 = model.render();
        String json2 = model.render();
        assertThat(json1).isEqualTo(json2);

        // Top-level order check: reflection -> resources -> foreign
        int reflectionIdx = json1.indexOf("\"reflection\"");
        int resourcesIdx = json1.indexOf("\"resources\"");
        int foreignIdx = json1.indexOf("\"foreign\"");
        assertThat(reflectionIdx).isGreaterThan(0);
        assertThat(resourcesIdx).isGreaterThan(reflectionIdx);
        assertThat(foreignIdx).isGreaterThan(resourcesIdx);

        // Reflection types order check: aaa.Apple -> zzz.Zebra
        int appleIdx = json1.indexOf("\"aaa.Apple\"");
        int zebraIdx = json1.indexOf("\"zzz.Zebra\"");
        assertThat(appleIdx).isGreaterThan(0);
        assertThat(zebraIdx).isGreaterThan(appleIdx);

        // Methods within aaa.Apple order check: consume() before harvest(...)
        int consumeIdx = json1.indexOf("\"consume\"");
        int harvestIdx = json1.indexOf("\"harvest\"");
        assertThat(consumeIdx).isGreaterThan(0);
        assertThat(harvestIdx).isGreaterThan(consumeIdx);

        // Fields within aaa.Apple order check: color before variety
        int colorIdx = json1.indexOf("\"color\"");
        int varietyIdx = json1.indexOf("\"variety\"");
        assertThat(colorIdx).isGreaterThan(0);
        assertThat(varietyIdx).isGreaterThan(colorIdx);

        // Resources order check: V1 before V2
        int v1Idx = json1.indexOf("V1.sql");
        int v2Idx = json1.indexOf("V2.sql");
        assertThat(v1Idx).isGreaterThan(0);
        assertThat(v2Idx).isGreaterThan(v1Idx);

        // Downcalls order check: jint before void
        int jintDowncall = json1.indexOf("\"returnType\": \"jint\"");
        int voidDowncall = json1.indexOf("\"returnType\": \"void\"");
        assertThat(jintDowncall).isGreaterThan(0);
        assertThat(voidDowncall).isGreaterThan(jintDowncall);

        // Trailing newline check
        assertThat(json1).endsWith("\n");
        assertThat(json1).doesNotContain("\r");
    }

    @Test
    @DisplayName("Byte-exact canonical rendering matches expected golden output")
    void byteExactGoldenRendering() {
        ReachabilityMetadata model = ReachabilityMetadata.builder()
                .registerType("com.example.PlainType")
                .registerField("aaa.Apple", "color")
                .registerField("aaa.Apple", "variety")
                .registerMethod("aaa.Apple", "consume")
                .registerMethod("aaa.Apple", "harvest", "java.lang.String", "int")
                .registerAllDeclaredFields("aaa.Apple")
                .registerResource("db/migration/V1.sql")
                .registerResource("db/migration/V2.sql")
                .registerDowncall("jint", List.of("jlong", "jint"), Map.of("captureCallState", true))
                .registerDowncall("void", List.of())
                .registerDirectUpcall("eu.exeris.Callback", "onEvent", "jint", List.of("jlong"))
                .registerDirectUpcall("eu.exeris.EmptyCallback", "noop", "void", List.of())
                .build();

        String expected = """
{
  "reflection": [
    {
      "type": "aaa.Apple",
      "methods": [
        {
          "name": "consume",
          "parameterTypes": []
        },
        {
          "name": "harvest",
          "parameterTypes": [
            "java.lang.String",
            "int"
          ]
        }
      ],
      "fields": [
        {
          "name": "color"
        },
        {
          "name": "variety"
        }
      ],
      "allDeclaredFields": true
    },
    {
      "type": "com.example.PlainType"
    }
  ],
  "resources": [
    {
      "glob": "db/migration/V1.sql"
    },
    {
      "glob": "db/migration/V2.sql"
    }
  ],
  "foreign": {
    "downcalls": [
      {
        "returnType": "jint",
        "parameterTypes": [
          "jlong",
          "jint"
        ],
        "options": {
          "captureCallState": true
        }
      },
      {
        "returnType": "void",
        "parameterTypes": []
      }
    ],
    "directUpcalls": [
      {
        "class": "eu.exeris.Callback",
        "method": "onEvent",
        "returnType": "jint",
        "parameterTypes": [
          "jlong"
        ]
      },
      {
        "class": "eu.exeris.EmptyCallback",
        "method": "noop",
        "returnType": "void",
        "parameterTypes": []
      }
    ]
  }
}
""";

        assertThat(model.render()).isEqualTo(expected);
    }

    @Test
    @DisplayName("DowncallRef constructor rejects unsupported option value types")
    void downcallRefRejectsUnsupportedOptionTypes() {
        assertThatThrownBy(() -> ReachabilityMetadata.builder()
                .registerDowncall("jint", List.of("jlong"), Map.of("bad", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported option value type");
    }

    @Test
    @DisplayName("Downcall with nested map options renders as nested JSON object")
    void downcallNestedMapOptionsRendering() {
        ReachabilityMetadata downcallModel = ReachabilityMetadata.builder()
                .registerDowncall("jint", List.of("jlong"), Map.of(
                        "captureCallState", true,
                        "nested", Map.of("enabled", true, "timeout", 100)
                ))
                .build();
        String json = downcallModel.render();
        String expected = """
{
  "foreign": {
    "downcalls": [
      {
        "returnType": "jint",
        "parameterTypes": [
          "jlong"
        ],
        "options": {
          "captureCallState": true,
          "nested": {
            "enabled": true,
            "timeout": 100
          }
        }
      }
    ]
  }
}
""";
        assertThat(json).isEqualTo(expected);
    }

    @Test
    @DisplayName("Single top-level sections render valid JSON without misplaced commas")
    void singleSectionRendering() {
        ReachabilityMetadata reflOnly = ReachabilityMetadata.builder()
                .registerType("com.example.OnlyType")
                .build();
        assertThat(reflOnly.render()).isEqualTo("""
{
  "reflection": [
    {
      "type": "com.example.OnlyType"
    }
  ]
}
""");

        ReachabilityMetadata resOnly = ReachabilityMetadata.builder()
                .registerResource("db/migration/V1.sql")
                .build();
        assertThat(resOnly.render()).isEqualTo("""
{
  "resources": [
    {
      "glob": "db/migration/V1.sql"
    }
  ]
}
""");

        ReachabilityMetadata downOnly = ReachabilityMetadata.builder()
                .registerDowncall("void", List.of())
                .build();
        assertThat(downOnly.render()).isEqualTo("""
{
  "foreign": {
    "downcalls": [
      {
        "returnType": "void",
        "parameterTypes": []
      }
    ]
  }
}
""");

        ReachabilityMetadata upOnly = ReachabilityMetadata.builder()
                .registerDirectUpcall("eu.exeris.Callback", "onEvent", "void", List.of())
                .build();
        assertThat(upOnly.render()).isEqualTo("""
{
  "foreign": {
    "directUpcalls": [
      {
        "class": "eu.exeris.Callback",
        "method": "onEvent",
        "returnType": "void",
        "parameterTypes": []
      }
    ]
  }
}
""");

        ReachabilityMetadata foreignBoth = ReachabilityMetadata.builder()
                .registerDowncall("void", List.of())
                .registerDirectUpcall("eu.exeris.Callback", "onEvent", "void", List.of())
                .build();
        assertThat(foreignBoth.render()).isEqualTo("""
{
  "foreign": {
    "downcalls": [
      {
        "returnType": "void",
        "parameterTypes": []
      }
    ],
    "directUpcalls": [
      {
        "class": "eu.exeris.Callback",
        "method": "onEvent",
        "returnType": "void",
        "parameterTypes": []
      }
    ]
  }
}
""");
    }

    @Test
    @DisplayName("registerType renders a type-only reflection entry without methods or fields")
    void registerTypeRendersTypeOnlyEntry() {
        ReachabilityMetadata model = ReachabilityMetadata.builder()
                .registerType("eu.exeris.kernel.jfr.EventCatalog")
                .build();

        String expected = """
{
  "reflection": [
    {
      "type": "eu.exeris.kernel.jfr.EventCatalog"
    }
  ]
}
""";
        assertThat(model.render()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Control characters below U+0020 and standard escapes are properly escaped in JSON")
    void controlCharactersEscaped() {
        StringBuilder sb = new StringBuilder();
        JsonEscaper.escape(sb, "hello\u0000world\u001f\n\r\t\b\f\"\\");
        assertThat(sb.toString()).isEqualTo("hello\\u0000world\\u001f\\n\\r\\t\\b\\f\\\"\\\\");
    }

    @Test
    @DisplayName("Literal extraction over small fixture class extracts receiver, field name, and field type")
    void literalExtractionOverSmallFixtureClass() throws Exception {
        URL resource = getClass().getResource("ReachabilityMetadataRenderingTest$ValidFixture.class");
        assertThat(resource).isNotNull();
        Path classFile = Path.of(resource.toURI());

        List<DeclarationSiteScanner.VarHandleTarget> targets =
                DeclarationSiteScanner.scanVarHandlesInClass(classFile);

        assertThat(targets).hasSize(2);

        DeclarationSiteScanner.VarHandleTarget counterTarget = targets.get(0);
        assertThat(counterTarget.receiverClass()).isEqualTo(ValidFixture.class.getName());
        assertThat(counterTarget.fieldName()).isEqualTo("counter");
        assertThat(counterTarget.fieldType()).isEqualTo("int");

        DeclarationSiteScanner.VarHandleTarget stringTarget = targets.get(1);
        assertThat(stringTarget.receiverClass()).isEqualTo("java.lang.String");
        assertThat(stringTarget.fieldName()).isEqualTo("value");
        assertThat(stringTarget.fieldType()).isEqualTo("[B");
    }

    @Test
    @DisplayName("A non-literal findVarHandle fixture fails loudly during bytecode scan")
    void nonLiteralFindVarHandleFixtureFails() throws Exception {
        URL resource = getClass().getResource("ReachabilityMetadataRenderingTest$NonLiteralFixture.class");
        assertThat(resource).isNotNull();
        Path classFile = Path.of(resource.toURI());

        assertThatThrownBy(() -> DeclarationSiteScanner.scanClass(classFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Non-literal findVarHandle");
    }

    @Test
    @DisplayName("Allowlist matching detects unlisted declaration sites and stale allowlist entries")
    void allowlistMatching() {
        DeclarationSite site = new DeclarationSite("foo.Bar", "init", "Class.forName");
        List<DeclarationSite> sites = List.of(site);

        // Missing site in allowlist fails
        assertThatThrownBy(() -> DeclarationSiteScanner.assertAllowlistMatches(sites, List.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("new declaration site foo.Bar.init → Class.forName: add metadata coverage or a disposition");

        // Stale entry in allowlist fails
        DeclarationSiteScanner.AllowlistEntry stale =
                DeclarationSiteScanner.allow("other.Baz", "Class.forName", "never used");
        DeclarationSiteScanner.AllowlistEntry active =
                DeclarationSiteScanner.allow("foo.Bar", "Class.forName", "used in init");
        assertThatThrownBy(() -> DeclarationSiteScanner.assertAllowlistMatches(sites, List.of(active, stale)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("stale allowlist entry for other.Baz → Class.forName: no matching declaration sites found");

        // Matching allowlist passes
        DeclarationSiteScanner.assertAllowlistMatches(sites, List.of(active));
    }

    @Test
    @DisplayName("Allowlist collects all problems in one AssertionError, rejecting duplicates and blank dispositions")
    void allowlistCollectsAllProblems() {
        DeclarationSite site1 = new DeclarationSite("pkg.ClassA", "m1", "Class.forName");
        DeclarationSite site2 = new DeclarationSite("pkg.ClassB", "m2", "Linker.downcallHandle");
        List<DeclarationSite> sites = List.of(site1, site2);

        DeclarationSiteScanner.AllowlistEntry dup1 =
                DeclarationSiteScanner.allow("pkg.ClassA", "m1", "Class.forName", "first copy");
        DeclarationSiteScanner.AllowlistEntry dup2 =
                DeclarationSiteScanner.allow("pkg.ClassA", "m1", "Class.forName", "duplicate copy");
        DeclarationSiteScanner.AllowlistEntry blank =
                DeclarationSiteScanner.allow("pkg.ClassC", "m3", "Class.forName", "   ");
        DeclarationSiteScanner.AllowlistEntry stale =
                DeclarationSiteScanner.allow("pkg.ClassD", "Class.forName", "stale disposition");

        assertThatThrownBy(() -> DeclarationSiteScanner.assertAllowlistMatches(sites, List.of(dup1, dup2, blank, stale)))
                .isInstanceOf(AssertionError.class)
                .satisfies(error -> {
                    String msg = error.getMessage();
                    assertThat(msg).contains("duplicate allowlist entry for pkg.ClassA.m1 → Class.forName");
                    assertThat(msg).contains("blank disposition for allowlist entry: pkg.ClassC.m3 → Class.forName");
                    assertThat(msg).contains("new declaration site pkg.ClassB.m2 → Linker.downcallHandle");
                    assertThat(msg).contains("stale allowlist entry for pkg.ClassD → Class.forName");
                });
    }

    @Test
    @DisplayName("Allowlist rejects wildcard and method-specific overlap as a configuration error")
    void allowlistRejectsWildcardAndMethodSpecificOverlap() {
        DeclarationSite site = new DeclarationSite("pkg.ClassA", "m1", "Class.forName");
        DeclarationSiteScanner.AllowlistEntry wildcard =
                DeclarationSiteScanner.allow("pkg.ClassA", "Class.forName", "wildcard disposition");
        DeclarationSiteScanner.AllowlistEntry specific =
                DeclarationSiteScanner.allow("pkg.ClassA", "m1", "Class.forName", "specific disposition");

        assertThatThrownBy(() -> DeclarationSiteScanner.assertAllowlistMatches(List.of(site), List.of(wildcard, specific)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("overlapping wildcard and method-specific allowlist entry for pkg.ClassA → Class.forName");
    }


    @Test
    @DisplayName("Positive fixture detects exact sorted declaration sites across all API families and invoke shapes")
    void positiveFixtureExactSortedDeclarationSites() throws Exception {
        Path tempDir = Files.createTempDirectory("positive-fixture-test");
        try {
            String pfName = PositiveFixture.class.getSimpleName();
            String ipName = PositiveFixture.class.getSimpleName() + "$"
                    + PositiveFixture.InnerPositive.class.getSimpleName();
            String tfName = PositiveFixture.class.getSimpleName() + "$"
                    + PositiveFixture.ThrowingFunction.class.getSimpleName();

            URL pfUrl = getClass().getResource("ReachabilityMetadataRenderingTest$" + pfName + ".class");
            assertThat(pfUrl).isNotNull();
            byte[] originalBytes;
            try (InputStream in = pfUrl.openStream()) {
                originalBytes = in.readAllBytes();
            }

            byte[] transformed = ClassFile.of().transformClass(
                    ClassFile.of().parse(originalBytes),
                    ClassTransform.endHandler(clb -> {
                        clb.withMethodBody("ldcMethodHandle", MethodTypeDesc.of(ConstantDescs.CD_void), 1, cob -> {
                            cob.ldc(MethodHandleDesc.ofMethod(
                                    DirectMethodHandleDesc.Kind.STATIC,
                                    ClassDesc.of("java.lang.Class"),
                                    "forName",
                                    MethodTypeDesc.of(ClassDesc.of("java.lang.Class"), ConstantDescs.CD_String)));
                            cob.return_();
                        });
                    }));

            Files.write(tempDir.resolve("ReachabilityMetadataRenderingTest$" + pfName + ".class"), transformed);

            URL ipUrl = getClass().getResource("ReachabilityMetadataRenderingTest$" + ipName + ".class");
            assertThat(ipUrl).isNotNull();
            try (InputStream in = ipUrl.openStream()) {
                Files.write(tempDir.resolve("ReachabilityMetadataRenderingTest$" + ipName + ".class"),
                        in.readAllBytes());
            }

            URL tfUrl = getClass().getResource("ReachabilityMetadataRenderingTest$" + tfName + ".class");
            if (tfUrl != null) {
                try (InputStream in = tfUrl.openStream()) {
                    Files.write(tempDir.resolve("ReachabilityMetadataRenderingTest$" + tfName + ".class"),
                            in.readAllBytes());
                }
            }

            List<DeclarationSite> sites = DeclarationSiteScanner.scan(tempDir);

            String pf = PositiveFixture.class.getName();
            String ip = PositiveFixture.InnerPositive.class.getName();

            List<DeclarationSite> expected = List.of(
                    new DeclarationSite(pf, "<constant-pool>", "org/graalvm/"),
                    new DeclarationSite(pf, "classLoaderLoadClass", "ClassLoader.loadClass"),
                    new DeclarationSite(pf, "interfaceDowncallHandle", "Linker.downcallHandle"),
                    new DeclarationSite(pf, "interfaceStaticArenaOfShared", "Arena.ofShared"),
                    new DeclarationSite(pf, "interfaceStaticFirstVariadicArg", "Linker$Option.firstVariadicArg"),
                    new DeclarationSite(pf, "interfaceStaticFunctionDescriptor", "FunctionDescriptor.ofVoid"),
                    new DeclarationSite(pf, "lambda$lambdaSite$0", "Class.forName"),
                    new DeclarationSite(pf, "ldcMethodHandle", "Class.forName"),
                    new DeclarationSite(pf, "lookupFindStatic", "MethodHandles$Lookup.findStatic"),
                    new DeclarationSite(pf, "methodRefSite", "Class.forName"),
                    new DeclarationSite(pf, "moduleGetResourceAsStream", "Module.getResourceAsStream"),
                    new DeclarationSite(pf, "staticForName", "Class.forName"),
                    new DeclarationSite(pf, "subclassClassLoaderSite", "ClassLoader.getResource"),
                    new DeclarationSite(pf, "virtualGetDeclaredField", "Class.getDeclaredField"),
                    new DeclarationSite(ip, "innerSite", "Class.forName")
            );

            assertThat(sites).containsExactlyElementsOf(expected);
        } finally {
            try (var s = Files.walk(tempDir)) {
                s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("Negative fixture yields zero declaration sites")
    void negativeFixtureYieldsZeroSites() throws Exception {
        Path tempDir = Files.createTempDirectory("negative-fixture-test");
        try {
            String nfName = NegativeFixture.class.getSimpleName();
            URL nfUrl = getClass().getResource("ReachabilityMetadataRenderingTest$" + nfName + ".class");
            assertThat(nfUrl).isNotNull();
            try (InputStream in = nfUrl.openStream()) {
                Files.write(tempDir.resolve("ReachabilityMetadataRenderingTest$" + nfName + ".class"),
                        in.readAllBytes());
            }

            List<DeclarationSite> sites = DeclarationSiteScanner.scan(tempDir);
            assertThat(sites).isEmpty();
        } finally {
            try (var s = Files.walk(tempDir)) {
                s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("Non-directory classes location throws IllegalStateException")
    void nonDirectoryLocationThrows() throws Exception {
        Path tempFile = Files.createTempFile("test-non-dir", ".class");
        try {
            assertThatThrownBy(() -> DeclarationSiteScanner.scan(tempFile))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a directory");
            assertThatThrownBy(() -> DeclarationSiteScanner.scanVarHandles(tempFile))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a directory");
            assertThatThrownBy(() -> DeclarationSiteScanner.scanAll(tempFile))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a directory");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("Scanning directory with zero class files throws IllegalStateException")
    void zeroClassScanThrows() throws Exception {
        Path emptyDir = Files.createTempDirectory("empty-classes-dir");
        try {
            assertThatThrownBy(() -> DeclarationSiteScanner.scan(emptyDir))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No .class files found");
            assertThatThrownBy(() -> DeclarationSiteScanner.scanAll(emptyDir))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No .class files found");
        } finally {
            Files.deleteIfExists(emptyDir);
        }
    }

    @Test
    @DisplayName("ReachabilityMetadataGuard writes expected file on mismatch, prints cp hint, and leaves src untouched")
    void guardMismatchWritesExpectedAndLeavesSrcUntouched() throws Exception {
        ReachabilityMetadata model = ReachabilityMetadata.builder()
                .registerType("dummy.Type")
                .build();
        String artifactId = "guard-mismatch-test";
        Path classesDir = DeclarationSiteScanner.classesDirectory(ReachabilityMetadataRenderingTest.class);
        Path targetDir = classesDir.getParent();
        Path expectedFile = targetDir.resolve("native-image-expected")
                .resolve(artifactId)
                .resolve("reachability-metadata.json");
        Path moduleRoot = targetDir.getParent();
        Path sourceFile = moduleRoot.resolve("src/main/resources/META-INF/native-image/eu.exeris")
                .resolve(artifactId)
                .resolve("reachability-metadata.json");

        Files.deleteIfExists(expectedFile);

        assertThatThrownBy(() -> ReachabilityMetadataGuard.assertCommittedMatches(
                ReachabilityMetadataRenderingTest.class, artifactId, model))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("cp " + expectedFile.toAbsolutePath() + " " + sourceFile.toAbsolutePath());

        assertThat(Files.exists(expectedFile)).isTrue();
        assertThat(Files.readString(expectedFile)).isEqualTo(model.render());
        assertThat(Files.exists(sourceFile)).isFalse();

        // Clean up generated expected file
        Files.deleteIfExists(expectedFile);
        Files.deleteIfExists(expectedFile.getParent());
    }

    @Test
    @DisplayName("jfrClosure computes enclosing classes for nested event types and throws on unknown classes")
    void jfrClosureCalculatesEnclosingClasses() {
        ClassLoader cl = getClass().getClassLoader();
        List<String> closure = ReachabilityMetadataGuard.jfrClosure(
                List.of(OuterJfr.MiddleJfr.InnerEvent.class.getName()), cl);

        assertThat(closure).containsExactly(
                ReachabilityMetadataRenderingTest.class.getName(),
                OuterJfr.class.getName(),
                OuterJfr.MiddleJfr.class.getName(),
                OuterJfr.MiddleJfr.InnerEvent.class.getName()
        );

        assertThatThrownBy(() -> ReachabilityMetadataGuard.jfrClosure(List.of("non.existent.EventClass"), cl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to resolve JFR event class: non.existent.EventClass");
    }

    @Test
    @DisplayName("readStaticField accesses static constants and casts to expected type, throwing on missing fields")
    void readStaticFieldContract() {
        assertThat(ReachabilityMetadataGuard.readStaticField(StaticFieldFixture.class, "TEST_STR", String.class))
                .isEqualTo("secret-token");
        assertThat(ReachabilityMetadataGuard.readStaticField(StaticFieldFixture.class, "TEST_INT", Integer.class))
                .isEqualTo(12345);
        assertThat(ReachabilityMetadataGuard.readStaticField(StaticFieldFixture.class, "TEST_STR"))
                .isEqualTo("secret-token");

        assertThatThrownBy(() -> ReachabilityMetadataGuard.readStaticField(StaticFieldFixture.class, "NON_EXISTENT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read static field");
    }

    @Test
    @DisplayName("TCK scanner over exeris-kernel-spi classes yields zero declaration sites (drift check)")
    void spiClassesContainZeroSitesViaTckScanner() throws Exception {
        Path classesDir = resolveSpiClassesDir();
        List<DeclarationSite> sites = DeclarationSiteScanner.scan(classesDir);
        assertThat(sites)
                .as("TCK scanner must find zero declaration sites in exeris-kernel-spi")
                .isEmpty();
    }

    private static Path resolveSpiClassesDir() {
        CodeSource source = MemoryAllocator.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("Could not determine CodeSource location for MemoryAllocator");
        }
        try {
            Path loc = Path.of(source.getLocation().toURI());
            if (Files.isDirectory(loc)) {
                return loc;
            }
            Path candidate = loc.getParent().resolve("classes");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            throw new IllegalStateException("CodeSource location is not a classes directory: " + loc);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid CodeSource URI for MemoryAllocator: " + source.getLocation(), e);
        }
    }

    @Test
    @DisplayName("scanAll returns both declaration sites and VarHandle targets in a single pass")
    void scanAllSinglePass() throws Exception {
        URL resource = getClass().getResource("ReachabilityMetadataRenderingTest$ValidFixture.class");
        assertThat(resource).isNotNull();
        Path classFile = Path.of(resource.toURI());

        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanClassAll(classFile);
        assertThat(result.sites()).hasSize(2);
        assertThat(result.varHandles()).hasSize(2);
    }
}
