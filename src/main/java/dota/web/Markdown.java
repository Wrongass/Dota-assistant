package dota.web;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Ручные планы (*.md) → HTML. Сырые HTML-теги из файла экранируются: показываем только Markdown.
 * Относительные ссылки между .md-файлами переводятся в адреса сайта (linkResolver); ссылка без пары на сайте
 * превращается в обычный текст, чтобы не вести на 404.
 */
final class Markdown {

    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true).build();

    private Markdown() {
    }

    static String toHtml(String markdown) {
        return toHtml(markdown, UnaryOperator.identity());
    }

    /** linkResolver: href из файла → адрес на сайте, или null — убрать ссылку и оставить текст. */
    static String toHtml(String markdown, UnaryOperator<String> linkResolver) {
        Node doc = PARSER.parse(markdown);
        List<Link> links = new ArrayList<>();
        doc.accept(new AbstractVisitor() {
            @Override
            public void visit(Link link) {
                links.add(link);
                visitChildren(link);
            }
        });
        for (Link link : links) {
            String target = linkResolver.apply(link.getDestination());
            if (target != null) {
                link.setDestination(target);
                continue;
            }
            // заменить ссылку её содержимым
            Node child = link.getFirstChild();
            while (child != null) {
                Node next = child.getNext();
                link.insertBefore(child);
                child = next;
            }
            link.unlink();
        }
        return RENDERER.render(doc);
    }

    /**
     * Делит Markdown по заголовкам второго уровня: [0] — текст до первого «## » (заголовок пустой),
     * дальше пары {заголовок, тело раздела}. Строки внутри блоков кода не проверяются: в прогнозе их нет.
     */
    static List<String[]> sections(String markdown) {
        List<String[]> out = new ArrayList<>();
        String title = "";
        StringBuilder body = new StringBuilder();
        for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("## ")) {
                out.add(new String[]{title, body.toString()});
                title = line.substring(3).trim();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        out.add(new String[]{title, body.toString()});
        return out;
    }

    static String fileToHtml(Path file, UnaryOperator<String> linkResolver) throws IOException {
        return toHtml(Files.readString(file, StandardCharsets.UTF_8), linkResolver);
    }
}
