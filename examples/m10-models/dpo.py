"""DPO с LoRA: учим модель, какой из двух ответов лучше (тон, краткость, уместный отказ).

Данные — JSONL в «разговорном» preference-формате TRL:
  {"prompt": [{"role": "user", "content": "..."}],
   "chosen": [{"role": "assistant", "content": "..."}],
   "rejected": [{"role": "assistant", "content": "..."}]}
GPU (bf16):
  uv run --python 3.12 --with trl==1.14.2 --with peft==0.21.2 --with transformers==5.19.0 \
      --with datasets==5.1.0 --with accelerate==1.15.0 --with torch \
      python dpo.py --train pairs_train.jsonl --val pairs_val.jsonl --model out/ticket-sft/merged
CPU dry-run: ... python dpo.py --demo --model Qwen/Qwen3-0.6B --cpu --max-steps 4
"""
import argparse

import torch
from datasets import Dataset, load_dataset
from peft import LoraConfig
from trl import DPOConfig, DPOTrainer

DEMO = [  # chosen: коротко, по делу, со следующим шагом; rejected: вода, обещания, которых бот не может дать
    ("Списали деньги дважды", "Вижу два списания по заказу. Второе вернётся за 3–5 рабочих дней, номер возврата пришлю в чат.",
     "Здравствуйте! Большое спасибо за обращение! Мы очень ценим вас. Скорее всего, всё в порядке, но мы обязательно разберёмся и вернём вам всё в двойном размере!"),
    ("Курьер не приехал", "Извините за ожидание. Курьер опаздывает на 40 минут; могу перенести доставку на завтра или отменить заказ — что удобнее?",
     "Курьеры иногда опаздывают, это нормально. Подождите ещё."),
    ("Как удалить аккаунт?", "Профиль → Настройки → «Удалить аккаунт». Данные удалятся через 30 дней; до этого можно восстановить вход.",
     "Удаление аккаунта — серьёзный шаг. Вы уверены? Подумайте ещё раз, ведь у нас столько преимуществ!"),
    ("Приложение падает при оплате", "Похоже на известную ошибку версии 5.2. Обновите приложение до 5.3; если не поможет — пришлите скриншот, передам в техподдержку.",
     "У нас всё работает, проблема на вашей стороне."),
]


def demo_rows() -> Dataset:
    return Dataset.from_list([{"prompt": [{"role": "user", "content": q}],
                               "chosen": [{"role": "assistant", "content": good}],
                               "rejected": [{"role": "assistant", "content": bad}]} for q, good, bad in DEMO * 4])


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--model", default="Qwen/Qwen3-4B", help="лучше — уже прошедшая SFT модель")
    for f in ("--train", "--val"):
        p.add_argument(f)
    p.add_argument("--demo", action="store_true")
    p.add_argument("--cpu", action="store_true")
    p.add_argument("--max-steps", type=int, default=-1)
    p.add_argument("--out", default="out/support-dpo")
    a = p.parse_args()

    if a.demo:
        train, val = demo_rows(), demo_rows().select(range(4))
    else:
        data = load_dataset("json", data_files={"train": a.train, "validation": a.val})
        train, val = data["train"], data["validation"]

    bf16 = torch.cuda.is_available() and torch.cuda.is_bf16_supported() and not a.cpu
    args = DPOConfig(
        output_dir=a.out,
        model_init_kwargs={"dtype": torch.bfloat16 if bf16 else torch.float32},
        bf16=bf16,
        use_cpu=a.cpu,
        beta=0.1,               # сила «поводка» к референсной модели: больше β — меньше уход от неё
        loss_type=["sigmoid"],  # классический DPO; "ipo" устойчивее к шуму в парах
        learning_rate=5e-6,     # DPO чувствителен к LR: на порядок ниже, чем для SFT
        max_length=1024,        # prompt + ответ
        num_train_epochs=1,
        max_steps=a.max_steps,
        per_device_train_batch_size=4,
        gradient_accumulation_steps=4,
        eval_strategy="steps", eval_steps=2 if a.demo else 50,
        logging_steps=1 if a.demo else 10, report_to="none", seed=42,
    )
    trainer = DPOTrainer(
        model=a.model,
        ref_model=None,  # с peft_config референсом служит та же модель с выключенным адаптером: вторая копия не нужна
        args=args,
        train_dataset=train,
        eval_dataset=val,
        peft_config=LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, target_modules="all-linear", task_type="CAUSAL_LM"),
    )
    trainer.train()
    m = trainer.evaluate()
    # rewards/accuracies — доля пар, где chosen получил больший неявный reward, чем rejected;
    # margins — средний разрыв. Растут — модель учится различать; ушли в потолок за пару шагов — проверьте утечку или β.
    print({k: round(v, 4) for k, v in m.items() if "rewards" in k or k == "eval_loss"})
    trainer.save_model(f"{a.out}/best")


if __name__ == "__main__":
    main()
