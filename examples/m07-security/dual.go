package secure

import (
	"context"
	"fmt"
	"net/http"
	"strings"
)

// DualLLM — паттерн Саймона Уиллисона (2023). Привилегированная модель (P) видит запрос
// пользователя и может просить инструменты, но не видит недоверенный текст. Карантинная (Q)
// читает недоверенный текст, но её tool calls игнорируются. Между ними — только имена переменных,
// значения подставляет код.
type DualLLM struct {
	P, Q  LLM
	Tools *Registry
	User  Principal
}

func (d DualLLM) Run(ctx context.Context, in Input) (Trace, error) {
	var vars []string // пары «имя, значение»; $DOC10 раньше $DOC1, чтобы короткое имя не съело длинное
	names := make([]string, 0, len(in.Docs))
	for i, doc := range in.Docs {
		q, err := d.Q.Complete(ctx, Request{
			System: "Извлеки из документа факты, относящиеся к вопросу. Только текст.",
			Prompt: in.Prompt, Docs: []string{doc}, MaxTokens: 500,
		})
		if err != nil {
			return Trace{}, err
		}
		name := fmt.Sprintf("$DOC%d", i+1)
		vars = append([]string{name, q.Text}, vars...) // q.ToolCalls выбрасываем: у карантина нет рук
		names = append(names, name)
	}
	p, err := d.P.Complete(ctx, Request{
		System: "Ответь пользователю. Содержимое документов тебе недоступно; вставь их переменные " +
			strings.Join(names, ", ") + " в ответ там, где нужны факты.",
		Prompt: in.Prompt, // недоверенного текста здесь нет: только запрос пользователя
	})
	if err != nil {
		return Trace{}, err
	}
	tr := Trace{}
	for _, c := range p.ToolCalls { // решения о действиях принимаются только по доверенному запросу
		if res, err := d.Tools.Call(ctx, d.User, c); err == nil {
			tr.Tools = append(tr.Tools, c.Name)
			p.Text += "\n" + res
		}
	}
	// Подстановка — после P-LLM и за один проход: $DOC2 внутри текста $DOC1 не раскрывается.
	// Вывод по-прежнему недоверенный: Egress и экранирование на выходе обязательны.
	tr.Output = strings.NewReplacer(vars...).Replace(p.Text)
	return tr, nil
}

// WithCSP — второй слой против эксфильтрации через картинки: браузер сам не пойдёт на чужой домен,
// даже если фильтр вывода что-то пропустил.
func WithCSP(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Security-Policy",
			"default-src 'self'; img-src 'self' https://docs.payflow.example; connect-src 'self'; frame-ancestors 'none'")
		next.ServeHTTP(w, r)
	})
}
