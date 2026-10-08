// Общий JS курса: навигация, тема, прогресс, табы, подсветка кода.
// Страница модуля: <body data-module="m01"> + <main class="content">…</main> + этот скрипт.
(function () {
  const MODULES = [
    { id: "m00", title: "Что Go-разработчик уже умеет", desc: "Конкурентность, надёжность, observability, БД и тесты — как это переносится в AI engineering." },
    { id: "m01", title: "Как работают LLM", desc: "Токены, BPE, attention, сэмплинг, эмбеддинги, обучение, KV-cache, квантизация, reasoning." },
    { id: "m02", title: "LLM API", desc: "Messages, стриминг SSE, structured output, tool use, prompt caching, batch, мультимодальность." },
    { id: "m03", title: "Prompt engineering как инженерия", desc: "Инструкции, few-shot, XML, CoT, длинный контекст, версионирование, регрессия, prompt injection." },
    { id: "m04", title: "Инструменты", desc: "Python для Go-разработчика, FastAPI, pandas, SDK, LangChain/LlamaIndex/LangGraph, Jupyter, coding agents." },
    { id: "m05", title: "RAG", desc: "Чанкинг, эмбеддинги, векторные БД, HNSW, гибридный поиск, reranking, метрики, agentic RAG, GraphRAG, отладка." },
    { id: "m06", title: "Агенты и MCP", desc: "Агентный цикл, паттерны Anthropic, context engineering, long-horizon задачи, MCP-сервер на Go, мультиагентность." },
    { id: "m07", title: "Безопасность и guardrails", desc: "Модель угроз, OWASP Top 10 for LLM 2025, непрямые инъекции, excessive agency, guardrails на Go, red-teaming." },
    { id: "m08", title: "Evals", desc: "Error analysis, golden dataset, LLM-as-a-judge и κ, bootstrap, A/B, траектории агентов, quality gate в CI." },
    { id: "m09", title: "Продакшн", desc: "LLM-шлюз, ретраи, fallback, бюджеты, кэши, OpenTelemetry GenAI, SLO, дрейф, фича-флаги." },
    { id: "m10", title: "Свои модели: инференс и адаптация", desc: "vLLM и serving, батчинг, квантизация, VRAM, экономика self-host; LoRA, DPO, дистилляция, метрики ML." },
    { id: "m11", title: "Данные и governance", desc: "Ingestion и парсинг, PII, версии датасетов, разметка, data flywheel, логи диалогов, GDPR/152-ФЗ, EU AI Act." },
    { id: "m12", title: "AI system design", desc: "Скелет ответа и 5 разборов: поддержка, поиск, text-to-SQL, суммаризация, LLM-gateway." },
    { id: "m13", title: "Senior-практики", desc: "ADR и design review, выбор модели и вендора, TCO, SLO и постмортемы, метрики для бизнеса, стандарты команды." },
    { id: "m14", title: "Подготовка к собеседованию", desc: "Этапы, эталонные ответы, рассказ о проектах, take-home, behavioral для senior, чек-лист." },
    { id: "m15", title: "Капстоун", desc: "Итоговый продакшн-агент: RAG, MCP, guardrails, evals в CI, трейсинг, стоимость и защита design doc." },
    { id: "m16", title: "Мультимодальность и голос", desc: "Факультатив: vision и документы, STT → LLM → TTS против speech-to-speech, бюджет латентности, barge-in." },
    { id: "projects", title: "Сквозные проекты", desc: "План по неделям: RAG на Go + pgvector, eval-набор, агент с MCP, red-team, трейсинг, кэш, дашборд." },
  ];
  const KEY = "aiec:done";
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
  };
  const done = () => new Set(store.get(KEY, []));
  const setDone = (id, on) => { const s = done(); on ? s.add(id) : s.delete(id); store.set(KEY, [...s]); };
  const pct = () => Math.round(100 * MODULES.filter(m => done().has(m.id)).length / MODULES.length);
  const num = m => m.id === "projects" ? "★" : String(+m.id.slice(1));
  const href = m => m.id + ".html";

  // ---- Тема ----
  const root = document.documentElement;
  const savedTheme = store.get("aiec:theme", null);
  if (savedTheme) root.dataset.theme = savedTheme;
  function isDark() { return root.dataset.theme ? root.dataset.theme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches; }
  function toggleTheme() {
    root.dataset.theme = isDark() ? "light" : "dark";
    store.set("aiec:theme", root.dataset.theme);
    document.querySelectorAll("[data-theme-btn]").forEach(b => b.textContent = isDark() ? "☀︎ Светлая" : "☾ Тёмная");
    document.dispatchEvent(new CustomEvent("themechange"));
  }

  // ---- Подсветка кода (минимальная, без зависимостей) ----
  const KW = {
    go: "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var nil true false err error string int int64 float64 float32 bool byte any context",
    python: "and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield self",
    sql: "select from where join left inner on group by order limit insert into values update set delete create table index using as and or not null primary key references with",
  };
  function esc(s) { return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }
  function highlight(code, lang) {
    const kw = new Set((KW[lang] || "").split(" "));
    const lineC = lang === "python" || lang === "bash" || lang === "yaml" ? "#[^\\n]*" : lang === "sql" ? "--[^\\n]*" : "\\/\\/[^\\n]*|\\/\\*[\\s\\S]*?\\*\\/";
    const re = new RegExp(`(${lineC})|("""[\\s\\S]*?"""|\`[^\`]*\`|"(?:\\\\.|[^"\\\\\\n])*"|'(?:\\\\.|[^'\\\\\\n])*')|(\\b\\d+(?:\\.\\d+)?\\b)|([A-Za-z_][A-Za-z0-9_]*)`, "g");
    let out = "", last = 0, m;
    while ((m = re.exec(code))) {
      out += esc(code.slice(last, m.index)); last = re.lastIndex;
      if (m[1]) out += `<span class="tok-c">${esc(m[1])}</span>`;
      else if (m[2]) out += `<span class="tok-s">${esc(m[2])}</span>`;
      else if (m[3]) out += `<span class="tok-n">${m[3]}</span>`;
      else out += kw.has(lang === "sql" ? m[4].toLowerCase() : m[4]) ? `<span class="tok-k">${m[4]}</span>` : m[4];
    }
    return out + esc(code.slice(last));
  }

  function el(tag, attrs, html) { const e = document.createElement(tag); Object.assign(e, attrs || {}); if (html != null) e.innerHTML = html; return e; }

  function buildChrome(currentId) {
    const main = document.querySelector("main.content");
    if (!main) return;
    const top = el("header", { className: "topbar" }, `
      <button class="btn menu-btn" aria-label="Меню">☰</button>
      <a class="brand" href="index.html">AI Engineer · курс</a>
      <span class="spacer"></span>
      <span class="progress-mini" title="Прогресс курса"><i></i></span>
      <button class="btn" data-theme-btn></button>`);
    document.body.prepend(top);
    top.querySelector(".menu-btn").onclick = () => document.body.classList.toggle("nav-open");
    top.querySelector("[data-theme-btn]").onclick = toggleTheme;
    top.querySelector("[data-theme-btn]").textContent = isDark() ? "☀︎ Светлая" : "☾ Тёмная";

    const layout = el("div", { className: "layout" });
    const side = el("nav", { className: "sidebar", ariaLabel: "Модули" });
    main.replaceWith(layout); layout.append(side, main);

    const d = done();
    let html = `<h4>Модули</h4>`;
    for (const m of MODULES) {
      html += `<a href="${href(m)}" class="${m.id === currentId ? "current" : ""} ${d.has(m.id) ? "done" : ""}"><span class="n">${num(m)}</span><span>${m.title}</span></a>`;
      if (m.id === currentId) {
        const hs = [...main.querySelectorAll("h2")];
        hs.forEach((h, i) => { if (!h.id) h.id = "s" + (i + 1); });
        if (hs.length) html += `<div class="toc">${hs.map(h => `<a href="#${h.id}">${h.textContent}</a>`).join("")}</div>`;
      }
    }
    side.innerHTML = html;
    side.addEventListener("click", e => { if (e.target.closest("a")) document.body.classList.remove("nav-open"); });
    top.querySelector(".progress-mini > i").style.width = pct() + "%";

    const idx = MODULES.findIndex(m => m.id === currentId);
    if (idx >= 0) {
      const toggle = el("div", { className: "done-toggle" });
      const b = el("button", { className: "btn" });
      const paint = () => { const on = done().has(currentId); b.className = "btn " + (on ? "ok" : "primary"); b.textContent = on ? "✓ Модуль пройден (снять отметку)" : "Отметить модуль пройденным"; };
      b.onclick = () => { setDone(currentId, !done().has(currentId)); paint(); side.querySelector("a.current")?.classList.toggle("done", done().has(currentId)); top.querySelector(".progress-mini > i").style.width = pct() + "%"; };
      paint(); toggle.append(b); main.append(toggle);
      const prev = MODULES[idx - 1], next = MODULES[idx + 1];
      main.append(el("div", { className: "module-footer" },
        `<span>${prev ? `<a class="btn" href="${href(prev)}">← ${num(prev)}. ${prev.title}</a>` : `<a class="btn" href="index.html">← Оглавление</a>`}</span>
         <span>${next ? `<a class="btn primary" href="${href(next)}">${num(next)}. ${next.title} →</a>` : `<a class="btn primary" href="index.html">К оглавлению →</a>`}</span>`));
    }
  }

  function enhanceCode() {
    document.querySelectorAll("pre > code").forEach(c => {
      const lang = (c.className.match(/lang-(\w+)/) || [])[1];
      if (lang && !c.dataset.hl) { c.innerHTML = highlight(c.textContent, lang); c.dataset.hl = 1; }
      const pre = c.parentElement;
      if (pre.querySelector(".copy")) return;
      const b = el("button", { className: "btn copy", type: "button", textContent: "копировать" });
      b.onclick = () => navigator.clipboard?.writeText(c.textContent).then(() => { b.textContent = "✓"; setTimeout(() => b.textContent = "копировать", 1200); });
      pre.append(b);
    });
  }

  // <div class="tabs"><div class="tab-panel" data-tab="Go">…</div><div class="tab-panel" data-tab="Python">…</div></div>
  function enhanceTabs() {
    document.querySelectorAll(".tabs").forEach(t => {
      if (t.querySelector(".tab-bar")) return;
      const panels = [...t.querySelectorAll(":scope > .tab-panel")];
      const bar = el("div", { className: "tab-bar", role: "tablist" });
      panels.forEach((p, i) => {
        const b = el("button", { type: "button", textContent: p.dataset.tab || "Tab " + (i + 1) });
        b.onclick = () => { panels.forEach(x => x.classList.remove("active")); bar.querySelectorAll("button").forEach(x => x.classList.remove("active")); p.classList.add("active"); b.classList.add("active"); };
        bar.append(b);
        if (i === 0) { p.classList.add("active"); b.classList.add("active"); }
      });
      t.prepend(bar);
    });
  }

  // Чекбоксы в заданиях сохраняются: <input type="checkbox" data-save="m05-t1">
  function enhanceChecks() {
    document.querySelectorAll("input[type=checkbox][data-save]").forEach(cb => {
      const k = "aiec:cb:" + cb.dataset.save;
      cb.checked = store.get(k, false);
      cb.onchange = () => store.set(k, cb.checked);
    });
  }

  function buildIndex() {
    const list = document.getElementById("module-list");
    if (!list) return;
    const d = done();
    list.innerHTML = MODULES.map(m => `<a class="card ${d.has(m.id) ? "done" : ""}" href="${href(m)}"><div class="num">${m.id === "projects" ? "Практика" : "Модуль " + num(m)}</div><h3>${m.title}</h3><p>${m.desc}</p></a>`).join("");
    const bar = document.querySelector(".progress-big > i"); if (bar) bar.style.width = pct() + "%";
    const t = document.getElementById("progress-text");
    if (t) t.textContent = `Пройдено ${MODULES.filter(m => d.has(m.id)).length} из ${MODULES.length} (${pct()}%)`;
    const reset = document.getElementById("progress-reset");
    if (reset) reset.onclick = () => { store.set(KEY, []); buildIndex(); };
  }

  // Утилиты для визуализаций в модулях.
  window.Course = {
    MODULES, isDark,
    css: name => getComputedStyle(root).getPropertyValue(name).trim(),
    svg(tag, attrs, parent) { const e = document.createElementNS("http://www.w3.org/2000/svg", tag); for (const k in attrs || {}) e.setAttribute(k, attrs[k]); if (parent) parent.append(e); return e; },
    // Привязка ползунка к <output> рядом: bindRange(input, fmt, onChange)
    bindRange(input, fmt, cb) { const out = input.parentElement.querySelector("output"); const f = () => { if (out) out.textContent = fmt ? fmt(+input.value) : input.value; cb && cb(+input.value); }; input.addEventListener("input", f); f(); },
  };

  function init() {
    const id = document.body.dataset.module;
    if (id) buildChrome(id);
    else {
      const b = document.querySelector("[data-theme-btn]");
      if (b) { b.textContent = isDark() ? "☀︎ Светлая" : "☾ Тёмная"; b.onclick = toggleTheme; }
      buildIndex();
    }
    enhanceTabs(); enhanceCode(); enhanceChecks();
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init); else init();
})();
