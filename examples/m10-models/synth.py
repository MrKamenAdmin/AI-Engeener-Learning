"""Синтетические тикеты от сильной модели и их фильтрация перед обучением.

Без ключа — режим заглушки: детерминированный «учитель» с типичными дефектами, проверка, что каждый фильтр срабатывает:
  python3 synth.py
С ключом (учитель Opus, независимый разметчик Haiku), eval-набор — тот, на котором будете мерить модель:
  ANTHROPIC_API_KEY=... uv run --with anthropic python synth.py --per-label 60 --eval test.jsonl
Выход: synth_train.jsonl в том же чат-формате и с тем же system prompt, что у lora_sft.py.
"""
import argparse
import hashlib
import json
import os
import re
from collections import Counter

SYSTEM = "Классифицируй тикет поддержки. Ответ: одна метка из списка: billing, delivery, account, bug, other."
LABELS = {"billing": "оплата, двойные списания, возвраты, счета", "delivery": "сроки и место доставки, курьер",
          "account": "вход, пароль, профиль, коды подтверждения", "bug": "ошибки и падения сайта или приложения",
          "other": "всё остальное: вопросы о магазине, отзывы, сотрудничество"}
PERSONAS = ["пожилой клиент, пишет длинно и вежливо", "раздражённый клиент, коротко, с опечатками",
            "айтишник, приводит текст ошибки", "пишет с телефона, без знаков препинания"]


def norm(t: str) -> str:
    return re.sub(r"\s+", " ", t.lower()).strip()


def shingles(t: str, k: int = 5) -> set[str]:
    t = norm(t)
    return {t[i:i + k] for i in range(max(1, len(t) - k + 1))}


def jaccard(a: set[str], b: set[str]) -> float:
    return len(a & b) / len(a | b) if a | b else 1.0


class ClaudeTeacher:
    def __init__(self) -> None:
        import anthropic
        self.client = anthropic.Anthropic()  # ANTHROPIC_API_KEY из окружения

    def _text(self, **kw) -> str:
        resp = self.client.messages.create(**kw)
        return "".join(b.text for b in resp.content if b.type == "text")

    def generate(self, label: str, persona: str, n: int) -> list[str]:
        text = self._text(model="claude-opus-5", max_tokens=8000, messages=[{"role": "user", "content":
            f"Сгенерируй {n} разных обращений в поддержку интернет-магазина на тему «{label}» ({LABELS[label]}). "
            f"Автор: {persona}. Варьируй длину (5–60 слов), детали и первые слова. "
            "Не упоминай название темы. Верни только JSON-массив строк."}])
        return json.loads(text[text.find("["):text.rfind("]") + 1])

    def label(self, text: str) -> str:  # разметчик не знает, под какую метку генерировали текст
        words = self._text(model="claude-haiku-4-5", max_tokens=10, system=SYSTEM,
                           messages=[{"role": "user", "content": text}]).split()
        return words[0].strip(".,").lower() if words else ""


class StubTeacher:
    """Офлайн-учитель: на каждую метку выдаёт хорошие тексты и по одному дефекту каждого вида."""
    GOOD = {"billing": ["С карты дважды списали 2490 за один заказ", "Возврат за отменённый заказ так и не пришёл"],
            "delivery": ["Курьер опаздывает уже на два часа, где он?", "Заказ привезли в другой пункт выдачи"],
            "account": ["Не приходит смс с кодом для входа в профиль", "Забыл пароль, а почта старая недоступна"],
            "bug": ["При оплате приложение вылетает на экран загрузки", "Ошибка 500 при открытии истории заказов"],
            "other": ["Можно ли купить у вас подарочный сертификат?", "Хочу предложить сотрудничество поставщика"]}

    def generate(self, label: str, persona: str, n: int) -> list[str]:
        a, b = self.GOOD[label]
        other = self.GOOD["bug" if label != "bug" else "billing"][0]
        bad = ["  " + a.upper() + " ",  # точный дубль после нормализации
               a + "!!",               # почти дубль
               "ок",                   # слишком коротко
               other + " ???"]         # тема не совпадает с меткой: разметчик не согласится
        if label == "delivery":
            bad.append("Где мой заказ номер 1042?!")  # почти копия примера из eval
        return ([a, b] + bad)[:n]

    def label(self, text: str) -> str:
        rules = [("bug", "ошибк|вылет|500"), ("billing", "списал|возврат"), ("delivery", "курьер|пункт выдачи|где мой заказ"),
                 ("account", "пароль|код|профил"), ("other", "сертификат|сотрудничеств")]
        return next((lab for lab, rx in rules if re.search(rx, text.lower())), "other")


