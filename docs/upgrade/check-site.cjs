// Проверка сайта курса в headless Chromium: рендер, схемы, спойлеры, навигация, прогресс, внутренние ссылки.
// Запуск из корня репозитория:
//   npm i --prefix /tmp/pw playwright@1.64.0 && NODE_PATH=/tmp/pw/node_modules node docs/upgrade/check-site.cjs [m07 m10 ...]
// Без аргументов проверяет index.html и все страницы из MODULES в assets/course.js.
const { chromium } = require("playwright");
const http = require("http");
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "../../ai-engineer-course");
const ids = [...fs.readFileSync(path.join(ROOT, "assets/course.js"), "utf8").matchAll(/\{ id: "([^"]+)"/g)].map(m => m[1]);
const only = process.argv.slice(2);
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css" };

const server = http.createServer((req, res) => {
  const p = path.join(ROOT, decodeURIComponent(req.url.split("?")[0].split("#")[0]));
  if (!p.startsWith(ROOT) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { "content-type": TYPES[path.extname(p)] || "application/octet-stream" });
  fs.createReadStream(p).pipe(res);
});

const htmlCache = {};
const raw = f => (htmlCache[f] ??= fs.existsSync(path.join(ROOT, f)) ? fs.readFileSync(path.join(ROOT, f), "utf8") : null);

async function checkPage(browser, base, file, id) {
  const problems = [];
  const page = await browser.newPage();
  page.on("console", m => { if (m.type() === "error" && !m.text().startsWith("Failed to load resource")) problems.push("console: " + m.text()); });
  page.on("response", r => { if (r.status() >= 400 && !r.url().endsWith("/favicon.ico")) problems.push("HTTP " + r.status() + ": " + r.url()); });
  page.on("pageerror", e => problems.push("pageerror: " + e.message));
  page.on("dialog", d => { problems.push("dialog: " + d.message()); d.dismiss(); });
  const resp = await page.goto(base + file, { waitUntil: "load" });
  if (!resp || resp.status() !== 200) { problems.push("HTTP " + (resp && resp.status())); await page.close(); return { problems }; }

  const stats = await page.evaluate(() => ({
    h2: [...document.querySelectorAll("main h2")].map(h => h.id),
    q: document.querySelectorAll("details.q:not(.interview)").length,
    iq: document.querySelectorAll("details.q.interview").length,
    viz: document.querySelectorAll(".viz").length,
    task: document.querySelectorAll(".task").length,
    code: document.querySelectorAll("pre > code").length,
    emptyViz: [...document.querySelectorAll(".viz")].filter(v => {
      if (v.querySelector("svg *, canvas, table")) return false;
      const c = v.cloneNode(true); c.querySelectorAll(".viz-title, .viz-sub").forEach(x => x.remove());
      return c.textContent.trim().length < 20;
    }).map(v => v.id || "(без id)"),
    saves: [...document.querySelectorAll("input[data-save]")].map(c => c.dataset.save),
  }));

  if (id && id !== "index") {
    for (const need of ["practice", "selfcheck", "interview", "links"]) if (!stats.h2.includes(need)) problems.push("нет раздела h2#" + need);
    const bad = stats.saves.filter(s => !s.startsWith(id + "-") && id !== "projects");
    if (bad.length) problems.push("data-save не с префиксом " + id + ": " + bad.join(", "));
    if (stats.emptyViz.length) problems.push("пустые схемы: " + stats.emptyViz.join(", "));

    // Интерактив: двигаем ползунки, переключаем чекбоксы и селекты, жмём кнопки внутри схем.
    await page.evaluate(async () => {
      const fire = (el, t) => el.dispatchEvent(new Event(t, { bubbles: true }));
      for (const v of document.querySelectorAll(".viz")) {
        for (const r of v.querySelectorAll("input[type=range]")) { for (const val of [r.min, r.max, r.defaultValue]) { r.value = val; fire(r, "input"); fire(r, "change"); } }
        for (const c of v.querySelectorAll("input[type=checkbox]:not([disabled])")) { c.click(); c.click(); }
        for (const s of v.querySelectorAll("select")) { for (const o of s.options) { s.value = o.value; fire(s, "change"); } }
        for (const t of v.querySelectorAll("textarea, input[type=text]")) { t.value += " тест"; fire(t, "input"); }
        for (const b of v.querySelectorAll("button")) b.click();
      }
    });
    await page.waitForTimeout(2500); // анимации на setInterval

    const closed = await page.evaluate(() => {
      const ds = [...document.querySelectorAll("details.q")];
      ds.forEach(d => d.open = true);
      return ds.filter(d => !d.querySelector(".a") || d.querySelector(".a").offsetHeight === 0).length;
    });
    if (closed) problems.push(closed + " спойлеров без видимого ответа");

    const nav = await page.evaluate(() => [...document.querySelectorAll(".module-footer a")].map(a => a.getAttribute("href")));
    const i = ids.indexOf(id);
    const expect = [i > 0 ? ids[i - 1] + ".html" : "index.html", i < ids.length - 1 ? ids[i + 1] + ".html" : "index.html"];
    if (JSON.stringify(nav) !== JSON.stringify(expect)) problems.push("навигация " + JSON.stringify(nav) + " ≠ " + JSON.stringify(expect));

    const prog = await page.evaluate(async id => {
      const b = document.querySelector(".done-toggle button"); b.click();
      const on = JSON.parse(localStorage.getItem("aiec:done") || "[]").includes(id);
      const side = document.querySelector(".sidebar a.current")?.classList.contains("done");
      b.click();
      const off = !JSON.parse(localStorage.getItem("aiec:done") || "[]").includes(id);
      return on && side && off;
    }, id);
    if (!prog) problems.push("отметка «пройден» не работает");
  } else if (id === "index") {
    const cards = await page.evaluate(() => document.querySelectorAll("#module-list a.card").length);
    if (cards !== ids.length) problems.push("карточек " + cards + " ≠ модулей " + ids.length);
  }

  // Внутренние ссылки: файл существует, якорь есть в исходнике.
  const links = await page.evaluate(() => [...document.querySelectorAll("main a[href]")].map(a => a.getAttribute("href")));
  const external = [];
  for (const h of links) {
    if (/^(https?:|mailto:)/.test(h)) { external.push(h); continue; }
    const [f, anchor] = h.split("#");
    const target = f || file;
    const src = raw(target);
    if (src == null) { problems.push("битая ссылка: " + h); continue; }
    if (anchor && !new RegExp(`id="${anchor}"`).test(src) && !/^s\d+$/.test(anchor)) problems.push("нет якоря: " + h);
  }
  await page.close();
  return { problems, stats, external };
}

(async () => {
  await new Promise(r => server.listen(0, r));
  const base = `http://127.0.0.1:${server.address().port}/`;
  const browser = await chromium.launch().catch(() => chromium.launch({ channel: "chrome" })); // нет скачанного Chromium — берём установленный Chrome
  const pages = [["index.html", "index"], ...ids.map(id => [id + ".html", id])].filter(([, id]) => !only.length || only.includes(id));
  let failed = 0;
  const ext = new Set();
  for (const [file, id] of pages) {
    const { problems, stats, external = [] } = await checkPage(browser, base, file, id);
    external.forEach(u => ext.add(u));
    const s = stats ? `h2=${stats.h2.length} viz=${stats.viz} task=${stats.task} q=${stats.q} iq=${stats.iq} code=${stats.code}` : "";
    console.log(`${problems.length ? "✗" : "✓"} ${file.padEnd(16)} ${s}`);
    problems.forEach(p => console.log("    - " + p));
    if (problems.length) failed++;
  }
  fs.writeFileSync(path.join(__dirname, ".external-links.txt"), [...ext].sort().join("\n") + "\n");
  console.log(`\n${pages.length - failed}/${pages.length} страниц без проблем; внешних ссылок: ${ext.size} (список в docs/upgrade/.external-links.txt)`);
  await browser.close();
  server.close();
  process.exit(failed ? 1 : 0);
})();
