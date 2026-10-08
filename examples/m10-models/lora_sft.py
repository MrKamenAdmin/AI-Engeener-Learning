"""LoRA-дообучение малой модели под классификацию тикетов (TRL + PEFT) с оценкой до и после.

GPU с bf16 (Colab L4/A100, RTX 30xx+, H100), данные из скрипта подготовки датасета модуля 10:
  uv run --python 3.12 --with trl==1.14.2 --with peft==0.21.2 --with transformers==5.19.0 \
      --with datasets==5.1.0 --with accelerate==1.15.0 --with torch \
      python lora_sft.py --train train.jsonl --val val.jsonl --test test.jsonl --model Qwen/Qwen3-4B
CPU dry-run (проверка, что пайплайн собирается; метрики на демо-данных ничего не значат):
  ... python lora_sft.py --demo --model Qwen/Qwen3-0.6B --cpu --max-steps 4
"""
import argparse
import random

import torch
from datasets import Dataset, load_dataset
from peft import LoraConfig
from transformers import EarlyStoppingCallback
from trl import SFTConfig, SFTTrainer

SYSTEM = "Классифицируй тикет поддержки. Ответ: одна метка из списка: billing, delivery, account, bug, other."
LABELS = ["billing", "delivery", "account", "bug", "other"]


def demo_split(n: int, seed: int) -> Dataset:
    texts = {
        "billing": ["Списали деньги дважды за заказ", "Не пришёл возврат", "Счёт больше, чем сумма в корзине"],
        "delivery": ["Курьер не приехал", "Где мой заказ, прошла неделя", "Привезли не в тот пункт выдачи"],
        "account": ["Не могу войти в аккаунт", "Как сменить email в профиле?", "Не приходит код подтверждения"],
        "bug": ["Приложение падает при оплате", "Кнопка «Купить» не нажимается", "Ошибка 500 на странице заказа"],
        "other": ["Есть ли подарочные карты?", "Хочу похвалить курьера", "Работаете ли вы в праздники?"],
    }
    rnd = random.Random(seed)
    rows = []
    for _ in range(n):
        label = rnd.choice(LABELS)
        rows.append({"messages": [{"role": "system", "content": SYSTEM},
                                  {"role": "user", "content": rnd.choice(texts[label])},
                                  {"role": "assistant", "content": label}]})
    return Dataset.from_list(rows)


@torch.no_grad()
def correct(model, tok, rows: Dataset) -> list[int]:
    """1/0 на каждый пример: совпала ли первая сгенерированная метка с эталоном (greedy)."""
    model.eval()
    out = []
    for ex in rows:
        prompt = tok.apply_chat_template(ex["messages"][:-1], add_generation_prompt=True, tokenize=False,
                                         enable_thinking=False)  # Qwen3: без <think>; другие шаблоны игнорируют
        ids = tok(prompt, return_tensors="pt", add_special_tokens=False).to(model.device)
        gen = model.generate(**ids, max_new_tokens=6, do_sample=False)
        words = tok.decode(gen[0, ids["input_ids"].shape[1]:], skip_special_tokens=True).split()
        out.append(int(bool(words) and words[0].strip(".,") == ex["messages"][-1]["content"]))
    return out


