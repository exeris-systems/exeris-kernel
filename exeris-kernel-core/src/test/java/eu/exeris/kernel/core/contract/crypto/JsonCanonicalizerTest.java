/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.crypto;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JsonCanonicalizer (RFC 8785 JCS)")
class JsonCanonicalizerTest {

    @Test
    @DisplayName("Sorts object keys lexicographically by UTF-16 code units")
    void sortsKeysLexicographically() {
        String json = "{\"z\": 1, \"a\": 2, \"m\": 3}";
        byte[] canonical = JsonCanonicalizer.canonicalize(json);
        assertThat(new String(canonical, StandardCharsets.UTF_8)).isEqualTo("{\"a\":2,\"m\":3,\"z\":1}");
    }

    @Test
    @DisplayName("Eliminates whitespace outside of string literals")
    void eliminatesWhitespace() {
        String json = "{\n  \"message\" :   \"hello world\" ,\n  \"active\" : true \n}";
        byte[] canonical = JsonCanonicalizer.canonicalize(json);
        assertThat(new String(canonical, StandardCharsets.UTF_8)).isEqualTo("{\"active\":true,\"message\":\"hello world\"}");
    }

    @Test
    @DisplayName("Preserves whitespace and special chars inside string literals")
    void preservesWhitespaceInStrings() {
        String json = "{\"text\":\"line 1\\nline 2\\tindent  spaces\"}";
        byte[] canonical = JsonCanonicalizer.canonicalize(json);
        assertThat(new String(canonical, StandardCharsets.UTF_8)).isEqualTo("{\"text\":\"line 1\\nline 2\\tindent  spaces\"}");
    }

    @Test
    @DisplayName("Omit detached signature property from root object")
    void omitsDetachedSignature() {
        String json = "{\"data\":\"payload\",\"signature\":{\"value\":\"abc\"},\"version\":\"1.0\"}";
        byte[] canonical = JsonCanonicalizer.canonicalizeDetached(json, "signature");
        assertThat(new String(canonical, StandardCharsets.UTF_8)).isEqualTo("{\"data\":\"payload\",\"version\":\"1.0\"}");
    }

    @Test
    @DisplayName("Handles nested objects and arrays deterministically")
    void handlesNestedObjectsAndArrays() {
        String json = "{\"items\": [3, 1, {\"b\": 2, \"a\": 1}], \"info\": {\"nested\": true}}";
        byte[] canonical = JsonCanonicalizer.canonicalize(json);
        assertThat(new String(canonical, StandardCharsets.UTF_8))
                .isEqualTo("{\"info\":{\"nested\":true},\"items\":[3,1,{\"a\":1,\"b\":2}]}");
    }

    @Test
    @DisplayName("Correctly serializes booleans, numbers, and nulls")
    void serializesPrimitives() {
        String json = "{\"bool\":false,\"nullVal\":null,\"number\":42,\"zero\":0}";
        byte[] canonical = JsonCanonicalizer.canonicalize(json);
        assertThat(new String(canonical, StandardCharsets.UTF_8))
                .isEqualTo("{\"bool\":false,\"nullVal\":null,\"number\":42,\"zero\":0}");
    }

