package com.padnote.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lossless renderer-fragment pagination for the supported Markdown pipe-table subset.
 * A continuation row currently splits one over-height plain cell at a time. If
 * another cell in that same projected row still prevents a fit, the table is
 * rejected without changing its canonical source instead of attempting a
 * coordinated multi-cell split.
 */
final class MarkdownTablePagination {
    private static final int MAX_EXPANSION_ROWS = 4096;
    private static final int MAX_SPLIT_ROW_CHARACTERS = 16_384;
    private static final long MAX_SPLIT_WORK_UNITS = 1_000_000L;
    static final String EXPANSION_LIMIT_REASON = "table pagination safety budget exceeded";

    interface HeightEstimator {
        float estimate(String markdown);
    }

    enum Status { NOT_TABLE, FITS, PAGINATED, PAGE_LIMIT, UNSUPPORTED }

    static final class Result {
        final Status status;
        final List<String> fragments;
        final String reason;

        private Result(Status status, List<String> fragments, String reason) {
            this.status = status;
            this.fragments = Collections.unmodifiableList(new ArrayList<>(fragments));
            this.reason = reason;
        }

        static Result of(Status status, List<String> fragments, String reason) {
            return new Result(status, fragments, reason);
        }
    }

    private static final class Row {
        final List<String> cells;
        final String prefix;
        final String suffix;
        final boolean leadingPipe;
        final boolean trailingPipe;

        Row(List<String> cells, String prefix, String suffix,
            boolean leadingPipe, boolean trailingPipe) {
            this.cells = cells;
            this.prefix = prefix;
            this.suffix = suffix;
            this.leadingPipe = leadingPipe;
            this.trailingPipe = trailingPipe;
        }

        String serialize() {
            StringBuilder result = new StringBuilder(prefix);
            if (leadingPipe) result.append('|');
            for (int index = 0; index < cells.size(); index++) {
                result.append(cells.get(index));
                if (index + 1 < cells.size() || trailingPipe) result.append('|');
            }
            result.append(suffix);
            return result.toString();
        }
    }

    private static final class ParsedRow {
        final Row row;
        final boolean valid;

        ParsedRow(Row row, boolean valid) {
            this.row = row;
            this.valid = valid;
        }
    }

    private MarkdownTablePagination() { }

    static Result paginate(String source, float capacity, HeightEstimator estimator) {
        return paginate(source, capacity, 500, estimator);
    }

    static boolean isTableStart(String header, String delimiter) {
        if (header == null || delimiter == null || header.indexOf('|') < 0) return false;
        ParsedRow parsedHeader = parseRow(header);
        ParsedRow parsedDelimiter = parseRow(delimiter);
        return parsedHeader.valid && parsedDelimiter.valid &&
                parsedHeader.row.cells.size() >= 2 &&
                parsedHeader.row.cells.size() == parsedDelimiter.row.cells.size() &&
                validDelimiter(parsedDelimiter.row.cells);
    }

    static int columnCount(String rowSource) {
        ParsedRow row = parseRow(rowSource == null ? "" : rowSource);
        return row.valid ? row.row.cells.size() : 0;
    }

    static boolean isTableRow(String rowSource, int expectedColumns) {
        if (rowSource == null || expectedColumns < 2) return false;
        ParsedRow parsed = parseRow(rowSource);
        return parsed.valid && parsed.row.cells.size() == expectedColumns;
    }

