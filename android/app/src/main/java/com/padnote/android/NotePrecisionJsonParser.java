package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Strict JSON reader that keeps geometry -0 as a Double on Android API levels
 * whose JSONObject parser otherwise collapses that token to integral Long zero.
 * Other numeric tokens retain platform org.json Number semantics, including all
 * timestamp I64/F64 authority rules. */
final class NotePrecisionJsonParser {
    private static final int MAX_DEPTH = 256;
    private final String text;
    private java.util.Set<String> f64NegativeZeroTimestamps = java.util.Collections.emptySet();
    private int index;
    private int nodes;

    private NotePrecisionJsonParser(String text) {
        if (text == null) throw new IllegalArgumentException("json_required");
        this.text = text;
    }

    static JSONObject parseObject(String text) throws JSONException {
        return parseObject(text, java.util.Collections.<String>emptySet());
    }

    static JSONObject parseObject(String text, java.util.Set<String> f64NegativeZeroTimestamps) throws JSONException {
        NotePrecisionJsonParser parser = new NotePrecisionJsonParser(text);
        parser.f64NegativeZeroTimestamps = f64NegativeZeroTimestamps;
        Object value = parser.readValue("", 0);
        parser.skipWhitespace();
        if (parser.index != text.length() || !(value instanceof JSONObject)) {
            throw new JSONException("Expected one JSON object");
        }
        return (JSONObject) value;
    }

    private Object readValue(String path, int depth) throws JSONException {
        if (depth > MAX_DEPTH || ++nodes > 1_000_000) throw new JSONException("JSON complexity limit");
        skipWhitespace();
        if (index >= text.length()) throw new JSONException("Unexpected end of JSON");
        char c = text.charAt(index);
        if (c == '{') return readObject(path, depth + 1);
        if (c == '[') return readArray(path, depth + 1);
        if (c == '"') return readString();
        if (c == 't') return readLiteral("true", Boolean.TRUE);
        if (c == 'f') return readLiteral("false", Boolean.FALSE);
        if (c == 'n') return readLiteral("null", JSONObject.NULL);
        return readNumber(path);
    }

    private JSONObject readObject(String path, int depth) throws JSONException {
        index++;
        JSONObject result = new JSONObject();
        skipWhitespace();
        if (take('}')) return result;
        while (true) {
            skipWhitespace();
            if (index >= text.length() || text.charAt(index) != '"') throw new JSONException("Expected object key");
            String key = readString();
            if (result.has(key)) throw new JSONException("Duplicate object key");
            skipWhitespace();
            require(':');
            Object value = readValue(path + "/" + escapePointer(key), depth);
            result.put(key, value);
            skipWhitespace();
            if (take('}')) return result;
            require(',');
        }
    }

    private JSONArray readArray(String path, int depth) throws JSONException {
        index++;
        JSONArray result = new JSONArray();
        skipWhitespace();
        if (take(']')) return result;
        int element = 0;
        while (true) {
            result.put(readValue(path + "/" + element, depth));
            element++;
            skipWhitespace();
            if (take(']')) return result;
            require(',');
        }
    }

    private String readString() throws JSONException {
        int start = index;
        index++;
        while (index < text.length()) {
            char c = text.charAt(index++);
            if (c == '\\') {
                if (index >= text.length()) throw new JSONException("Truncated JSON escape");
                char escape = text.charAt(index++);
                if (escape == 'u') {
                    for (int digit = 0; digit < 4; digit++) {
                        if (index >= text.length() || !isHex(text.charAt(index++))) {
                            throw new JSONException("Invalid JSON unicode escape");
                        }
                    }
                } else if (escape != '"' && escape != '\\' && escape != '/'
                        && escape != 'b' && escape != 'f' && escape != 'n'
                        && escape != 'r' && escape != 't') {
                    throw new JSONException("Invalid JSON escape");
                }
            } else if (c == '"') {
                Object decoded = new JSONTokener(text.substring(start, index)).nextValue();
                if (!(decoded instanceof String)) throw new JSONException("Invalid JSON string");
                return (String) decoded;
            } else if (c < 0x20) {
                throw new JSONException("Control character in JSON string");
            }
        }
        throw new JSONException("Unterminated JSON string");
    }

    private static boolean isHex(char value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private Object readLiteral(String token, Object value) throws JSONException {
        if (!text.regionMatches(index, token, 0, token.length())) throw new JSONException("Invalid JSON literal");
        index += token.length();
        return value;
    }

    private Object readNumber(String path) throws JSONException {
        int start = index;
        if (take('-') && index >= text.length()) throw new JSONException("Invalid JSON number");
        if (take('0')) {
            if (index < text.length() && isDigit(text.charAt(index))) throw new JSONException("Leading zero");
        } else {
            if (index >= text.length() || text.charAt(index) < '1' || text.charAt(index) > '9') throw new JSONException("Invalid JSON number");
            while (index < text.length() && isDigit(text.charAt(index))) index++;
        }
        boolean fraction = false;
        if (take('.')) {
            fraction = true;
            int digits = index;
            while (index < text.length() && isDigit(text.charAt(index))) index++;
            if (digits == index) throw new JSONException("Invalid JSON fraction");
        }
        if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
            fraction = true;
            index++;
            if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
            int digits = index;
            while (index < text.length() && isDigit(text.charAt(index))) index++;
            if (digits == index) throw new JSONException("Invalid JSON exponent");
        }
        String token = text.substring(start, index);
        if (!fraction && "-0".equals(token)) {
            if (isGeometryNumberPath(path)) return Double.valueOf(-0.0d);
            if (isTimestampPath(path)) {
                if (f64NegativeZeroTimestamps.contains(path)) return Double.valueOf(-0.0d);
                return Long.valueOf(0L);
            }
        }
        Object parsed = new JSONTokener(token).nextValue();
        if (!(parsed instanceof Number)) throw new JSONException("Invalid JSON number");
        return parsed;
    }

    private static boolean isGeometryNumberPath(String path) {
        int slash = path.lastIndexOf('/');
        String key = slash < 0 ? path : path.substring(slash + 1);
        switch (key) {
            case "pageWidth": case "pageHeight": case "canvasWidth": case "canvasHeight":
            case "pageGap": case "viewportZoom": case "viewportCenterX": case "viewportCenterY":
            case "baseWidth": case "x": case "y": case "pressure": case "fontSizeSp":
            case "lineHeight": case "width": case "anchorXInPage": case "anchorYInPage":
            case "height": return true;
            default: return false;
        }
    }

    private static boolean isTimestampPath(String path) {
        int slash = path.lastIndexOf('/');
        String key = slash < 0 ? path : path.substring(slash + 1);
        return "updatedAt".equals(key) || "createdAt".equals(key) || "timestamp".equals(key);
    }

    private static String escapePointer(String value) { return value.replace("~", "~0").replace("/", "~1"); }
    private void skipWhitespace() { while (index < text.length() && (text.charAt(index) == ' ' || text.charAt(index) == '\n' || text.charAt(index) == '\r' || text.charAt(index) == '\t')) index++; }
    private boolean take(char expected) { if (index < text.length() && text.charAt(index) == expected) { index++; return true; } return false; }
    private void require(char expected) throws JSONException { if (!take(expected)) throw new JSONException("Expected '" + expected + "'"); }
    private static boolean isDigit(char value) { return value >= '0' && value <= '9'; }
}
