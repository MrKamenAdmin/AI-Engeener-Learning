package senior

import "time"

// Window — события за интервал: всего и «плохих».
type Window struct{ Total, Bad float64 }

// FromSample собирает Window для SLI качества. Технические ошибки видны на всём трафике,
// а качество — только на выборке, которую оценил онлайн-судья (модуль 8): долю плохих
// в выборке переносим на остальные ответы.
func FromSample(total, errors, judged, judgedBad float64) Window {
	w := Window{Total: total, Bad: errors}
	if judged > 0 {
		w.Bad += (total - errors) * judgedBad / judged
	}
	return w
}

// SLO — цель по доле хороших событий за скользящее окно.
type SLO struct {
	Target float64       // например, 0.99
	Period time.Duration // например, 28 суток
}

// BurnRate — во сколько раз быстрее нормы сжигается бюджет; 1 — ровно к концу окна.
func (s SLO) BurnRate(w Window) float64 {
	if w.Total == 0 {
		return 0
	}
	return w.Bad / w.Total / (1 - s.Target)
}

// Alert — правило multiwindow multi-burn-rate из Google SRE Workbook («Alerting on SLOs»):
// за окно Long сожжена доля Budget бюджета, и сжигание всё ещё идёт на коротком окне Short.
type Alert struct {
	Name        string
	Long, Short time.Duration
	Budget      float64 // доля бюджета всего окна SLO, например 0.02
	Page        bool    // true — будить дежурного, false — тикет в рабочее время
}

// Threshold переводит долю бюджета в порог burn rate: 2% за 1 ч при окне 30 суток → 14.4.
func (s SLO) Threshold(a Alert) float64 { return a.Budget * float64(s.Period) / float64(a.Long) }

// DefaultAlerts — рекомендованные пороги SRE Workbook (таблица 5-8), от срочного к медленному.
var DefaultAlerts = []Alert{
	{"page-fast", time.Hour, 5 * time.Minute, 0.02, true},
	{"page-slow", 6 * time.Hour, 30 * time.Minute, 0.05, true},
	{"ticket", 72 * time.Hour, 6 * time.Hour, 0.10, false},
}

// Metrics отдаёт события за последние d — обёртка над вашим Prometheus или ClickHouse.
type Metrics func(d time.Duration) Window

// Status — что говорит политика error budget прямо сейчас.
type Status struct {
	BudgetLeft float64 // доля оставшегося бюджета окна, бывает < 0
	Fired      *Alert  // первый сработавший алерт, nil — тихо
	Freeze     bool    // бюджет исчерпан: релизы промптов и моделей заморожены, кроме фиксов
}

// Check применяет алерты и политику заморозки к текущим метрикам.
func (s SLO) Check(m Metrics, alerts []Alert) Status {
	st := Status{BudgetLeft: 1}
	if w := m(s.Period); w.Total > 0 {
		st.BudgetLeft = 1 - w.Bad/((1-s.Target)*w.Total)
	}
	st.Freeze = st.BudgetLeft <= 0
	for i, a := range alerts {
		th := s.Threshold(a)
		if s.BurnRate(m(a.Long)) >= th && s.BurnRate(m(a.Short)) >= th {
			st.Fired = &alerts[i]
			break
		}
	}
	return st
}
