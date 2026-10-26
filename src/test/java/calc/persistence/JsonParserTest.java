package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.persistence.JsonException.Reason;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonParserTest {
    @Test
    void readsEveryKindOfValue() throws Exception {
        Object tree = JsonParser.parse(" {\"a\": [1, -2.5e3, \"x\\n\\u00e9\\\"\", true, false, null], \"b\": {}, \"c\": []} ");

        Map<?, ?> object = assertInstanceOf(Map.class, tree);
        assertEquals(List.of("a", "b", "c"), List.copyOf(object.keySet()), "Members keep their order.");
        List<?> list = assertInstanceOf(List.class, object.get("a"));
        assertEquals(new JsonParser.JsonNumber("1"), list.get(0));
        assertEquals(new JsonParser.JsonNumber("-2.5e3"), list.get(1), "A number stays its literal: never a floating-point value.");
        assertEquals("x\né\"", list.get(2));
        assertEquals(Boolean.TRUE, list.get(3));
        assertEquals(Boolean.FALSE, list.get(4));
        assertNull(list.get(5));
        assertEquals(Map.of(), object.get("b"));
        assertEquals(List.of(), object.get("c"));
    }

    @Test
    void aLeadingByteOrderMarkIsIgnored() throws Exception {
        assertEquals(Map.of(), JsonParser.parse("﻿{}"));
    }

    @Test
    void anIntegerLiteralIsReadAsAnIntOnlyWhenItIsSmallEnough() {
        assertEquals(Optional.of(2), new JsonParser.JsonNumber("2").asInt());
        assertEquals(Optional.of(-7), new JsonParser.JsonNumber("-7").asInt());
        assertEquals(Optional.of(999_999_999), new JsonParser.JsonNumber("999999999").asInt());
        assertEquals(Optional.empty(), new JsonParser.JsonNumber("1000000000").asInt());
        assertEquals(Optional.empty(), new JsonParser.JsonNumber("2.0").asInt());
        assertEquals(Optional.empty(), new JsonParser.JsonNumber("2e0").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "}", "[", "]", "[1,]", "[,1]", "{\"a\":1,}", "{\"a\"}", "{\"a\":}", "{a:1}", "{'a':1}", "[1 2]",
        "\"unterminated", "\"bad\\q\"", "\"bad\\u12\"", "\"bad\\u12G4\"", "01", "-", "+1", "1.", ".5", "1e", "1e+", "--1", "tru", "nul", "True",
        "NaN", "Infinity", "[1] [2]", "{} x", "\"tab\there\"", "\"new\nline\"", "/* c */ {}", "// c\n{}"})
    void refusesWhatIsNotStrictJson(String text) {
        JsonException failure = assertThrows(JsonException.class, () -> JsonParser.parse(text));

        assertEquals(Reason.SYNTAX, failure.reason(), text);
        assertTrue(failure.getMessage().matches("SYNTAX at character \\d+"), "The message gives the offset, never the content.");
    }

    @Test
    void aDuplicateKeyIsRefused() {
        assertEquals(Reason.DUPLICATE_KEY, assertThrows(JsonException.class, () -> JsonParser.parse("{\"a\":1,\"a\":2}")).reason());
    }

    @Test
    void theDepthLimitIsExactlyTheDocumentedOne() throws Exception {
        int limit = StorageLimits.MAX_DEPTH;

        JsonParser.parse("[".repeat(limit) + "]".repeat(limit));
        assertEquals(Reason.TOO_DEEP, assertThrows(JsonException.class,
                () -> JsonParser.parse("[".repeat(limit + 1) + "]".repeat(limit + 1))).reason());
        assertEquals(Reason.TOO_DEEP, assertThrows(JsonException.class,
                () -> JsonParser.parse("{\"a\":".repeat(limit + 1) + "1" + "}".repeat(limit + 1))).reason());
    }

    @Test
    void aDocumentFarDeeperThanTheLimitNeverOverflowsTheStack() {
        for (String open : List.of("[", "{\"a\":")) {
            String text = open.repeat(2_000_000);
            assertEquals(Reason.TOO_DEEP, assertThrows(JsonException.class, () -> JsonParser.parse(text)).reason());
        }
    }

    @Test
    void theStringLimitIsExactlyTheDocumentedOne() throws Exception {
        String longest = "a".repeat(StorageLimits.MAX_STRING_LENGTH);

        assertEquals(longest, JsonParser.parse("\"" + longest + "\""));
        assertEquals(Reason.STRING_TOO_LONG, assertThrows(JsonException.class, () -> JsonParser.parse("\"" + longest + "a\"")).reason());
        assertEquals(Reason.STRING_TOO_LONG, assertThrows(JsonException.class,
                () -> JsonParser.parse("{\"" + longest + "a\": 1}")).reason(), "A key is a string too.");
        // The length counts characters after unescaping, and an unterminated huge string is refused as soon as it is too long.
        assertEquals(Reason.STRING_TOO_LONG, assertThrows(JsonException.class,
                () -> JsonParser.parse("\"" + "\\u0041".repeat(StorageLimits.MAX_STRING_LENGTH + 1) + "\"")).reason());
        assertEquals(Reason.STRING_TOO_LONG, assertThrows(JsonException.class, () -> JsonParser.parse("\"" + "a".repeat(5_000_000))).reason());
    }

    @Test
    void theNumberLimitIsExactlyTheDocumentedOne() throws Exception {
        String longest = "1".repeat(StorageLimits.MAX_NUMBER_LENGTH);

        assertEquals(new JsonParser.JsonNumber(longest), JsonParser.parse(longest));
        assertEquals(Reason.NUMBER_TOO_LONG, assertThrows(JsonException.class, () -> JsonParser.parse(longest + "1")).reason());
        assertEquals(Reason.NUMBER_TOO_LONG, assertThrows(JsonException.class, () -> JsonParser.parse("1".repeat(5_000_000))).reason());
        assertEquals(Reason.NUMBER_TOO_LONG, assertThrows(JsonException.class,
                () -> JsonParser.parse("1." + "0".repeat(5_000_000))).reason());
        assertEquals(Reason.NUMBER_TOO_LONG, assertThrows(JsonException.class,
                () -> JsonParser.parse("1e" + "9".repeat(5_000_000))).reason());
    }

    @Test
    void onlyTheLimitsAreReportedAsLimits() {
        assertTrue(Reason.TOO_DEEP.isLimit() && Reason.STRING_TOO_LONG.isLimit() && Reason.NUMBER_TOO_LONG.isLimit());
        assertTrue(!Reason.SYNTAX.isLimit() && !Reason.DUPLICATE_KEY.isLimit());
    }
}
