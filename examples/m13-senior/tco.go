// Package senior — расчёты, на которые опираются решения senior-уровня:
// TCO варианта реализации AI-фичи, error budget и burn rate, проверка ADR.
package senior

import "math"

// Price — $ за 1M токенов входа и выхода (цены Claude в курсе — на сентябрь 2026).
type Price struct{ In, Out float64 }

// APICost — $ за один вызов API; cachedShare входа читается из prompt cache по 0.1× цены.
func APICost(in, out, cachedShare float64, p Price) float64 {
	cached := in * cachedShare
	return ((in-cached)*p.In + cached*p.In*0.1 + out*p.Out) / 1e6
}

// Option — вариант реализации фичи. Все цифры — ваши параметры, а не справочник.
type Option struct {
	Name        string
	PerRequest  float64 // $ переменной части: токены API (0 для self-host)
	UnitMonthly float64 // $ в месяц за реплику self-host: GPU, хостинг (0 для API)
	UnitCap     float64 // запросов в месяц на реплику при целевой утилизации
	MinUnits    int     // минимум реплик ради отказоустойчивости
	OpsFTE      float64 // доля инженера: эксплуатация, on-call, обновления, evals, разметка
	SuccessRate float64 // доля успешно решённых задач на вашем eval-наборе
}

// TCO — полная стоимость владения в месяц и её структура.
type TCO struct {
	Variable, Infra, People, Total float64
	Units                          int
	PerRequest, PerSuccess         float64
}

// MonthlyTCO считает TCO при объёме requests в месяц; fteMonthly — полная стоимость инженера в месяц.
// Упрощение: evals и разметка сидят в OpsFTE. Выделите их отдельной строкой, если они заметны в бюджете.
func MonthlyTCO(o Option, requests, fteMonthly float64) TCO {
	t := TCO{Variable: o.PerRequest * requests, People: o.OpsFTE * fteMonthly}
	if o.UnitCap > 0 {
		t.Units = max(o.MinUnits, int(math.Ceil(requests/o.UnitCap)))
		t.Infra = float64(t.Units) * o.UnitMonthly
	}
	t.Total = t.Variable + t.Infra + t.People
	if requests > 0 {
		t.PerRequest = t.Total / requests
		if o.SuccessRate > 0 {
			t.PerSuccess = t.Total / (requests * o.SuccessRate)
		}
	}
	return t
}
