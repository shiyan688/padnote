package com.padnote.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small bounded JSON codec for the archive manifest. It rejects duplicate keys and preserves number lexemes. */
final class LibraryBackupJson {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 250_000;

    static final class NumberToken {
        final String value;
        NumberToken(String value) { this.value = value; }
        @Override public String toString() { return value; }
    }

    private LibraryBackupJson() { }

    static Object parse(byte[] bytes, int maximumBytes) throws IOException {
        if (bytes == null || bytes.length > maximumBytes) throw new IOException("MANIFEST_SIZE_LIMIT");
        final String input;
        try {
            input = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("JSON_UTF8_INVALID");
        }
        Parser parser = new Parser(input);
        Object result = parser.value(0);
        parser.space();
        if (parser.position != input.length()) throw new IOException("JSON_TRAILING_DATA");
        return result;
    }

    /** Generic strict JSON entry point for later payload readers; duplicate keys are rejected at every depth. */
    static Map<String, Object> parseCheckedObject(byte[] bytes, int maximumBytes) throws IOException {
        return object(parse(bytes, maximumBytes), "JSON_ROOT_OBJECT");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String code) throws IOException {
        if (!(value instanceof Map)) throw new IOException(code);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value, String code) throws IOException {
        if (!(value instanceof List)) throw new IOException(code);
        return (List<Object>) value;
    }

    static String string(Object value, String code) throws IOException {
        if (!(value instanceof String)) throw new IOException(code);
        return (String) value;
    }

    static boolean bool(Object value, String code) throws IOException {
        if (!(value instanceof Boolean)) throw new IOException(code);
        return (Boolean) value;
    }

    static long integer(Object value, long minimum, long maximum, String code) throws IOException {
        if (!(value instanceof NumberToken)) throw new IOException(code);
        String raw = ((NumberToken) value).value;
        if (!raw.matches("-?(0|[1-9][0-9]*)")) throw new IOException(code);
        final long result;
        try { result = Long.parseLong(raw); }
        catch (NumberFormatException error) { throw new IOException(code); }
        if (result < minimum || result > maximum) throw new IOException(code);
        return result;
    }

    static void exactKeys(Map<String, Object> object, String code, String... keys) throws IOException {
        if (object.size() != keys.length) throw new IOException(code);
        for (String key : keys) if (!object.containsKey(key)) throw new IOException(code);
    }

