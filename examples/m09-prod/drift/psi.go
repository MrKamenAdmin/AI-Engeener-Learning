package drift

import "math"

// PSI — population stability index между эталонным распределением (например, прошлый месяц)
// и текущим (последние сутки) по категориям: темы, языки, бакеты длины.
// Эмпирика из кредитного скоринга: < 0.1 — стабильно, 0.1–0.25 — заметный сдвиг, > 0.25 — сильный.
func PSI(ref, cur map[string]float64) float64 {
	const eps = 1e-4 // пустая корзина дала бы ln(0) = −∞
	p, q := shares(ref, cur)
	var psi float64
	for k := range p {
		a, b := max(p[k], eps), max(q[k], eps)
		psi += (b - a) * math.Log(b/a)
	}
	return psi
}

// JS — дивергенция Йенсена–Шеннона по основанию 2: симметрична, ограничена [0, 1],
// пустые корзины не ломают формулу.
func JS(ref, cur map[string]float64) float64 {
	p, q := shares(ref, cur)
	kl := func(a, m float64) float64 {
		if a == 0 {
			return 0
		}
		return a * math.Log2(a/m)
	}
	var js float64
	for k := range p {
		m := (p[k] + q[k]) / 2
		js += kl(p[k], m)/2 + kl(q[k], m)/2
	}
	return js
}

// shares переводит счётчики в доли по объединению ключей: новая тема в текущем окне —
// тоже сигнал дрейфа, её нельзя потерять.
func shares(a, b map[string]float64) (map[string]float64, map[string]float64) {
	p, q := map[string]float64{}, map[string]float64{}
	var sa, sb float64
	for _, v := range a {
		sa += v
	}
	for _, v := range b {
		sb += v
	}
	for k := range a {
		p[k], q[k] = a[k]/sa, b[k]/sb
	}
	for k := range b {
		p[k], q[k] = a[k]/sa, b[k]/sb
	}
	return p, q
}

// LengthBucket — корзина длины запроса в токенах: длина — самый дешёвый признак дрейфа.
func LengthBucket(tokens int) string {
	switch {
	case tokens < 50:
		return "<50"
	case tokens < 200:
		return "50-199"
	case tokens < 1000:
		return "200-999"
	default:
		return "1000+"
	}
}
