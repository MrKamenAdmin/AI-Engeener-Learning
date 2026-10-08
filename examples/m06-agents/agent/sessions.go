package agent

import (
	"context"
	"errors"
)

// Check — состояние длинной задачи по мнению кода, а не модели: прогон тестов, число
// пунктов с passes=true в features.json, валидность артефактов. progress — отпечаток
// прогресса (например, «37/52 + хэш HEAD»), по нему видно, что сессия что-то сдвинула.
type Check func(ctx context.Context) (done bool, progress string, err error)

var ErrStuck = errors.New("agent: no progress in consecutive sessions")

// RunSessions крутит короткие сессии агента со свежим контекстом, пока Check не скажет
// «готово». Между сессиями состояние живёт только в файлах (progress, notes) и git,
// поэтому обрыв процесса стоит максимум одной сессии: перезапуск продолжает с того же места.
// Возвращает число проведённых сессий.
func RunSessions(ctx context.Context, a *Agent, task string, check Check, maxSessions, patience int) (int, error) {
	last, idle := "", 0
	for s := 0; s < maxSessions; s++ {
		done, progress, err := check(ctx)
		if err != nil || done {
			return s, err
		}
		if s > 0 && progress == last {
			if idle++; idle >= patience {
				return s, ErrStuck // сессии жгут бюджет, не двигая задачу: зовём человека
			}
		} else {
			idle = 0
		}
		last = progress
		// Лимит итераций — нормальный конец сессии: следующая продолжит по файлам.
		if _, err := a.Run(ctx, task); err != nil && !errors.Is(err, ErrMaxIters) {
			return s + 1, err // ErrBudget, отмена, недоступный API — стоп
		}
	}
	done, _, err := check(ctx)
	if err == nil && !done {
		err = errors.New("agent: session limit reached, task not done")
	}
	return maxSessions, err
}
