package data

import (
	"crypto/sha256"
	"hash/fnv"
	"math/bits"
	"strings"
	"unicode"
)

// Normalize — общий шаг для точных и нечётких дублей: нижний регистр,
// только буквы и цифры, пробелы схлопнуты. Даты, id и подписи вырезайте до этого.
func Normalize(s string) []string {
	return strings.FieldsFunc(strings.ToLower(s), func(r rune) bool {
		return !unicode.IsLetter(r) && !unicode.IsDigit(r)
	})
}

// SimHash — 64-битный отпечаток по шинглам из 3 слов (Charikar, 2002).
// Похожие тексты дают отпечатки с малым расстоянием Хэмминга.
func SimHash(words []string) uint64 {
	const k = 3
	var acc [64]int
	add := func(shingle string) {
		h := fnv.New64a()
		h.Write([]byte(shingle))
		x := h.Sum64()
		for i := 0; i < 64; i++ {
			if x&(1<<i) != 0 {
				acc[i]++
			} else {
				acc[i]--
			}
		}
	}
	if len(words) < k {
		add(strings.Join(words, " "))
	}
	for i := 0; i+k <= len(words); i++ {
		add(strings.Join(words[i:i+k], " "))
	}
	var out uint64
	for i, v := range acc {
		if v > 0 {
			out |= 1 << i
		}
	}
	return out
}

func Hamming(a, b uint64) int { return bits.OnesCount64(a ^ b) }

// Dedup ищет точные (sha256 нормализованного текста) и почти-дубли (SimHash ≤ MaxDist бит).
// Индекс — 4 таблицы по 16-битным блокам отпечатка: при расстоянии ≤ 3 хотя бы один блок
// совпадает целиком (принцип Дирихле), поэтому сравниваем только с кандидатами из корзин,
// а не со всем корпусом (Manku et al., 2007).
type Dedup struct {
	MaxDist int // 3 для 64 бит — значение из статьи Manku et al.; подбирайте на своей разметке
	exact   map[[32]byte]string
	bands   [4]map[uint16][]entry
}

type entry struct {
	id   string
	hash uint64
}

func NewDedup(maxDist int) *Dedup {
	d := &Dedup{MaxDist: maxDist, exact: map[[32]byte]string{}}
	for i := range d.bands {
		d.bands[i] = map[uint16][]entry{}
	}
	return d
}

// Add возвращает id найденного дубля и расстояние (0 — точная копия).
// Если дубля нет, документ добавляется в индекс и возвращается "".
// ponytail: корректно только для MaxDist ≤ 3 (4 блока); для большего порога — больше таблиц с перестановками битов.
func (d *Dedup) Add(id, text string) (dupOf string, dist int) {
	words := Normalize(text)
	key := sha256.Sum256([]byte(strings.Join(words, " ")))
	if prev, ok := d.exact[key]; ok {
		return prev, 0
	}
	h := SimHash(words)
	for i := range d.bands {
		for _, e := range d.bands[i][uint16(h>>(16*i))] {
			if dist := Hamming(h, e.hash); dist <= d.MaxDist {
				return e.id, dist
			}
		}
	}
	d.exact[key] = id
	for i := range d.bands {
		b := uint16(h >> (16 * i))
		d.bands[i][b] = append(d.bands[i][b], entry{id, h})
	}
	return "", 0
}