def paired_bootstrap(before: list[int], after: list[int], n: int = 10_000, seed: int = 0) -> tuple[float, float]:
    """95% ДИ разницы accuracy (after − before) на ОДНИХ И ТЕХ ЖЕ примерах: ресэмплим пары, а не системы по отдельности."""
    rnd, k = random.Random(seed), len(before)
    diffs = sorted(sum(after[i] - before[i] for i in (rnd.randrange(k) for _ in range(k))) / k for _ in range(n))
    return diffs[int(0.025 * n)], diffs[int(0.975 * n)]


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--model", default="Qwen/Qwen3-4B")
    for f in ("--train", "--val", "--test"):
        p.add_argument(f)
    p.add_argument("--demo", action="store_true", help="встроенные игрушечные данные вместо JSONL")
    p.add_argument("--cpu", action="store_true")
    p.add_argument("--max-steps", type=int, default=-1, help="-1 — обучать по эпохам")
    p.add_argument("--out", default="out/ticket-clf-lora")
    p.add_argument("--merge", action="store_true", help="дополнительно сохранить модель со слитым адаптером")
    a = p.parse_args()

    if a.demo:
        train, val, test = demo_split(48, 1), demo_split(8, 2), demo_split(8, 3)
    else:
        files = {"train": a.train, "validation": a.val, "test": a.test or a.val}
        data = load_dataset("json", data_files=files)
        train, val, test = data["train"], data["validation"], data["test"]

    # bf16 есть на Ampere и новее (L4, A100, RTX 30xx+, H100). На T4 и CPU — fp32: берите модель ≤ 1.7B или QLoRA.
    bf16 = torch.cuda.is_available() and torch.cuda.is_bf16_supported() and not a.cpu
    peft_config = LoraConfig(
        r=16,                         # ёмкость адаптера
        lora_alpha=32,                # масштаб ΔW = (alpha / r) · B·A
        lora_dropout=0.05,
        target_modules="all-linear",  # q,k,v,o + MLP: ближе к full FT, чем только attention
        task_type="CAUSAL_LM",
    )
    args = SFTConfig(
        output_dir=a.out,
        model_init_kwargs={"dtype": torch.bfloat16 if bf16 else torch.float32},
        bf16=bf16,                    # в SFTConfig по умолчанию True: без bf16 в железе обучение упадёт
        use_cpu=a.cpu,
        assistant_only_loss=True,     # loss только на ответе; TRL сам подставит шаблон с {% generation %} для Qwen, Llama, Gemma
        max_length=1024,              # длинные примеры обрезаются: проверьте, что метки не теряются
        num_train_epochs=3,
        max_steps=a.max_steps,
        learning_rate=1e-4,           # для LoRA выше, чем для full FT (дефолт SFTConfig 2e-5)
        per_device_train_batch_size=8,
        gradient_accumulation_steps=2,
        eval_strategy="steps", eval_steps=2 if a.demo else 50,
        save_strategy="steps", save_steps=2 if a.demo else 50, save_total_limit=2,
        load_best_model_at_end=True, metric_for_best_model="eval_loss", greater_is_better=False,
        logging_steps=1 if a.demo else 10, report_to="none", seed=42,
    )
    trainer = SFTTrainer(
        model=a.model,                # строка: тренер сам загрузит модель и токенизатор
        args=args,
        train_dataset=train,
        eval_dataset=val,
        peft_config=peft_config,
        callbacks=[EarlyStoppingCallback(early_stopping_patience=3)],
        # QLoRA: quantization_config=BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
        #          bnb_4bit_compute_dtype=torch.bfloat16, bnb_4bit_use_double_quant=True)
    )
    tok = trainer.processing_class
    trainer.model.print_trainable_parameters()

    before = correct(trainer.model, tok, test)  # B = 0 → адаптер пока ничего не меняет, это базовая модель
    trainer.train()
    after = correct(trainer.model, tok, test)
    lo, hi = paired_bootstrap(before, after)
    print(f"test accuracy: до {sum(before) / len(before):.3f} → после {sum(after) / len(after):.3f}, "
          f"разница 95% ДИ [{lo:+.3f}; {hi:+.3f}] на {len(test)} примерах")

    trainer.save_model(f"{a.out}/best")  # только адаптер: десятки МБ
    if a.merge:  # одна модель без адаптера: проще сервить, но теряется multi-LoRA
        trainer.model.merge_and_unload().save_pretrained(f"{a.out}/merged")
        tok.save_pretrained(f"{a.out}/merged")
    print(f"vllm serve {a.model} --enable-lora --lora-modules ticket-clf={a.out}/best --max-lora-rank 16")


if __name__ == "__main__":
    main()
