package slo

import "time"

// Counts — события SLI за окно: всего и «плохих».
type Counts struct{ Total, Bad float64 }

// BurnRate — во сколько раз быстрее допустимого тратится error budget:
// 1 — бюджет кончится ровно к концу окна SLO, 14.4 — за 30 дней / 14.4 ≈ 2 дня.
func BurnRate(c Counts, objective float64) float64 {
	if c.Total == 0 {
		return 0
	}
	return (c.Bad / c.Total) / (1 - objective)
}

// BudgetLeft — какая доля error budget осталась за окно SLO (уходит в минус при перерасходе).
func BudgetLeft(c Counts, objective float64) float64 {
	if c.Total == 0 {
		return 1
	}
	return 1 - c.Bad/(c.Total*(1-objective))
}

type Rule struct {
	Long, Short time.Duration
	Burn        float64
	Page        bool // false — тикет, а не звонок дежурному
}

// Workbook — multiwindow, multi-burn-rate правила из Google SRE Workbook для SLO на 30 дней.
// Короткое окно = 1/12 длинного: алерт гаснет, как только инцидент закончился.
var Workbook = []Rule{
	{Long: time.Hour, Short: 5 * time.Minute, Burn: 14.4, Page: true},   // 2% бюджета за час
	{Long: 6 * time.Hour, Short: 30 * time.Minute, Burn: 6, Page: true}, // 5% за 6 часов
	{Long: 72 * time.Hour, Short: 6 * time.Hour, Burn: 1, Page: false},  // 10% за 3 дня
}

// Firing возвращает сработавшие правила. window(d) отдаёт счётчики за последние d.
// minEvents отсекает решения по горстке событий: на малом трафике и на выборочном SLI
// (онлайн-judge на 2% ответов) короткое окно — это шум, а не сигнал.
func Firing(rules []Rule, objective, minEvents float64, window func(time.Duration) Counts) []Rule {
	var out []Rule
	for _, r := range rules {
		long, short := window(r.Long), window(r.Short)
		if short.Total < minEvents {
			continue
		}
		if BurnRate(long, objective) >= r.Burn && BurnRate(short, objective) >= r.Burn {
			out = append(out, r)
		}
	}
	return out
}
