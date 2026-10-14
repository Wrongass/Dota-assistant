package dota.web;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Ручные планы (*.md) → HTML. Сырые HTML-теги из файла экранируются: показываем только Markdown. */
final class Markdown {

    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true).build();

    private Markdown() {
    }

    static String toHtml(String markdown) {
        Node doc = PARSER.parse(markdown);
        return RENDERER.render(doc);
    }

    static String fileToHtml(Path file) throws IOException {
        return toHtml(Files.readString(file, StandardCharsets.UTF_8));
    }
}
