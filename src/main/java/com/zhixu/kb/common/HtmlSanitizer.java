package com.zhixu.kb.common;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/** Whitelist for the HTML produced by the Tiptap block editor. */
public final class HtmlSanitizer {
    private static final String BASE = "http://kb.invalid/";
    private static final Safelist SAFELIST = Safelist.none()
            .addTags("p", "br", "h1", "h2", "h3", "h4", "strong", "b", "em", "i", "u", "s", "del", "mark", "code", "pre",
                    "blockquote", "ul", "ol", "li", "hr", "a", "img", "div", "span",
                    "table", "colgroup", "col", "thead", "tbody", "tr", "th", "td")
            .addAttributes("a", "href", "target", "rel")
            .addAttributes("img", "src", "alt", "title", "width")
            .addAttributes("ol", "start")
            .addAttributes("ul", "data-type")
            .addAttributes("li", "data-type", "data-checked")
            .addAttributes("code", "class")
            .addAttributes("pre", "class")
            .addAttributes("div", "data-type")
            .addAttributes("th", "colspan", "rowspan", "colwidth")
            .addAttributes("td", "colspan", "rowspan", "colwidth")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https")
            .preserveRelativeLinks(true);
    private static final Document.OutputSettings OUTPUT = new Document.OutputSettings().prettyPrint(false);

    private HtmlSanitizer() {}

    public static String clean(String html) {
        return Jsoup.clean(html == null ? "" : html, BASE, SAFELIST, OUTPUT);
    }

    public static String plainText(String html) {
        return Jsoup.parse(html == null ? "" : html).text();
    }
}