    @Test
    @DisplayName("Parses JSON into nested Java collections")
    void parsesNestedCollections() {
        String json = "{\"name\":\"Exeris\",\"counts\":[1,2,3],\"meta\":{\"tier\":\"enterprise\"}}";
        Map<String, Object> map = JsonCanonicalizer.parseJsonObject(json);

        assertThat(map).containsEntry("name", "Exeris");
        assertThat(map.get("counts")).isEqualTo(List.of(1.0, 2.0, 3.0));
        assertThat(map.get("meta")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("Throws ContractBreachException on malformed JSON")
    void throwsOnMalformedJson() {
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize("{unquotedKey: 123}"))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0001));

        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize("{\"unclosed\": \"string}"))
                .isInstanceOf(ContractBreachException.class);

        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(""))
                .isInstanceOf(ContractBreachException.class);
    }

    @Test
    @DisplayName("Throws ContractBreachException on duplicate object keys per RFC 8785")
    void throwsOnDuplicateKeys() {
        assertThatThrownBy(() -> JsonCanonicalizer.parse("{\"a\": 1, \"a\": 2}"))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0001));
    }

    @Test
    @DisplayName("Throws ContractBreachException on adversarial deep nesting exceeding limit")
    void throwsOnExcessiveNestingDepth() {
        String deep = "[".repeat(600) + "]".repeat(600);
        assertThatThrownBy(() -> JsonCanonicalizer.parse(deep))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0001));
    }

    @Test
    @DisplayName("Throws ContractBreachException on invalid number syntax (trailing dot, leading zeros)")
    void throwsOnInvalidNumberSyntax() {
        assertThatThrownBy(() -> JsonCanonicalizer.parse("{\"val\": 1.}"))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0001));

        assertThatThrownBy(() -> JsonCanonicalizer.parse("{\"val\": 0123}"))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0001));

        assertThatThrownBy(() -> JsonCanonicalizer.parse("{\"val\": -}"))
                .isInstanceOf(ContractBreachException.class);
    }

    @Test
    @DisplayName("Throws ContractBreachException on unescaped control character in string")
    void throwsOnUnescapedControlCharacter() {
        assertThatThrownBy(() -> JsonCanonicalizer.parse("{\"val\": \"line1\nline2\"}"))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0001));
    }
    // ── RFC 8785 conformance ────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "0000000000000000, 0",
            "8000000000000000, 0",
            "0000000000000001, 5e-324",
            "8000000000000001, -5e-324",
            "7fefffffffffffff, 1.7976931348623157e+308",
            "ffefffffffffffff, -1.7976931348623157e+308",
            "4340000000000000, 9007199254740992",
            "c340000000000000, -9007199254740992",
            "4430000000000000, 295147905179352830000",
            "44b52d02c7e14af5, 9.999999999999997e+22",
            "44b52d02c7e14af6, 1e+23",
            "44b52d02c7e14af7, 1.0000000000000001e+23",
            "444b1ae4d6e2ef4e, 999999999999999700000",
            "444b1ae4d6e2ef4f, 999999999999999900000",
            "444b1ae4d6e2ef50, 1e+21",
            "3eb0c6f7a0b5ed8c, 9.999999999999997e-7",
            "3eb0c6f7a0b5ed8d, 0.000001",
            "41b3de4355555553, 333333333.3333332",
            "41b3de4355555554, 333333333.33333325",
            "41b3de4355555555, 333333333.3333333",
            "41b3de4355555556, 333333333.3333334",
            "41b3de4355555557, 333333333.33333343",
            "becbf647612f3696, -0.0000033333333333333333",
            "43143ff3c1cb0959, 1424953923781206.2"
    })
    @DisplayName("RFC 8785 Appendix B: IEEE-754 values serialize as ECMAScript numbers")
    void serializesAppendixBNumbers(String ieeeHex, String expected) {
        double value = Double.longBitsToDouble(Long.parseUnsignedLong(ieeeHex, 16));

        assertThat(JsonCanonicalizer.formatNumber(value)).isEqualTo(expected);
    }

    @Test
    @DisplayName("RFC 8785 §3.2.2: the specification's example canonicalizes byte for byte")
    void canonicalizesSpecificationExample() {
        String input = "{\n  \"numbers\": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001],\n"
                + "  \"string\": \"\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\\"\\/\",\n"
                + "  \"literals\": [null, true, false]\n}";
        String expected = "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                + "\"string\":\"\u20ac$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}";

        assertThat(new String(JsonCanonicalizer.canonicalize(input), StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    @Test
    @DisplayName("RFC 8785 §3.2.3: keys sort by UTF-16 code unit, a surrogate pair before U+FB33")
    void sortsKeysByUtf16CodeUnits() {
        String input = "{\"\\u20ac\":\"Euro Sign\",\"\\r\":\"Carriage Return\",\"\\ufb33\":\"Hebrew Letter Dalet With Dagesh\","
                + "\"1\":\"One\",\"\\ud83d\\ude00\":\"Emoji: Grinning Face\",\"\\u0080\":\"Control\","
                + "\"\\u00f6\":\"Latin Small Letter O With Diaeresis\"}";

        String canonical = new String(JsonCanonicalizer.canonicalize(input), StandardCharsets.UTF_8);

        assertThat(canonical).isEqualTo("{\"\\r\":\"Carriage Return\",\"1\":\"One\",\"\u0080\":\"Control\","
                + "\"\u00f6\":\"Latin Small Letter O With Diaeresis\",\"\u20ac\":\"Euro Sign\","
                + "\"\ud83d\ude00\":\"Emoji: Grinning Face\",\"\ufb33\":\"Hebrew Letter Dalet With Dagesh\"}");
    }

    @Test
    @DisplayName("An integer above 2^53 canonicalizes through IEEE-754, as RFC 8785 requires")
    void integerAboveSafeRangeRoundsThroughDouble() {
        assertThat(new String(JsonCanonicalizer.canonicalize("[9007199254740993]"), StandardCharsets.UTF_8))
                .isEqualTo("[9007199254740992]");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "[\"\\ud800\"]",
            "[\"\\udc00x\"]",
            "[\"a\\ud83d\"]",
            "[1\u0663]",
            "[\"\\u+041\"]",
            "[\"\\u-041\"]",
            "[1E400]",
            "[-1e999]"
    })
    @DisplayName("Rejects input with no single canonical form: lone surrogates, non-ASCII digits, signed \\u, overflow")
    void rejectsAmbiguousInput(String json) {
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(json))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0001));
    }

    @Test
    @DisplayName("Rejects malformed UTF-8 instead of substituting U+FFFD")
    void rejectsMalformedUtf8() {
        byte[] malformed = {'[', '"', (byte) 0xC3, (byte) 0x28, '"', ']'};

        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(malformed))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0001));
    }

    @Test
    @DisplayName("A surrogate pair, escaped or raw, is accepted and emitted as one UTF-8 code point")
    void acceptsSurrogatePair() {
        byte[] canonical = JsonCanonicalizer.canonicalize("[\"\\ud83d\\ude00\"]");

        assertThat(canonical).isEqualTo("[\"\ud83d\ude00\"]".getBytes(StandardCharsets.UTF_8));
    }
}
