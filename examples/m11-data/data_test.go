package data

import (
	"fmt"
	"strings"
	"testing"
)

func TestFind(t *testing.T) {
	cases := []struct {
		text string
		want []string // Kind:Value
	}{
		{"карта 4111 1111 1111 1111, звоните +7 (912) 345-67-89", []string{"CARD:4111 1111 1111 1111", "PHONE:+7 (912) 345-67-89"}},
		{"карта 4111 1111 1111 1112", nil}, // Луна не сходится — не карта
		{"ИНН 7707083893, ИНН ИП 500100732259", []string{"INN:7707083893", "INN:500100732259"}},
		{"номер заказа 7707083894", nil}, // 10 цифр, но контрольная цифра не та
		{"СНИЛС 112-233-445 95", []string{"SNILS:112-233-445 95"}},
		{"СНИЛС 112-233-445 96", nil},
		{"паспорт 45 12 345678, почта Ivan.Petrov+ai@mail.example.ru", []string{"PASSPORT:45 12 345678", "EMAIL:Ivan.Petrov+ai@mail.example.ru"}},
		{"тел. 89123456789 и 8 912 345 67 89", []string{"PHONE:89123456789", "PHONE:8 912 345 67 89"}},
	}
	for _, c := range cases {
		var got []string
		for _, f := range Find(c.text) {
			got = append(got, f.Kind+":"+f.Value)
		}
		if fmt.Sprint(got) != fmt.Sprint(c.want) {
			t.Errorf("Find(%q) = %v, want %v", c.text, got, c.want)
		}
	}
}

func TestRedactModes(t *testing.T) {
	in := "Иван, карта 4111111111111111, тел +79123456789; повторно: 8 912 345-67-89"
	masked, vault := Redactor{}.Redact(in)
	if masked != "Иван, карта [CARD], тел [PHONE]; повторно: [PHONE]" || len(vault) != 0 {
		t.Fatalf("mask: %q %v", masked, vault)
	}

	r := Redactor{Key: []byte("test-key")}
	ps, vault := r.Redact(in)
	if strings.Contains(ps, "4111") || strings.Contains(ps, "912") {
		t.Fatalf("PII осталась: %q", ps)
	}
	// Один номер в двух записях → один токен (детерминированность).
	toks := strings.Fields(strings.NewReplacer(";", " ", ",", " ").Replace(ps))
	var phones []string
	for _, w := range toks {
		if strings.HasPrefix(w, "[PHONE:") {
			phones = append(phones, w)
		}
	}
	if len(phones) != 2 || phones[0] != phones[1] {
		t.Fatalf("ожидали одинаковые токены телефона: %v", phones)
	}
	// Другой ключ — другие токены: псевдонимы не сопоставить без ключа.
	other, _ := Redactor{Key: []byte("other")}.Redact(in)
	if other == ps {
		t.Fatal("токены не зависят от ключа")
	}
	if back := Restore(ps, vault); !strings.Contains(back, "4111111111111111") {
		t.Fatalf("restore: %q", back)
	}
}

func TestChecksums(t *testing.T) {
	for _, d := range []string{"4111111111111111", "5555555555554444", "2200000000000004"} {
		if !luhn(d) {
			t.Errorf("luhn(%s) = false", d)
		}
	}
	if snils("00100199800") {
		t.Error("СНИЛС ≤ 001-001-998 не проверяется и не должен считаться валидным")
	}
}

func TestDedup(t *testing.T) {
	base := "Возврат средств за годовую подписку оформляется в личном кабинете в разделе Платежи. " +
		"Деньги возвращаются на ту же карту в течение десяти рабочих дней после одобрения заявки. " +
		"Если оплата была подарочным сертификатом, возврат возможен только на баланс аккаунта. " +
		"Частичный возврат за неиспользованные месяцы рассчитывается пропорционально и без комиссии. " +
		"По вопросам возврата пишите в поддержку через форму обратной связи в приложении."
	d := NewDedup(3)
	if dup, _ := d.Add("v1", base); dup != "" {
		t.Fatalf("первый документ не может быть дублем: %s", dup)
	}
	if dup, dist := d.Add("copy", "  "+strings.ToUpper(base)+"!!"); dup != "v1" || dist != 0 {
		t.Fatalf("точный дубль после нормализации: %q %d", dup, dist)
	}
	near := strings.Replace(base, "десяти рабочих", "десяти календарных", 1)
	if dup, dist := d.Add("v2", near); dup != "v1" {
		t.Fatalf("почти-дубль не найден, расстояние до v1 = %d", Hamming(SimHash(Normalize(base)), SimHash(Normalize(near))))
	} else {
		t.Logf("v2 ~ v1, Hamming = %d", dist)
	}
	other := "Для подключения API создайте ключ в консоли, ограничьте его права и храните в секрет-менеджере, а не в коде."
	if dup, _ := d.Add("api", other); dup != "" {
		t.Fatalf("ложный дубль: %s", dup)
	}
}

func TestSplitMarkdown(t *testing.T) {
	md := `Вступление без заголовка.

# Тарифы
Общий абзац.

## Возвраты
| План | Срок |
|------|------|
| Год  | 14 дней |

` + "```go\n// # это не заголовок\n\nfunc main() {}\n```" + `

### Сроки
- пункт один
- пункт два
# Контакты
Почта поддержки.`
	got := SplitMarkdown(md, 40)
	want := []Chunk{
		{"", "Вступление без заголовка."},
		{"Тарифы", "Общий абзац."},
		{"Тарифы › Возвраты", "| План | Срок |\n|------|------|\n| Год  | 14 дней |"},
		{"Тарифы › Возвраты", "```go\n// # это не заголовок\n\nfunc main() {}\n```"},
		{"Тарифы › Возвраты › Сроки", "- пункт один\n- пункт два"},
		{"Контакты", "Почта поддержки."},
	}
	if fmt.Sprintf("%q", got) != fmt.Sprintf("%q", want) {
		t.Fatalf("got\n%q\nwant\n%q", got, want)
	}
}

// Пайплайн ingest в миниатюре: структура → PII → дедупликация.
func Example() {
	md := "# Возвраты\nПишите на help@shop.example или звоните 8 800 555-35-35.\n\n# Возвраты (копия)\nПишите на help@shop.example или звоните 8 800 555-35-35."
	red := Redactor{Key: []byte("rotate-me")}
	seen := NewDedup(3)
	for i, c := range SplitMarkdown(md, 800) {
		text, _ := red.Redact(c.Text)
		if dup, _ := seen.Add(fmt.Sprint(i), text); dup != "" {
			fmt.Printf("%d: дубль чанка %s, пропускаем\n", i, dup)
			continue
		}
		fmt.Printf("%d: [%s] %s\n", i, c.Heading, text)
	}
	// Output:
	// 0: [Возвраты] Пишите на [EMAIL:3326cad8] или звоните [PHONE:7fa340c5].
	// 1: дубль чанка 0, пропускаем
}
