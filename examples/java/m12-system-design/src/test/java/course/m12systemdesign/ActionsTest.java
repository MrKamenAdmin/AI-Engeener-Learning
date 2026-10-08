package course.m12systemdesign;

import static course.m12systemdesign.Actions.CARD_BLOCK;
import static course.m12systemdesign.Actions.OWN_TRANSFER;
import static course.m12systemdesign.Actions.TEMPLATE_PAY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import course.m12systemdesign.Actions.Client;
import course.m12systemdesign.Actions.Confirmation;
import course.m12systemdesign.Actions.Limits;
import course.m12systemdesign.Actions.NotConfirmedException;
import course.m12systemdesign.Actions.Proposal;
import course.m12systemdesign.Actions.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ActionsTest {
    static final Client CLIENT = new Client(Set.of("acc-1", "acc-2"), Set.of("tpl-mobile"), Set.of("card-1"), 250_000_00);
    static final Limits LIMITS = new Limits(300_000_00, 500_000_00, 30_000_00);

    static Proposal transfer(String from, String to, long amount) {
        return new Proposal(OWN_TRANSFER, from, to, null, null, amount);
    }

    static Stream<Arguments> authorizeCases() {
        return Stream.of(
                Arguments.of("свои счета, мелкая сумма", transfer("acc-1", "acc-2", 1_000_00), Verdict.CONFIRM),
                Arguments.of("крупная сумма требует step-up", transfer("acc-1", "acc-2", 30_000_00), Verdict.STEP_UP),
                Arguments.of("чужой счёт из инъекции", transfer("acc-1", "acc-evil", 1_000_00), Verdict.DENY),
                Arguments.of("тот же счёт", transfer("acc-1", "acc-1", 1_000_00), Verdict.DENY),
                Arguments.of("шаблон клиента", new Proposal(TEMPLATE_PAY, "acc-1", null, "tpl-mobile", null, 500_00), Verdict.CONFIRM),
                Arguments.of("чужой шаблон", new Proposal(TEMPLATE_PAY, "acc-1", null, "tpl-x", null, 500_00), Verdict.DENY),
                Arguments.of("отрицательная сумма", transfer("acc-1", "acc-2", -1), Verdict.DENY),
                Arguments.of("лимит на операцию", transfer("acc-1", "acc-2", 300_000_01), Verdict.DENY),
                Arguments.of("дневной лимит", transfer("acc-1", "acc-2", 260_000_00), Verdict.DENY),
                Arguments.of("блокировка своей карты", new Proposal(CARD_BLOCK, null, null, null, "card-1", 0), Verdict.CONFIRM),
                Arguments.of("блокировка чужой карты", new Proposal(CARD_BLOCK, null, null, null, "card-9", 0), Verdict.DENY),
                Arguments.of("перевод новому получателю не поддерживается",
                        new Proposal("transfer_to_new_payee", null, null, null, null, 100), Verdict.DENY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authorizeCases")
    void authorize(String name, Proposal p, Verdict want) {
        var got = Actions.authorize(CLIENT, LIMITS, p);
        assertEquals(want, got.verdict(), got.reason());
    }

    @Test
    void execute() {
        Proposal small = transfer("acc-1", "acc-2", 1_000_00);
        Proposal big = transfer("acc-1", "acc-2", 40_000_00);
        List<String> keys = new ArrayList<>();
        Actions.Core core = (key, p) -> keys.add(key);

        assertDoesNotThrow(() -> Actions.execute(core, CLIENT, LIMITS, small, new Confirmation(small.digest(), "c1", false)));
        assertEquals(List.of("c1"), keys, "ключ идемпотентности = ID подтверждения");

        // Подтверждали 1 000 ₽, а исполнить пытаются 40 000 ₽: дайджест не совпадает.
        assertThrows(NotConfirmedException.class,
                () -> Actions.execute(core, CLIENT, LIMITS, big, new Confirmation(small.digest(), "c2", true)));
        // Крупная сумма без step-up.
        assertThrows(NotConfirmedException.class,
                () -> Actions.execute(core, CLIENT, LIMITS, big, new Confirmation(big.digest(), "c3", false)));
        assertEquals(1, keys.size(), "core вызван для неподтверждённых действий: " + keys);
    }

    @Test
    void digestChangesWithEveryField() {
        Proposal base = transfer("acc-1", "acc-2", 100);
        for (Proposal p : List.of(transfer("acc-1", "acc-2", 101), transfer("acc-1", "acc-3", 100), transfer("acc-2", "acc-2", 100))) {
            assertNotEquals(base.digest(), p.digest(), p.toString());
        }
    }
}