def filter_examples(cands: list[tuple[str, str]], eval_texts: list[str], labeler, sim: float = 0.8):
    """Дешёвые проверки первыми, вызов LLM-разметчика — последним. Порог sim подбирают по выборке глазами."""
    kept, dropped, hashes, kept_sh = [], Counter(), set(), []
    eval_sh = [shingles(t) for t in eval_texts]
    for text, label in cands:
        if label not in LABELS or not 15 <= len(text) <= 2000:
            dropped["схема или длина"] += 1
            continue
        h = hashlib.sha256(norm(text).encode()).hexdigest()
        if h in hashes:
            dropped["точный дубль"] += 1
            continue
        sh = shingles(text)
        if any(jaccard(sh, e) >= sim for e in eval_sh):
            dropped["похож на eval"] += 1
            continue
        if any(jaccard(sh, k) >= sim for k in kept_sh):  # ponytail: O(n²); от ~10⁴ примеров — MinHash/LSH
            dropped["почти дубль"] += 1
            continue
        if labeler(text) != label:
            dropped["разметчик не согласен"] += 1
            continue
        hashes.add(h)
        kept_sh.append(sh)
        kept.append((text, label))
    return kept, dropped


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-label", type=int, default=28, help="кандидатов на метку (делятся между персонами)")
    ap.add_argument("--eval", help="JSONL eval-набора в чат-формате: его тексты не должны попасть в train")
    ap.add_argument("--out", default="synth_train.jsonl")
    a = ap.parse_args()

    stub = not os.environ.get("ANTHROPIC_API_KEY")
    teacher = StubTeacher() if stub else ClaudeTeacher()
    if a.eval:
        with open(a.eval, encoding="utf-8") as f:
            eval_texts = [m["content"] for line in f for m in json.loads(line)["messages"] if m["role"] == "user"]
    else:
        eval_texts = ["Где мой заказ номер 1042?"]

    per_persona = max(1, a.per_label // len(PERSONAS))
    cands = [(t, label) for label in LABELS for p in PERSONAS[:1 if stub else None]
             for t in teacher.generate(label, p, per_persona)]
    kept, dropped = filter_examples(cands, eval_texts, teacher.label)

    with open(a.out, "w", encoding="utf-8") as f:
        for text, label in kept:
            f.write(json.dumps({"messages": [{"role": "system", "content": SYSTEM},
                                             {"role": "user", "content": text},
                                             {"role": "assistant", "content": label}]}, ensure_ascii=False) + "\n")
    starts = Counter(" ".join(norm(t).split()[:3]) for t, _ in kept)
    print(f"{'заглушка' if stub else 'Claude'}: кандидатов {len(cands)}, оставлено {len(kept)}, отброшено {dict(dropped)}")
    print(f"метки {dict(Counter(lab for _, lab in kept))}; уникальных начал фраз {len(starts)}/{len(kept)}; → {a.out}")
    if stub:  # самопроверка: каждый фильтр поймал свой дефект, хорошие тексты прошли все
        assert set(dropped) == {"схема или длина", "точный дубль", "почти дубль", "разметчик не согласен", "похож на eval"}, dropped
        assert len(kept) == 2 * len(LABELS), kept


if __name__ == "__main__":
    main()
