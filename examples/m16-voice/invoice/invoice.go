// Package invoice — проверка полей, которые VLM извлекла из скана счёта.
// Выход модели — недоверенный ввод: суммы сверяются арифметикой, а не на глаз.
package invoice

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Invoice — ответ модели по JSON-схеме. Суммы — строкой «как напечатано»:
// модель только переписывает, а разбор числа детерминирован и тестируется.
type Invoice struct {
	Number string `json:"number"`
	Date   string `json:"date"` // YYYY-MM-DD
	Lines  []Line `json:"lines"`
	Total  string `json:"total"`
}

type Line struct {
	Name   string `json:"name"`
	Amount string `json:"amount"`
}

// Validate возвращает все найденные расхождения; nil — счёт сходится.
func (inv Invoice) Validate() error {
	var errs []error
	if strings.TrimSpace(inv.Number) == "" {
		errs = append(errs, errors.New("нет номера"))
	}
	if _, err := time.Parse(time.DateOnly, inv.Date); err != nil {
		errs = append(errs, fmt.Errorf("дата %q: %w", inv.Date, err))
	}
	var sum int64
	for i, l := range inv.Lines {
		k, err := Kopecks(l.Amount)
		if err != nil {
			errs = append(errs, fmt.Errorf("строка %d: %w", i+1, err))
		}
		sum += k
	}
	total, err := Kopecks(inv.Total)
	if err != nil {
		errs = append(errs, fmt.Errorf("итог: %w", err))
	} else if len(errs) == 0 && sum != total {
		// Классика VLM: перепутанные 3/8, потерянный разряд, строка прочитана дважды.
		errs = append(errs, fmt.Errorf("сумма строк %d ≠ итог %d (коп.)", sum, total))
	}
	return errors.Join(errs...)
}

// Kopecks разбирает «12 345,67», «12345.67», «1 200» в копейки без float.
func Kopecks(s string) (int64, error) {
	clean := strings.NewReplacer(" ", "", " ", "", " ", "", ",", ".").Replace(strings.TrimSpace(s))
	rub, kop, _ := strings.Cut(clean, ".")
	if len(kop) == 1 {
		kop += "0"
	}
	if rub == "" || len(kop) > 2 || strings.HasPrefix(kop, "-") {
		return 0, fmt.Errorf("сумма %q", s)
	}
	r, err := strconv.ParseInt(rub, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("сумма %q: %w", s, err)
	}
	k, err := strconv.ParseInt("0"+kop, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("сумма %q: %w", s, err)
	}
	if strings.HasPrefix(rub, "-") { // и «-0,50» тоже
		return r*100 - k, nil
	}
	return r*100 + k, nil
}
