// Package actions — политика действий банковского ассистента (модуль 12, Разбор 6).
// Модель только предлагает действие; разрешает, подтверждает и исполняет код.
package actions

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
)

type Kind string

const (
	OwnTransfer Kind = "own_transfer"     // между своими счетами
	TemplatePay Kind = "template_payment" // по сохранённому шаблону
	CardBlock   Kind = "card_block"
)

// Proposal собирается из аргументов tool_use: каждое поле — недоверенные данные.
type Proposal struct {
	Kind      Kind   `json:"kind"`
	From      string `json:"from,omitempty"`
	To        string `json:"to,omitempty"` // для own_transfer
	Template  string `json:"template,omitempty"`
	Card      string `json:"card,omitempty"`
	AmountKop int64  `json:"amount_kop,omitempty"` // деньги в копейках, без float
}

// Client — то, что банк знает о клиенте из core banking по его сессии, а не со слов модели.
type Client struct {
	Accounts, Templates, Cards map[string]bool
	SpentTodayKop              int64
}

type Limits struct{ PerOpKop, DailyKop, StepUpKop int64 }

type Verdict int

const (
	Deny    Verdict = iota
	Confirm         // подтверждение на нативном экране приложения
	StepUp          // подтверждение + PIN или биометрия
)

type Decision struct {
	Verdict Verdict
	Reason  string // уходит модели как tool_result, чтобы она объяснила клиенту
}

func deny(reason string) Decision { return Decision{Deny, reason} }

// Authorize — детерминированная политика: никаких вызовов модели внутри.
func Authorize(c Client, l Limits, p Proposal) Decision {
	switch p.Kind {
	case CardBlock:
		if !c.Cards[p.Card] {
			return deny("карта не принадлежит клиенту")
		}
		return Decision{Confirm, "блокировка карты"}
	case OwnTransfer:
		if !c.Accounts[p.From] || !c.Accounts[p.To] || p.From == p.To {
			return deny("перевод возможен только между разными своими счетами")
		}
	case TemplatePay:
		if !c.Accounts[p.From] || !c.Templates[p.Template] {
			return deny("у клиента нет такого счёта или шаблона")
		}
	default:
		return deny(fmt.Sprintf("действие %q ассистенту недоступно", p.Kind))
	}
	switch {
	case p.AmountKop <= 0:
		return deny("сумма должна быть положительной")
	case p.AmountKop > l.PerOpKop:
		return deny("превышен лимит на операцию")
	case c.SpentTodayKop+p.AmountKop > l.DailyKop:
		return deny("превышен дневной лимит")
	case p.AmountKop >= l.StepUpKop:
		return Decision{StepUp, "крупная сумма"}
	}
	return Decision{Confirm, "в пределах лимитов"}
}

// Digest привязывает подтверждение к сумме и получателю, как dynamic linking в SCA:
// изменилось любое поле — подтверждение недействительно.
func (p Proposal) Digest() string {
	b, _ := json.Marshal(p) // порядок полей структуры фиксирован
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

// Confirmation приходит с нативного экрана приложения после действия клиента, а не из чата.
type Confirmation struct {
	Digest    string
	ID        string // уникален для каждого подтверждения
	SteppedUp bool
}

type Core interface {
	Execute(ctx context.Context, idempotencyKey string, p Proposal) error
}

var ErrNotConfirmed = errors.New("подтверждение не совпадает с предложением")

func Execute(ctx context.Context, core Core, c Client, l Limits, p Proposal, conf Confirmation) error {
	d := Authorize(c, l, p) // повторно: с момента предложения могли измениться лимиты
	if d.Verdict == Deny {
		return fmt.Errorf("отказ: %s", d.Reason)
	}
	if conf.Digest != p.Digest() || (d.Verdict == StepUp && !conf.SteppedUp) {
		return ErrNotConfirmed
	}
	return core.Execute(ctx, conf.ID, p) // ретрай с тем же ID не исполнит операцию дважды
}
