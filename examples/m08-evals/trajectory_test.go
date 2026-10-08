package evals

import (
	"math"
	"reflect"
	"testing"
)

var refundSpec = Spec{
	WantState: map[string]string{"order:42": "refunded", "email:42": "sent"},
	Required:  []string{"get_order", "refund"},
	Allowed:   []string{"search_policy", "send_email"},
	Forbidden: []string{"delete_order"},
	Before:    [][2]string{{"get_order", "refund"}},
	MaxSteps:  8,
}

var done = map[string]string{"order:42": "refunded", "email:42": "sent"}

func steps(tools ...string) []Step {
	s := make([]Step, len(tools))
	for i, t := range tools {
		s[i] = Step{Tool: t, Input: `{"order_id":42}`}
	}
	return s
}

func TestCheck(t *testing.T) {
	retry := steps("get_order", "get_order", "refund", "send_email")
	retry[0].IsError = true // таймаут, повтор — нормальное восстановление
	loop := steps("get_order", "search_policy", "search_policy", "search_policy", "search_policy", "get_order", "refund", "send_email", "send_email")

	for _, c := range []struct {
		name      string
		tr        Trajectory
		failures  []string
		redundant int
	}{
		{"canonical", Trajectory{Steps: steps("get_order", "search_policy", "refund", "send_email"), State: done}, nil, 0},
		{"other valid order", Trajectory{Steps: steps("search_policy", "get_order", "refund", "send_email"), State: done}, nil, 0},
		{"retry after error", Trajectory{Steps: retry, State: done}, nil, 0},
		{"blind refund: right outcome, wrong path", Trajectory{Steps: steps("refund", "send_email"), State: done},
			[]string{"required: get_order not called", "order: get_order must precede refund"}, 0},
		{"forbidden tool", Trajectory{Steps: steps("get_order", "delete_order", "refund", "send_email"), State: done},
			[]string{"forbidden: delete_order at step 2"}, 0},
		{"gave up", Trajectory{Steps: steps("get_order", "search_policy", "escalate"), State: map[string]string{"order:42": "paid"}},
			[]string{`state: email:42 = "", want "sent"`, `state: order:42 = "paid", want "refunded"`, "required: refund not called"}, 0},
		{"search loop", Trajectory{Steps: loop, State: done}, []string{"steps: 9 > limit 8"}, 5},
	} {
		t.Run(c.name, func(t *testing.T) {
			v := Check(refundSpec, c.tr)
			if !reflect.DeepEqual(v.Failures, c.failures) || v.Success != (len(c.failures) == 0) {
				t.Errorf("failures = %q, want %q", v.Failures, c.failures)
			}
			if v.Redundant != c.redundant {
				t.Errorf("redundant = %d, want %d", v.Redundant, c.redundant)
			}
		})
	}
}

func TestPrecisionRecall(t *testing.T) {
	v := Check(refundSpec, Trajectory{Steps: steps("get_order", "search_policy", "escalate"), State: done})
	if math.Abs(v.Precision-2.0/3) > 1e-9 || v.Recall != 0.5 {
		t.Fatalf("precision %.2f recall %.2f", v.Precision, v.Recall)
	}
}

func TestAggregate(t *testing.T) {
	ok := Trajectory{TaskID: "refund-42", Steps: steps("get_order", "refund", "send_email"), State: done, CostUSD: 0.02}
	bad := Trajectory{TaskID: "refund-42", Steps: steps("refund", "send_email"), State: done, CostUSD: 0.01}
	easy := Trajectory{TaskID: "easy", Steps: steps("get_order"), State: map[string]string{}, CostUSD: 0.01}
	specs := map[string]Spec{"refund-42": refundSpec, "easy": {Required: []string{"get_order"}}}

	s, err := Aggregate(specs, []Trajectory{ok, ok, ok, bad, easy, easy, easy, easy}, 2)
	if err != nil {
		t.Fatal(err)
	}
	// refund-42: 3 из 4 → pass^2 = C(3,2)/C(4,2) = 0.5; easy: 4 из 4 → 1. Среднее 0.75.
	if s.Tasks != 2 || s.SuccessRate != 7.0/8 || math.Abs(s.PassHatK-0.75) > 1e-9 {
		t.Fatalf("got %+v", s)
	}
	if s.FailureKinds["order"] != 1 || s.FailureKinds["required"] != 1 {
		t.Fatalf("failure kinds %v", s.FailureKinds)
	}
	if _, err := Aggregate(specs, []Trajectory{ok}, 2); err == nil {
		t.Fatal("want error: fewer runs than k")
	}
}
