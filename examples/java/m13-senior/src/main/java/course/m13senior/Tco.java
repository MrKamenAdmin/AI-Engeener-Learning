package course.m13senior;

/**
 * Расчёты, на которые опираются решения senior-уровня:
 * TCO варианта реализации AI-фичи (Tco), error budget и burn rate (Budget), проверка ADR (Adr).
 */
public final class Tco {
    private Tco() {}

    /** $ за 1M токенов входа и выхода (цены Claude в курсе — на сентябрь 2026). */
    public record Price(double in, double out) {}

    /** $ за один вызов API; cachedShare входа читается из prompt cache по 0.1× цены. */
    public static double apiCost(double in, double out, double cachedShare, Price p) {
        double cached = in * cachedShare;
        return ((in - cached) * p.in() + cached * p.in() * 0.1 + out * p.out()) / 1e6;
    }

    /**
     * Вариант реализации фичи. Все цифры — ваши параметры, а не справочник.
     *
     * @param perRequest  $ переменной части: токены API (0 для self-host)
     * @param unitMonthly $ в месяц за реплику self-host: GPU, хостинг (0 для API)
     * @param unitCap     запросов в месяц на реплику при целевой утилизации
     * @param minUnits    минимум реплик ради отказоустойчивости
     * @param opsFte      доля инженера: эксплуатация, on-call, обновления, evals, разметка
     * @param successRate доля успешно решённых задач на вашем eval-наборе
     */
    public record Option(String name, double perRequest, double unitMonthly, double unitCap, int minUnits,
                         double opsFte, double successRate) {
        public static Option api(String name, double perRequest, double opsFte, double successRate) {
            return new Option(name, perRequest, 0, 0, 0, opsFte, successRate);
        }

        public static Option selfHost(String name, double unitMonthly, double unitCap, int minUnits,
                                      double opsFte, double successRate) {
            return new Option(name, 0, unitMonthly, unitCap, minUnits, opsFte, successRate);
        }
    }

    /** Полная стоимость владения в месяц и её структура. */
    public record Result(double variable, double infra, double people, double total, int units,
                         double perRequest, double perSuccess) {}

    /**
     * TCO при объёме requests в месяц; fteMonthly — полная стоимость инженера в месяц.
     * Упрощение: evals и разметка сидят в opsFte. Выделите их отдельной строкой, если они заметны в бюджете.
     */
    public static Result monthly(Option o, double requests, double fteMonthly) {
        double variable = o.perRequest() * requests, people = o.opsFte() * fteMonthly, infra = 0;
        int units = 0;
        if (o.unitCap() > 0) {
            units = Math.max(o.minUnits(), (int) Math.ceil(requests / o.unitCap()));
            infra = units * o.unitMonthly();
        }
        double total = variable + infra + people, perRequest = 0, perSuccess = 0;
        if (requests > 0) {
            perRequest = total / requests;
            if (o.successRate() > 0) perSuccess = total / (requests * o.successRate());
        }
        return new Result(variable, infra, people, total, units, perRequest, perSuccess);
    }
}
