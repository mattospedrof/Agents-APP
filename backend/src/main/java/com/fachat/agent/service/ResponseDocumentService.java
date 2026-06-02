package com.fachat.agent.service;

import com.fachat.agent.dto.AssistantBlock;
import com.fachat.agent.dto.AssistantDocument;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.Extension;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ResponseDocumentService {
    private static final Pattern EMPTY_TABLE_NOISE = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final Pattern TABLE_LINE = Pattern.compile("^\\s*\\|?.*\\|.*\\|?\\s*$");
    private static final int CACHE_LIMIT = 128;

    private final Parser parser;
    private final Map<String, DocumentBuildResult> documentCache = java.util.Collections.synchronizedMap(
        new LinkedHashMap<String, DocumentBuildResult>(CACHE_LIMIT, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, DocumentBuildResult> eldest) {
                return size() > CACHE_LIMIT;
            }
        }
    );

    public record DocumentBuildResult(
        AssistantDocument document,
        long documentMs,
        long repairMs,
        boolean cached
    ) {
    }

    public ResponseDocumentService() {
        this.parser = Parser.builder()
            .extensions(List.<Extension>of(org.commonmark.ext.gfm.tables.TablesExtension.create()))
            .build();
    }

    public AssistantDocument toDocument(String rawContent) {
        return toDocumentWithMetrics(rawContent).document();
    }

    public DocumentBuildResult toDocumentWithMetrics(String rawContent) {
        long started = System.nanoTime();
        if (rawContent == null || rawContent.isBlank()) {
            return new DocumentBuildResult(AssistantDocument.of(List.of()), elapsedMs(started), 0L, false);
        }

        DocumentBuildResult cached = documentCache.get(rawContent);
        if (cached != null) {
            return new DocumentBuildResult(cached.document(), elapsedMs(started), 0L, true);
        }

        long repairStarted = System.nanoTime();
        boolean needsRepair = needsMarkdownRepair(rawContent);
        String normalized = needsRepair ? normalizeForParsing(rawContent) : rawContent;
        long repairMs = needsRepair ? elapsedMs(repairStarted) : 0L;
        List<AssistantBlock> blocks = new ArrayList<>();
        appendContentSegments(normalized, blocks);

        DocumentBuildResult result = new DocumentBuildResult(
            AssistantDocument.of(blocks),
            elapsedMs(started),
            repairMs,
            false
        );
        documentCache.put(rawContent, result);
        return result;
    }

    private boolean needsMarkdownRepair(String content) {
        return content.contains("|")
            || content.contains("###")
            || content.contains("---")
            || content.matches("(?s).*\\d+\\.\\S.*")
            || content.matches("(?s).*[-*â€¢]\\S.*")
            || content.matches("(?s).*[.!?:][#*\\d].*")
            || content.contains("<br");
    }

    private void appendContentSegments(String content, List<AssistantBlock> blocks) {
        StringBuilder markdownBuffer = new StringBuilder();
        List<String> tableBuffer = new ArrayList<>();
        List<String> hyphenTableBuffer = new ArrayList<>();
        boolean insideCodeFence = false;

        for (String line : content.split("\\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                flushTable(tableBuffer, blocks);
                tableBuffer.clear();
                flushHyphenTable(hyphenTableBuffer, blocks);
                hyphenTableBuffer.clear();
                markdownBuffer.append(line).append('\n');
                insideCodeFence = !insideCodeFence;
                continue;
            }

            if (!insideCodeFence && looksLikeLooseTableLine(line)) {
                flushMarkdown(markdownBuffer, blocks);
                markdownBuffer.setLength(0);
                flushHyphenTable(hyphenTableBuffer, blocks);
                hyphenTableBuffer.clear();
                tableBuffer.add(line);
                continue;
            }

            if (!insideCodeFence && looksLikeHyphenTableLine(line)) {
                flushMarkdown(markdownBuffer, blocks);
                markdownBuffer.setLength(0);
                flushTable(tableBuffer, blocks);
                tableBuffer.clear();
                hyphenTableBuffer.add(line);
                continue;
            }

            flushTable(tableBuffer, blocks);
            tableBuffer.clear();
            flushHyphenTable(hyphenTableBuffer, blocks);
            hyphenTableBuffer.clear();
            markdownBuffer.append(line).append('\n');
        }

        flushTable(tableBuffer, blocks);
        flushHyphenTable(hyphenTableBuffer, blocks);
        flushMarkdown(markdownBuffer, blocks);
    }

    private void flushMarkdown(StringBuilder markdownBuffer, List<AssistantBlock> blocks) {
        String markdown = markdownBuffer.toString().trim();
        if (markdown.isBlank()) {
            return;
        }
        Node document = parser.parse(markdown);
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            appendBlock(node, blocks);
        }
    }

    private void flushTable(List<String> tableLines, List<AssistantBlock> blocks) {
        if (tableLines.isEmpty()) {
            return;
        }
        AssistantBlock table = toLooseTableBlock(tableLines);
        if (table != null) {
            blocks.add(table);
            return;
        }
        if (appendPipeTableFallback(tableLines, blocks)) {
            return;
        }
        List<String> fallbackItems = tableLinesToFallbackItems(tableLines);
        if (!fallbackItems.isEmpty()) {
            blocks.add(AssistantBlock.list("bulletList", fallbackItems));
        }
    }

    private void flushHyphenTable(List<String> tableLines, List<AssistantBlock> blocks) {
        if (tableLines.isEmpty()) {
            return;
        }
        if (appendHyphenTableFallback(tableLines, blocks)) {
            return;
        }

        for (String line : tableLines) {
            String text = sanitizeInlineText(line);
            if (!text.isBlank()) {
                blocks.add(AssistantBlock.paragraph(text));
            }
        }
    }

    private void appendBlock(Node node, List<AssistantBlock> blocks) {
        if (node instanceof Heading heading) {
            String text = sanitizeInlineText(markdownText(heading));
            if (!text.isBlank()) {
                blocks.add(AssistantBlock.heading(heading.getLevel(), text));
            }
            return;
        }

        if (node instanceof Paragraph paragraph) {
            appendParagraphOrTableFallback(paragraph, blocks);
            return;
        }

        if (node instanceof BulletList list) {
            appendList("bulletList", list, blocks);
            return;
        }

        if (node instanceof OrderedList list) {
            appendList("orderedList", list, blocks);
            return;
        }

        if (node instanceof FencedCodeBlock codeBlock) {
            blocks.add(AssistantBlock.codeBlock(
                safeText(codeBlock.getInfo()).split("\\s+")[0],
                codeBlock.getLiteral() == null ? "" : codeBlock.getLiteral()
            ));
            return;
        }

        if (node instanceof IndentedCodeBlock codeBlock) {
            blocks.add(AssistantBlock.codeBlock("", codeBlock.getLiteral() == null ? "" : codeBlock.getLiteral()));
            return;
        }

        if (node instanceof TableBlock tableBlock) {
            AssistantBlock table = toTableBlock(tableBlock);
            if (table != null) {
                blocks.add(table);
            }
            return;
        }

        if (node instanceof BlockQuote quote) {
            String text = sanitizeInlineText(markdownText(quote));
            if (!text.isBlank()) {
                blocks.add(AssistantBlock.quote(text));
            }
            return;
        }

        if (node instanceof ThematicBreak) {
            blocks.add(AssistantBlock.horizontalRule());
        }
    }

    private void appendParagraphOrTableFallback(Paragraph paragraph, List<AssistantBlock> blocks) {
        String text = markdownText(paragraph).trim();
        if (text.isBlank()) {
            return;
        }

        List<String> lines = text.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
        if (lines.stream().anyMatch(this::looksLikeLooseTableLine)) {
            AssistantBlock table = toLooseTableBlock(lines);
            if (table != null) {
                blocks.add(table);
                return;
            }
            List<String> fallbackItems = tableLinesToFallbackItems(lines);
            if (!fallbackItems.isEmpty()) {
                blocks.add(AssistantBlock.list("bulletList", fallbackItems));
                return;
            }
        }

        if (appendHyphenTableFallback(lines, blocks)) {
            return;
        }

        String sanitizedText = sanitizeInlineText(text);
        if (!sanitizedText.isBlank()) {
            blocks.add(AssistantBlock.paragraph(sanitizedText));
        }
    }

    private boolean appendHyphenTableFallback(List<String> lines, List<AssistantBlock> blocks) {
        if (lines.size() < 2 || !looksLikeHyphenTableHeader(lines.get(0))) {
            return false;
        }

        List<String> headerCells = splitHyphenCells(lines.get(0));
        if (headerCells.size() < 3) {
            return false;
        }

        String heading = sanitizeInlineText(headerCells.get(0));
        List<String> items = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            List<String> cells = splitHyphenCells(line);
            if (cells.size() < 2) {
                continue;
            }

            String subject = sanitizeInlineText(cells.get(0));
            List<String> details = cells.subList(1, cells.size()).stream()
                .map(this::sanitizeInlineText)
                .filter(cell -> !cell.isBlank())
                .toList();
            if (!subject.isBlank() && !details.isEmpty()) {
                items.add(subject + ": " + String.join("; ", details));
            }
        }

        if (heading.isBlank() || items.isEmpty()) {
            return false;
        }

        blocks.add(AssistantBlock.heading(2, heading));
        blocks.add(AssistantBlock.list("bulletList", items));
        return true;
    }

    private boolean appendPipeTableFallback(List<String> lines, List<AssistantBlock> blocks) {
        List<String> contentLines = lines.stream()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .filter(line -> !EMPTY_TABLE_NOISE.matcher(line).matches())
            .filter(line -> !line.startsWith("- |"))
            .filter(line -> !line.startsWith("* |"))
            .filter(line -> !line.startsWith("\u2022 |"))
            .toList();

        if (contentLines.size() < 2) {
            return false;
        }

        List<String> columns = splitCells(contentLines.get(0));
        if (columns.size() < 2) {
            return false;
        }

        List<String> items = new ArrayList<>();
        for (String line : contentLines.subList(1, contentLines.size())) {
            List<String> cells = splitCells(line);
            if (cells.size() < 2) {
                continue;
            }

            String subject = sanitizeInlineText(cells.get(0));
            List<String> details = cells.subList(1, cells.size()).stream()
                .map(this::sanitizeInlineText)
                .filter(cell -> !cell.isBlank())
                .toList();
            if (!subject.isBlank() && !details.isEmpty()) {
                items.add(subject + ": " + String.join("; ", details));
            }
        }

        if (items.isEmpty()) {
            return false;
        }

        blocks.add(AssistantBlock.heading(2, tableFallbackHeading(columns)));
        blocks.add(AssistantBlock.list("bulletList", items));
        return true;
    }

    private String tableFallbackHeading(List<String> columns) {
        String first = columns.isEmpty() ? "" : sanitizeInlineText(columns.get(0));
        String normalized = first.toLowerCase(java.util.Locale.ROOT);
        if (first.length() >= 8
            && (normalized.contains("principais")
                || normalized.contains("impactos")
                || normalized.contains("efeitos")
                || normalized.contains("diferen")
                || normalized.contains("compar"))) {
            return first;
        }
        return "Dados da tabela";
    }

    private boolean looksLikeHyphenTableHeader(String line) {
        List<String> cells = splitHyphenCells(line);
        if (cells.size() < 3) {
            return false;
        }
        String firstCell = sanitizeInlineText(cells.get(0));
        return !firstCell.isBlank()
            && firstCell.length() >= 6
            && cells.subList(1, cells.size()).stream().allMatch(cell -> sanitizeInlineText(cell).length() >= 3);
    }

    private boolean looksLikeHyphenTableLine(String line) {
        return splitHyphenCells(line).size() >= 3;
    }

    private List<String> splitHyphenCells(String line) {
        String clean = safeText(line).trim();
        if (clean.isBlank()) {
            return List.of();
        }
        return List.of(clean.split("\\s+[-\\u2013\\u2014]\\s+")).stream()
            .map(this::sanitizeInlineText)
            .filter(cell -> !cell.isBlank())
            .toList();
    }

    private void appendList(String type, Node list, List<AssistantBlock> blocks) {
        List<String> items = new ArrayList<>();
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) {
                continue;
            }
            String text = sanitizeInlineText(markdownText(item));
            if (!text.isBlank() && !looksLikeLooseTableLine(text)) {
                items.add(text);
            }
        }
        if (!items.isEmpty()) {
            blocks.add(AssistantBlock.list(type, items));
        }
    }

    private AssistantBlock toTableBlock(TableBlock tableBlock) {
        List<List<String>> allRows = new ArrayList<>();
        collectTableRows(tableBlock, allRows);
        if (allRows.size() < 2) {
            return null;
        }

        List<String> columns = cleanCells(allRows.get(0));
        if (columns.size() < 2 || columns.stream().anyMatch(String::isBlank)) {
            return null;
        }

        List<List<String>> rows = new ArrayList<>();
        for (List<String> rawRow : allRows.subList(1, allRows.size())) {
            List<String> row = cleanCells(rawRow);
            if (row.size() != columns.size() || row.stream().allMatch(String::isBlank)) {
                return null;
            }
            rows.add(row);
        }

        return rows.isEmpty() ? null : AssistantBlock.table(columns, rows);
    }

    private void collectTableRows(Node node, List<List<String>> rows) {
        if (node instanceof TableRow row) {
            List<String> cells = new ArrayList<>();
            for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                if (cell instanceof TableCell) {
                    cells.add(sanitizeInlineText(markdownText(cell)).replace("|", ","));
                }
            }
            rows.add(cells);
            return;
        }

        if (node instanceof TableHead || node instanceof TableBody || node instanceof TableBlock) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                collectTableRows(child, rows);
            }
        }
    }

    private List<String> cleanCells(List<String> cells) {
        return cells.stream()
            .map(this::sanitizeInlineText)
            .filter(cell -> !cell.isBlank())
            .toList();
    }

    private boolean looksLikeLooseTableLine(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("- |") || trimmed.startsWith("* |") || trimmed.startsWith("• |")) {
            return true;
        }
        if (EMPTY_TABLE_NOISE.matcher(trimmed).matches()) {
            return true;
        }
        return TABLE_LINE.matcher(trimmed).matches() && trimmed.chars().filter(ch -> ch == '|').count() >= 2;
    }

    private List<String> tableLinesToFallbackItems(List<String> lines) {
        List<String> items = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.replaceFirst("^[-*•]\\s*", "").trim();
            if (EMPTY_TABLE_NOISE.matcher(trimmed).matches()) {
                continue;
            }
            if (!looksLikeLooseTableLine(trimmed)) {
                items.add(sanitizeInlineText(trimmed));
                continue;
            }
            List<String> cells = splitCells(trimmed);
            if (cells.size() >= 2) {
                items.add(String.join(" - ", cells));
            }
        }
        return items.stream().map(this::sanitizeInlineText).filter(item -> !item.isBlank()).toList();
    }

    private AssistantBlock toLooseTableBlock(List<String> lines) {
        List<String> tableLines = lines.stream()
            .filter(this::looksLikeLooseTableLine)
            .filter(line -> !line.trim().startsWith("- |"))
            .filter(line -> !line.trim().startsWith("* |"))
            .filter(line -> !line.trim().startsWith("• |"))
            .toList();

        if (tableLines.size() < 3 || !EMPTY_TABLE_NOISE.matcher(tableLines.get(1).trim()).matches()) {
            return null;
        }

        List<String> columns = splitCells(tableLines.get(0));
        if (columns.size() < 2 || columns.stream().anyMatch(String::isBlank)) {
            return null;
        }

        List<List<String>> rows = new ArrayList<>();
        for (String line : tableLines.subList(2, tableLines.size())) {
            if (EMPTY_TABLE_NOISE.matcher(line.trim()).matches()) {
                continue;
            }
            List<String> row = splitCells(line);
            if (row.size() != columns.size() || row.stream().allMatch(String::isBlank)) {
                return null;
            }
            rows.add(row);
        }

        return rows.isEmpty() ? null : AssistantBlock.table(columns, rows);
    }

    private List<String> splitCells(String line) {
        String clean = line.trim().replaceFirst("^\\|", "").replaceFirst("\\|$", "");
        return List.of(clean.split("\\|")).stream()
            .map(this::sanitizeInlineText)
            .filter(cell -> !cell.isBlank())
            .toList();
    }

    private String normalizeForParsing(String content) {
        String[] chunks = content.split("(?m)(```[\\s\\S]*?```)", -1);
        java.util.regex.Matcher matcher = Pattern.compile("(?m)```[\\s\\S]*?```").matcher(content);
        List<String> codeBlocks = new ArrayList<>();
        while (matcher.find()) {
            codeBlocks.add(matcher.group());
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < chunks.length; i++) {
            result.append(normalizeNonCodeMarkdown(chunks[i]));
            if (i < codeBlocks.size()) {
                result.append(codeBlocks.get(i));
            }
        }
        return result.toString();
    }

    private String normalizeNonCodeMarkdown(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\n", -1)) {
            if (looksLikeLooseTableLine(line) || looksLikeHyphenTableLine(line)) {
                lines.add(line);
                continue;
            }
            lines.add(normalizeNonTableLine(line));
        }
        return String.join("\n", lines);
    }

    private String normalizeNonTableLine(String line) {
        return line
            .replaceAll("\\[([^\\]]+)]\\(([^)\\s]+)\\)", "$1 ($2)")
            .replaceAll("`([^`]+)`", "$1")
            .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
            .replaceAll("__([^_]+)__", "$1")
            .replaceAll("(?<![\\p{L}\\p{N}])\\*([^*\\n]+)\\*(?=\\s|\\p{Punct}|$)", "$1")
            .replaceAll("(?<![\\p{L}\\p{N}])_([^_\\n]+)_(?=\\s|\\p{Punct}|$)", "$1")
            .replaceAll("([^\\n])\\s*(#{1,3})(\\d+\\.)", "$1\n\n$2 $3")
            .replaceAll("^\\s*(#{1,3})(\\d+\\.)", "$1 $2")
            .replaceAll("([^\\n])\\s*(#{1,3})\\s+([\\p{L}\\p{N}])", "$1\n\n$2 $3")
            .replaceAll("---\\s*(#{1,3})", "---\n\n$1")
            .replaceAll("(?Uiu)\\b(Defini\\u00e7\\u00e3o|Resumo r\\u00e1pido|Pr\\u00f3ximo passo)\\s*(?=\\p{Lu})", "$1: ")
            .replaceAll("(?Uiu)\\b(Conceito B\\u00e1sico|Diferen\\u00e7a para Mudan\\u00e7as Clim\\u00e1ticas)\\s*(?=\\p{Lu})", "$1: ")
            .replaceAll("(?iu)\\b(clim\\u00e1ticos)(s\\u00e3o)\\b", "$1 $2")
            .replaceAll("(\\*\\*[^*\\n]{1,80}:\\*\\*):", "$1")
            .replaceAll("([.!?])(?=Pr\\u00f3ximo passo:)", "$1\n\n")
            .replaceAll("(?iu)\\b(Principais tipos|Como se formam|Pontos chave)\\s*(?=\\d+[.)])", "$1\n")
            .replaceAll("(?<=\\p{Ll})(\\d+[.)]\\s*)", "\n$1")
            .replaceAll("\\*\\*([^*]{2,40})\\*\\*(?=\\p{L})", "**$1** ")
            .replaceAll("(\\d+\\.\\s+[^\\n]+?)(?=\\d+\\.\\s+)", "$1\n")
            .replaceAll("([-*•]\\s+[^\\n]+?)(?=[-*•]\\s+)", "$1\n");
    }

    private String markdownText(Node node) {
        InlineTextVisitor visitor = new InlineTextVisitor();
        node.accept(visitor);
        return visitor.text();
    }

    private String compact(String value) {
        return safeText(value)
            .replaceAll("[\\t\\x0B\\f\\r]+", " ")
            .replaceAll(" {2,}", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    String sanitizeInlineText(String input) {
        String clean = compact(input)
            .replaceAll("(?is)<[^>]+>", " ")
            .replaceAll("\\[([^\\]]+)]\\(([^)\\s]+)\\)", "$1 ($2)")
            .replaceAll("`([^`]+)`", "$1")
            .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
            .replaceAll("__([^_]+)__", "$1")
            .replaceAll("\\*([^*\\n]+)\\*", "$1")
            .replaceAll("_([^_\\n]+)_", "$1")
            .replaceAll("(?<!\\*)\\*(?=\\p{L})", "")
            .replaceAll("(?<=\\p{L})\\*(?!\\*)", "")
            .replaceAll("(?<=[.!?])(?=\\p{Lu})", " ")
            .replaceAll("(?<=:)(?=\\p{Lu})", " ")
            .replaceAll("(?<=\\p{Ll})\\.(?=\\p{Lu})", ". ")
            .replaceAll("(?<=\\p{Ll})!(?=\\p{Lu})", "! ")
            .replaceAll("(?<=\\p{Ll})\\?(?=\\p{Lu})", "? ")
            .replaceAll("(?Uiu)\\b(Defini\\u00e7\\u00e3o|Resumo r\\u00e1pido|Pr\\u00f3ximo passo)\\s*(?=\\p{Lu})", "$1: ")
            .replaceAll("(?Uiu)\\b(Conceito B\\u00e1sico|Diferen\\u00e7a para Mudan\\u00e7as Clim\\u00e1ticas)\\s*(?=\\p{Lu})", "$1: ")
            .replaceAll("(?iu)\\b(clim\\u00e1ticos)(s\\u00e3o)\\b", "$1 $2")
            .replaceAll("(?iu)(\\b[\\p{L}][\\p{L}\\s]{1,48}:):", "$1")
            .replaceAll("([.!?])(?=Pr\\u00f3ximo passo:)", "$1 ")
            .replaceAll("(?iu)\\b(Principais tipos|Como se formam|Pontos chave)\\s*(?=\\d+[.)])", "$1 ")
            .replaceAll("(?iu)\\b(de|da|do|das|dos|a|e|ate|at(?:e|\\u00e9)|entre|cada)\\s*(?=\\d)", "$1 ")
            .replaceAll("(?iu)(\\d)(meses|dias|anos|horas)\\b", "$1 $2")
            .replaceAll("\\s+", " ")
            .trim();

        return compact(clean);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private long elapsedMs(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static final class InlineTextVisitor extends AbstractVisitor {
        private final StringBuilder builder = new StringBuilder();

        @Override
        public void visit(Text text) {
            builder.append(text.getLiteral());
        }

        @Override
        public void visit(Code code) {
            builder.append('`').append(code.getLiteral()).append('`');
        }

        @Override
        public void visit(SoftLineBreak softLineBreak) {
            builder.append('\n');
        }

        @Override
        public void visit(HardLineBreak hardLineBreak) {
            builder.append('\n');
        }

        @Override
        public void visit(StrongEmphasis strongEmphasis) {
            builder.append("**");
            visitChildren(strongEmphasis);
            builder.append("**");
        }

        @Override
        public void visit(Emphasis emphasis) {
            builder.append('*');
            visitChildren(emphasis);
            builder.append('*');
        }

        @Override
        public void visit(Link link) {
            builder.append('[');
            visitChildren(link);
            builder.append("](").append(link.getDestination()).append(')');
        }

        @Override
        public void visit(HtmlInline htmlInline) {
            builder.append(' ');
        }

        @Override
        public void visit(HtmlBlock htmlBlock) {
            builder.append(' ');
        }

        String text() {
            return builder.toString();
        }
    }
}
