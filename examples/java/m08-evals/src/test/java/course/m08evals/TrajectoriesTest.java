package course.m08evals;

import static org.junit.jupiter.api.Assertions.*;

import course.m08evals.Trajectories.Order;
import course.m08evals.Trajectories.Spec;
import course.m08evals.Trajectories.Step;
import course.m08evals.Trajectories.Trajectory;
import course.m08evals.Trajectories.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TrajectoriesTest {
    static final Spec REFUND = new Spec(
            Map.of("order:42", "refunded", "email:42", "sent"),
            List.of("get_order", "refund"),
            List.of("search_policy", "send_email"),
            List.of("delete_order"),
            List.of(new Order("get_order", "refund")),
            8, 0);

    static final Map<String, String> DONE = Map.of("order:42", "refunded", "email:42", "sent");

    static List<Step> steps(String... tools) {
        return Stream.of(tools).map(t -> new Step(t, "{\"order_id\":42}", false)).toList();
    }

    static Trajectory tr(List<Step> steps, Map<String, String> state) {
        return new Trajectory("", steps, state, 0);
    }

    static Stream<Arguments> cases() {
        List<Step> retry = new ArrayList<>(steps("get_order", "get_order", "refund", "send_email"));
        retry.set(0, new Step("get_order", "{\"order_id\":42}", true)); // таймаут, повтор — нормальное восстановление
        List<Step> loop = steps("get_order", "search_policy", "search_policy", "search_policy", "search_policy",
                "get_order", "refund", "send_email", "send_email");
        return Stream.of(
                Arguments.of("canonical", tr(steps("get_order", "search_policy", "refund", "send_email"), DONE), List.of(), 0),
                Arguments.of("other valid order", tr(steps("search_policy", "get_order", "refund", "send_email"), DONE), List.of(), 0),
                Arguments.of("retry after error", tr(retry, DONE), List.of(), 0),
                Arguments.of("blind refund: right outcome, wrong path", tr(steps("refund", "send_email"), DONE),
                        List.of("required: get_order not called", "order: get_order must precede refund"), 0),
                Arguments.of("forbidden tool", tr(steps("get_order", "delete_order", "refund", "send_email"), DONE),
                        List.of("forbidden: delete_order at step 2"), 0),
                Arguments.of("gave up", tr(steps("get_order", "search_policy", "escalate"), Map.of("order:42", "paid")),
                        List.of("state: email:42 = \"\", want \"sent\"", "state: order:42 = \"paid\", want \"refunded\"",
                                "required: refund not called"), 0),
                Arguments.of("search loop", tr(loop, DONE), List.of("steps: 9 > limit 8"), 5));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void check(String name, Trajectory t, List<String> failures, int redundant) {
        Verdict v = Trajectories.check(REFUND, t);
        assertEquals(failures, v.failures());
        assertEquals(failures.isEmpty(), v.success());
        assertEquals(redundant, v.redundant());
    }

    @Test
    void precisionRecall() {
        Verdict v = Trajectories.check(REFUND, tr(steps("get_order", "search_policy", "escalate"), DONE));
        assertEquals(2.0 / 3, v.precision(), 1e-9);
        assertEquals(0.5, v.recall());
    }

    @Test
    void aggregate() {
        var ok = new Trajectory("refund-42", steps("get_order", "refund", "send_email"), DONE, 0.02);
        var bad = new Trajectory("refund-42", steps("refund", "send_email"), DONE, 0.01);
        var easy = new Trajectory("easy", steps("get_order"), Map.of(), 0.01);
        Map<String, Spec> specs = Map.of("refund-42", REFUND,
                "easy", new Spec(null, List.of("get_order"), null, null, null, 0, 0));

        var s = Trajectories.aggregate(specs, List.of(ok, ok, ok, bad, easy, easy, easy, easy), 2);
        // refund-42: 3 из 4 → pass^2 = C(3,2)/C(4,2) = 0.5; easy: 4 из 4 → 1. Среднее 0.75.
        assertEquals(2, s.tasks());
        assertEquals(7.0 / 8, s.successRate());
        assertEquals(0.75, s.passHatK(), 1e-9);
        assertEquals(Map.of("order", 1, "required", 1), s.failureKinds());

        assertThrows(IllegalArgumentException.class, () -> Trajectories.aggregate(specs, List.of(ok), 2),
                "want error: fewer runs than k");
    }
}
