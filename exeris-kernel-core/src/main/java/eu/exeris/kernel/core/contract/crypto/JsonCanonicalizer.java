/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.crypto;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Zero-dependency, deterministic JSON canonicalizer implementing RFC 8785 (JSON Canonicalization Scheme - JCS).
 *
 * <h2>RFC 8785 Rules Applied</h2>
 * <ul>
 *   <li><b>Lexicographical key sorting:</b> Object member keys are sorted strictly by UTF-16 code units.</li>
 *   <li><b>Whitespace elimination:</b> Whitespace outside string literals is completely stripped.</li>
 *   <li><b>Deterministic string serialization:</b> Quotation marks and reverse solidus escaped;
 *       control characters U+0000..U+001F escaped using {@code \b}, {@code \t}, {@code \n}, {@code \f},
 *       {@code \r}, or lowercase Unicode escape hex. All other characters serialized verbatim in UTF-8.</li>
 *   <li><b>Deterministic number serialization:</b> every number is an IEEE-754 double, serialized as
 *       ECMAScript {@code Number.prototype.toString} does (RFC 8785 §3.2.2.3): {@code 1e+21},
 *       {@code 1e-7}, {@code 0.000001}, and {@code -0} as {@code 0}.</li>
 *   <li><b>Strict input:</b> malformed UTF-8, unpaired surrogates, non-ASCII digits, duplicate keys and
 *       non-finite numbers are rejected, so two different documents never share canonical bytes.</li>
 *   <li><b>Detached signature support:</b> Allows omitting a specific property
 *       (e.g. {@code "signature"}) from root.</li>
 * </ul>
 *
 * @since 0.13
 */
@SuppressWarnings({
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.AvoidLiteralsInIfCondition",
        "PMD.ShortVariable",
        "PMD.TooManyMethods"
})
public final class JsonCanonicalizer {

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    private static final double MAX_EXACT_INTEGER = 0x1p53;

    private JsonCanonicalizer() {}

    /**
     * Canonicalizes the provided JSON string according to RFC 8785.
     *
     * @param json source JSON string
     * @return canonical UTF-8 encoded bytes
     */
    public static byte[] canonicalize(String json) {
        return canonicalizeDetached(json, null);
    }

    /**
     * Canonicalizes the provided JSON bytes according to RFC 8785.
     *
     * @param jsonBytes source JSON UTF-8 bytes
     * @return canonical UTF-8 encoded bytes
     */
    public static byte[] canonicalize(byte[] jsonBytes) {
        Objects.requireNonNull(jsonBytes, "jsonBytes must not be null");
        return canonicalizeDetached(decodeUtf8(jsonBytes), null);
    }

