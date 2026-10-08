package course.m11data;

import java.util.ArrayList;
import java.util.List;

/** Markdown-сплиттер модуля 11: секции по заголовкам, блоки не режутся. */
public final class MdSplit {
    private MdSplit() {}

    /**
     * Кусок markdown с путём заголовков. Эмбеддим heading + "\n\n" + text:
     * путь заголовков даёт чанку контекст, которого нет в самом тексте.
     */
    public record Chunk(String heading, String text) {}

    /**
     * Режет markdown по ATX-заголовкам (# … ######), затем пакует блоки секции (абзацы,
     * списки, таблицы, код) в чанки до maxChars символов. Блок не режется никогда:
     * код-блок и таблица уходят целиком, даже если длиннее maxChars.
     * ponytail: блок длиннее maxChars остаётся одним чанком; прозу дорезайте chunkSection из модуля 5.
     */
    public static List<Chunk> splitMarkdown(String md, int maxChars) {
        return new Splitter(maxChars).run(md);
    }

    private static final class Splitter {
        final int max;
        final List<Chunk> out = new ArrayList<>();
        final List<String> path = new ArrayList<>();   // текущий путь заголовков по уровням
        final List<String> blocks = new ArrayList<>(); // блоки текущей секции
        final List<String> cur = new ArrayList<>();    // строки текущего блока
        String fence = "";                             // открытый ``` или ~~~

        Splitter(int max) { this.max = max; }

        void endBlock() {
            String t = String.join("\n", cur).strip();
            if (!t.isEmpty()) blocks.add(t);
            cur.clear();
        }

        void endSection() {
            endBlock();
            String heading = String.join(" › ", path.stream().filter(s -> !s.isEmpty()).toList());
            List<String> buf = new ArrayList<>();
            int size = 0;
            for (String b : blocks) {
                int n = b.codePointCount(0, b.length());
                if (size > 0 && size + n > max) {
                    out.add(new Chunk(heading, String.join("\n\n", buf)));
                    buf.clear();
                    size = 0;
                }
                buf.add(b);
                size += n;
            }
            if (!buf.isEmpty()) out.add(new Chunk(heading, String.join("\n\n", buf)));
            blocks.clear();
        }

        List<Chunk> run(String md) {
            for (String line : md.split("\n", -1)) {
                String trim = line.strip();
                int lvl;
                if (!fence.isEmpty()) { // внутри код-блока: ни заголовков, ни разрывов
                    cur.add(line);
                    if (trim.startsWith(fence)) {
                        fence = "";
                        endBlock();
                    }
                } else if (trim.startsWith("```") || trim.startsWith("~~~")) {
                    endBlock();
                    fence = trim.substring(0, 3);
                    cur.add(line);
                } else if ((lvl = headingLevel(trim)) > 0) {
                    endSection();
                    while (path.size() < lvl) path.add("");
                    path.subList(lvl - 1, path.size()).clear();
                    path.add(trim.substring(lvl).strip());
                } else if (trim.isEmpty()) {
                    endBlock();
                } else {
                    cur.add(line);
                }
            }
            endSection();
            return out;
        }
    }

    /** "## Title" → 2; "#hashtag" и "####### x" — не заголовки. */
    static int headingLevel(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == '#') n++;
        if (n == 0 || n > 6 || (n < s.length() && s.charAt(n) != ' ')) return 0;
        return n;
    }
}
