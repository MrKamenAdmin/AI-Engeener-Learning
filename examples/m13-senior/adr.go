package senior

import (
	"regexp"
	"strings"
	"time"
)

// ADRSections — разделы, без которых ADR по AI-системе не выносится на ревью.
// Заголовок в документе может быть длиннее: «## Стоимость на запрос» закрывает «Стоимость».
var ADRSections = []string{
	"Статус", "Контекст", "Решение", "Альтернативы", "Последствия",
	"Eval-доказательства", "Стоимость", "Данные и комплаенс",
	"Режимы отказа", "План отката", "Пересмотр",
}

// MissingSections возвращает обязательные разделы, которых нет среди заголовков «## …».
func MissingSections(md string) []string {
	var heads, missing []string
	for _, line := range strings.Split(md, "\n") {
		if h, ok := strings.CutPrefix(strings.TrimSpace(line), "## "); ok {
			heads = append(heads, h)
		}
	}
	for _, need := range ADRSections {
		found := false
		for _, h := range heads {
			if strings.HasPrefix(h, need) {
				found = true
				break
			}
		}
		if !found {
			missing = append(missing, need)
		}
	}
	return missing
}

var isoDate = regexp.MustCompile(`\d{4}-\d{2}-\d{2}`)

// ReviewBy достаёт дату из раздела «## Пересмотр». Ночная джоба в CI открывает задачу
// на каждый ADR, у которого дата прошла или не указана: модели устаревают, цены меняются.
func ReviewBy(md string) (time.Time, bool) {
	_, sec, ok := strings.Cut(md, "\n## Пересмотр")
	if !ok {
		return time.Time{}, false
	}
	sec, _, _ = strings.Cut(sec, "\n## ")
	t, err := time.Parse(time.DateOnly, isoDate.FindString(sec))
	return t, err == nil
}
