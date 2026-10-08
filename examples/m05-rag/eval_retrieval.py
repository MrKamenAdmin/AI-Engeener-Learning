"""Оценка retrieval на golden-наборе: recall@k, MRR, nDCG@k.
golden.jsonl: {"query": "...", "relevant": {"doc-12#3": 2, "doc-40#0": 1}}
  оценки: 2 — точно в тему, 1 — частично; список ["doc-12#3", ...] = все с оценкой 1
Запуск: python3 eval_retrieval.py golden.jsonl   # против вашего Go-сервиса /search
        python3 eval_retrieval.py --demo         # встроенный пример, без сервиса
Только стандартная библиотека.
"""
import json
import math
import statistics
import sys
import urllib.request

SEARCH_URL = "http://localhost:8080/search"  # ваш Go-сервис
K = 10


def search(query: str, k: int) -> list[str]:
    body = json.dumps({"query": query, "k": k}).encode()
    req = urllib.request.Request(SEARCH_URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return [h["id"] for h in json.load(r)["hits"]]


def grades(relevant) -> dict[str, int]:
    return dict(relevant) if isinstance(relevant, dict) else {d: 1 for d in relevant}


def recall_at_k(found: list[str], rel: dict[str, int], k: int) -> float:
    return len(set(found[:k]) & rel.keys()) / len(rel)


def reciprocal_rank(found: list[str], rel: dict[str, int]) -> float:
    for i, doc in enumerate(found, start=1):
        if doc in rel:
            return 1 / i
    return 0.0


def ndcg_at_k(found: list[str], rel: dict[str, int], k: int) -> float:
    # gain = 2^rel − 1, скидка 1/log2(позиция + 1); IDCG — по всем известным релевантным,
    # а не только по найденным: иначе пропуск релевантного документа не штрафуется.
    dcg = sum((2 ** rel.get(d, 0) - 1) / math.log2(i + 2) for i, d in enumerate(found[:k]))
    ideal = sorted(rel.values(), reverse=True)[:k]
    idcg = sum((2 ** g - 1) / math.log2(i + 2) for i, g in enumerate(ideal))
    return dcg / idcg if idcg else 0.0


def evaluate(rows: list[dict], search_fn, k: int = K) -> None:
    recalls, rrs, ndcgs, misses = [], [], [], []
    for row in rows:
        rel = grades(row["relevant"])
        found = search_fn(row["query"], k)
        recalls.append(recall_at_k(found, rel, k))
        rrs.append(reciprocal_rank(found, rel))
        ndcgs.append(ndcg_at_k(found, rel, k))
        if recalls[-1] == 0:
            misses.append(row["query"])
    print(f"n={len(rows)}  recall@{k}={statistics.mean(recalls):.3f}  "
          f"MRR={statistics.mean(rrs):.3f}  nDCG@{k}={statistics.mean(ndcgs):.3f}")
    for q in misses[:20]:  # провалы читать глазами — это самый ценный вывод скрипта
        print("  MISS:", q)


DEMO_GOLDEN = [
    {"query": "как ретраить 429", "relevant": {"limits#2": 2, "go-sdk#5": 2, "errors#1": 1}},
    {"query": "лимит токенов в минуту", "relevant": ["limits#1"]},
    {"query": "оплата картой мир", "relevant": {"billing#4": 2}},
]
DEMO_RESULTS = {  # что «вернул поиск» на каждый запрос
    "как ретраить 429": ["go-sdk#5", "changelog#9", "limits#2", "python-sdk#3", "errors#1"],
    "лимит токенов в минуту": ["limits#3", "limits#1", "batch#2"],
    "оплата картой мир": ["billing#1", "faq#7", "billing#2"],
}

if __name__ == "__main__":
    rel = {"a": 2, "b": 1}
    assert recall_at_k(["x", "a", "y"], rel, 2) == 0.5
    assert reciprocal_rank(["x", "a"], rel) == 0.5
    assert ndcg_at_k(["a", "b"], rel, 2) == 1.0  # идеальный порядок
    assert 0 < ndcg_at_k(["b", "a"], rel, 2) < 1  # те же документы, хуже порядок
    if sys.argv[1:] == ["--demo"]:
        evaluate(DEMO_GOLDEN, lambda q, k: DEMO_RESULTS[q][:k], k=5)
    elif len(sys.argv) == 2:
        with open(sys.argv[1], encoding="utf-8") as f:
            evaluate([json.loads(line) for line in f if line.strip()], search)
    else:
        sys.exit(__doc__)
