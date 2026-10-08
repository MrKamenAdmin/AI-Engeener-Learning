package senior

import (
	"fmt"
	"math"
	"os"
	"slices"
	"testing"
	"time"
)

func near(a, b float64) bool { return math.Abs(a-b) < 1e-9 }

func TestAPICost(t *testing.T) {
	sonnet, haiku := Price{2, 10}, Price{1, 5} // Sonnet 5 и Haiku 4.5, сентябрь 2026
	cases := []struct {
		p      Price
		cached float64
		want   float64
	}{
		{sonnet, 0, 0.0075},
		{haiku, 0, 0.00375},
		{sonnet, 0.6, 0.00426},
		{haiku, 0.6, 0.00213},
	}
	for _, c := range cases {
		if got := APICost(3000, 150, c.cached, c.p); !near(got, c.want) {
			t.Errorf("APICost(%v, cached=%.1f) = %.6f, want %.6f", c.p, c.cached, got, c.want)
		}
	}
}

// Условные варианты из схемы модуля: дешёвое API и fine-tuned малая модель на своих GPU.
var (
	cheapAPI = Option{Name: "API", PerRequest: APICost(3000, 400, 0, Price{1, 5}), OpsFTE: 0.3, SuccessRate: 0.9}
	smallFT  = Option{Name: "self-host", UnitMonthly: 2100, UnitCap: 6e6, MinUnits: 2, OpsFTE: 1.3, SuccessRate: 0.92}
)

func TestMonthlyTCOCrossover(t *testing.T) {
	const fte = 15000
	low, high := 1e6, 5e7
	if a, s := MonthlyTCO(cheapAPI, low, fte), MonthlyTCO(smallFT, low, fte); a.Total >= s.Total {
		t.Errorf("на %g запросов API должно быть дешевле: API %.0f, self-host %.0f", low, a.Total, s.Total)
	}
	if a, s := MonthlyTCO(cheapAPI, high, fte), MonthlyTCO(smallFT, high, fte); a.Total <= s.Total {
		t.Errorf("на %g запросов self-host должен быть дешевле: API %.0f, self-host %.0f", high, a.Total, s.Total)
	}
	if u := MonthlyTCO(smallFT, 1000, fte).Units; u != 2 {
		t.Errorf("минимум реплик не соблюдён: %d", u)
	}
	if got := MonthlyTCO(cheapAPI, low, fte).PerSuccess; !near(got, 9500/0.9e6) {
		t.Errorf("PerSuccess = %.6f", got)
	}
}

func TestThresholdMatchesWorkbook(t *testing.T) {
	slo := SLO{Target: 0.999, Period: 30 * 24 * time.Hour}
	for i, want := range []float64{14.4, 6, 1} {
		if got := slo.Threshold(DefaultAlerts[i]); math.Abs(got-want) > 1e-9 {
			t.Errorf("%s: порог %.4f, в SRE Workbook %.1f", DefaultAlerts[i].Name, got, want)
		}
	}
}

func TestCheck(t *testing.T) {
	slo := SLO{Target: 0.99, Period: 28 * 24 * time.Hour}
	ratio := func(r map[time.Duration]float64, period float64) Metrics {
		return func(d time.Duration) Window {
			bad, ok := r[d]
			if !ok {
				bad = r[0] // фон
			}
			if d == slo.Period {
				bad = period
			}
			return Window{Total: 10000, Bad: 10000 * bad}
		}
	}
	cases := []struct {
		name   string
		m      Metrics
		alert  string
		freeze bool
	}{
		{"фон 0.5%", ratio(map[time.Duration]float64{0: 0.005}, 0.005), "", false},
		{"инцидент идёт", ratio(map[time.Duration]float64{0: 0.005, time.Hour: 0.2, 5 * time.Minute: 0.2}, 0.006), "page-fast", false},
		{"инцидент закончился: длинное окно горячее, короткое нет", ratio(map[time.Duration]float64{0: 0.005, time.Hour: 0.2}, 0.006), "", false},
		{"бюджет окна исчерпан", ratio(map[time.Duration]float64{0: 0.005}, 0.012), "", true},
	}
	for _, c := range cases {
		st := slo.Check(c.m, DefaultAlerts)
		got := ""
		if st.Fired != nil {
			got = st.Fired.Name
		}
		if got != c.alert || st.Freeze != c.freeze {
			t.Errorf("%s: алерт %q, заморозка %v; ждали %q, %v", c.name, got, st.Freeze, c.alert, c.freeze)
		}
	}
}

func TestFromSample(t *testing.T) {
	// 20 технических ошибок, судья оценил 200 ответов и 4 из них счёл плохими (2%).
	if w := FromSample(10000, 20, 200, 4); !near(w.Bad, 20+9980*0.02) {
		t.Errorf("Bad = %.2f", w.Bad)
	}
}

func TestADRTemplate(t *testing.T) {
	tmpl, err := os.ReadFile("templates/adr.md")
	if err != nil {
		t.Fatal(err)
	}
	if m := MissingSections(string(tmpl)); len(m) > 0 {
		t.Errorf("шаблон неполон: %v", m)
	}
	if _, ok := ReviewBy(string(tmpl)); ok {
		t.Error("в шаблоне нет даты, ReviewBy должен вернуть ok=false")
	}
	draft := "# ADR-0001\n## Статус\n## Контекст\n## Решение\n## Альтернативы\n## Последствия\n" +
		"## Eval-доказательства\n## Стоимость на запрос\n## Данные и комплаенс\n## Режимы отказа\n"
	if m := MissingSections(draft); !slices.Equal(m, []string{"План отката", "Пересмотр"}) {
		t.Errorf("MissingSections(draft) = %v", m)
	}
	due, ok := ReviewBy(draft + "## Пересмотр\nДата: 2027-03-01, досрочно при выводе модели.\n## Ссылки\n2030-01-01\n")
	if !ok || due != time.Date(2027, 3, 1, 0, 0, 0, 0, time.UTC) {
		t.Errorf("ReviewBy = %v, %v", due, ok)
	}
}

func ExampleMonthlyTCO() {
	for _, n := range []float64{1e6, 5e7} {
		a, s := MonthlyTCO(cheapAPI, n, 15000), MonthlyTCO(smallFT, n, 15000)
		fmt.Printf("%.0e запросов/мес: API $%.0f, self-host $%.0f (реплик: %d)\n", n, a.Total, s.Total, s.Units)
	}
	// Output:
	// 1e+06 запросов/мес: API $9500, self-host $23700 (реплик: 2)
	// 5e+07 запросов/мес: API $254500, self-host $38400 (реплик: 9)
}

func ExampleSLO_Check() {
	slo := SLO{Target: 0.99, Period: 28 * 24 * time.Hour}
	// Последний час 20% ответов плохие (ошибки + оценка онлайн-судьи), до этого — фон.
	m := Metrics(func(d time.Duration) Window {
		return map[time.Duration]Window{
			5 * time.Minute:  {Total: 170, Bad: 34},
			30 * time.Minute: {Total: 1_000, Bad: 200},
			time.Hour:        {Total: 2_000, Bad: 400},
			6 * time.Hour:    {Total: 12_000, Bad: 450},
			72 * time.Hour:   {Total: 144_000, Bad: 1_090},
			slo.Period:       {Total: 1_344_000, Bad: 7_120},
		}[d]
	})
	st := slo.Check(m, DefaultAlerts)
	fmt.Printf("осталось бюджета %.0f%%, алерт %s, заморозка %v\n", st.BudgetLeft*100, st.Fired.Name, st.Freeze)
	// Output: осталось бюджета 47%, алерт page-fast, заморозка false
}
