# 00 · Разведка: как устроен курс

Состояние на 8 октября 2026, ветка `feature/senior-upgrade`, исходный коммит `bda5060`.

## Архитектура сайта

- Статический сайт без сборки. Всё лежит в `ai-engineer-course/`. На GitHub Pages его публикует `.github/workflows/pages.yml`: при пуше в `master` каталог загружается как артефакт.
- **Один модуль — один HTML-файл** (`m00.html` … `m11.html`, `projects.html`). Урок — это раздел `<h2 id="…">` внутри файла модуля. Отдельных файлов для уроков, Markdown и роутинга нет.
- `assets/course.js` — общий скрипт без зависимостей:
  - массив `MODULES` (`id`, `title`, `desc`) — **единственный источник** порядка модулей. Из него строятся сайдбар, карточки на главной, кнопки «← / →» и процент прогресса;
  - номер в навигации вычисляется из id: `+m.id.slice(1)`. Для `projects` выводится «★»;
  - тема (светлая или тёмная), минимальная подсветка кода (`lang-go|python|sql|bash|yaml`), табы, кнопка «копировать»;
  - `window.Course` — утилиты для схем: `svg()`, `bindRange()`, `css()`, `isDark()`.
- `assets/style.css` — токены цветов (`--accent`, `--ok`, `--warn`, `--bad`, `--violet`, `--teal` и их `-soft`), тёмная тема и все компоненты.
- `index.html` — герой, `#module-list` (заполняет course.js), карточки «Как проходить» с оценкой темпа и SVG «Карта курса» с номерами модулей, нарисованная вручную.

## Формат модуля (шаблон)

```html
<body data-module="mNN">
<main class="content">
  <div class="module-hero">
    <div class="eyebrow">Модуль N</div>
    <h1>Название</h1>
    <p class="lead">Абзац: о чём модуль и что студент вынесет.</p>
    <div class="meta-row"><span class="chip">≈ 8 часов</span><span class="chip">Go + Python</span>
      <span class="chip">3 визуализации</span><span class="chip">на момент написания: сентябрь 2026</span></div>
  </div>

  <h2 id="slug">Урок 1</h2>      <!-- 8–11 тематических разделов -->
  <p>…</p> <ul><li><b>Термин</b>: …</li></ul> <table>…</table>
  <div class="callout go|tip|warn|danger"><span class="title">Аналогия из Go</span>…</div>
  <pre><code class="lang-go">…</code></pre>
  <div class="tabs"><div class="tab-panel" data-tab="Go">…</div><div class="tab-panel" data-tab="Python">…</div></div>
  <div class="viz" id="viz-x"><p class="viz-title">…</p><p class="viz-sub">…</p>
    <div class="controls">…range/select/checkbox…</div><svg id="…" viewBox="…"></svg><div class="readout"></div></div>

  <h2 id="practice">Практика</h2>
  <div class="task"><h3>Задание 1: …</h3><p>…</p><ul>критерии</ul>
    <label class="check"><input type="checkbox" data-save="mNN-t1"> Сделал</label></div>
  <h2 id="selfcheck">Самопроверка</h2>
  <details class="q"><summary>Вопрос?</summary><div class="a"><p>Ответ.</p></div></details>
  <h2 id="interview">Вопросы с собеседований</h2>
  <details class="q interview"><summary>…</summary><div class="a"><p>…</p></div></details>
  <h2 id="links">Источники</h2>
  <ul><li><a href="…" target="_blank" rel="noopener">…</a></li></ul>
</main>
<script src="assets/course.js"></script>
<script>/* схемы: IIFE на каждую, Course.svg + цвета только var(--…) */</script>
```

Типичный объём модуля: 85–150 КБ, 8–11 тематических разделов, 3–8 схем, 3–4 задания, 8–9 вопросов самопроверки, 7–8 вопросов с собеседований, 10–15 источников. Тон: «вы», сжато, режимы отказа и цифры, callout «Аналогия из Go» почти в каждом модуле. Код встроен прямо в HTML (`&lt;`, `&gt;`, `&amp;` экранируются). Отдельных файлов с примерами в проекте не было.

## Прогресс и id

- `localStorage["aiec:done"]` — JSON-массив id пройденных модулей (`"m03"`, `"projects"`). Процент = пройдено / `MODULES.length`.
- `localStorage["aiec:cb:<data-save>"]` — чекбоксы заданий. Ключи `data-save` имеют вид `mNN-tK`; в проектах — `projects-a-1` и т. п.
- `aiec:theme` — тема; `aiec:m11:cards`, `aiec:m11:story` и `m11-rdy-*` — состояние интерактивов модуля «Собеседование».
- Привязка жёсткая: id модуля = имя файла = `data-module` = префикс `data-save`. При перенумерации нужно менять все четыре места, а также eyebrow «Модуль N», `<title>N · …` и текстовые упоминания «модуль N».

## Сквозные проекты

`projects.html` (id `projects`, «★») содержит четыре проекта и финиш:

| Проект | Недели | Модули (старые номера) |
|---|---|---|
| A · RAG на Go + pgvector | 1–3, 6 | 2, 3, 4 |
| B · Eval-набор и CI | 4–6 | 6, 9 |
| C · Агент с MCP-сервером на Go | 7–8 | 5 (+6) |
| D · Продакшнизация | 9–11 | 7 |
| Финиш: README, демо, рассказ | 12 | 10, 11 |

План по неделям хранится в трёх местах: HTML-таблица `#overview`, чипы `proj-meta` у каждого проекта и JS-массив `WEEKS` (поле `mods`) для диаграммы Ганта.

## Перекрёстные ссылки

- `href="mNN.html"` встречается всего один раз (`projects.html` → `m11.html#stories`). Всё остальное — текстовые «модуль N», около 75 мест, плюс таблица в `m00#skills` и `mods` в `projects.html`.

## Локальный запуск

```bash
python3 -m http.server 8765 -d ai-engineer-course   # http://localhost:8765/
```

Та же конфигурация лежит в `.claude/launch.json`.

## Автоматическая проверка

`docs/upgrade/check-site.cjs` открывает в headless Chromium (или в установленном Chrome) главную и все страницы из `MODULES` и проверяет:

- ошибки консоли и 404;
- наличие разделов practice, selfcheck, interview и links;
- префикс `data-save`;
- что схемы не пустые, а их контролы не бросают исключений;
- что спойлеры раскрываются;
- кнопки «← / →»;
- отметку «пройден» и прогресс;
- внутренние ссылки и якоря.

```bash
npm i --prefix /tmp/pw playwright@1.64.0
NODE_PATH=/tmp/pw/node_modules node docs/upgrade/check-site.cjs        # все страницы
NODE_PATH=/tmp/pw/node_modules node docs/upgrade/check-site.cjs m07    # одна страница
```

Перед изменениями скрипт прошёл на всех 14 страницах исходного курса без ошибок.
