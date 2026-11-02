package com.padnote.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Token-aware inline Markdown parser for profile 2. The profile-1 parser remains frozen. */
final class MarkdownV2Parser {
    private static final int MAX_INLINE_DEPTH = 32;
    enum Kind { TEXT, CODE, MATH, STRONG, EMPHASIS, STRIKE, LINK }

    static final class Node {
        final Kind kind;
        final String value;
        final boolean displayMath;
        final List<Node> children;

        Node(Kind kind, String value, boolean displayMath, List<Node> children) {
            this.kind = kind;
            this.value = value == null ? "" : value;
            this.displayMath = displayMath;
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
        }
    }

    private static final class MathSpan {
        final int start, contentStart, end, closeLength;
        final boolean display;
        MathSpan(int start, int contentStart, int end, int closeLength, boolean display) {
            this.start = start; this.contentStart = contentStart; this.end = end;
            this.closeLength = closeLength; this.display = display;
        }
    }

    private MarkdownV2Parser() { }

    static List<Node> parseInline(String source) {
        return parse(source == null ? "" : source, 0);
    }

    static String inlineToHtml(String source) {
        StringBuilder html = new StringBuilder();
        appendHtml(html, parseInline(source), false);
        return html.toString();
    }

    private static List<Node> parse(String text, int depth) {
        if (depth >= MAX_INLINE_DEPTH) {
            return Collections.singletonList(node(Kind.TEXT, text, false, null));
        }
        ArrayList<Node> nodes = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            // Math delimiters beginning with a backslash (\\[ and \\( ) take
            // precedence over ordinary Markdown backslash escapes.
            MathSpan math = mathAt(text, i);
            if (math != null) {
                flushText(nodes, plain);
                nodes.add(node(Kind.MATH, text.substring(math.contentStart, math.end), math.display, null));
                i = math.end + math.closeLength;
                continue;
            }
            if (c == '\\' && i + 1 < text.length() && isEscapable(text.charAt(i + 1))) {
                plain.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int run = markerRun(text, i, '`');
                int close = findCodeClose(text, i + run, run);
                if (close >= 0) {
                    flushText(nodes, plain);
                    int closeRun = markerRun(text, close, '`');
                    nodes.add(node(Kind.CODE, normalizeCodeSpan(text.substring(i + run, close)), false, null));
                    i = close + closeRun;
                    continue;
                }
            }
            if (c == '[') {
                int labelEnd = findUnescaped(text, "](", i + 1);
                int destinationEnd = labelEnd < 0 ? -1 : findUnescaped(text, ")", labelEnd + 2);
                if (labelEnd > i + 1 && destinationEnd > labelEnd + 2) {
                    flushText(nodes, plain);
                    String destination = text.substring(labelEnd + 2, destinationEnd);
                    nodes.add(node(Kind.LINK, destination, false,
                            parse(text.substring(i + 1, labelEnd), depth + 1)));
                    i = destinationEnd + 1;
                    continue;
                }
            }
            String delimiter = delimiterAt(text, i);
            if (delimiter != null) {
                if (delimiter.length() > 3 || (delimiter.charAt(0) == '~' && delimiter.length() != 2)) {
                    plain.append(delimiter);
                    i += delimiter.length();
                    continue;
                }
                int close = findDelimiterClose(text, i + delimiter.length(), delimiter);
                if (close > i + delimiter.length()) {
                    flushText(nodes, plain);
                    List<Node> children = parse(text.substring(i + delimiter.length(), close), depth + 1);
                    if (delimiter.length() == 3) {
                        nodes.add(node(Kind.STRONG, "", false,
                                Collections.singletonList(node(Kind.EMPHASIS, "", false, children))));
                    } else {
                        Kind kind = delimiter.length() == 2 && !"~~".equals(delimiter)
                                ? Kind.STRONG : "~~".equals(delimiter) ? Kind.STRIKE : Kind.EMPHASIS;
                        nodes.add(node(kind, "", false, children));
                    }
                    i = close + delimiter.length();
                    continue;
                }
                plain.append(delimiter);
                i += delimiter.length();
                continue;
            }
            plain.append(c);
            i++;
        }
        flushText(nodes, plain);
        return Collections.unmodifiableList(nodes);
    }

    private static Node node(Kind kind, String value, boolean display, List<Node> children) {
        return new Node(kind, value, display, children == null ? Collections.emptyList() : children);
    }

    private static void flushText(List<Node> nodes, StringBuilder plain) {
        if (plain.length() == 0) return;
        nodes.add(node(Kind.TEXT, plain.toString(), false, null));
        plain.setLength(0);
    }

    private static String delimiterAt(String text, int index) {
        char first = text.charAt(index);
        if ((first == '*' || first == '_' || first == '~') && !isEscaped(text, index)) {
            int run = markerRun(text, index, first);
            if (run >= 3) {
                return (first != '~' && run == 3) ? text.substring(index, index + 3)
                        : text.substring(index, index + run);
            }
        }
        for (String marker : new String[]{"**", "__", "~~", "*", "_"}) {
            if (text.startsWith(marker, index) && !isEscaped(text, index)) return marker;
        }
        return null;
    }

    private static int findDelimiterClose(String text, int from, String marker) {
        int i = from;
        while (i < text.length()) {
            MathSpan math = mathAt(text, i);
            if (math != null) { i = math.end + math.closeLength; continue; }
            if (text.charAt(i) == '\\' && i + 1 < text.length()) { i += 2; continue; }
            if (text.charAt(i) == '`') {
                int run = markerRun(text, i, '`');
                int close = findCodeClose(text, i + run, run);
                if (close >= 0) { i = close + run; continue; }
            }
            if (text.startsWith(marker, i) && !isEscaped(text, i) &&
                    isExactDelimiterRun(text, i, marker)) return i;
            i++;
        }
        return -1;
    }

    private static boolean isExactDelimiterRun(String text, int index, String marker) {
        char c = marker.charAt(0);
        int run = markerRun(text, index, c);
        boolean startsRun = index == 0 || text.charAt(index - 1) != c;
        boolean endsRun = index + run >= text.length() || text.charAt(index + run) != c;
        return startsRun && endsRun && marker.length() == run && (c != '~' || run == 2);
    }

    private static MathSpan mathAt(String text, int index) {
        String open = null, close = null;
        boolean display = false;
        if (text.startsWith("\\[", index)) { open = "\\["; close = "\\]"; display = true; }
        else if (text.startsWith("\\(", index)) { open = "\\("; close = "\\)"; }
        else if (text.startsWith("$$", index)) { open = "$$"; close = "$$"; display = true; }
        else if (text.charAt(index) == '$' && !isEscaped(text, index)
                && (index + 1 >= text.length() || text.charAt(index + 1) != '$')) {
            open = "$"; close = "$";
        }
        if (open == null || ((open.charAt(0) == '$') && isEscaped(text, index))) return null;
        int contentStart = index + open.length();
        int end = findUnescaped(text, close, contentStart);
        if (end < 0) return null;
        return new MathSpan(index, contentStart, end, close.length(), display);
    }

    private static int findUnescaped(String text, String needle, int from) {
        int index = text.indexOf(needle, from);
        while (index >= 0 && isEscaped(text, index)) index = text.indexOf(needle, index + needle.length());
        return index;
    }

    private static int findCodeClose(String text, int from, int runLength) {
        int index = from;
        while (index < text.length()) {
            int tick = text.indexOf('`', index);
            if (tick < 0) return -1;
            int run = markerRun(text, tick, '`');
            if (run == runLength && !isEscaped(text, tick)) return tick;
            index = tick + run;
        }
        return -1;
    }

    /** CommonMark padding normalization: trim one surrounding space when both are present. */
    private static String normalizeCodeSpan(String contents) {
        if (contents.length() >= 2 && contents.charAt(0) == ' '
                && contents.charAt(contents.length() - 1) == ' ') {
            boolean allSpaces = true;
            for (int i = 0; i < contents.length(); i++) {
                if (contents.charAt(i) != ' ') { allSpaces = false; break; }
            }
            if (!allSpaces) return contents.substring(1, contents.length() - 1);
        }
        return contents;
    }

    /** Width-only proxy for KaTeX inline math in the paper paginator's estimator. */
    static String mathWidthProxy(String tex) {
        float atoms = 0f;
        StringBuilder rest = new StringBuilder();
        for (int index = 0; index < tex.length(); ) {
            char value = tex.charAt(index);
            if (value == '\\' && index + 1 < tex.length()
                    && Character.isLetter(tex.charAt(index + 1))) {
                int end = index + 1;
                while (end < tex.length() && Character.isLetter(tex.charAt(end))) end++;
                atoms += 1.2f;
                index = end;
                continue;
            }
            if (value == '{' || value == '}') {
                index++;
                continue;
            }
            if ((value == '_' || value == '^') && index + 1 < tex.length()) {
                atoms += 0.6f;
                index += 2;
                continue;
            }
            if (!Character.isWhitespace(value)) rest.append(value);
            index++;
        }
        atoms += rest.length();
        int width = Math.max(1, Math.round(atoms * 1.1f));
        StringBuilder proxy = new StringBuilder(width);
        for (int index = 0; index < width; index++) proxy.append('x');
        return proxy.toString();
    }

    private static int markerRun(String text, int index, char marker) {
        int end = index;
        while (end < text.length() && text.charAt(end) == marker) end++;
        return end - index;
    }

    private static boolean isEscaped(String text, int index) {
        int slashes = 0;
        for (int i = index - 1; i >= 0 && text.charAt(i) == '\\'; i--) slashes++;
        return (slashes & 1) != 0;
    }

    private static boolean isEscapable(char value) {
        return "\\`*_{}[]()#+-.!~$>".indexOf(value) >= 0;
    }

    private static void appendHtml(StringBuilder out, List<Node> nodes, boolean strong) {
        for (Node node : nodes) {
            switch (node.kind) {
                case TEXT:
                    out.append(escapeHtml(node.value));
                    break;
                case CODE:
                    out.append("<code>").append(escapeHtml(node.value)).append("</code>");
                    break;
                case MATH:
                    String tag = node.displayMath ? "div" : "span";
                    out.append('<').append(tag).append(" class=\"")
                            .append(node.displayMath ? "math-display" : "math-inline")
                            .append("\" data-display=\"").append(node.displayMath ? '1' : '0')
                            .append("\" data-tex=\"").append(escapeHtml(node.value.trim())).append('"');
                    if (strong) out.append(" data-bold=\"1\"");
                    out.append("></").append(tag).append('>');
                    break;
                case STRONG:
                    out.append("<strong class=\"md-strong\">");
                    appendHtml(out, node.children, true);
                    out.append("</strong>");
                    break;
                case EMPHASIS:
                    out.append("<em>"); appendHtml(out, node.children, strong); out.append("</em>");
                    break;
                case STRIKE:
                    out.append("<del>"); appendHtml(out, node.children, strong); out.append("</del>");
                    break;
                case LINK:
                    out.append("<span class=\"md-link\" data-destination=\"")
                            .append(escapeHtml(node.value)).append("\">");
                    appendHtml(out, node.children, strong);
                    out.append("</span>");
                    break;
            }
        }
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