    static Result paginate(String source, float capacity, int maximumPageGroups,
                           HeightEstimator estimator) {
        if (source == null || estimator == null || !Float.isFinite(capacity) || capacity <= 0f) {
            return Result.of(Status.UNSUPPORTED, Collections.<String>emptyList(),
                    "invalid table pagination inputs");
        }
        List<String> lines = splitLines(source);
        int first = firstNonEmpty(lines, 0);
        if (first < 0 || first + 1 >= lines.size()) {
            return Result.of(Status.NOT_TABLE, Collections.<String>emptyList(), null);
        }
        int second = firstNonEmpty(lines, first + 1);
        if (second < 0 || lines.get(first).indexOf('|') < 0 ||
                !looksLikeDelimiter(lines.get(second))) {
            return Result.of(Status.NOT_TABLE, Collections.<String>emptyList(), null);
        }
        if (estimator.estimate(source) <= capacity) {
            return Result.of(Status.FITS, Collections.singletonList(source), null);
        }
        if (maximumPageGroups <= 0) {
            return Result.of(Status.PAGE_LIMIT, Collections.<String>emptyList(),
                    "table pagination exceeds the remaining page limit");
        }

        ParsedRow parsedHeader = parseRow(lines.get(first));
        ParsedRow parsedDelimiter = parseRow(lines.get(second));
        if (!parsedHeader.valid || !parsedDelimiter.valid ||
                parsedHeader.row.cells.size() < 2 ||
                parsedHeader.row.cells.size() != parsedDelimiter.row.cells.size() ||
                !validDelimiter(parsedDelimiter.row.cells)) {
            return Result.of(Status.UNSUPPORTED, Collections.<String>emptyList(),
                    "unsupported table header or alignment row");
        }

        List<Row> body = new ArrayList<>();
        for (int index = second + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.trim().isEmpty()) {
                if (index == lines.size() - 1) continue;
                return Result.of(Status.UNSUPPORTED, Collections.<String>emptyList(),
                        "blank line separates table blocks");
            }
            if (body.size() >= MAX_EXPANSION_ROWS) {
                return Result.of(Status.PAGE_LIMIT, Collections.<String>emptyList(),
                        EXPANSION_LIMIT_REASON);
            }
            ParsedRow parsed = parseRow(line);
            if (!parsed.valid || parsed.row.cells.size() != parsedHeader.row.cells.size()) {
                return Result.of(Status.UNSUPPORTED, Collections.<String>emptyList(),
                        "table row cannot be parsed without changing columns");
            }
            body.add(parsed.row);
        }
        if (body.isEmpty()) {
            return Result.of(Status.UNSUPPORTED, Collections.<String>emptyList(),
                    "oversized table has no pageable body rows");
        }

