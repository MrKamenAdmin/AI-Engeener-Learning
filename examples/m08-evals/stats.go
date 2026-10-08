// Package evals — проверенные примеры кода модуля 8 «Evals»: bootstrap-интервалы,
// калибровка judge (κ, Rogan–Gladen), SRM-проверка A/B, проверка траекторий агента
// и гейт «PR против baseline» для CI.
package evals

import (
	"math"
	"math/rand/v2"
	"slices"
)

// Interval — точечная оценка и 95%-й интервал.
type Interval struct{ Est, Lo, Hi float64 }

// Bootstrap — перцентильный bootstrap-интервал среднего по кейсам.
// scores[i] — результат кейса i: 0/1 или доля успешных повторов. Во втором случае
// это кластерный bootstrap: повторы одного кейса ресемплируются вместе.
func Bootstrap(scores []float64, b int, seed uint64) Interval {
	n := len(scores)
	if n == 0 || b <= 0 {
		return Interval{math.NaN(), math.NaN(), math.NaN()}
	}
	r := rand.New(rand.NewPCG(seed, 0))
	stats := make([]float64, b)
	for k := range stats {
		s := 0.0
		for range n {
			s += scores[r.IntN(n)]
		}
		stats[k] = s / float64(n)
	}
	slices.Sort(stats)
	q := func(p float64) float64 { return stats[int(p*float64(b-1))] }
	return Interval{Est: mean(scores), Lo: q(0.025), Hi: q(0.975)}
}

// PairedBootstrap — интервал разности cand − base на одних и тех же кейсах.
// Ресемплируются кейсы целиком (обе версии вместе), поэтому разброс сложности
// кейсов вычитается и интервал уже, чем у двух независимых bootstrap.
func PairedBootstrap(base, cand []float64, b int, seed uint64) Interval {
	if len(base) != len(cand) {
		panic("evals: base and cand must be aligned by case")
	}
	d := make([]float64, len(base))
	for i := range d {
		d[i] = cand[i] - base[i]
	}
	return Bootstrap(d, b, seed)
}

func mean(x []float64) float64 {
	s := 0.0
	for _, v := range x {
		s += v
	}
	return s / float64(len(x))
}
