package llmmw

import (
	"encoding/json"
	"fmt"
	"hash/fnv"
	"sync/atomic"
)

// Variant — что переключает флаг: промпт, модель и параметры вместе. Промпт, отлаженный
// под одну модель, на другой ведёт себя иначе, поэтому раздельные флаги дают непроверенные комбинации.
type Variant struct {
	Name      string `json:"name"`   // пишется в трейс (app.flag.variant) и в онлайн-evals
	Prompt    string `json:"prompt"` // "answer@v13"
	Model     string `json:"model"`
	MaxTokens int64  `json:"max_tokens"`
}

type Flag struct {
	Key       string          `json:"key"`
	Killed    bool            `json:"killed"` // kill switch: всем Control, без деплоя
	Control   Variant         `json:"control"`
	Treatment Variant         `json:"treatment"`
	Percent   float64         `json:"percent"` // доля трафика на Treatment, 0–100
	Tenants   map[string]bool `json:"tenants"` // таргетинг: true — всегда Treatment, false — никогда
}

// Evaluate детерминирован: один unit (пользователь или тенант) всегда получает один вариант,
// а при росте Percent 1 → 5 → 25 → 100 попавшие в Treatment из него не выпадают.
func (f *Flag) Evaluate(tenant, unit string) Variant {
	if f.Killed {
		return f.Control
	}
	if on, ok := f.Tenants[tenant]; ok {
		if on {
			return f.Treatment
		}
		return f.Control
	}
	h := fnv.New32a()
	h.Write([]byte(f.Key + "/" + unit)) // ключ флага в хэше: разные флаги режут трафик независимо
	if float64(h.Sum32()%10000) < f.Percent*100 {
		return f.Treatment
	}
	return f.Control
}

// Registry хранит флаги и перечитывает их на лету (файл, etcd, сервис флагов).
type Registry struct {
	flags atomic.Pointer[map[string]Flag]
}

// Load заменяет конфиг целиком. Битый конфиг отклоняется, и работает предыдущий:
// опечатка в JSON не должна переключить весь прод на непроверенный промпт.
func (r *Registry) Load(data []byte) error {
	var list []Flag
	if err := json.Unmarshal(data, &list); err != nil {
		return fmt.Errorf("flags: %w", err)
	}
	m := make(map[string]Flag, len(list))
	for _, f := range list {
		if f.Percent < 0 || f.Percent > 100 || f.Control.Model == "" || f.Treatment.Model == "" {
			return fmt.Errorf("flags: %q: percent 0–100 and both models required", f.Key)
		}
		m[f.Key] = f
	}
	r.flags.Store(&m)
	return nil
}

// Variant — вариант для запроса; если флага нет, возвращается def (поведение «как до флагов»).
func (r *Registry) Variant(key, tenant, unit string, def Variant) Variant {
	m := r.flags.Load()
	if m == nil {
		return def
	}
	f, ok := (*m)[key]
	if !ok {
		return def
	}
	return f.Evaluate(tenant, unit)
}
