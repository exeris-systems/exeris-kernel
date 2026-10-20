/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that {@link OpenSslDowncall} holds no dead rows and no double bindings: reading the compiled
 * {@link CoreOpenSslLoader}, every constant is referenced from exactly one site, so the table the
 * ABI symbol-resolution suite derives its manifest from is the set of symbols the loader binds.
 *
 * <p>{@code OPENSSL_version_major} is the one deliberate exception, referenced exactly three times: the
 * version gate reads it once from the merged lookup and the cross-major check reads it again from each
 * library.
 */
@DisplayName("L0: CoreOpenSslLoader binds every OpenSslDowncall exactly once")
class CoreOpenSslLoaderBindingGuardTest {

    private static final String DOWNCALL_OWNER = "eu/exeris/kernel/core/crypto/openssl/OpenSslDowncall";
    private static final Map<String, Integer> PROBED_PER_LIBRARY = Map.of("OPENSSL_VERSION_MAJOR", 3);

    @Test
    @DisplayName("each constant has one loader reference; the per-library probe has exactly its three")
    void everyConstantIsBoundOnce() throws IOException {
        Map<String, Integer> references = loaderReferences();

        List<String> unbound = Arrays.stream(OpenSslDowncall.values()).map(Enum::name)
                .filter(name -> !references.containsKey(name)).toList();
        assertThat(unbound).as("OpenSslDowncall rows the loader never binds").isEmpty();

        List<String> repeated = references.entrySet().stream()
                .filter(e -> e.getValue() != PROBED_PER_LIBRARY.getOrDefault(e.getKey(), 1))
                .map(e -> e.getKey() + " x" + e.getValue()).toList();
        assertThat(repeated).as("OpenSslDowncall rows the loader binds more than once").isEmpty();
    }

    private static Map<String, Integer> loaderReferences() throws IOException {
        byte[] bytes;
        try (InputStream in = CoreOpenSslLoader.class.getResourceAsStream("CoreOpenSslLoader.class")) {
            bytes = in.readAllBytes();
        }
        ClassModel model = ClassFile.of().parse(bytes);
        Map<String, Integer> counts = new TreeMap<>();
        for (MethodModel method : model.methods()) {
            for (CodeElement element : method.code().map(code -> code.elementList()).orElse(List.of())) {
                if (element instanceof FieldInstruction field
                        && field.opcode() == Opcode.GETSTATIC
                        && DOWNCALL_OWNER.equals(field.owner().asInternalName())
                        && !"$VALUES".equals(field.name().stringValue())) {
                    counts.merge(field.name().stringValue(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }
}
