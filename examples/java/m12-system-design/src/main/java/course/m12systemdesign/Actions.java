package course.m12systemdesign;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * Политика действий банковского ассистента (модуль 12, Разбор 6).
 * Модель только предлагает действие; разрешает, подтверждает и исполняет код.
 */
public final class Actions {
    private Actions() {}

    public static final String OWN_TRANSFER = "own_transfer";     // между своими счетами
    public static final String TEMPLATE_PAY = "template_payment"; // по сохранённому шаблону
    public static final String CARD_BLOCK = "card_block";

    /**
     * Собирается из аргументов tool_use: каждое поле — недоверенные данные.
     * kind — строка, а не enum: модель может прислать что угодно, и это надо отклонить, а не упасть.
     */
    public record Proposal(String kind, String from, String to, String template, String card,
                           long amountKop) { // деньги в копейках, без double

        /**
         * Привязывает подтверждение к сумме и получателю, как dynamic linking в SCA:
         * изменилось любое поле — подтверждение недействительно.
         * Каноничная сериализация: длина + значение, чтобы "a|b"+"c" не совпало с "a"+"b|c".
         */
        public String digest() {
            StringBuilder b = new StringBuilder();
            for (String f : new String[] {kind, from, to, template, card, Long.toString(amountKop)}) {
                String v = f == null ? "" : f;
                b.append(v.length()).append(':').append(v).append(';');
            }
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** То, что банк знает о клиенте из core banking по его сессии, а не со слов модели. */
    public record Client(Set<String> accounts, Set<String> templates, Set<String> cards, long spentTodayKop) {}

    public record Limits(long perOpKop, long dailyKop, long stepUpKop) {}

    public enum Verdict {
        DENY,
        CONFIRM, // подтверждение на нативном экране приложения
        STEP_UP  // подтверждение + PIN или биометрия
    }

    /** reason уходит модели как tool_result, чтобы она объяснила клиенту. */
    public record Decision(Verdict verdict, String reason) {}

    private static Decision deny(String reason) { return new Decision(Verdict.DENY, reason); }

    /** Детерминированная политика: никаких вызовов модели внутри. */
    public static Decision authorize(Client c, Limits l, Proposal p) {
        switch (String.valueOf(p.kind())) {
            case CARD_BLOCK -> {
                if (!c.cards().contains(p.card())) return deny("карта не принадлежит клиенту");
                return new Decision(Verdict.CONFIRM, "блокировка карты");
            }
            case OWN_TRANSFER -> {
                if (!c.accounts().contains(p.from()) || !c.accounts().contains(p.to()) || p.from().equals(p.to())) {
                    return deny("перевод возможен только между разными своими счетами");
                }
            }
            case TEMPLATE_PAY -> {
                if (!c.accounts().contains(p.from()) || !c.templates().contains(p.template())) {
                    return deny("у клиента нет такого счёта или шаблона");
                }
            }
            default -> {
                return deny("действие \"%s\" ассистенту недоступно".formatted(p.kind()));
            }
        }
        if (p.amountKop() <= 0) return deny("сумма должна быть положительной");
        if (p.amountKop() > l.perOpKop()) return deny("превышен лимит на операцию");
        if (c.spentTodayKop() + p.amountKop() > l.dailyKop()) return deny("превышен дневной лимит");
        if (p.amountKop() >= l.stepUpKop()) return new Decision(Verdict.STEP_UP, "крупная сумма");
        return new Decision(Verdict.CONFIRM, "в пределах лимитов");
    }

    /** Приходит с нативного экрана приложения после действия клиента, а не из чата. */
    public record Confirmation(String digest, String id /* уникален для каждого подтверждения */, boolean steppedUp) {}

    public interface Core {
        void execute(String idempotencyKey, Proposal p);
    }

    public static final class DeniedException extends RuntimeException {
        public DeniedException(String reason) { super("отказ: " + reason); }
    }

    public static final class NotConfirmedException extends RuntimeException {
        public NotConfirmedException() { super("подтверждение не совпадает с предложением"); }
    }

    public static void execute(Core core, Client c, Limits l, Proposal p, Confirmation conf) {
        Decision d = authorize(c, l, p); // повторно: с момента предложения могли измениться лимиты
        if (d.verdict() == Verdict.DENY) throw new DeniedException(d.reason());
        if (!p.digest().equals(conf.digest()) || (d.verdict() == Verdict.STEP_UP && !conf.steppedUp())) {
            throw new NotConfirmedException();
        }
        core.execute(conf.id(), p); // ретрай с тем же ID не исполнит операцию дважды
    }
}
