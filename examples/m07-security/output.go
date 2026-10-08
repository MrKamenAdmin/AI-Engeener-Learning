package secure

import (
	"context"
	"errors"
	"fmt"
	"html"
	"html/template"
	"io"
	"net/url"
	"os/exec"
	"regexp"
	"slices"
	"sort"
	"strings"
)

// ---------- Markdown: эксфильтрация через ссылки и картинки ----------

// URLPolicy — allowlist доменов, на которые ответ модели может ссылаться.
// Только домены, которые вы контролируете: открытый редирект или прокси на «доверенном»
// домене превращает его в канал утечки (так EchoLeak обошёл CSP M365 Copilot).
type URLPolicy struct{ Hosts []string } // "docs.example.com" разрешает и поддомены

func (p URLPolicy) Allowed(raw string) bool {
	// CommonMark декодирует HTML-сущности в адресе ссылки: h&#116;tps:// станет https://.
	raw = html.UnescapeString(strings.TrimSpace(strings.Trim(raw, "<>")))
	if raw == "" || strings.ContainsAny(raw, "\\\t\r\n ") { // браузер читает \ как /: /\evil.io → //evil.io
		return false
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.User != nil {
		return false // относительные, //host, javascript:, data: и прочее — нет
	}
	h := strings.ToLower(u.Hostname())
	for _, a := range p.Hosts {
		if h == a || strings.HasSuffix(h, "."+a) {
			return true
		}
	}
	return false
}

var (
	mdInline = regexp.MustCompile(`(!?)\[([^\]]*)\]\(\s*(<[^>]*>|[^)\s]*)[^)]*\)`) // [t](url "title"), ![a](url)
	mdDest   = regexp.MustCompile(`\]\(\s*(<[^>]*>|[^)\s]*)[^)]*\)`)               // любой ](url): вложенные [], \], [![a](x)](y)
	// [id]: url — и в цитате или списке (> [id]: …), и с адресом на следующей строке.
	mdRefDef = regexp.MustCompile(`(?m)^(?:[ \t]*(?:>|[*+-]|\d{1,9}[.)]))*[ \t]*\[(?:\\.|[^\]\\])+\]:[ \t]*(?:\n[ \t>]*)?(<[^>]*>|\S+).*$`)
	bareURL  = regexp.MustCompile(`(?i)\bhttps?://[^\s<>"'()\[\]]+`) // автоссылки и <img src=…>
)

// StripLinks удаляет ссылки и картинки на домены вне allowlist; текст ссылки остаётся.
// Возвращает новый текст и число удалённых адресов. Регэкспы по markdown — эвристика:
// надёжнее проверять узлы ast.Link и ast.Image после разбора goldmark, а CSP держит пропущенное.
func StripLinks(md string, p URLPolicy) (string, int) {
	n := 0
	md = mdInline.ReplaceAllStringFunc(md, func(m string) string {
		s := mdInline.FindStringSubmatch(m)
		if p.Allowed(s[3]) {
			return m
		}
		n++
		if s[1] == "!" {
			return "[изображение удалено]"
		}
		return s[2]
	})
	// Второй проход: то, что первый не разобрал или сам собрал из [![a](x)](y), — без адреса.
	md = mdDest.ReplaceAllStringFunc(md, func(m string) string {
		if p.Allowed(mdDest.FindStringSubmatch(m)[1]) {
			return m
		}
		n++
		return "]"
	})
	md = mdRefDef.ReplaceAllStringFunc(md, func(m string) string {
		if p.Allowed(mdRefDef.FindStringSubmatch(m)[1]) {
			return m
		}
		n++
		return ""
	})
	md = bareURL.ReplaceAllStringFunc(md, func(m string) string {
		if p.Allowed(m) {
			return m
		}
		n++
		return "[ссылка удалена]"
	})
	return md, n
}

// ---------- SQL: модель выбирает из allowlist, а не пишет запрос ----------

// QuerySpec — то, что модель возвращает через structured output вместо сырого SQL.
type QuerySpec struct {
	Table   string            `json:"table"`
	Columns []string          `json:"columns"`
	Where   map[string]string `json:"where"` // колонка = значение
	Limit   int               `json:"limit"`
}

// Schema — allowlist: таблица → разрешённые колонки. В SQL попадают только эти строки.
type Schema map[string][]string

var ErrNotAllowed = errors.New("not in allowlist")

// BuildSelect собирает параметризованный SELECT. tenantID берётся из сессии, а не от модели;
// значения уходят в args, идентификаторы — только из allowlist. Исполнять под read-only ролью
// со statement_timeout (модуль 12, разбор text-to-SQL).
func BuildSelect(s Schema, q QuerySpec, tenantID string) (string, []any, error) {
	cols, ok := s[q.Table]
	if !ok {
		return "", nil, fmt.Errorf("%w: table %q", ErrNotAllowed, q.Table)
	}
	if len(q.Columns) == 0 {
		return "", nil, fmt.Errorf("%w: empty column list", ErrNotAllowed)
	}
	for _, c := range q.Columns {
		if !slices.Contains(cols, c) {
			return "", nil, fmt.Errorf("%w: column %q", ErrNotAllowed, c)
		}
	}
	keys := make([]string, 0, len(q.Where))
	for k := range q.Where {
		if !slices.Contains(cols, k) {
			return "", nil, fmt.Errorf("%w: filter %q", ErrNotAllowed, k)
		}
		keys = append(keys, k)
	}
	sort.Strings(keys) // детерминированный SQL: удобно в тестах и в кэше планов
	var b strings.Builder
	args := []any{tenantID}
	fmt.Fprintf(&b, "SELECT %s FROM %s WHERE tenant_id = $1", strings.Join(q.Columns, ", "), q.Table)
	for _, k := range keys {
		args = append(args, q.Where[k])
		fmt.Fprintf(&b, " AND %s = $%d", k, len(args))
	}
	fmt.Fprintf(&b, " LIMIT %d", min(max(q.Limit, 1), 100))
	return b.String(), args, nil
}

// ---------- HTML: контекстное экранирование html/template ----------

type Source struct{ Title, URL string }

// Ответ модели — недоверенный текст. html/template экранирует его по контексту:
// <script> в тексте станет &lt;script&gt;, а javascript: в href — #ZgotmplZ.
var answerTmpl = template.Must(template.New("answer").Parse(
	`<div class="answer">{{.Text}}</div>
<ul>{{range .Sources}}<li><a href="{{.URL}}" rel="noopener nofollow">{{.Title}}</a></li>{{end}}</ul>`))

func RenderAnswer(w io.Writer, text string, sources []Source) error {
	return answerTmpl.Execute(w, struct {
		Text    string
		Sources []Source
	}{text, sources})
}

// ---------- Shell: никакого sh -c ----------

// Command — разрешённая команда: фиксированный бинарь и флаги, аргументы модели — только позиционные.
type Command struct {
	Path    string         // абсолютный путь: не ищем в $PATH, который мог подменить кто-то ещё
	Fixed   []string       // флаги задаём мы
	Arg     *regexp.Regexp // с якорями ^…$: MatchString ищет подстроку
	MaxArgs int
}

// Build проверяет аргументы и собирает *exec.Cmd без shell: ; | $() и кавычки
// остаются обычными символами. «--» перед аргументами не даёт выдать их за флаги.
func (c Command) Build(ctx context.Context, args []string) (*exec.Cmd, error) {
	if len(args) == 0 || len(args) > c.MaxArgs {
		return nil, fmt.Errorf("%w: %d args", ErrNotAllowed, len(args))
	}
	for _, a := range args {
		if strings.HasPrefix(a, "-") || strings.Contains(a, "..") || !c.Arg.MatchString(a) {
			return nil, fmt.Errorf("%w: arg %q", ErrNotAllowed, a)
		}
	}
	argv := append(append(slices.Clone(c.Fixed), "--"), args...)
	cmd := exec.CommandContext(ctx, c.Path, argv...)
	cmd.Env = []string{"PATH=/usr/bin:/bin", "LANG=C.UTF-8"} // никаких секретов из окружения сервиса
	return cmd, nil
}
