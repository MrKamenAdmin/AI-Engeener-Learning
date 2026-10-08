package evals

import (
	"fmt"
	"math"
	"testing"
)

func TestChi2SurvivalCriticalValues(t *testing.T) {
	for _, c := range []struct {
		x  float64
		df int
	}{{3.841, 1}, {5.991, 2}, {7.815, 3}, {9.488, 4}} {
		if p := chi2Survival(c.x, c.df); math.Abs(p-0.05) > 0.0005 {
			t.Errorf("df=%d x=%.3f: p=%.4f, want 0.05", c.df, c.x, p)
		}
	}
}

func TestSRM(t *testing.T) {
	half := []float64{0.5, 0.5}
	if _, p := SRM([]int{10000, 10300}, half); p < 0.001 {
		t.Errorf("10000/10300 is plausible noise, p=%.4f", p)
	}
	if chi2, p := SRM([]int{10000, 10600}, half); p >= 0.001 {
		t.Errorf("10000/10600 must be SRM: chi2=%.2f p=%.2g", chi2, p)
	}
}

func ExampleSRM() {
	chi2, p := SRM([]int{10000, 10600}, []float64{0.5, 0.5})
	fmt.Printf("chi2=%.1f p=%.0e\n", chi2, p)
	// Output: chi2=17.5 p=3e-05
}
