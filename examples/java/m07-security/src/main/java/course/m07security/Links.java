package course.m07security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Markdown: эксфильтрация через ссылки и картинки. */
public final class Links {
    private Links() {}

    /**
     * Allowlist доменов, на которые ответ модели может ссылаться ("docs.example.com" разрешает
     * и поддомены). Только домены, которые вы контролируете: открытый редирект или прокси на
     * «доверенном» домене превращает его в канал утечки (так EchoLeak обошёл CSP M365 Copilot).
     */
    public record URLPolicy(List<String> hosts) {
        public boolean allowed(String raw) {
            // CommonMark декодирует HTML-сущности в адресе ссылки: h&#116;tps:// станет https://.
            raw = unescapeHtml(stripAngles(raw.strip()).strip());
            if (raw.isEmpty() || raw.matches("(?s).*[\\\\\\t\\r\\n ].*")) { // браузер читает \ как /: /\evil.io → //evil.io
                return false;
            }
            URI u;
            try {
                u = new URI(raw);
            } catch (URISyntaxException e) {
                return false;
            }
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    || u.getRawUserInfo() != null || u.getHost() == null) {
                return false; // относительные, //host, javascript:, data: и прочее — нет
            }
            String h = u.getHost().toLowerCase(Locale.ROOT);
            return hosts.stream().anyMatch(a -> h.equals(a) || h.endsWith("." + a));
        }
    }

    private static String stripAngles(String s) {
        int from = 0, to = s.length();
        while (from < to && (s.charAt(from) == '<' || s.charAt(from) == '>')) from++;
        while (to > from && (s.charAt(to - 1) == '<' || s.charAt(to - 1) == '>')) to--;
        return s.substring(from, to);
    }

    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]*);");
    private static final Map<String, String> NAMED = Map.of(
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'",
            "colon", ":", "sol", "/", "period", ".", "Tab", "\t", "NewLine", "\n");

    // ponytail: в JDK нет html.UnescapeString — декодируем числовые сущности и частые именованные,
    // а незнакомую именованную превращаем в пробел: такой адрес отклонится (fail-closed).
    static String unescapeHtml(String s) {
        return ENTITY.matcher(s).replaceAll(m -> {
            String e = m.group(1);
            String out;
            if (e.startsWith("#x") || e.startsWith("#X")) {
                out = Character.toString(Integer.parseInt(e.substring(2), 16));
            } else if (e.startsWith("#")) {
                int cp = Integer.parseInt(e.substring(1));
                out = cp <= Character.MAX_CODE_POINT ? Character.toString(cp) : " ";
            } else {
                out = NAMED.getOrDefault(e, " ");
            }
            return Matcher.quoteReplacement(out);
        });
    }

    private static final Pattern MD_INLINE = Pattern.compile("(!?)\\[([^\\]]*)\\]\\(\\s*(<[^>]*>|[^)\\s]*)[^)]*\\)"); // [t](url "title"), ![a](url)
    private static final Pattern MD_DEST = Pattern.compile("\\]\\(\\s*(<[^>]*>|[^)\\s]*)[^)]*\\)"); // любой ](url): вложенные [], \], [![a](x)](y)
    // [id]: url — и в цитате или списке (> [id]: …), и с адресом на следующей строке.
    private static final Pattern MD_REF_DEF = Pattern.compile(
            "^(?:[ \\t]*(?:>|[*+-]|\\d{1,9}[.)]))*[ \\t]*\\[(?:\\\\.|[^\\]\\\\])+\\]:[ \\t]*(?:\\n[ \\t>]*)?(<[^>]*>|\\S+).*$",
            Pattern.MULTILINE | Pattern.UNIX_LINES);
    private static final Pattern BARE_URL = Pattern.compile("\\bhttps?://[^\\s<>\"'()\\[\\]]+", Pattern.CASE_INSENSITIVE); // автоссылки и <img src=…>

    public record Stripped(String text, int removed) {}

    /**
     * Удаляет ссылки и картинки на домены вне allowlist; текст ссылки остаётся. Регэкспы по
     * markdown — эвристика: надёжнее проверять узлы Link и Image после разбора commonmark-java,
     * а CSP держит пропущенное.
     */
    public static Stripped strip(String md, URLPolicy p) {
        var n = new AtomicInteger();
        md = MD_INLINE.matcher(md).replaceAll(m -> {
            if (p.allowed(m.group(3))) {
                return Matcher.quoteReplacement(m.group());
            }
            n.incrementAndGet();
            return Matcher.quoteReplacement(m.group(1).equals("!") ? "[изображение удалено]" : m.group(2));
        });
        // Второй проход: то, что первый не разобрал или сам собрал из [![a](x)](y), — без адреса.
        md = MD_DEST.matcher(md).replaceAll(m -> {
            if (p.allowed(m.group(1))) {
                return Matcher.quoteReplacement(m.group());
            }
            n.incrementAndGet();
            return "]";
        });
        md = MD_REF_DEF.matcher(md).replaceAll(m -> {
            if (p.allowed(m.group(1))) {
                return Matcher.quoteReplacement(m.group());
            }
            n.incrementAndGet();
            return "";
        });
        md = BARE_URL.matcher(md).replaceAll(m -> {
            if (p.allowed(m.group())) {
                return Matcher.quoteReplacement(m.group());
            }
            n.incrementAndGet();
            return Matcher.quoteReplacement("[ссылка удалена]");
        });
        return new Stripped(md, n.get());
    }
}
