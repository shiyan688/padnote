package com.padnote.android;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates render-only Markdown fragments without severing inline syntax scopes.
 * The authored TextFlow source is never rewritten; generated delimiters live only
 * in NoteTextBox.fragmentSource for the current pagination layout.
 */
final class MarkdownInlineFragmenter {
    private static final int MAX_SCOPE_DEPTH = 32;
    private static final Pattern[] LEGACY_INLINE_SCOPES = {
            Pattern.compile("\\*\\*([^*]+)\\*\\*"),
            Pattern.compile("__([^_]+)__"),
            Pattern.compile("~~([^~]+)~~"),
            Pattern.compile("\\*([^*]+)\\*"),
            Pattern.compile("_([^_]+)_")
    };
    private static final Pattern LEGACY_LINK = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");

    private MarkdownInlineFragmenter() { }

    private static class Range {
        final int start;
        final int end;
        final boolean forbidStart;
        final boolean forbidEnd;
        Range(int start, int end) { this(start, end, false, false); }
        Range(int start, int end, boolean forbidStart, boolean forbidEnd) {
            this.start = start; this.end = end;
            this.forbidStart = forbidStart; this.forbidEnd = forbidEnd;
        }
    }

    private static final class Scope extends Range {
        final String open;
        final String close;
        Scope(int start, int end, String open, String close) {
            super(start, end); this.open = open; this.close = close;
        }
    }

    private static final class Scan {
        final List<Scope> scopes = new ArrayList<>();
        final List<Range> protectedRanges = new ArrayList<>();
        final List<Range> literalMarkerRanges = new ArrayList<>();
    }

    static final class Plan {
        final String source;
        final int profile;
        final List<Scope> scopes;
        final Map<Scope, Delimiters> renderDelimiters;
        final List<Range> protectedRanges;
        final List<Range> protectedIntervals;
        final Set<Integer> forbiddenStarts;
        final Set<Integer> forbiddenEnds;
        final List<Range> literalMarkerRanges;

        private Plan(String source, int profile, Scan scan) {
            this.source = source;
            this.profile = profile;
            Collections.sort(scan.scopes, Comparator.comparingInt((Scope scope) -> scope.start)
                    .thenComparing((left, right) -> Integer.compare(right.end, left.end)));
            this.scopes = Collections.unmodifiableList(scan.scopes);
            this.renderDelimiters = Collections.unmodifiableMap(
                    selectDelimiters(this.scopes));
            Collections.sort(scan.protectedRanges, Comparator
                    .comparingInt((Range range) -> range.start)
                    .thenComparingInt(range -> range.end));
            this.protectedRanges = Collections.unmodifiableList(scan.protectedRanges);
            ArrayList<Range> intervals = new ArrayList<>();
            HashSet<Integer> starts = new HashSet<>();
            HashSet<Integer> ends = new HashSet<>();
            for (Range range : scan.protectedRanges) {
                if (range.forbidStart) starts.add(range.start);
                if (range.forbidEnd) ends.add(range.end);
                if (intervals.isEmpty() || range.start >= intervals.get(intervals.size() - 1).end) {
                    intervals.add(new Range(range.start, range.end));
                } else {
                    Range previous = intervals.remove(intervals.size() - 1);
                    intervals.add(new Range(previous.start, Math.max(previous.end, range.end)));
                }
            }
            this.protectedIntervals = Collections.unmodifiableList(intervals);
            this.forbiddenStarts = Collections.unmodifiableSet(starts);
            this.forbiddenEnds = Collections.unmodifiableSet(ends);
            this.literalMarkerRanges = Collections.unmodifiableList(scan.literalMarkerRanges);
        }
    }

    static final class Fragment {
        final String source;
        final String renderedSource;
        final String measurementSource;
        Fragment(String source, String renderedSource) {
            this(source, renderedSource, source);
        }
        Fragment(String source, String renderedSource, String measurementSource) {
            this.source = source;
            this.renderedSource = renderedSource;
            this.measurementSource = measurementSource == null ? source : measurementSource;
        }
    }

