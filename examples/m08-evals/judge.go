package evals

import (
	"errors"
	"math"
	"math/rand/v2"
	"slices"
)

// Confusion — сверка вердиктов judge с человеческой разметкой.
// Положительный класс — PASS (как в схеме калибровки модуля).
type Confusion struct{ TP, FN, FP, TN int }

func Confuse(human, judge []bool) Confusion {
	var c Confusion
	for i, h := range human {
		switch j := judge[i]; {
		case h && j:
			c.TP++
		case h:
			c.FN++ // хороший ответ, judge забраковал: ложная тревога
		case j:
			c.FP++ // плохой ответ, judge пропустил: дефект уйдёт в прод
		default:
			c.TN++
		}
	}
	return c
}

// TPR — доля хороших (по людям) ответов, которые judge признал PASS.
func (c Confusion) TPR() float64 { return float64(c.TP) / float64(c.TP+c.FN) }

// TNR — доля плохих ответов, которые judge поймал. Для CI-гейта это главная цифра.
func (c Confusion) TNR() float64 { return float64(c.TN) / float64(c.TN+c.FP) }

// Kappa — Cohen's κ = (po − pe) / (1 − pe): согласие сверх случайного.
func (c Confusion) Kappa() float64 {
	n := float64(c.TP + c.FN + c.FP + c.TN)
	po := float64(c.TP+c.TN) / n
	humanPass, judgePass := float64(c.TP+c.FN)/n, float64(c.TP+c.FP)/n
	pe := humanPass*judgePass + (1-humanPass)*(1-judgePass)
	if pe == 1 {
		return math.NaN() // оба ставят одну метку всем кейсам: κ не определена
	}
	return (po - pe) / (1 - pe)
}

var ErrUselessJudge = errors.New("evals: judge is no better than chance (TPR+TNR <= 1)")

// CorrectedPassRate — поправка Rogan–Gladen (1978): истинная доля PASS по доле PASS,
// которую показал judge, и его TPR/TNR на размеченном тестовом наборе.
// observed = TPR·θ + (1−TNR)·(1−θ)  ⇒  θ = (observed + TNR − 1) / (TPR + TNR − 1).
func CorrectedPassRate(observed, tpr, tnr float64) (float64, error) {
	den := tpr + tnr - 1
	if !(den > 0) { // ловит и NaN, когда в выборке нет одного из классов
		return 0, ErrUselessJudge
	}
	return min(1, max(0, (observed+tnr-1)/den)), nil
}

// CorrectedPassRateCI — интервал для скорректированной доли. Ресемплируются и
// вердикты judge на прогоне, и калибровочные пары: TPR/TNR, измеренные на 100–200
// кейсах, сами шумят, и этот шум часто больше шума прогона.
func CorrectedPassRateCI(verdicts, human, judge []bool, b int, seed uint64) (Interval, error) {
	c := Confuse(human, judge)
	est, err := CorrectedPassRate(passRate(verdicts), c.TPR(), c.TNR())
	if err != nil {
		return Interval{}, err
	}
	r := rand.New(rand.NewPCG(seed, 0))
	v := make([]bool, len(verdicts))
	h, j := make([]bool, len(human)), make([]bool, len(human))
	stats := make([]float64, 0, b)
	for range b {
		for i := range v {
			v[i] = verdicts[r.IntN(len(verdicts))]
		}
		for i := range h {
			k := r.IntN(len(human))
			h[i], j[i] = human[k], judge[k]
		}
		cb := Confuse(h, j)
		if x, err := CorrectedPassRate(passRate(v), cb.TPR(), cb.TNR()); err == nil {
			stats = append(stats, x)
		}
	}
	if len(stats) == 0 {
		return Interval{}, ErrUselessJudge
	}
	slices.Sort(stats)
	q := func(p float64) float64 { return stats[int(p*float64(len(stats)-1))] }
	return Interval{Est: est, Lo: q(0.025), Hi: q(0.975)}, nil
}

func passRate(v []bool) float64 {
	k := 0
	for _, ok := range v {
		if ok {
			k++
		}
	}
	return float64(k) / float64(len(v))
}
