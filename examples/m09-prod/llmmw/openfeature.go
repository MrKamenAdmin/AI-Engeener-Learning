package llmmw

import (
	"context"
	"encoding/json"

	"github.com/open-feature/go-sdk/openfeature"
)

// VariantOF — тот же выбор варианта через OpenFeature: процент, таргетинг и kill switch
// живут в сервисе флагов (flagd, Unleash, LaunchDarkly и др.), код от вендора не зависит.
func VariantOF(ctx context.Context, c *openfeature.Client, key, tenant, user string, def Variant) Variant {
	v, err := c.ObjectValue(ctx, key, def, openfeature.NewEvaluationContext(user, map[string]any{"tenant": tenant}))
	if err != nil {
		return def // сервис флагов недоступен — работаем на проверенном варианте
	}
	var out Variant // провайдеры отдают объект как map[string]any из JSON
	if b, err := json.Marshal(v); err != nil || json.Unmarshal(b, &out) != nil || out.Model == "" {
		return def
	}
	return out
}
