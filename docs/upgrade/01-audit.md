# 01 · Аудит покрытия

Аудит проведён по фактическому тексту всех 13 страниц курса, в старой нумерации, 8 октября 2026. Обозначения: `mNN#id` — файл и раздел (урок). Статусы: ✅ есть полностью · 🟡 есть частично · ❌ нет.

**Главный вывод:** курс глубже, чем следует из карты модулей. MCP, context rot, калибровка judge, статистика evals, гибридный поиск, self-hosted serving и LoRA уже раскрыты на хорошем уровне. Реальные пробелы такие:

- безопасность как система: OWASP Top 10 целиком, red-teaming;
- senior-практики: ADR, design review, SLO, постмортемы;
- governance и данные;
- мультимодальность и голос;
- капстоун с измеримой приёмкой;
- код там, где сейчас только текст: bootstrap и κ на Go, YAML для CI, компакция, траектории, вызов reranker'а.

| # | Тема | Статус | Где есть | Чего не хватает до senior-минимума |
|---|---|---|---|---|
| 1 | Context engineering | 🟡 | **m05#context**: context rot (Chroma 2025, Lost in the Middle), определение Anthropic, таблица из 7 техник (обрезка, context editing, compaction, `NOTES.md`, memory tool, векторная память, субагенты), порядок вмешательства, just-in-time. m05#multi — изоляция контекста субагентами (200K → 2K) и цена ×4/×15. m01#context — окно и цена. m02#messages — компакция истории. m03#longcontext | Таксономия write / select / compress / isolate. Код компакции, заметок и субагента с изолированным контекстом (callout в m05#go-loop прямо говорит «нет compaction»). Бюджет токенов как часть дизайна. Long-horizon обвязка (progress-файл, git как память). Метрика размера контекста по шагам |
| 2 | MCP | ✅ (🟡 по коду) | **m05#mcp**: host / client / server, JSON-RPC, handshake, sampling и elicitation, транспорты stdio и Streamable HTTP, tools / resources / prompts, схема viz-mcp. **m05#mcp-security**: tool poisoning, rug pull, shadowing, OAuth 2.1 без token passthrough, least privilege. **m05#mcp-go**: сервер на официальном `go-sdk` v1.8.0, stdio и HTTP. Проект C | Код resources и prompts. Тест сервера через in-memory транспорт. Подтверждение действий в коде клиента |
| 3 | Безопасность LLM-приложений | 🟡 | m03#injection: прямые и непрямые инъекции, lethal trifecta, слои защиты, `Untrusted()`, demo viz-inj, red team своего агента в задании 3. m07#guardrails: middleware `GuardedComplete`, входные и выходные проверки, каскад классификаторов. m05#mcp-security, m05#hitl (уровни автономии, sandbox). m04#vectordb (ACL, инверсия эмбеддингов). m10#case-sql (4 слоя защиты) | **OWASP Top 10 for LLM 2025 целиком**: LLM03 Supply Chain, LLM04 Poisoning и LLM08 Vector не упомянуты вовсе, остальные пункты разбросаны без системы. Модель угроз. Термины excessive agency и least privilege как принцип. Безопасная обработка вывода в SQL, HTML и shell. Методика red-teaming и eval-датасет атак. Инструменты (promptfoo, garak, PyRIT). Unbounded consumption как угроза |
| 4 | Self-hosted инференс | 🟡 (≈70%) | **m07#selfhosted**: vLLM / SGLang / TGI / Ollama, PagedAttention, continuous batching, prefix caching, VRAM весов и KV, формула $/1M, калькулятор viz-gpu, `vllm serve`, нагрузочный тест. **m01#inference**: prefill и decode, compute- и memory-bound, KV-cache, таблица квантизаций (GPTQ, AWQ, GGUF), viz-kv | Конкурентность из бюджета KV-cache. TPOT, ITL и goodput как метрики. Speculative decoding и chunked prefill. Влияние квантизации на качество и как его мерить. Go-шлюз перед OpenAI-совместимым vLLM. Ollama для локальной разработки в коде. Сравнение open-weight и API на eval-сете |
| 5 | Адаптация моделей | ✅ (🟡 по коду) | **m08 целиком**: дерево «промпт → RAG → FT» (viz-tree), SFT / DPO / RLHF / RFT / дистилляция, LoRA и QLoRA (viz-lora), синтетика и её риски, датасет и дедупликация, кривые обучения, forgetting, метрики, TRL `SFTTrainer` | Лицензии open-weight моделей. Код DPO. Код генерации и фильтрации синтетики. Merge адаптера. Eval «до/после» поверх реальной модели. Связь со своим serving (multi-LoRA) |
| 6 | Evals (глубина) | ✅ (🟡 по коду) | **m06**: golden dataset, judge и **калибровка с κ** (viz-calib), bias (viz-bias), error analysis (open и axial coding), **статистика** (Уилсон, размер выборки, кластерные SE, pass^k, viz-ci), Go-тест-гейт `TestSupportEvals`, онлайн-оценка. m00#evals: Уилсон и McNemar на Go. m03#regression: pytest-гейт | **Bootstrap на Go** (назван, но кода нет). Код κ. **YAML GitHub Actions** (только упомянут). Дизайн A/B: мощность, SRM, guardrail-метрики. **Eval агентов по траекториям с кодом** (tool precision/recall, эффективность шагов). Комментарий в PR с диффом против baseline |
| 7 | Продакшн (глубина) | ✅ (🟡 SLO, дрейф, флаги) | **m07**: ретраи и jitter, retry budget, fallback между провайдерами, circuit breaker, бюджеты на тенанта, prompt и semantic cache, **OTel GenAI semconv** (атрибуты `gen_ai.*`, метрики), middleware-цепочка на Go. m10#case-gateway: полный кейс шлюза | **SLO и error budget для недетерминированных систем** (слов SLO и SLI в курсе нет). **Мониторинг дрейфа** с методами. **Фича-флаги для промптов и моделей**. Роутинг по сложности кодом. Спаны агента (`invoke_agent`, `execute_tool`) и `gen_ai.client.operation.duration` в коде |
| 8 | RAG (глубина) | ✅ (🟡 agentic, GraphRAG) | **m04**: чанкинг, эмбеддинги, HNSW, **BM25 + dense + RRF** с кодом на SQL и Go, reranking (абзац), **recall@k / MRR / nDCG** (viz-metrics), contextual retrieval, HyDE, ACL, отладка | **Agentic RAG** (один пункт без кода). GraphRAG: local и global, критерии «когда оправдан» (пока только абзац). Код вызова reranker'а. nDCG в Python-скрипте |
| 9 | Данные для AI | 🟡 | m08#dataset: дедупликация (хэш, MinHash), κ разметчиков. m06#dataset: версионирование JSONL. m06#humans: разметка. m07#observability: PII-маскирование (Presidio, `MaskPII`). m10#case-summary: PII-редакция. m04: идемпотентная индексация | Ingestion-пайплайн: парсинг PDF и таблиц, OCR, чанкинг с сохранением структуры. Версионирование датасетов инструментами (DVC и аналоги). **Data flywheel** (термина нет). Гайдлайны разметки. Сбор фидбека как система |
| 10 | Governance и регуляторика | ❌ | Только фрагменты: GDPR (удаление по запросу) в m07, ZDR и резидентность в m10, ToS при дистилляции в m08 | EU AI Act (GPAI, ст. 50, high-risk, Digital Omnibus). GDPR и 152-ФЗ для логов диалогов. Документация системы (model and system cards). Human oversight |
| 11 | Стоимость | ✅ (🟡 TCO, build-vs-buy) | m10#cost: формула, калькулятор, Go и Python. m02#models: таблица цен, routing, viz-calc. m07: $/запрос в трейсе, TCO self-host, viz-gpu. m00: расчёт на 1M запросов | TCO всей фичи, включая людей, evals, разметку и on-call. Структурный build-vs-buy. Матрица выбора модели по качеству, цене, латентности, lock-in и комплаенсу |
| 12 | Senior-практики | ❌ (фрагменты) | m03#versioning: промпт в PR с владельцем. m11#interview: «объяснить PM», «нужна ли LLM», «несогласие». m10: таблицы «решение / альтернативы» | ADR, design review, выбор вендора, SLO и on-call, **постмортем AI-инцидента**, коммуникация метрик бизнесу, менторство, стандарты команды, **дисциплина работы с coding agents** |
| 13 | Мультимодальность | 🟡 (мало) | m02#multimodal: изображения и PDF, токены за картинку, Go-код document-блока. m10#case-summary: ASR, WER | Понимание документов как задача. STT → LLM → TTS против speech-to-speech. Бюджет латентности голоса. Barge-in. VAD |
| 14 | Капстоун | ❌ | projects#practice: «портфолио готово». Чекбоксы без числовых порогов качества, защиты design doc нет | Итоговый проект с измеримыми критериями приёмки, чек-лист самооценки, вопросы для защиты |

