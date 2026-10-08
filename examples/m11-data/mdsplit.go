package data

import (
	"strings"
	"unicode/utf8"
)

// Chunk — кусок markdown с путём заголовков. Эмбеддим Heading + "\n\n" + Text:
// путь заголовков даёт чанку контекст, которого нет в самом тексте.
type Chunk struct {
	Heading string // "Тарифы › Возвраты › Сроки"
	Text    string
}

// SplitMarkdown режет markdown по ATX-заголовкам (# … ######), затем пакует блоки
// секции (абзацы, списки, таблицы, код) в чанки до maxRunes. Блок не режется никогда:
// код-блок и таблица уходят целиком, даже если длиннее maxRunes.
// ponytail: блок длиннее maxRunes остаётся одним чанком; прозу дорезайте ChunkSection из модуля 5.
func SplitMarkdown(md string, maxRunes int) []Chunk {
	var (
		out    []Chunk
		path   []string // текущий путь заголовков по уровням
		blocks []string // блоки текущей секции
		cur    []string // строки текущего блока
		fence  string   // открытый ``` или ~~~
	)
	endBlock := func() {
		if t := strings.TrimSpace(strings.Join(cur, "\n")); t != "" {
			blocks = append(blocks, t)
		}
		cur = nil
	}
	endSection := func() {
		endBlock()
		heading := strings.Join(nonEmpty(path), " › ")
		var buf []string
		size := 0
		for _, b := range blocks {
			n := utf8.RuneCountInString(b)
			if size > 0 && size+n > maxRunes {
				out = append(out, Chunk{heading, strings.Join(buf, "\n\n")})
				buf, size = nil, 0
			}
			buf = append(buf, b)
			size += n
		}
		if len(buf) > 0 {
			out = append(out, Chunk{heading, strings.Join(buf, "\n\n")})
		}
		blocks = nil
	}
	for _, line := range strings.Split(md, "\n") {
		trim := strings.TrimSpace(line)
		switch {
		case fence != "": // внутри код-блока: ни заголовков, ни разрывов
			cur = append(cur, line)
			if strings.HasPrefix(trim, fence) {
				fence = ""
				endBlock()
			}
		case strings.HasPrefix(trim, "```") || strings.HasPrefix(trim, "~~~"):
			endBlock()
			fence = trim[:3]
			cur = append(cur, line)
		case headingLevel(trim) > 0:
			endSection()
			lvl := headingLevel(trim)
			for len(path) < lvl {
				path = append(path, "")
			}
			path = append(path[:lvl-1], strings.TrimSpace(trim[lvl:]))
		case trim == "":
			endBlock()
		default:
			cur = append(cur, line)
		}
	}
	endSection()
	return out
}

// headingLevel: "## Title" → 2; "#hashtag" и "####### x" — не заголовки.
func headingLevel(s string) int {
	n := 0
	for n < len(s) && s[n] == '#' {
		n++
	}
	if n == 0 || n > 6 || (n < len(s) && s[n] != ' ') {
		return 0
	}
	return n
}

func nonEmpty(ss []string) []string {
	var out []string
	for _, s := range ss {
		if s != "" {
			out = append(out, s)
		}
	}
	return out
}
