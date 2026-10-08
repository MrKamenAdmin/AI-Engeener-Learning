package evals

import "math"

// SRM — проверка sample ratio mismatch: χ²-критерий согласия наблюдаемых размеров
// групп A/B(/n) с запланированными долями. Маленькое p (обычно порог 0.001)
// значит, что рандомизация или логирование сломаны и метрики эксперимента читать нельзя.
func SRM(observed []int, shares []float64) (chi2, p float64) {
	total := 0
	for _, o := range observed {
		total += o
	}
	for i, o := range observed {
		e := float64(total) * shares[i]
		d := float64(o) - e
		chi2 += d * d / e
	}
	return chi2, chi2Survival(chi2, len(observed)-1)
}

// chi2Survival — P(χ²(df) ≥ x) для целого df ≥ 1 без внешних библиотек:
// Q(1) = erfc(√(x/2)), Q(2) = e^(−x/2), Q(k+2) = Q(k) + (x/2)^(k/2)·e^(−x/2)/Γ(k/2+1).
func chi2Survival(x float64, df int) float64 {
	q, k := math.Exp(-x/2), 2.0
	if df%2 == 1 {
		q, k = math.Erfc(math.Sqrt(x/2)), 1
	}
	for ; k < float64(df); k += 2 {
		lg, _ := math.Lgamma(k/2 + 1)
		q += math.Exp(k/2*math.Log(x/2) - x/2 - lg)
	}
	return q
}