    /**
     * Decodes strict UTF-8: a malformed or truncated sequence is rejected, never replaced.
     *
     * @param bytes UTF-8 bytes
     * @return decoded text
     * @throws ContractBreachException with EX-LIC-0001 on malformed input
     */
    public static String decodeUtf8(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ContractBreachException(KernelErrorCodes.EX_LIC_0001, "JSON payload is not valid UTF-8", e);
        }
    }

    /**
     * Canonicalizes the JSON string, excluding the specified top-level property (detached signature).
     *
     * @param json                 source JSON string
     * @param detachedPropertyKey  root-level property key to exclude, or {@code null} if none
     * @return canonical UTF-8 encoded bytes
     */
    public static byte[] canonicalizeDetached(String json, String detachedPropertyKey) {
        Objects.requireNonNull(json, "json must not be null");
        return canonicalizeDetached(parse(json), detachedPropertyKey);
    }

    /**
     * Canonicalizes an already parsed JSON tree, excluding the specified top-level property.
     *
     * @param parsed              root entity as returned by {@link #parse(String)}
     * @param detachedPropertyKey root-level property key to exclude, or {@code null} if none
     * @return canonical UTF-8 encoded bytes
     */
    public static byte[] canonicalizeDetached(Object parsed, String detachedPropertyKey) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(parsed, sb, detachedPropertyKey);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses a JSON string into nested Java maps, lists, strings, numbers, booleans, and nulls.
     *
     * @param json source JSON
     * @return root JSON entity
     */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ContractBreachException(KernelErrorCodes.EX_LIC_0001, "Cannot parse empty or null JSON payload");
        }
        return new Parser(json).parseRoot();
    }

    /**
     * Parses a JSON document whose root is an object.
     *
     * @param json source JSON
     * @return root object, keys in document order
     * @throws ContractBreachException with EX-LIC-0001 if the document is not valid JSON or its root is not
     *                                 an object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonObject(String json) {
        Object root = parse(json);
        if (root instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new ContractBreachException(KernelErrorCodes.EX_LIC_0001, "Expected JSON object at root");
    }

    private static void writeCanonical(Object value, StringBuilder sb, String rootDetachedKey) {
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            List<String> sortedKeys = new ArrayList<>();
            for (Object k : map.keySet()) {
                String keyStr = k.toString();
                if (rootDetachedKey != null && rootDetachedKey.equals(keyStr)) {
                    continue;
                }
                sortedKeys.add(keyStr);
            }
            sortedKeys.sort(String::compareTo);
            for (int i = 0; i < sortedKeys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String key = sortedKeys.get(i);
                writeString(key, sb);
                sb.append(':');
                writeCanonical(map.get(key), sb, null);
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeCanonical(list.get(i), sb, null);
            }
            sb.append(']');
        } else if (value instanceof String str) {
            writeString(str, sb);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value == null) {
            sb.append("null");
        } else if (value instanceof Number num) {
            writeNumber(num, sb);
        } else {
            writeString(value.toString(), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u00")
                                .append(HEX_DIGITS[(c >> 4) & 0x0F])
                                .append(HEX_DIGITS[c & 0x0F]);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void writeNumber(Number num, StringBuilder sb) {
        sb.append(formatNumber(num.doubleValue()));
    }

    /**
     * Returns the shortest decimal that reads back as {@code magnitude}, choosing the closest when two
     * qualify. {@link Double#toString(double)} renders at least two significant digits, so where a
     * single digit already round-trips (for example {@code 5e-324}) it is found here.
     */
    // new BigDecimal(double) is the exact binary value; BigDecimal.valueOf would round through
    // Double.toString first and could hand back the two-digit form this method exists to shorten.
    @SuppressWarnings({"PMD.AvoidDecimalLiteralsInBigDecimalConstructor", "java:S2111"})
    private static BigDecimal shortestDecimal(double magnitude) {
        BigDecimal decimal = new BigDecimal(Double.toString(magnitude)).stripTrailingZeros();
        if (decimal.precision() != 2) {
            return decimal;
        }
        BigDecimal exact = new BigDecimal(magnitude);
        BigDecimal down = exact.round(new MathContext(1, RoundingMode.FLOOR));
        BigDecimal up = exact.round(new MathContext(1, RoundingMode.CEILING));
        boolean downReads = down.doubleValue() == magnitude;
        boolean upReads = up.doubleValue() == magnitude;
        if (downReads && upReads) {
            int cmp = exact.subtract(down).compareTo(up.subtract(exact));
            if (cmp == 0) {
                return (down.unscaledValue().intValue() % 2 == 0 ? down : up).stripTrailingZeros();
            }
            return (cmp < 0 ? down : up).stripTrailingZeros();
        }
        if (downReads) {
            return down.stripTrailingZeros();
        }
        return upReads ? up.stripTrailingZeros() : decimal;
    }

    /**
     * Serializes a double as ECMAScript {@code Number.prototype.toString} does, which RFC 8785 §3.2.2.3
     * requires. {@link Double#toString(double)} yields the shortest digit string that round-trips; only
     * the placement of the decimal point and the exponent notation differ from ECMAScript.
     *
     * @param value finite double
     * @return canonical number text
     */
    /* default */ static String formatNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0001,
                    "NaN/Infinity numbers are not permitted in JSON"
            );
        }
        if (value == 0.0) {
            return "0";
        }
        if (value == Math.rint(value) && Math.abs(value) <= MAX_EXACT_INTEGER) {
            // Below 2^53 an integral double prints as its digits, the same text ECMAScript produces.
            return Long.toString((long) value);
        }
        BigDecimal decimal = shortestDecimal(Math.abs(value));
        String digits = decimal.unscaledValue().toString();
        int k = digits.length();
        int n = k - decimal.scale();
        StringBuilder out = new StringBuilder(value < 0 ? "-" : "");
        if (k <= n && n <= 21) {
            out.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            out.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            out.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int exponent = n - 1;
            out.append(digits.charAt(0));
            if (k > 1) {
                out.append('.').append(digits, 1, k);
            }
            out.append('e').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
        }
        return out.toString();
    }

    @SuppressWarnings({
            "PMD.CyclomaticComplexity",
            "PMD.CognitiveComplexity",
            "PMD.NPathComplexity",
            "PMD.TooManyMethods",
            "PMD.ShortVariable",
            "PMD.AvoidLiteralsInIfCondition",
            "PMD.AssignmentInOperand",
            "PMD.CommentDefaultAccessModifier"
    })
    private static final class Parser {
        private static final int MAX_NESTING_DEPTH = 512;

        private final String src;
        private final int len;
        private int pos;
        private int depth;

        private Parser(String src) {
            this.src = src;
            this.len = src.length();
            this.pos = 0;
            this.depth = 0;
        }

        Object parseRoot() {
            skipWhitespace();
            if (pos >= len) {
                throw new ContractBreachException(KernelErrorCodes.EX_LIC_0001, "Unexpected end of JSON input");
            }
            Object root = parseValue();
            skipWhitespace();
            if (pos < len) {
                throw new ContractBreachException(
                        KernelErrorCodes.EX_LIC_0001,
                        "Trailing characters after JSON payload at position " + pos
                );
            }
            return root;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= len) {
                throw new ContractBreachException(KernelErrorCodes.EX_LIC_0001, "Unexpected end of JSON input");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw new ContractBreachException(
                            KernelErrorCodes.EX_LIC_0001,
                            "Unexpected character at position " + pos
                    );
                }
            };
        }

        private Map<String, Object> parseObject() {
            if (++depth > MAX_NESTING_DEPTH) {
                throw new ContractBreachException(
                        KernelErrorCodes.EX_LIC_0001,
                        "JSON nesting depth exceeds maximum limit of " + MAX_NESTING_DEPTH
                );
            }
            try {
                consume('{');
                Map<String, Object> map = new LinkedHashMap<>();
                skipWhitespace();
                if (peek('}')) {
                    consume('}');
                    return map;
                }
                while (true) {
                    skipWhitespace();
                    if (!peek('"')) {
                        throw new ContractBreachException(
                                KernelErrorCodes.EX_LIC_0001,
                                "Expected string key in object at position " + pos
                        );
                    }
                    String key = parseString();
                    if (map.containsKey(key)) {
                        throw new ContractBreachException(
                                KernelErrorCodes.EX_LIC_0001,
                                "Duplicate key in JSON object at position " + pos
                        );
                    }
                    skipWhitespace();
                    consume(':');
                    Object val = parseValue();
                    map.put(key, val);
                    skipWhitespace();
                    if (peek(',')) {
                        consume(',');
                        continue;
                    }
                    if (peek('}')) {
                        consume('}');
                        break;
                    }
                    throw new ContractBreachException(
                            KernelErrorCodes.EX_LIC_0001,
                            "Expected ',' or '}' in object at position " + pos
                    );
                }
                return map;
            } finally {
                depth--;
            }
        }

        private List<Object> parseArray() {
            if (++depth > MAX_NESTING_DEPTH) {
                throw new ContractBreachException(
                        KernelErrorCodes.EX_LIC_0001,
                        "JSON nesting depth exceeds maximum limit of " + MAX_NESTING_DEPTH
                );
            }
            try {
                consume('[');
                List<Object> list = new ArrayList<>();
                skipWhitespace();
                if (peek(']')) {
                    consume(']');
                    return list;
                }
                while (true) {
                    Object val = parseValue();
                    list.add(val);
                    skipWhitespace();
                    if (peek(',')) {
                        consume(',');
                        continue;
                    }
                    if (peek(']')) {
                        consume(']');
                        break;
                    }
                    throw new ContractBreachException(
                            KernelErrorCodes.EX_LIC_0001,
                            "Expected ',' or ']' in array at position " + pos
                    );
                }
                return list;
            } finally {
                depth--;
            }
        }

        private String parseString() {
            consume('"');
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return requireWellFormed(sb);
                }
                if (c == '\\') {
                    if (pos >= len) {
                        throw new ContractBreachException(
                                KernelErrorCodes.EX_LIC_0001,
                                "Unterminated escape sequence in string"
                        );
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > len) {
                                throw new ContractBreachException(
                                        KernelErrorCodes.EX_LIC_0001,
                                        "Malformed \\uXXXX escape in string"
                                );
                            }
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                int digit = Character.digit(src.charAt(pos + i), 16);
                                if (digit < 0 || src.charAt(pos + i) > 'f') {
                                    throw new ContractBreachException(
                                            KernelErrorCodes.EX_LIC_0001,
                                            "Invalid hex in \\uXXXX escape at position " + pos
                                    );
                                }
                                code = (code << 4) | digit;
                            }
                            pos += 4;
                            sb.append((char) code);
                        }
                        default -> throw new ContractBreachException(
                                KernelErrorCodes.EX_LIC_0001,
                                "Invalid escape sequence at position " + (pos - 2)
                        );
                    }
                } else {
                    if (c < 0x20) {
                        throw new ContractBreachException(
                                KernelErrorCodes.EX_LIC_0001,
                                "Unescaped control character in string at position " + (pos - 1)
                        );
                    }
                    sb.append(c);
                }
            }
            throw new ContractBreachException(KernelErrorCodes.EX_LIC_0001, "Unterminated string literal");
        }

        /** Rejects an unpaired surrogate, which has no UTF-8 encoding and would serialize as '?'. */
        private static String requireWellFormed(StringBuilder sb) {
            int i = 0;
            while (i < sb.length()) {
                char c = sb.charAt(i);
                if (Character.isHighSurrogate(c) && i + 1 < sb.length() && Character.isLowSurrogate(sb.charAt(i + 1))) {
                    i += 2;
                    continue;
                }
                i++;
                if (Character.isSurrogate(c)) {
                    throw new ContractBreachException(
                            KernelErrorCodes.EX_LIC_0001,
                            "Unpaired surrogate in string literal"
                    );
                }
            }
            return sb.toString();
        }

        private static boolean isAsciiDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private Number parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= len || !isAsciiDigit(src.charAt(pos))) {
                throw new ContractBreachException(
                        KernelErrorCodes.EX_LIC_0001,
                        "Invalid number format at position " + start
                );
            }
            if (src.charAt(pos) == '0') {
                pos++;
                if (pos < len && isAsciiDigit(src.charAt(pos))) {
                    throw new ContractBreachException(
                            KernelErrorCodes.EX_LIC_0001,
                            "Leading zero not permitted in JSON number at position " + start
                    );
                }
            } else {
                while (pos < len && isAsciiDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < len && src.charAt(pos) == '.') {
                pos++;
                if (pos >= len || !isAsciiDigit(src.charAt(pos))) {
                    throw new ContractBreachException(
                            KernelErrorCodes.EX_LIC_0001,
                            "Decimal point must be followed by at least one digit at position " + (pos - 1)
                    );
                }
                while (pos < len && isAsciiDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                pos++;
                if (pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos >= len || !isAsciiDigit(src.charAt(pos))) {
                    throw new ContractBreachException(
                            KernelErrorCodes.EX_LIC_0001,
                            "Exponent must have at least one digit at position " + (pos - 1)
                    );
                }
                while (pos < len && isAsciiDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            String numStr = src.substring(start, pos);
            double value = Double.parseDouble(numStr);
            if (Double.isInfinite(value)) {
                throw new ContractBreachException(
                        KernelErrorCodes.EX_LIC_0001,
                        "Number out of IEEE-754 double range at position " + start
                );
            }
            return value;
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new ContractBreachException(KernelErrorCodes.EX_LIC_0001, "Invalid boolean token at position " + pos);
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new ContractBreachException(KernelErrorCodes.EX_LIC_0001, "Invalid null token at position " + pos);
        }

        private void skipWhitespace() {
            while (pos < len) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private boolean peek(char expected) {
            return pos < len && src.charAt(pos) == expected;
        }

        private void consume(char expected) {
            if (pos >= len || src.charAt(pos) != expected) {
                throw new ContractBreachException(KernelErrorCodes.EX_LIC_0001,
                        "Expected '" + expected + "' at position " + pos);
            }
            pos++;
        }
    }
}