    static byte[] encode(Object value) throws IOException {
        StringBuilder output = new StringBuilder();
        writeValue(output, value, 0);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void writeValue(StringBuilder out, Object value, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("JSON_DEPTH_LIMIT");
        if (value == null) { out.append("null"); return; }
        if (value instanceof String) { quote(out, (String) value); return; }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value); return;
        }
        if (value instanceof NumberToken) {
            String raw = ((NumberToken) value).value;
            if (!raw.matches("-?(0|[1-9][0-9]*)")) throw new IOException("JSON_NUMBER_INVALID");
            out.append(raw); return;
        }
        if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) out.append(',');
                first = false;
                writeValue(out, item, depth + 1);
            }
            out.append(']'); return;
        }
        if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IOException("JSON_KEY_INVALID");
                if (!first) out.append(',');
                first = false;
                quote(out, (String) entry.getKey());
                out.append(':');
                writeValue(out, entry.getValue(), depth + 1);
            }
            out.append('}'); return;
        }
        throw new IOException("JSON_VALUE_INVALID");
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) out.append('0');
                        out.append(hex);
                    } else out.append(c);
            }
        }
        out.append('"');
    }

    private static final class Parser {
        final String input;
        int position;
        int nodes;
        Parser(String input) { this.input = input; }

        void space() {
            while (position < input.length()) {
                char c = input.charAt(position);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') position++;
                else break;
            }
        }

        Object value(int depth) throws IOException {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) throw new IOException("JSON_COMPLEXITY_LIMIT");
            space();
            if (position >= input.length()) throw new IOException("JSON_TRUNCATED");
            char c = input.charAt(position);
            if (c == '{') return object(depth + 1);
            if (c == '[') return array(depth + 1);
            if (c == '"') return string();
            if (c == 't') return keyword("true", Boolean.TRUE);
            if (c == 'f') return keyword("false", Boolean.FALSE);
            if (c == 'n') return keyword("null", null);
            return number();
        }

        Map<String, Object> object(int depth) throws IOException {
            position++;
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            space();
            if (take('}')) return map;
            while (true) {
                space();
                if (position >= input.length() || input.charAt(position) != '"') throw new IOException("JSON_OBJECT_KEY");
                String key = string();
                if (map.containsKey(key)) throw new IOException("JSON_DUPLICATE_KEY");
                space();
                if (!take(':')) throw new IOException("JSON_COLON_EXPECTED");
                map.put(key, value(depth));
                space();
                if (take('}')) return map;
                if (!take(',')) throw new IOException("JSON_OBJECT_DELIMITER");
            }
        }

        List<Object> array(int depth) throws IOException {
            position++;
            List<Object> values = new ArrayList<>();
            space();
            if (take(']')) return values;
            while (true) {
                values.add(value(depth));
                space();
                if (take(']')) return values;
                if (!take(',')) throw new IOException("JSON_ARRAY_DELIMITER");
            }
        }

        String string() throws IOException {
            if (!take('"')) throw new IOException("JSON_STRING_EXPECTED");
            StringBuilder out = new StringBuilder();
            while (position < input.length()) {
                char c = input.charAt(position++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw new IOException("JSON_STRING_CONTROL");
                if (c == '\\') {
                    if (position >= input.length()) throw new IOException("JSON_STRING_ESCAPE");
                    char escaped = input.charAt(position++);
                    switch (escaped) {
                        case '"': out.append('"'); break;
                        case '\\': out.append('\\'); break;
                        case '/': out.append('/'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u': appendUnicode(out); break;
                        default: throw new IOException("JSON_STRING_ESCAPE");
                    }
                } else out.append(c);
            }
            throw new IOException("JSON_STRING_TRUNCATED");
        }

        void appendUnicode(StringBuilder out) throws IOException {
            char first = (char) hex4();
            if (Character.isHighSurrogate(first)) {
                if (position + 2 > input.length() || input.charAt(position) != '\\' || input.charAt(position + 1) != 'u')
                    throw new IOException("JSON_SURROGATE_INVALID");
                position += 2;
                char second = (char) hex4();
                if (!Character.isLowSurrogate(second)) throw new IOException("JSON_SURROGATE_INVALID");
                out.append(first).append(second);
            } else if (Character.isLowSurrogate(first)) throw new IOException("JSON_SURROGATE_INVALID");
            else out.append(first);
        }

        int hex4() throws IOException {
            if (position + 4 > input.length()) throw new IOException("JSON_UNICODE_ESCAPE");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char c = input.charAt(position++);
                int digit = Character.digit(c, 16);
                if (digit < 0) throw new IOException("JSON_UNICODE_ESCAPE");
                value = (value << 4) | digit;
            }
            return value;
        }

        Object keyword(String word, Object result) throws IOException {
            if (!input.startsWith(word, position)) throw new IOException("JSON_TOKEN_INVALID");
            position += word.length();
            return result;
        }

        NumberToken number() throws IOException {
            int start = position;
            if (take('-') && position >= input.length()) throw new IOException("JSON_NUMBER_INVALID");
            if (take('0')) {
                if (position < input.length() && Character.isDigit(input.charAt(position))) throw new IOException("JSON_NUMBER_INVALID");
            } else {
                if (position >= input.length() || input.charAt(position) < '1' || input.charAt(position) > '9') throw new IOException("JSON_TOKEN_INVALID");
                while (position < input.length() && input.charAt(position) >= '0' && input.charAt(position) <= '9') position++;
            }
            if (take('.')) {
                int digits = position;
                while (position < input.length() && input.charAt(position) >= '0' && input.charAt(position) <= '9') position++;
                if (position == digits) throw new IOException("JSON_NUMBER_INVALID");
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                int digits = position;
                while (position < input.length() && input.charAt(position) >= '0' && input.charAt(position) <= '9') position++;
                if (position == digits) throw new IOException("JSON_NUMBER_INVALID");
            }
            return new NumberToken(input.substring(start, position));
        }

        boolean take(char expected) {
            if (position < input.length() && input.charAt(position) == expected) { position++; return true; }
            return false;
        }
    }
}
