"""PDF → markdown + таблицы + отчёт о парсинге через Docling (модуль 11, урок «Ingestion»).

Запуск без аргументов генерирует тестовый sample.pdf (заголовки, список, таблица) и разбирает его:
    uv run --with docling==2.135.0 --with pymupdf==1.28.2 python pdf_to_md.py
Свой файл:
    uv run --with docling==2.135.0 python pdf_to_md.py path/to/file.pdf

Первый запуск ставит torch и скачивает модели layout и TableFormer с Hugging Face (сотни МБ, минуты).
PyMuPDF нужен только для генерации тестового PDF; его лицензия AGPL-3.0 — см. урок, прежде чем брать его в продукт.
"""
import hashlib
import json
import sys
import time
from pathlib import Path

SAMPLE_HTML = """
<style>table{border-collapse:collapse} th,td{border:1px solid black;padding:4px 8px}</style>
<h1>Политика возвратов</h1>
<p>Возврат за годовую подписку оформляется в личном кабинете, раздел «Платежи».</p>
<h2>Сроки</h2>
<ul><li>на карту — до 10 рабочих дней;</li><li>на баланс аккаунта — сразу после одобрения.</li></ul>
<h2>Тарифы</h2>
<table>
<tr><th>План</th><th>Срок на возврат</th><th>Комиссия</th></tr>
<tr><td>Месяц</td><td>3 дня</td><td>0%</td></tr>
<tr><td>Год</td><td>14 дней</td><td>0%</td></tr>
</table>
<p>Вопросы — через форму обратной связи в приложении.</p>
"""


def make_sample(path: Path) -> None:
    import pymupdf  # только для теста: собираем PDF из HTML с кириллицей

    story = pymupdf.Story(html=SAMPLE_HTML)
    writer = pymupdf.DocumentWriter(str(path))
    page = pymupdf.paper_rect("a4")
    more = True
    while more:
        dev = writer.begin_page(page)
        more, _ = story.place(page + (50, 50, -50, -50))
        story.draw(dev)
        writer.end_page()
    writer.close()


def parse(path: Path) -> tuple[str, dict]:
    from docling.document_converter import DocumentConverter

    t0 = time.perf_counter()
    res = DocumentConverter().convert(path)  # OCR растровых областей включён по умолчанию
    doc = res.document
    md = doc.export_to_markdown()
    pages = doc.num_pages()
    report = {
        "file": path.name,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest()[:16],  # версия источника
        "status": str(res.status),
        "pages": pages,
        "chars_per_page": round(len(md) / max(pages, 1)),
        "headings": [t.text for t in doc.texts if t.label == "section_header"],
        "tables": [t.export_to_dataframe(doc=doc).to_dict(orient="records") for t in doc.tables],
        "seconds": round(time.perf_counter() - t0, 1),
    }
    # Дешёвые проверки качества: пустой текст почти всегда значит скан без OCR или битые шрифты.
    report["warnings"] = [w for w, bad in [
        ("мало текста на страницу: скан или битый ToUnicode", report["chars_per_page"] < 200),
        ("нет заголовков: структура потеряна, чанкинг по секциям не сработает", not report["headings"]),
    ] if bad]
    return md, report


if __name__ == "__main__":
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).with_name("sample.pdf")
    if len(sys.argv) == 1:
        make_sample(src)
    md, report = parse(src)
    print(md)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if len(sys.argv) == 1:  # самопроверка на тестовом PDF
        assert "Политика возвратов" in report["headings"], report["headings"]
        assert len(report["tables"]) == 1 and len(report["tables"][0]) == 2, report["tables"]
        assert "| Год" in md, "таблица должна остаться таблицей в markdown"
        print("OK: заголовки и таблица сохранены")