        Row header = parsedHeader.row;
        Row delimiter = parsedDelimiter.row;
        List<String> fragments = new ArrayList<>();
        List<Row> group = new ArrayList<>();
        int expandedRowCount = 0;
        for (Row row : body) {
            List<Row> split = splitOversizedRow(header, delimiter, row, capacity, estimator,
                    MAX_EXPANSION_ROWS - expandedRowCount);
            if (split != null && split.isEmpty()) {
                return Result.of(Status.PAGE_LIMIT, Collections.<String>emptyList(),
                        EXPANSION_LIMIT_REASON);
            }
            if (split == null) {
                return Result.of(Status.UNSUPPORTED, Collections.<String>emptyList(),
                        "a table cell cannot be safely split within one page");
            }
            expandedRowCount += split.size();
            for (Row expandedRow : split) {
                List<Row> candidate = new ArrayList<>(group);
                candidate.add(expandedRow);
                String candidateMarkdown = tableMarkdown(header, delimiter, candidate);
                if (estimator.estimate(candidateMarkdown) <= capacity) {
                    group.add(expandedRow);
                    continue;
                }
                if (group.isEmpty()) {
                    return Result.of(Status.UNSUPPORTED, Collections.<String>emptyList(),
                            "table header and one body row cannot fit within one page");
                }
                fragments.add(tableMarkdown(header, delimiter, group));
                if (fragments.size() >= maximumPageGroups) {
                    return Result.of(Status.PAGE_LIMIT, Collections.<String>emptyList(),
                            "table pagination exceeds the remaining page limit");
                }
                group = new ArrayList<>();
                group.add(expandedRow);
                String firstRowGroup = tableMarkdown(header, delimiter, group);
                if (estimator.estimate(firstRowGroup) > capacity) {
                    return Result.of(Status.UNSUPPORTED, Collections.<String>emptyList(),
                            "table header and one body row cannot fit within one page");
                }
            }
        }
        if (!group.isEmpty()) {
            if (fragments.size() >= maximumPageGroups) {
                return Result.of(Status.PAGE_LIMIT, Collections.<String>emptyList(),
                        "table pagination exceeds the remaining page limit");
            }
            fragments.add(tableMarkdown(header, delimiter, group));
        }
        return Result.of(Status.PAGINATED, fragments, null);
    }

    private static List<Row> splitOversizedRow(Row header, Row delimiter, Row original,
                                                float capacity, HeightEstimator estimator,
                                                int remainingRowBudget) {
        if (remainingRowBudget <= 0) return Collections.emptyList();
        List<List<String>> piecesByCell = new ArrayList<>();
        List<Boolean> splittableByCell = new ArrayList<>();
        int rowCharacters = 0;
        for (String cell : original.cells) {
            rowCharacters += cell.length();
            if (rowCharacters > MAX_SPLIT_ROW_CHARACTERS) return Collections.emptyList();
            List<String> pieces = new ArrayList<>();
            pieces.add(cell);
            piecesByCell.add(pieces);
            splittableByCell.add(safeToSplitCell(cell));
        }
        int rowIndex = 0;
        int rowCount = 1;
        int safety = 0;
        long splitWork = 0L;
        while (safety++ <= TextFlow.MAX_SOURCE_LENGTH) {
            if (rowIndex >= rowCount) return buildProjectedRows(original, piecesByCell, rowCount);
            Row candidateAtRow = projectedRow(original, piecesByCell, rowIndex);
            if (estimator.estimate(tableMarkdown(header, delimiter,
                    Collections.singletonList(candidateAtRow))) <= capacity) {
                rowIndex++;
                continue;
            }
            long rowWork = 0L;
            for (List<String> cellPieces : piecesByCell) {
                if (rowIndex < cellPieces.size()) {
                    int length = cellPieces.get(rowIndex).length();
                    rowWork += (long) length * 2L;
                }
            }
            splitWork += rowWork;
            if (splitWork > MAX_SPLIT_WORK_UNITS) return Collections.emptyList();
            int chosenCell = -1;
            int chosenEnd = -1;
            int longest = -1;
            for (int cellIndex = 0; cellIndex < piecesByCell.size(); cellIndex++) {
                List<String> cellPieces = piecesByCell.get(cellIndex);
                if (rowIndex >= cellPieces.size()) continue;
                String value = cellPieces.get(rowIndex);
                if (value.length() <= longest || !splittableByCell.get(cellIndex)) continue;
                int end = largestFittingBoundary(header, delimiter, original, piecesByCell,
                        rowIndex, cellIndex, value, capacity, estimator);
                if (end > 0) {
                    chosenCell = cellIndex;
                    chosenEnd = end;
                    longest = value.length();
                }
            }
            if (chosenCell < 0) return null;
            List<String> chosenPieces = piecesByCell.get(chosenCell);
            String raw = chosenPieces.get(rowIndex);
            String head = raw.substring(0, chosenEnd);
            String tail = raw.substring(chosenEnd);
            if (head.isEmpty() || tail.isEmpty()) return null;
            chosenPieces.set(rowIndex, head);
            chosenPieces.add(rowIndex + 1, tail);
            rowCount = Math.max(rowCount, chosenPieces.size());
            if (rowCount > remainingRowBudget) return Collections.emptyList();
        }
        return null;
    }

    private static List<Row> buildProjectedRows(Row original,
                                                List<List<String>> piecesByCell,
                                                int rowCount) {
        List<Row> result = new ArrayList<>(rowCount);
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            result.add(projectedRow(original, piecesByCell, rowIndex));
        }
        return result;
    }

    private static int largestFittingBoundary(Row header, Row delimiter, Row original,
                                               List<List<String>> piecesByCell,
                                               int rowIndex, int cellIndex, String raw,
                                               float capacity, HeightEstimator estimator) {
        List<Integer> boundaries = safeBoundaries(raw);
        int low = 0;
        int high = boundaries.size() - 1;
        int best = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int boundary = boundaries.get(middle);
            String firstPart = raw.substring(0, boundary);
            Row candidate = projectedRowReplacing(original, piecesByCell, rowIndex,
                    cellIndex, firstPart);
            float height = estimator.estimate(tableMarkdown(header, delimiter,
                    Collections.singletonList(candidate)));
            if (height <= capacity) {
                best = boundary;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if (best == 0) return 0;
        int whitespace = 0;
        for (int boundary : boundaries) {
            if (boundary > best) break;
            if (Character.isWhitespace(raw.codePointBefore(boundary))) whitespace = boundary;
        }
        // Prefer a natural word boundary when it keeps a meaningful portion of the cell.
        if (whitespace >= Math.max(1, best / 2)) best = whitespace;
        return best;
    }

    private static Row projectedRow(Row original, List<List<String>> piecesByCell,
                                    int rowIndex) {
        List<String> cells = new ArrayList<>(piecesByCell.size());
        for (List<String> pieces : piecesByCell) {
            cells.add(rowIndex < pieces.size() ? pieces.get(rowIndex) : "");
        }
        return new Row(cells, original.prefix, original.suffix,
                original.leadingPipe, original.trailingPipe);
    }

    private static Row projectedRowReplacing(Row original, List<List<String>> piecesByCell,
                                             int rowIndex, int cellIndex,
                                             String replacement) {
        Row candidate = projectedRow(original, piecesByCell, rowIndex);
        List<String> cells = new ArrayList<>(candidate.cells);
        cells.set(cellIndex, replacement);
        return new Row(cells, candidate.prefix, candidate.suffix,
                candidate.leadingPipe, candidate.trailingPipe);
    }

    private static boolean safeToSplitCell(String cell) {
        if (cell.isEmpty()) return false;
        if (cell.matches("(?s).*&(?:#[0-9]+|#x[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]+);.*")) {
            return false;
        }
        if (cell.matches("(?is).*(?:\\b(?:https?|ftp)://|(?<![\\w@])www\\.|\\b[^\\s@]+@[^\\s@]+\\.[^\\s@]+).*")) {
            return false;
        }
        // Splitting inline syntax would turn a well-formed span into malformed
        // Markdown in one of the derived fragments. Preserve these cells intact.
        for (int index = 0; index < cell.length(); index++) {
            char value = cell.charAt(index);
            if (value == '\\' || value == '`' || value == '[' || value == ']' ||
                    value == '(' || value == ')' || value == '<' || value == '>' ||
                    value == '$' || value == '*' || value == '_' || value == '~') {
                return false;
            }
        }
        return !safeBoundaries(cell).isEmpty();
    }

    private static List<Integer> safeBoundaries(String value) {
        List<Integer> boundaries = new ArrayList<>();
        int index = 0;
        while (index < value.length()) {
            int cp = value.codePointAt(index);
            index += Character.charCount(cp);
            if (isRegionalIndicator(cp)) {
                if (index < value.length() &&
                        isRegionalIndicator(value.codePointAt(index))) {
                    int next = value.codePointAt(index);
                    index += Character.charCount(next);
                }
            }
            while (index < value.length()) {
                int next = value.codePointAt(index);
                if (isExtender(next)) {
                    index += Character.charCount(next);
                    continue;
                }
                if (next == 0x200D) {
                    index += Character.charCount(next);
                    if (index < value.length()) {
                        int joined = value.codePointAt(index);
                        index += Character.charCount(joined);
                        while (index < value.length() && isExtender(value.codePointAt(index))) {
                            int extender = value.codePointAt(index);
                            index += Character.charCount(extender);
                        }
                        continue;
                    }
                }
                break;
            }
            boundaries.add(index);
        }
        return boundaries;
    }

    private static boolean isExtender(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK ||
                type == Character.ENCLOSING_MARK || (cp >= 0xFE00 && cp <= 0xFE0F) ||
                (cp >= 0xE0100 && cp <= 0xE01EF) || (cp >= 0x1F3FB && cp <= 0x1F3FF) ||
                (cp >= 0xE0020 && cp <= 0xE007F);
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static List<String> splitLines(String source) {
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] values = normalized.split("\n", -1);
        List<String> lines = new ArrayList<>(values.length);
        Collections.addAll(lines, values);
        return lines;
    }

    private static int firstNonEmpty(List<String> lines, int start) {
        for (int index = start; index < lines.size(); index++) {
            if (!lines.get(index).trim().isEmpty()) return index;
        }
        return -1;
    }

    private static boolean looksLikeDelimiter(String line) {
        ParsedRow parsed = parseRow(line);
        if (!parsed.valid || parsed.row.cells.size() < 2) return false;
        for (String cell : parsed.row.cells) {
            if (!cell.trim().matches(":?-{3,}:?")) return false;
        }
        return true;
    }

    private static boolean validDelimiter(List<String> cells) {
        for (String cell : cells) {
            if (!cell.trim().matches(":?-{3,}:?")) return false;
        }
        return true;
    }

    private static ParsedRow parseRow(String line) {
        List<String> cells = new ArrayList<>();
        int firstNonWhitespace = 0;
        while (firstNonWhitespace < line.length() &&
                (line.charAt(firstNonWhitespace) == ' ' || line.charAt(firstNonWhitespace) == '\t')) {
            firstNonWhitespace++;
        }
        String prefix = line.substring(0, firstNonWhitespace);
        boolean leading = firstNonWhitespace < line.length() &&
                line.charAt(firstNonWhitespace) == '|';
        if (!leading) prefix = "";
        int start = leading ? firstNonWhitespace + 1 : 0;
        int codeRun = 0;
        int lastDelimiter = -1;
        for (int index = start; index < line.length(); ) {
            char value = line.charAt(index);
            if (value == '`') {
                // A backslash escape outside a code span makes this a literal
                // backtick. Inside a code span backslashes are ordinary text,
                // so they must not prevent a matching backtick run from closing.
                if (codeRun == 0 && isEscaped(line, index)) {
                    index++;
                    continue;
                }
                int end = index + 1;
                while (end < line.length() && line.charAt(end) == '`') end++;
                int run = end - index;
                if (codeRun == 0) codeRun = run;
                else if (codeRun == run) codeRun = 0;
                index = end;
                continue;
            }
            if (value == '|' && codeRun == 0 && !isEscaped(line, index)) {
                cells.add(line.substring(start, index));
                start = index + 1;
                lastDelimiter = index;
            }
            index++;
        }
        if (codeRun != 0) {
            return new ParsedRow(new Row(cells, prefix, "", leading, false), false);
        }
        boolean trailing = lastDelimiter >= 0 &&
                line.substring(lastDelimiter + 1).trim().isEmpty();
        String suffix = trailing ? line.substring(lastDelimiter + 1) : "";
        if (!trailing) cells.add(line.substring(start));
        return new ParsedRow(new Row(cells, prefix, suffix, leading, trailing), cells.size() > 1);
    }

    private static boolean isEscaped(String value, int index) {
        int slashes = 0;
        for (int cursor = index - 1; cursor >= 0 && value.charAt(cursor) == '\\'; cursor--) {
            slashes++;
        }
        return (slashes & 1) == 1;
    }

    private static String tableMarkdown(Row header, Row delimiter, List<Row> body) {
        StringBuilder result = new StringBuilder(header.serialize()).append('\n')
                .append(delimiter.serialize());
        for (Row row : body) result.append('\n').append(row.serialize());
        return result.toString();
    }
}