    private static final class Delimiters {
        final String open;
        final String close;
        Delimiters(String open, String close) { this.open = open; this.close = close; }
    }

    private static final class Edit {
        final int start;
        final int end;
        final String text;
        final int order;
        final int scopeStart;
        Edit(int start, int end, String text, int order, int scopeStart) {
            this.start = start; this.end = end; this.text = text;
            this.order = order; this.scopeStart = scopeStart;
        }
    }

    /**
     * Moves a proposed substring boundary away from delimiter tokens and opaque
     * code/math spans. The result may exceed maximum only when no legal grapheme
     * boundary exists before a protected token's end; that token must be measured
     * as a whole and rejected by normal layout limits if it cannot fit.
     */
    static Plan plan(String source, int profile) {
        String normalized = source == null ? "" : source;
        return new Plan(normalized, profile, scan(normalized, profile));
    }

    static int safeBoundary(String source, int start, int maximum, int profile) {
        return safeBoundary(plan(source, profile), start, maximum);
    }

    static int safeBoundary(Plan plan, int start, int maximum) {
        String source = plan.source;
        if (source == null || start < 0 || start >= source.length()) return start;
        int bounded = Math.max(start + 1, Math.min(source.length(), maximum));
        BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        graphemes.setText(source);
        int bestAtOrBeforeMaximum = -1;
        int firstAfterMaximum = -1;
        for (int boundary = graphemes.following(start); boundary != BreakIterator.DONE;
             boundary = graphemes.next()) {
            if (boundary <= start || !isLegalBoundary(plan, boundary)) continue;
            if (boundary <= bounded) bestAtOrBeforeMaximum = boundary;
            else {
                firstAfterMaximum = boundary;
                break;
            }
        }
        if (bestAtOrBeforeMaximum > start) return bestAtOrBeforeMaximum;
        if (firstAfterMaximum > start) return firstAfterMaximum;
        return source.length();
    }

