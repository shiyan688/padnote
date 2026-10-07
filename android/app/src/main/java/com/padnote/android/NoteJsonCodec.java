package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Serializes note JSON without normalizing the sign of binary64 negative zero. */
final class NoteJsonCodec {
    private NoteJsonCodec() { }

    static String stringify(JSONObject value) throws JSONException {
        StringBuilder output = new StringBuilder();
        append(output, value);
        return output.toString();
    }

    static String stringifyLegacyWithout(JSONObject value, String... omittedKeys) throws JSONException {
        JSONObject copy = new JSONObject(value.toString());
        if (omittedKeys != null) for (String key : omittedKeys) copy.remove(key);
        return copy.toString();
    }

    /** Exact JSON-pointer locations of binary64 negative zero values in a receipt tree. */
    static JSONArray signedZeroPaths(JSONObject value) throws JSONException {
        List<String> paths = new ArrayList<>();
        collectSignedZeroPaths(value, "", paths);
        Collections.sort(paths);
        JSONArray result = new JSONArray();
        for (String path : paths) result.put(path);
        return result;
    }

    /** Legacy platform serialization may normalize integer-valued doubles, but may not erase -0. */
    static boolean signedZeroPathsMatch(JSONObject value) throws JSONException {
        if (!value.has("negativeZeroPaths")) return true; // old schema-1 receipts
        JSONArray expected = value.getJSONArray("negativeZeroPaths");
        List<String> paths = new ArrayList<>();
        collectSignedZeroPaths(value, "", paths);
        Collections.sort(paths);
        if (expected.length() != paths.size()) return false;
        for (int index = 0; index < paths.size(); index++) {
            if (!paths.get(index).equals(expected.optString(index))) return false;
        }
        return true;
    }

    private static void collectSignedZeroPaths(Object value, String path, List<String> paths)
            throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                collectSignedZeroPaths(object.get(key), path + "/" + pointerEscape(key), paths);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                collectSignedZeroPaths(array.get(index), path + "/" + index, paths);
            }
        } else if (value instanceof Number) {
            Number number = (Number) value;
            if ((number instanceof Double || number instanceof Float)
                    && Double.doubleToRawLongBits(number.doubleValue()) == Long.MIN_VALUE) {
                paths.add(path);
            }
        }
    }

    private static String pointerEscape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    static String stringifyWithout(JSONObject value, String... omittedKeys) throws JSONException {
        StringBuilder output = new StringBuilder();
        output.append('{');
        Iterator<String> keys = value.keys();
        boolean first = true;
        while (keys.hasNext()) {
            String key = keys.next();
            boolean omitted = false;
            if (omittedKeys != null) {
                for (String omittedKey : omittedKeys) {
                    if (key.equals(omittedKey)) {
                        omitted = true;
                        break;
                    }
                }
            }
            if (omitted) continue;
            if (!first) output.append(',');
            first = false;
            output.append(JSONObject.quote(key)).append(':');
            append(output, value.get(key));
        }
        output.append('}');
        return output.toString();
    }

    private static void append(StringBuilder output, Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) {
            output.append("null");
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            output.append('{');
            Iterator<String> keys = object.keys();
            boolean first = true;
            while (keys.hasNext()) {
                String key = keys.next();
                if (!first) output.append(',');
                first = false;
                output.append(JSONObject.quote(key)).append(':');
                append(output, object.get(key));
            }
            output.append('}');
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            output.append('[');
            for (int index = 0; index < array.length(); index++) {
                if (index > 0) output.append(',');
                append(output, array.get(index));
            }
            output.append(']');
        } else if (value instanceof String) {
            output.append(JSONObject.quote((String) value));
        } else if (value instanceof Boolean) {
            output.append(value.toString());
        } else if (value instanceof Number) {
            Number number = (Number) value;
            String token;
            if (number instanceof Double || number instanceof Float) {
                double exact = number.doubleValue();
                if (!Double.isFinite(exact)) throw new JSONException("Non-finite note number");
                token = number instanceof Float ? Float.toString(number.floatValue())
                        : Double.toString(exact);
            } else {
                token = number.toString();
            }
            if (!isJsonNumber(token)) throw new JSONException("Invalid note number");
            output.append(token);
        } else {
            throw new JSONException("Unsupported note JSON value");
        }
    }

    private static boolean isJsonNumber(String value) {
        if (value == null || value.isEmpty()) return false;
        int index = 0;
        if (value.charAt(index) == '-') index++;
        if (index >= value.length()) return false;
        if (value.charAt(index) == '0') {
            index++;
        } else {
            if (value.charAt(index) < '1' || value.charAt(index) > '9') return false;
            while (index < value.length() && Character.isDigit(value.charAt(index))) index++;
        }
        if (index < value.length() && value.charAt(index) == '.') {
            index++;
            int start = index;
            while (index < value.length() && Character.isDigit(value.charAt(index))) index++;
            if (start == index) return false;
        }
        if (index < value.length() && (value.charAt(index) == 'e' || value.charAt(index) == 'E')) {
            index++;
            if (index < value.length() && (value.charAt(index) == '+' || value.charAt(index) == '-')) index++;
            int start = index;
            while (index < value.length() && Character.isDigit(value.charAt(index))) index++;
            if (start == index) return false;
        }
        return index == value.length();
    }
}
