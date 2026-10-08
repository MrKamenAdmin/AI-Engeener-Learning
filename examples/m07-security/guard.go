package secure

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"regexp"
	"strings"
	"time"
)

// Agent — то, что видит пользователь (и red-team раннер): вход → ответ и сделанные действия.
type Agent interface {
	Run(ctx context.Context, in Input) (Trace, error)
}

type Input struct {
	Prompt string
	Docs   []string // недоверенный контент, найденный retrieval или пришедший из инструментов
}

type Trace struct {
	Output  string
	Tools   []string // успешно исполненные инструменты
	Refused bool
}

// Guard проверяет текст и может вернуть его изменённым (редакция PII, вырезанные ссылки).
// Отказ по политике — ошибка *Blocked; любая другая ошибка значит «guard сломался».
type Guard interface {
	Name() string
	Check(ctx context.Context, text string) (string, error)
}

type Blocked struct{ Guard, Reason string }

func (b *Blocked) Error() string { return "blocked by " + b.Guard + ": " + b.Reason }

var ErrGuardUnavailable = errors.New("guardrail unavailable")

// RunChain прогоняет текст через guards по порядку: дешёвые первыми.
// По умолчанию fail-closed: сломанный guard блокирует запрос. Fail-open включается явно — FailOpen(g).
func RunChain(ctx context.Context, gs []Guard, text string) (string, error) {
	for _, g := range gs {
		out, err := g.Check(ctx, text)
		var b *Blocked
		switch {
		case errors.As(err, &b):
			return "", err
		case err != nil:
			return "", fmt.Errorf("%w: %s: %v", ErrGuardUnavailable, g.Name(), err)
		}
		text = out
	}
	return text, nil
}

type failOpen struct{ Guard }

// FailOpen: если guard недоступен, пропускаем текст и пишем предупреждение.
// Только для guards, чей пропуск дешевле отказа (тематический фильтр внутреннего инструмента).
func FailOpen(g Guard) Guard { return failOpen{g} }

func (f failOpen) Check(ctx context.Context, text string) (string, error) {
	out, err := f.Guard.Check(ctx, text)
	var b *Blocked
	if err != nil && !errors.As(err, &b) {
		slog.WarnContext(ctx, "guard unavailable, fail-open", "guard", f.Name(), "err", err)
		return text, nil
	}
	return out, err
}

// Guarded — middleware вокруг агента, как http.Handler вокруг http.Handler.
type Guarded struct {
	Next   Agent
	Input  []Guard // ввод пользователя: блок → вежливый отказ
	Docs   []Guard // недоверенный контент: блок → документ выбрасывается, ответ строится без него
	Output []Guard // ответ: блок → отказ, иначе — переписанный текст
}

const refusal = "Не могу помочь с этим запросом."

func (g Guarded) Run(ctx context.Context, in Input) (Trace, error) {
	p, err := RunChain(ctx, g.Input, in.Prompt)
	if err != nil {
		return refuse(Trace{}, err)
	}
	docs := make([]string, 0, len(in.Docs))
	for _, d := range in.Docs {
		clean, err := RunChain(ctx, g.Docs, d)
		var b *Blocked
		switch {
		case errors.As(err, &b):
			slog.WarnContext(ctx, "doc dropped", "reason", b.Error())
			continue
		case err != nil:
			return Trace{}, err
		}
		docs = append(docs, clean)
	}
	tr, err := g.Next.Run(ctx, Input{Prompt: p, Docs: docs})
	if err != nil {
		return Trace{}, err
	}
	out, err := RunChain(ctx, g.Output, tr.Output)
	if err != nil {
		// Инструменты уже отработали: output guard не отменяет побочных эффектов,
		// поэтому контроль действий стоит в Registry, до исполнения.
		return refuse(tr, err)
	}
	tr.Output = out
	return tr, nil
}

func refuse(tr Trace, err error) (Trace, error) {
	var b *Blocked
	if !errors.As(err, &b) {
		return Trace{}, err // fail-closed: инфраструктурная ошибка, не «атака отбита»
	}
	slog.Info("guardrail refusal", "reason", b.Error())
	tr.Output, tr.Refused = refusal, true
	return tr, nil
}

// Heuristic — дешёвый детектор известных шаблонов инъекций. Ловит копипасту,
// но не адаптивного атакующего: перефразировка, другой язык, base64 проходят.
type Heuristic struct{}

var injectionRe = regexp.MustCompile(`(?i)(ignore|disregard)\s+(all\s+|any\s+)?(previous|prior|above)\s+instructions` +
	`|игнорируй\s+(все\s+)?(предыдущие\s+|прошлые\s+|системные\s+)?(инструкции|правила)` +
	`|system\s+prompt|системн\p{L}*\s+промпт|developer\s+mode|ты\s+теперь`)

func (Heuristic) Name() string { return "heuristic" }

func (Heuristic) Check(_ context.Context, text string) (string, error) {
	if m := injectionRe.FindString(text); m != "" {
		return "", &Blocked{"heuristic", "pattern " + m}
	}
	return text, nil
}

// Classifier — внешний детектор: Llama Prompt Guard, Prompt Shields, LLM-judge на Haiku.
type Classifier interface {
	Score(ctx context.Context, text string) (float64, error)
}

type ClassifierGuard struct {
	C         Classifier
	Threshold float64
	Timeout   time.Duration // свой дедлайн: guard не должен съесть весь бюджет латентности
}

func (g ClassifierGuard) Name() string { return "classifier" }

func (g ClassifierGuard) Check(ctx context.Context, text string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, g.Timeout)
	defer cancel()
	s, err := g.C.Score(ctx, text)
	if err != nil {
		return "", err // что делать дальше, решает политика: RunChain (closed) или FailOpen
	}
	if s >= g.Threshold {
		return "", &Blocked{"classifier", fmt.Sprintf("score %.2f >= %.2f", s, g.Threshold)}
	}
	return text, nil
}

// Canary — уникальный токен в system prompt. Его появление в ответе означает дословную утечку промпта.
// Пересказ своими словами canary не ловит: секретов в system prompt быть не должно (LLM07).
type Canary struct{ Token string }

func (Canary) Name() string { return "canary" }

func (c Canary) Check(_ context.Context, text string) (string, error) {
	if strings.Contains(text, c.Token) {
		return "", &Blocked{"canary", "system prompt leak"}
	}
	return text, nil
}

// Egress вырезает из markdown ссылки и картинки на домены вне allowlist (см. StripLinks).
type Egress struct{ Policy URLPolicy }

func (Egress) Name() string { return "egress" }

func (e Egress) Check(_ context.Context, text string) (string, error) {
	out, n := StripLinks(text, e.Policy)
	if n > 0 {
		slog.Warn("egress: links removed", "count", n) // всплеск — повод смотреть, кто подложил документ
	}
	return out, nil
}

// Redact маскирует секреты и простые PII. Подробно о PII-редакции (NER, Presidio) — модуль 11.
type Redact struct{}

var redactRe = []struct {
	re   *regexp.Regexp
	mask string
}{
	{regexp.MustCompile(`sk-ant-[A-Za-z0-9_-]{10,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36,}`), "[секрет]"},
	{regexp.MustCompile(`[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}`), "[email]"},
	{regexp.MustCompile(`\+7[\s(-]*\d{3}[\s)-]*\d{3}[\s-]*\d{2}[\s-]*\d{2}`), "[телефон]"},
}

func (Redact) Name() string { return "redact" }

func (Redact) Check(_ context.Context, text string) (string, error) {
	for _, r := range redactRe {
		text = r.re.ReplaceAllString(text, r.mask)
	}
	return text, nil
}
