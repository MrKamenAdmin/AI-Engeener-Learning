// Package data — примеры модуля 11 «Данные и governance»:
// PII-редактор (pii.go), near-duplicate детектор (dedup.go), markdown-сплиттер (mdsplit.go).
package data

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"regexp"
	"sort"
	"strings"
)

// Finding — найденный фрагмент PII: байтовые границы в исходном тексте.
type Finding struct {
	Kind       string // EMAIL, PHONE, CARD, INN, SNILS, PASSPORT
	Start, End int
	Value      string
}

type detector struct {
	kind  string
	re    *regexp.Regexp
	valid func(digits string) bool // nil — проверки нет, только формат
}

// Порядок = приоритет при пересечениях: сначала то, что подтверждено контрольной суммой.
var detectors = []detector{
	{"CARD", regexp.MustCompile(`\b(?:\d{4}[ -]){3}\d{4}\b|\b\d{13,19}\b`), luhn},
	{"SNILS", regexp.MustCompile(`\b\d{3}-?\d{3}-?\d{3}[ -]?\d{2}\b`), snils},
	{"INN", regexp.MustCompile(`\b\d{12}\b|\b\d{10}\b`), inn},
	{"EMAIL", regexp.MustCompile(`[\w.+-]+@[\w-]+(?:\.[\w-]+)+`), nil},
	{"PHONE", regexp.MustCompile(`(?:\+7|\b8)[ (-]*\d{3}[ )-]*\d{3}[ -]?\d{2}[ -]?\d{2}\b`), nil},
	// Паспорт РФ: 4 цифры серии + 6 цифр номера. Контрольной суммы нет — без пробела
	// перед номером не ловим, иначе каждое 10-значное число станет «паспортом».
	{"PASSPORT", regexp.MustCompile(`\b\d{2} ?\d{2} \d{6}\b`), nil},
}

// Find возвращает непересекающиеся находки, отсортированные по позиции.
func Find(text string) []Finding {
	var all []Finding
	for _, d := range detectors {
		for _, m := range d.re.FindAllStringIndex(text, -1) {
			v := text[m[0]:m[1]]
			if d.valid != nil && !d.valid(digitsOnly(v)) {
				continue
			}
			all = append(all, Finding{Kind: d.kind, Start: m[0], End: m[1], Value: v})
		}
	}
	// all уже упорядочен по приоритету детектора; берём находку, если она не пересекается с принятыми.
	// ponytail: O(n²) по числу находок — для сообщений и документов хватает, для гигабайтных дампов нужен interval tree.
	var out []Finding
	for _, f := range all {
		overlap := false
		for _, o := range out {
			if f.Start < o.End && o.Start < f.End {
				overlap = true
				break
			}
		}
		if !overlap {
			out = append(out, f)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Start < out[j].Start })
	return out
}

// Redactor заменяет PII. Key == nil — маскирование ([CARD]); иначе — псевдонимизация:
// [CARD:3f9a1c2b] = HMAC(key, тип+нормализованное значение). Одно и то же значение даёт
// один и тот же токен во всех документах — можно считать, джойнить и искать по нему, не видя PII.
type Redactor struct {
	Key []byte
}

// Redact возвращает текст без PII и словарь токен → исходное значение.
// Словарь (vault) нужен только для обратной подстановки: храните его отдельно,
// с отдельными правами и сроком жизни, или не храните вовсе, если восстановление не нужно.
func (r Redactor) Redact(text string) (string, map[string]string) {
	var b strings.Builder
	vault := map[string]string{}
	last := 0
	for _, f := range Find(text) {
		b.WriteString(text[last:f.Start])
		tok := "[" + f.Kind + "]"
		if r.Key != nil {
			tok = "[" + f.Kind + ":" + r.pseudonym(f) + "]"
			vault[tok] = f.Value
		}
		b.WriteString(tok)
		last = f.End
	}
	b.WriteString(text[last:])
	return b.String(), vault
}

func (r Redactor) pseudonym(f Finding) string {
	norm := strings.ToLower(f.Value)
	if f.Kind != "EMAIL" {
		norm = digitsOnly(f.Value)
		if f.Kind == "PHONE" && strings.HasPrefix(norm, "8") {
			norm = "7" + norm[1:] // 8 912… и +7 912… — один и тот же номер
		}
	}
	m := hmac.New(sha256.New, r.Key)
	m.Write([]byte(f.Kind + ":" + norm))
	return hex.EncodeToString(m.Sum(nil)[:4]) // 32 бита: читаемо; для больших баз берите 8+ байт
}

// Restore — обратная подстановка, например в ответе модели перед показом пользователю.
func Restore(text string, vault map[string]string) string {
	pairs := make([]string, 0, 2*len(vault))
	for tok, v := range vault {
		pairs = append(pairs, tok, v)
	}
	return strings.NewReplacer(pairs...).Replace(text)
}

func digitsOnly(s string) string {
	return strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, s)
}

// luhn — контрольная сумма номеров карт (ISO/IEC 7812).
func luhn(d string) bool {
	sum := 0
	for i := len(d) - 1; i >= 0; i-- {
		n := int(d[i] - '0')
		if (len(d)-1-i)%2 == 1 {
			n *= 2
			if n > 9 {
				n -= 9
			}
		}
		sum += n
	}
	return sum%10 == 0
}

// inn — контрольные цифры ИНН: 10 знаков (организация) или 12 (физлицо, ИП).
func inn(d string) bool {
	check := func(w []int) int {
		s := 0
		for i, k := range w {
			s += k * int(d[i]-'0')
		}
		return s % 11 % 10
	}
	switch len(d) {
	case 10:
		return check([]int{2, 4, 10, 3, 5, 9, 4, 6, 8}) == int(d[9]-'0')
	case 12:
		return check([]int{7, 2, 4, 10, 3, 5, 9, 4, 6, 8}) == int(d[10]-'0') &&
			check([]int{3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8}) == int(d[11]-'0')
	}
	return false
}

// snils — контрольное число СНИЛС: веса 9…1 по первым 9 цифрам, затем mod 101 (100 и 101 → 00).
// Номера не больше 001-001-998 не проверяются — для детектора считаем их невалидными.
func snils(d string) bool {
	if len(d) != 11 || d[:9] <= "001001998" {
		return false
	}
	s := 0
	for i := 0; i < 9; i++ {
		s += int(d[i]-'0') * (9 - i)
	}
	s %= 101
	if s == 100 {
		s = 0
	}
	return s == int(d[9]-'0')*10+int(d[10]-'0')
}
