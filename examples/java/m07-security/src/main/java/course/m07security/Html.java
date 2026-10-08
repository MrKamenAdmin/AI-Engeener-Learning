package course.m07security;

import java.net.URI;
import java.util.List;

/**
 * HTML: ответ модели — недоверенный текст. В Spring MVC это делает шаблонизатор
 * (Thymeleaf th:text экранирует), здесь — вручную, чтобы было видно оба правила:
 * экранирование текста и проверка схемы URL в href.
 */
public final class Html {
    private Html() {}

    public record Source(String title, String url) {}

    public static String renderAnswer(String text, List<Source> sources) {
        var b = new StringBuilder("<div class=\"answer\">").append(escape(text)).append("</div>\n<ul>");
        for (Source s : sources) {
            b.append("<li><a href=\"").append(escape(safeUrl(s.url()))).append("\" rel=\"noopener nofollow\">")
                    .append(escape(s.title())).append("</a></li>");
        }
        return b.append("</ul>").toString();
    }

    /** Экранирует пять символов, значимых в тексте и в значениях атрибутов. */
    static String escape(String s) {
        var b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&#34;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** Только http(s): javascript:, data: и прочее заменяются на "#", как #ZgotmplZ в Go html/template. */
    static String safeUrl(String raw) {
        try {
            String scheme = URI.create(raw.strip()).getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")) ? raw : "#";
        } catch (IllegalArgumentException e) {
            return "#";
        }
    }
}
