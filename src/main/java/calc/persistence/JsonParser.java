package calc.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A strict JSON reader (RFC 8259) with hard bounds, written for this format only: no dependency, and the limits of
 * {@link StorageLimits} are enforced while scanning, before any large structure is built.
 *
 * <p>The tree is made of {@code Map<String, Object>} (insertion order), {@code List<Object>}, {@code String},
 * {@code Boolean}, {@link JsonNumber} for numbers (the literal, never converted to a floating-point value) and {@code null}.
 * A duplicate key, a control character inside a string, a leading zero, a trailing comma or any content after the value is
 * a syntax error. The parser never recurses deeper than {@link StorageLimits#MAX_DEPTH}, so no input can overflow the
 * stack, and a string or a number that is too long is refused as soon as it is found to be, not after it has been read.
 */
final class JsonParser {
    /** A number literal as written; it is only ever read as a small integer ({@link #asInt()}). */
    record JsonNumber(String literal) {
        /** The value when the literal is an integer of at most nine digits, optionally negative. */
        Optional<Integer> asInt() {
            int digits = literal.startsWith("-") ? literal.length() - 1 : literal.length();
            for (int index = literal.length() - digits; index < literal.length(); index++) {
                if (literal.charAt(index) < '0' || literal.charAt(index) > '9') {
                    return Optional.empty();
                }
            }
            return digits <= 9 ? Optional.of(Integer.parseInt(literal)) : Optional.empty();
        }
    }

    private final String text;
    private final int maxValues;
    private int position;
    private int values;

    private JsonParser(String text, int maxValues) {
        this.text = text;
        this.maxValues = maxValues;
    }

    /** The only value of {@code text}. A leading byte order mark is ignored. */
    static Object parse(String text) throws JsonException {
        return parse(text, Integer.MAX_VALUE);
    }

    /**
     * The only value of {@code text}, which may hold at most {@code maxValues} values in all (every object, array, string,
     * number, boolean and null counts one): a file of millions of tiny values is refused before it fills the memory.
     */
    static Object parse(String text, int maxValues) throws JsonException {
        JsonParser parser = new JsonParser(text, maxValues);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            parser.position = 1;
        }
        parser.skipWhitespace();
        Object value = parser.readValue(1);
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw parser.error(JsonException.Reason.SYNTAX);
        }
        return value;
    }

    private Object readValue(int depth) throws JsonException {
        if (position >= text.length()) {
            throw error(JsonException.Reason.SYNTAX);
        }
        if (++values > maxValues) {
            throw error(JsonException.Reason.TOO_MANY_VALUES);
        }
        char first = text.charAt(position);
        return switch (first) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if (first == '-' || first >= '0' && first <= '9') {
                    yield readNumber();
                }
                throw error(JsonException.Reason.SYNTAX);
            }
        };
    }

    private Map<String, Object> readObject(int depth) throws JsonException {
        if (depth > StorageLimits.MAX_DEPTH) {
            throw error(JsonException.Reason.TOO_DEEP);
        }
        position++;
        Map<String, Object> members = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) {
            return members;
        }
        while (true) {
            skipWhitespace();
            if (position >= text.length() || text.charAt(position) != '"') {
                throw error(JsonException.Reason.SYNTAX);
            }
            String key = readString();
            skipWhitespace();
            if (!consume(':')) {
                throw error(JsonException.Reason.SYNTAX);
            }
            skipWhitespace();
            Object value = readValue(depth + 1);
            if (members.containsKey(key)) {
                throw error(JsonException.Reason.DUPLICATE_KEY);
            }
            members.put(key, value);
            skipWhitespace();
            if (consume('}')) {
                return members;
            }
            if (!consume(',')) {
                throw error(JsonException.Reason.SYNTAX);
            }
        }
    }

    private List<Object> readArray(int depth) throws JsonException {
        if (depth > StorageLimits.MAX_DEPTH) {
            throw error(JsonException.Reason.TOO_DEEP);
        }
        position++;
        List<Object> elements = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) {
            return elements;
        }
        while (true) {
            skipWhitespace();
            elements.add(readValue(depth + 1));
            skipWhitespace();
            if (consume(']')) {
                return elements;
            }
            if (!consume(',')) {
                throw error(JsonException.Reason.SYNTAX);
            }
        }
    }

    private String readString() throws JsonException {
        position++;
        StringBuilder value = new StringBuilder();
        while (position < text.length()) {
            char current = text.charAt(position++);
            if (current == '"') {
                return value.toString();
            }
            if (current < 0x20) {
                throw error(JsonException.Reason.SYNTAX);
            }
            if (current == '\\') {
                value.append(readEscape());
            } else {
                value.append(current);
            }
            if (value.length() > StorageLimits.MAX_STRING_LENGTH) {
                throw error(JsonException.Reason.STRING_TOO_LONG);
            }
        }
        throw error(JsonException.Reason.SYNTAX);
    }

    private char readEscape() throws JsonException {
        if (position >= text.length()) {
            throw error(JsonException.Reason.SYNTAX);
        }
        char escaped = text.charAt(position++);
        return switch (escaped) {
            case '"', '\\', '/' -> escaped;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            default -> throw error(JsonException.Reason.SYNTAX);
        };
    }

    private char readUnicodeEscape() throws JsonException {
        if (position + 4 > text.length()) {
            throw error(JsonException.Reason.SYNTAX);
        }
        int code = 0;
        for (int index = 0; index < 4; index++) {
            int digit = Character.digit(text.charAt(position++), 16);
            if (digit < 0) {
                throw error(JsonException.Reason.SYNTAX);
            }
            code = code * 16 + digit;
        }
        return (char) code;
    }

    private JsonNumber readNumber() throws JsonException {
        int start = position;
        consume('-');
        if (position >= text.length()) {
            throw error(JsonException.Reason.SYNTAX);
        }
        if (text.charAt(position) == '0') {
            position++;
        } else {
            readDigits(start);
        }
        if (consume('.')) {
            readDigits(start);
        }
        if (consume('e') || consume('E')) {
            if (!consume('+')) {
                consume('-');
            }
            readDigits(start);
        }
        return new JsonNumber(text.substring(start, position));
    }

    // At least one digit; the literal is refused as soon as it is too long, even when it goes on for megabytes.
    private void readDigits(int numberStart) throws JsonException {
        int first = position;
        while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') {
            position++;
            if (position - numberStart > StorageLimits.MAX_NUMBER_LENGTH) {
                throw error(JsonException.Reason.NUMBER_TOO_LONG);
            }
        }
        if (position == first) {
            throw error(JsonException.Reason.SYNTAX);
        }
    }

    private Object readLiteral(String word, Object value) throws JsonException {
        if (!text.startsWith(word, position)) {
            throw error(JsonException.Reason.SYNTAX);
        }
        position += word.length();
        return value;
    }

    private boolean consume(char expected) {
        if (position < text.length() && text.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char current = text.charAt(position);
            if (current != ' ' && current != '\t' && current != '\n' && current != '\r') {
                return;
            }
            position++;
        }
    }

    private JsonException error(JsonException.Reason reason) {
        return new JsonException(reason, position);
    }
}
