package agent

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand/v2"
	"sync"
	"time"
)

// ErrTransient помечает ошибку, которую имеет смысл повторить: таймаут, 429, 503.
// Оборачивайте в неё только то, что безопасно повторить: чтение или идемпотентную запись.
var ErrTransient = errors.New("transient")

// retry повторяет f при ErrTransient с экспоненциальной задержкой и джиттером:
// 100, 200, 400 мс плюс случайная добавка, чтобы ретраи не шли синхронной волной.
func retry(ctx context.Context, attempts int, f func(context.Context) (string, error)) (string, error) {
	for i := 0; ; i++ {
		out, err := f(ctx)
		if err == nil || !errors.Is(err, ErrTransient) || i == attempts-1 {
			return out, err
		}
		d := time.Duration(100<<i) * time.Millisecond
		d += rand.N(d)
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(d):
		}
	}
}

type idemKeyCtx struct{}

// IdempotencyKey — ключ текущего вызова; инструмент передаёт его во внешний API
// (заголовок Idempotency-Key), чтобы дедуплицировал и сервер: ретрай после таймаута
// мог дойти до него дважды.
func IdempotencyKey(ctx context.Context) string {
	k, _ := ctx.Value(idemKeyCtx{}).(string)
	return k
}

// Idempotent оборачивает мутирующий инструмент: одинаковый вызов (сессия + имя +
// аргументы) выполняется один раз, повтор получает сохранённый результат. Агент повторяет
// вызовы после таймаутов, компакции и рестарта — без этого письмо уйдёт дважды.
func Idempotent(session string, t Tool) Tool {
	var mu sync.Mutex
	done := map[string]string{}
	run := t.Run
	t.Run = func(ctx context.Context, input json.RawMessage) (string, error) {
		key, err := idemKey(session, t.Param.Name, input)
		if err != nil {
			return "", err
		}
		mu.Lock()
		prev, ok := done[key]
		mu.Unlock()
		if ok {
			return "Already done earlier in this session, not repeated. Result was: " + prev, nil
		}
		out, err := run(context.WithValue(ctx, idemKeyCtx{}, key), input)
		if err == nil { // ошибки не запоминаем: повтор после ошибки должен выполниться
			mu.Lock()
			done[key] = out
			mu.Unlock()
		}
		return out, err
	}
	// Упрощение: журнал в памяти процесса и без блокировки на время выполнения.
	// Для рестартов и параллельных одинаковых вызовов — таблица с UNIQUE(key) в БД.
	return t
}

// idemKey не зависит от порядка ключей и пробелов: {"b":1,"a":2} и {"a":2, "b":1} — один вызов.
func idemKey(session, tool string, input json.RawMessage) (string, error) {
	var v any
	if err := json.Unmarshal(input, &v); err != nil {
		return "", fmt.Errorf("invalid input JSON: %w", err)
	}
	canon, err := json.Marshal(v) // encoding/json сортирует ключи map
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256([]byte(session + "\x00" + tool + "\x00" + string(canon)))
	return hex.EncodeToString(sum[:16]), nil
}
