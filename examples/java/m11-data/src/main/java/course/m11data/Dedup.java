package course.m11data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ищет точные (sha256 нормализованного текста) и почти-дубли (SimHash ≤ maxDist бит).
 * Индекс — 4 таблицы по 16-битным блокам отпечатка: при расстоянии ≤ 3 хотя бы один блок
 * совпадает целиком (принцип Дирихле), поэтому сравниваем только с кандидатами из корзин,
 * а не со всем корпусом (Manku et al., 2007).
 */
public final class Dedup {
    /** Найденный дубль: id и расстояние Хэмминга (0 — точная копия). */
    public record Match(String dupOf, int dist) {}

    private record Entry(String id, long hash) {}

    private final int maxDist; // 3 для 64 бит — значение из статьи Manku et al.; подбирайте на своей разметке
    private final Map<String, String> exact = new HashMap<>();
    @SuppressWarnings("unchecked")
    private final Map<Integer, List<Entry>>[] bands = new Map[4];

    public Dedup(int maxDist) {
        this.maxDist = maxDist;
        for (int i = 0; i < bands.length; i++) bands[i] = new HashMap<>();
    }

    /**
     * Общий шаг для точных и нечётких дублей: нижний регистр, только буквы и цифры,
     * пробелы схлопнуты. Даты, id и подписи вырезайте до этого.
     */
    public static List<String> normalize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        s.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (Character.isLetter(cp) || Character.isDigit(cp)) {
                w.appendCodePoint(cp);
            } else if (!w.isEmpty()) {
                out.add(w.toString());
                w.setLength(0);
            }
        });
        if (!w.isEmpty()) out.add(w.toString());
        return out;
    }

    /** 64-битный отпечаток по шинглам из 3 слов (Charikar, 2002). */
    public static long simHash(List<String> words) {
        final int k = 3;
        int[] acc = new int[64];
        List<String> shingles = new ArrayList<>();
        if (words.size() < k) shingles.add(String.join(" ", words));
        for (int i = 0; i + k <= words.size(); i++) shingles.add(String.join(" ", words.subList(i, i + k)));
        for (String sh : shingles) {
            long x = fnv64a(sh.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 64; i++) acc[i] += ((x >>> i) & 1) != 0 ? 1 : -1;
        }
        long out = 0;
        for (int i = 0; i < 64; i++) if (acc[i] > 0) out |= 1L << i;
        return out;
    }

    public static int hamming(long a, long b) { return Long.bitCount(a ^ b); }

    /**
     * Возвращает найденный дубль или null; если дубля нет, документ добавляется в индекс.
     * ponytail: корректно только для maxDist ≤ 3 (4 блока); для большего порога — больше таблиц с перестановками битов.
     */
    public Match add(String id, String text) {
        List<String> words = normalize(text);
        String key = sha256Hex(String.join(" ", words));
        String prev = exact.get(key);
        if (prev != null) return new Match(prev, 0);
        long h = simHash(words);
        for (int i = 0; i < bands.length; i++) {
            for (Entry e : bands[i].getOrDefault(band(h, i), List.of())) {
                int dist = hamming(h, e.hash());
                if (dist <= maxDist) return new Match(e.id(), dist);
            }
        }
        exact.put(key, id);
        for (int i = 0; i < bands.length; i++) {
            bands[i].computeIfAbsent(band(h, i), b -> new ArrayList<>()).add(new Entry(id, h));
        }
        return null;
    }

    private static int band(long h, int i) { return (int) ((h >>> (16 * i)) & 0xFFFF); }

    private static long fnv64a(byte[] data) {
        long h = 0xcbf29ce484222325L;
        for (byte b : data) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