    private static boolean isLegalBoundary(Plan plan, int boundary) {
        if (plan.forbiddenStarts.contains(boundary) || plan.forbiddenEnds.contains(boundary)) {
            return false;
        }
        int low = 0, high = plan.protectedIntervals.size() - 1, candidate = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Range range = plan.protectedIntervals.get(middle);
            if (range.start < boundary) { candidate = middle; low = middle + 1; }
            else high = middle - 1;
        }
        return candidate < 0 || plan.protectedIntervals.get(candidate).end <= boundary;
    }

    /** Prefers a legal word/sentence edge; only an unbroken token falls back to a grapheme cut. */
    static int preferredReadableBoundary(Plan plan, int start, int maximum) {
        int legal = safeBoundary(plan, start, maximum);
        if (legal <= start) return legal;
        BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        graphemes.setText(plan.source);
        for (int boundary = legal; boundary > start; boundary = graphemes.preceding(boundary)) {
            if (boundary == BreakIterator.DONE || boundary <= start) break;
            if (!isLegalBoundary(plan, boundary)) continue;
            int previous = plan.source.codePointBefore(boundary);
            if (Character.isWhitespace(previous) || isReadableSentenceEnd(previous)) return boundary;
        }
        return legal;
    }

    private static boolean isReadableSentenceEnd(int codePoint) {
        return codePoint == '.' || codePoint == '!' || codePoint == '?'
                || codePoint == '。' || codePoint == '！' || codePoint == '？'
                || codePoint == '；' || codePoint == ';';
    }

    /** Returns a renderer input for [start,end), balancing only scopes crossing its edges. */
    static String renderFragment(String source, int start, int end, int profile) {
        return renderFragment(plan(source, profile), start, end);
    }

    static String renderFragment(Plan plan, int start, int end) {
        return fragment(plan, start, end).renderedSource;
    }

    static Fragment fragment(Plan plan, int start, int end) {
        String source = plan.source;
        int from = Math.max(0, Math.min(start, source.length()));
        int to = Math.max(from, Math.min(end, source.length()));
        if (plan.profile != 2) {
            ArrayList<Scope> opening = new ArrayList<>();
            ArrayList<Scope> closing = new ArrayList<>();
            for (Scope scope : plan.scopes) {
                if (scope.start >= to) break;
                if (scope.start < from && scope.end > from) opening.add(scope);
                if (scope.start < to && scope.end > to) closing.add(scope);
            }
            Collections.sort(opening, Comparator.comparingInt(scope -> scope.start));
            Collections.sort(closing, (left, right) -> Integer.compare(right.start, left.start));
            StringBuilder rendered = new StringBuilder(to - from + 48);
            for (Scope scope : opening) rendered.append(scope.open);
            rendered.append(source, from, to);
            for (Scope scope : closing) rendered.append(scope.close);
            String raw = source.substring(from, to);
            return new Fragment(raw, rendered.toString(), raw);
        }

        Map<Scope, Delimiters> selected = plan.renderDelimiters;
        ArrayList<Edit> edits = new ArrayList<>();
        for (Scope scope : plan.scopes) {
            if (scope.start >= to) break;
            if (scope.end <= from) continue;
            Delimiters delimiters = selected.get(scope);
            if (scope.start < from) {
                edits.add(new Edit(from, from, delimiters.open, 1, scope.start));
            } else {
                edits.add(new Edit(scope.start, scope.start + scope.open.length(),
                        delimiters.open, 1, scope.start));
            }
            int closeStart = scope.end - scope.close.length();
            if (scope.end > to) {
                edits.add(new Edit(to, to, delimiters.close, 0, scope.start));
            } else {
                edits.add(new Edit(closeStart, scope.end, delimiters.close, 0, scope.start));
            }
        }
        for (Range literal : plan.literalMarkerRanges) {
            int left = Math.max(from, literal.start);
            int right = Math.min(to, literal.end);
            if (left < right) {
                StringBuilder escaped = new StringBuilder((right - left) * 2);
                for (int index = left; index < right; index++) {
                    escaped.append('\\').append(source.charAt(index));
                }
                edits.add(new Edit(left, right, escaped.toString(), 2, literal.start));
            }
        }
        Collections.sort(edits, Comparator.comparingInt((Edit edit) -> edit.start)
                .thenComparingInt(edit -> edit.order)
                .thenComparing((left, right) -> left.order == 0
                        ? Integer.compare(right.scopeStart, left.scopeStart)
                        : Integer.compare(left.scopeStart, right.scopeStart)));
        StringBuilder rendered = new StringBuilder(to - from + 64);
        int cursor = from;
        for (Edit edit : edits) {
            if (edit.start < cursor) continue;
            if (edit.start > cursor) rendered.append(source, cursor, edit.start);
            rendered.append(edit.text);
            cursor = Math.max(cursor, edit.end);
        }
        if (cursor < to) rendered.append(source, cursor, to);
        String raw = source.substring(from, to);
        String renderedSource = rendered.toString();
        return new Fragment(raw, renderedSource, visibleMeasurementText(renderedSource));
    }

    private static String visibleMeasurementText(String renderedSource) {
        StringBuilder visible = new StringBuilder(renderedSource.length());
        appendVisibleMeasurementText(MarkdownV2Parser.parseInline(renderedSource), visible);
        return visible.toString();
    }

    private static void appendVisibleMeasurementText(List<MarkdownV2Parser.Node> nodes,
                                                     StringBuilder visible) {
        for (MarkdownV2Parser.Node node : nodes) {
            switch (node.kind) {
                case TEXT:
                case CODE:
                    visible.append(node.value);
                    break;
                case MATH:
                    visible.append(MarkdownV2Parser.mathWidthProxy(node.value));
                    break;
                case STRONG:
                case EMPHASIS:
                case STRIKE:
                case LINK:
                    appendVisibleMeasurementText(node.children, visible);
                    break;
            }
        }
    }

    private static Map<Scope, Delimiters> selectDelimiters(List<Scope> scopes) {
        IdentityHashMap<Scope, Delimiters> selected = new IdentityHashMap<>();
        Deque<Scope> active = new ArrayDeque<>();
        for (Scope scope : scopes) {
            while (!active.isEmpty() && scope.start >= active.peekLast().end) active.removeLast();
            if (isLinkScope(scope)) {
                selected.put(scope, new Delimiters(scope.open, scope.close));
                active.addLast(scope);
                continue;
            }
            Scope parent = null;
            for (java.util.Iterator<Scope> iterator = active.descendingIterator(); iterator.hasNext();) {
                Scope possible = iterator.next();
                if (!isLinkScope(possible)) { parent = possible; break; }
            }
            Delimiters parentDelimiters = parent == null ? null : selected.get(parent);
            String marker = scope.open;
            Delimiters result;
            if ("***".equals(marker)) {
                String strong = chooseMarker("**", "__", parentDelimiters);
                String emphasis = strong.charAt(0) == '*' ? "_" : "*";
                result = new Delimiters(strong + emphasis, emphasis + strong);
            } else if ("**".equals(marker) || "__".equals(marker)) {
                String chosen = chooseMarker(marker, "**".equals(marker) ? "__" : "**",
                        parentDelimiters);
                result = new Delimiters(chosen, chosen);
            } else if ("*".equals(marker) || "_".equals(marker)) {
                String chosen = chooseMarker(marker, "*".equals(marker) ? "_" : "*",
                        parentDelimiters);
                result = new Delimiters(chosen, chosen);
            } else {
                result = new Delimiters(scope.open, scope.close);
            }
            selected.put(scope, result);
            active.addLast(scope);
        }
        return selected;
    }

    private static String chooseMarker(String preferred, String alternate,
                                       Delimiters parent) {
        if (parent == null || !touchesSameMarker(preferred, parent)) return preferred;
        return touchesSameMarker(alternate, parent) ? preferred : alternate;
    }

    private static boolean touchesSameMarker(String marker, Delimiters parent) {
        char first = marker.charAt(0);
        return parent.open.charAt(parent.open.length() - 1) == first
                || parent.close.charAt(0) == first;
    }

    private static boolean isLinkScope(Scope scope) {
        return "[".equals(scope.open) && scope.close.startsWith("](");
    }

    private static Scan scan(String source, int profile) {
        Scan result = new Scan();
        scanRange(source, 0, source.length(), 0, profile, result);
        return result;
    }

    private static void scanRange(String source, int from, int to, int depth,
                                  int profile, Scan result) {
        if (depth >= MAX_SCOPE_DEPTH) {
            // Match the renderer's bounded recursion: leave deeper syntax literal,
            // and keep it intact if pagination reaches into this opaque fallback.
            result.protectedRanges.add(new Range(from, to));
            return;
        }
        int index = from;
        while (index < to) {
            int mathEnd = mathEnd(source, index, to);
            if (mathEnd > index) {
                result.protectedRanges.add(new Range(index, mathEnd));
                index = mathEnd;
                continue;
            }
            char current = source.charAt(index);
            if (profile != 1 && current == '\\' && index + 1 < to) {
                result.protectedRanges.add(new Range(index, index + 2));
                index += 2;
                continue;
            }
            if (current == '`') {
                int run = runLength(source, index, to, '`');
                int close = findCodeClose(source, index + run, to, run);
                if (close >= 0) {
                    int end = close + run;
                    result.protectedRanges.add(new Range(index, end));
                    index = end;
                    continue;
                }
            }
            if (profile == 1 && current == '[') {
                Matcher link = LEGACY_LINK.matcher(source);
                link.region(index, to);
                if (link.lookingAt()) {
                    int labelStart = link.start(1), labelEnd = link.end(1);
                    int end = link.end();
                    if (containsMath(source, index, end)) {
                        index++;
                        continue;
                    }
                    String destination = link.group(2);
                    result.scopes.add(new Scope(index, end, "[", "](" + destination + ")"));
                    result.protectedRanges.add(new Range(index, labelStart, false, true));
                    result.protectedRanges.add(new Range(labelEnd, end, true, false));
                    scanRange(source, labelStart, labelEnd, depth + 1, profile, result);
                    index = end;
                    continue;
                }
            }
            if (current == '[' && profile != 1 && !isEscaped(source, index)) {
                int labelEnd = findUnescaped(source, "](", index + 1, to);
                int destinationEnd = labelEnd < 0 ? -1
                        : findUnescaped(source, ")", labelEnd + 2, to);
                if (labelEnd > index + 1 && destinationEnd > labelEnd + 2) {
                    int end = destinationEnd + 1;
                    String destination = source.substring(labelEnd + 2, destinationEnd);
                    result.scopes.add(new Scope(index, end, "[", "](" + destination + ")"));
                    result.protectedRanges.add(new Range(index, index + 1, false, true));
                    result.protectedRanges.add(new Range(labelEnd, end, true, false));
                    scanRange(source, index + 1, labelEnd, depth + 1, profile, result);
                    index = end;
                    continue;
                }
            }
            String delimiter = profile == 1 ? null : delimiterAt(source, index, to);
            LegacyScope legacy = profile == 1 ? legacyScopeAt(source, index, to) : null;
            if (legacy != null) {
                result.scopes.add(new Scope(index, legacy.end, legacy.open, legacy.close));
                int openEnd = index + legacy.open.length();
                if (openEnd < legacy.closeStart) {
                    int firstContentEnd = graphemeEnd(source, openEnd, legacy.closeStart);
                    result.protectedRanges.add(new Range(index, firstContentEnd, false, true));
                }
                result.protectedRanges.add(new Range(legacy.closeStart, legacy.end, true, false));
                scanRange(source, openEnd, legacy.closeStart, depth + 1, profile, result);
                index = legacy.end;
                continue;
            }
            if (profile == 1 && (current == '*' || current == '_' || current == '~')) {
                if (index == from || source.charAt(index - 1) != current) {
                    int literalEnd = index + runLength(source, index, to, current);
                    result.protectedRanges.add(new Range(index, literalEnd));
                }
                // Legacy regex replacement can match from inside a longer marker
                // run (for example ***x***), so scan each character for its exact
                // historical match while keeping cuts out of the entire run.
                index++;
                continue;
            }
            if (delimiter != null) {
                boolean supported = delimiter.length() <= 3
                        && !(delimiter.charAt(0) == '~' && delimiter.length() != 2);
                if (!supported) {
                    result.protectedRanges.add(new Range(index, index + delimiter.length()));
                    result.literalMarkerRanges.add(new Range(index, index + delimiter.length()));
                    index += delimiter.length();
                    continue;
                }
                int close = findDelimiterClose(source, index + delimiter.length(), to, delimiter);
                if (close > index + delimiter.length()) {
                    int end = close + delimiter.length();
                    result.scopes.add(new Scope(index, end, delimiter, delimiter));
                    int firstContentEnd = graphemeEnd(source, index + delimiter.length(), close);
                    result.protectedRanges.add(new Range(index, firstContentEnd, false, true));
                    result.protectedRanges.add(new Range(close, end, true, false));
                    scanRange(source, index + delimiter.length(), close, depth + 1,
                            profile, result);
                    index = end;
                    continue;
                }
                result.protectedRanges.add(new Range(index, index + delimiter.length()));
                result.literalMarkerRanges.add(new Range(index, index + delimiter.length()));
                index += delimiter.length();
                continue;
            }
            index++;
        }
    }

    private static String delimiterAt(String source, int index, int to) {
        char first = source.charAt(index);
        if (first != '*' && first != '_' && first != '~') return null;
        if (isEscaped(source, index)) return null;
        int run = runLength(source, index, to, first);
        if (run >= 3) return first != '~' && run == 3
                ? source.substring(index, index + 3) : source.substring(index, index + run);
        if (run == 2) return source.substring(index, index + 2);
        return source.substring(index, index + 1);
    }

    private static int findDelimiterClose(String source, int from, int to, String marker) {
        for (int index = from; index < to;) {
            int endMath = mathEnd(source, index, to);
            if (endMath > index) { index = endMath; continue; }
            char current = source.charAt(index);
            if (current == '\\' && index + 1 < to) { index += 2; continue; }
            if (current == '`') {
                int run = runLength(source, index, to, '`');
                int close = findCodeClose(source, index + run, to, run);
                if (close >= 0) { index = close + run; continue; }
            }
            if (source.startsWith(marker, index) && !isEscaped(source, index)
                    && exactRun(source, index, to, marker)) return index;
            index++;
        }
        return -1;
    }

    private static boolean exactRun(String source, int index, int to, String marker) {
        char c = marker.charAt(0);
        int run = runLength(source, index, to, c);
        return (index == 0 || source.charAt(index - 1) != c)
                && (index + run >= to || source.charAt(index + run) != c)
                && run == marker.length() && (c != '~' || run == 2);
    }

    private static int mathEnd(String source, int index, int to) {
        String open = null;
        String close = null;
        if (startsAt(source, index, "\\(", to)) { open = "\\("; close = "\\)"; }
        else if (startsAt(source, index, "\\[", to)) { open = "\\["; close = "\\]"; }
        else if (startsAt(source, index, "$$", to)) { open = "$$"; close = "$$"; }
        else if (source.charAt(index) == '$' && !isEscaped(source, index)
                && !startsAt(source, index, "$$", to)) { open = "$"; close = "$"; }
        if (open == null) return -1;
        int end = findUnescaped(source, close, index + open.length(), to);
        return end < 0 ? -1 : end + close.length();
    }

    private static boolean startsAt(String source, int index, String value, int to) {
        return index + value.length() <= to && source.startsWith(value, index);
    }

    private static int findCodeClose(String source, int from, int to, int runLength) {
        for (int index = from; index < to;) {
            int tick = source.indexOf('`', index);
            if (tick < 0 || tick >= to) return -1;
            int run = runLength(source, tick, to, '`');
            if (run == runLength && !isEscaped(source, tick)) return tick;
            index = tick + run;
        }
        return -1;
    }

    private static int findUnescaped(String source, String needle, int from, int to) {
        int index = source.indexOf(needle, from);
        while (index >= 0 && index + needle.length() <= to) {
            if (!isEscaped(source, index)) return index;
            index = source.indexOf(needle, index + needle.length());
        }
        return -1;
    }

    private static int runLength(String source, int index, int to, char marker) {
        int end = index;
        while (end < to && source.charAt(end) == marker) end++;
        return end - index;
    }

    private static boolean isEscaped(String source, int index) {
        int slashes = 0;
        for (int i = index - 1; i >= 0 && source.charAt(i) == '\\'; i--) slashes++;
        return (slashes & 1) != 0;
    }

    private static final class LegacyScope {
        final String open;
        final String close;
        final int closeStart;
        final int end;
        LegacyScope(String open, String close, int closeStart, int end) {
            this.open = open; this.close = close;
            this.closeStart = closeStart; this.end = end;
        }
    }

    private static LegacyScope legacyScopeAt(String source, int index, int limit) {
        for (Pattern pattern : LEGACY_INLINE_SCOPES) {
            Matcher matcher = pattern.matcher(source);
            matcher.region(index, limit);
            if (!matcher.lookingAt()) continue;
            String token = matcher.group();
            String marker;
            if (token.startsWith("**") || token.startsWith("__") || token.startsWith("~~")) {
                marker = token.substring(0, 2);
            } else {
                marker = token.substring(0, 1);
            }
            if (containsMath(source, index, matcher.end())) return null;
            return new LegacyScope(marker, marker, matcher.end() - marker.length(), matcher.end());
        }
        return null;
    }

    private static int graphemeEnd(String source, int start, int limit) {
        if (start >= limit) return start;
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(source);
        int end = iterator.following(start);
        return end == BreakIterator.DONE || end > limit ? Math.min(limit,
                start + Character.charCount(source.codePointAt(start))) : end;
    }

    private static int previousBoundary(BreakIterator iterator, int position) {
        int boundary = iterator.preceding(position);
        return boundary == BreakIterator.DONE ? 0 : boundary;
    }

    private static int nextBoundary(BreakIterator iterator, int position) {
        int boundary = iterator.following(position);
        return boundary == BreakIterator.DONE ? position : boundary;
    }

    private static boolean containsMath(String source, int start, int end) {
        for (int index = start; index < end;) {
            int math = mathEnd(source, index, end);
            if (math > index) return true;
            index++;
        }
        return false;
    }
}
