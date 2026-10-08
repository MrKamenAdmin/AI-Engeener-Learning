package actions

import (
	"context"
	"errors"
	"testing"
)

var (
	client = Client{
		Accounts:      map[string]bool{"acc-1": true, "acc-2": true},
		Templates:     map[string]bool{"tpl-mobile": true},
		Cards:         map[string]bool{"card-1": true},
		SpentTodayKop: 250_000_00,
	}
	limits = Limits{PerOpKop: 300_000_00, DailyKop: 500_000_00, StepUpKop: 30_000_00}
)

func TestAuthorize(t *testing.T) {
	cases := []struct {
		name string
		p    Proposal
		want Verdict
	}{
		{"свои счета, мелкая сумма", Proposal{Kind: OwnTransfer, From: "acc-1", To: "acc-2", AmountKop: 1_000_00}, Confirm},
		{"крупная сумма требует step-up", Proposal{Kind: OwnTransfer, From: "acc-1", To: "acc-2", AmountKop: 30_000_00}, StepUp},
		{"чужой счёт из инъекции", Proposal{Kind: OwnTransfer, From: "acc-1", To: "acc-evil", AmountKop: 1_000_00}, Deny},
		{"тот же счёт", Proposal{Kind: OwnTransfer, From: "acc-1", To: "acc-1", AmountKop: 1_000_00}, Deny},
		{"шаблон клиента", Proposal{Kind: TemplatePay, From: "acc-1", Template: "tpl-mobile", AmountKop: 500_00}, Confirm},
		{"чужой шаблон", Proposal{Kind: TemplatePay, From: "acc-1", Template: "tpl-x", AmountKop: 500_00}, Deny},
		{"отрицательная сумма", Proposal{Kind: OwnTransfer, From: "acc-1", To: "acc-2", AmountKop: -1}, Deny},
		{"лимит на операцию", Proposal{Kind: OwnTransfer, From: "acc-1", To: "acc-2", AmountKop: 300_000_01}, Deny},
		{"дневной лимит", Proposal{Kind: OwnTransfer, From: "acc-1", To: "acc-2", AmountKop: 260_000_00}, Deny},
		{"блокировка своей карты", Proposal{Kind: CardBlock, Card: "card-1"}, Confirm},
		{"блокировка чужой карты", Proposal{Kind: CardBlock, Card: "card-9"}, Deny},
		{"перевод новому получателю не поддерживается", Proposal{Kind: "transfer_to_new_payee", AmountKop: 100}, Deny},
	}
	for _, tc := range cases {
		if got := Authorize(client, limits, tc.p); got.Verdict != tc.want {
			t.Errorf("%s: verdict %d (%s), want %d", tc.name, got.Verdict, got.Reason, tc.want)
		}
	}
}

type fakeCore struct{ keys []string }

func (f *fakeCore) Execute(_ context.Context, key string, _ Proposal) error {
	f.keys = append(f.keys, key)
	return nil
}

func TestExecute(t *testing.T) {
	ctx := context.Background()
	small := Proposal{Kind: OwnTransfer, From: "acc-1", To: "acc-2", AmountKop: 1_000_00}
	big := Proposal{Kind: OwnTransfer, From: "acc-1", To: "acc-2", AmountKop: 40_000_00}

	core := &fakeCore{}
	if err := Execute(ctx, core, client, limits, small, Confirmation{Digest: small.Digest(), ID: "c1"}); err != nil {
		t.Fatalf("подтверждённое действие: %v", err)
	}
	if len(core.keys) != 1 || core.keys[0] != "c1" {
		t.Fatalf("ключ идемпотентности = ID подтверждения, got %v", core.keys)
	}

	// Подтверждали 1 000 ₽, а исполнить пытаются 40 000 ₽: дайджест не совпадает.
	if err := Execute(ctx, core, client, limits, big, Confirmation{Digest: small.Digest(), ID: "c2", SteppedUp: true}); !errors.Is(err, ErrNotConfirmed) {
		t.Fatalf("подмена суммы: want ErrNotConfirmed, got %v", err)
	}
	// Крупная сумма без step-up.
	if err := Execute(ctx, core, client, limits, big, Confirmation{Digest: big.Digest(), ID: "c3"}); !errors.Is(err, ErrNotConfirmed) {
		t.Fatalf("без step-up: want ErrNotConfirmed, got %v", err)
	}
	if len(core.keys) != 1 {
		t.Fatalf("core вызван для неподтверждённых действий: %v", core.keys)
	}
}

func TestDigestChangesWithEveryField(t *testing.T) {
	base := Proposal{Kind: OwnTransfer, From: "acc-1", To: "acc-2", AmountKop: 100}
	for _, p := range []Proposal{
		{Kind: OwnTransfer, From: "acc-1", To: "acc-2", AmountKop: 101},
		{Kind: OwnTransfer, From: "acc-1", To: "acc-3", AmountKop: 100},
		{Kind: OwnTransfer, From: "acc-2", To: "acc-2", AmountKop: 100},
	} {
		if p.Digest() == base.Digest() {
			t.Errorf("digest не изменился для %+v", p)
		}
	}
}