## Проверка модуля 5 «Агенты» по пункту В

| Пункт | Статус | Где |
|---|---|---|
| Различие workflow и agent | ✅ | m05#workflows, viz-tree, задание 3 |
| Лимиты шагов и стоимости | ✅ | m05#loop: `Limits{MaxIters, MaxInputTokens}`, `ErrBudget`, viz-loop. Бюджет в $ описан только текстом |
| Идемпотентность инструментов | 🟡 | m05#aci — абзац без кода |
| Восстановление после ошибок | ✅ | `is_error` с подсказкой, таймауты, обрезка. Ретраев инструментов с backoff нет |
| Human-in-the-loop | ✅ | m05#hitl: уровни 0–4, `Confirm`, флаг `Dangerous` |
| Long-horizon (компакция, заметки) | 🟡 | Таблица и интервью в m05#context, кода нет |
| Eval агента по траектории | 🟡 | Упоминания в m05, m06#types и m06#interview |

## Прочие находки

- В m01 в meta-row написано «7 интерактивных визуализаций», а на странице 8 `div.viz`.
- Расхождение в projects.html: в таблице у проекта C указан модуль 5, в диаграмме Ганта на неделе 8 — модули 5 и 6.
- В m04#interview для judge дан критерий «согласие > 85%», а m06#judge объясняет, почему одного agreement недостаточно (нужен κ).
- Модели и цены везде согласованы, «сентябрь 2026», $ за 1M токенов на вход и выход: Fable 5.1 — 10 / 50, Opus 5.5 — 4 / 20, Opus 5 — 5 / 25, Sonnet 5 — 2 / 10, Haiku 4.5 — 1 / 5. В примерах кода по умолчанию `claude-opus-5`. Новые модули должны использовать те же имена.
