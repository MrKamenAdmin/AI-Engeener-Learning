package llmmw

import (
	"context"
	"testing"

	"github.com/open-feature/go-sdk/openfeature"
	"github.com/open-feature/go-sdk/openfeature/memprovider"
)

func TestVariantOF(t *testing.T) {
	err := openfeature.SetProviderAndWait(memprovider.NewInMemoryProvider(map[string]memprovider.InMemoryFlag{
		"answer": {
			Key: "answer", State: memprovider.Enabled, DefaultVariant: "v13",
			Variants: map[string]any{
				"v12": map[string]any{"name": "v12", "prompt": "answer@v12", "model": "claude-opus-5"},
				"v13": map[string]any{"name": "v13", "prompt": "answer@v13", "model": "claude-sonnet-5", "max_tokens": 800},
			},
		},
	}))
	if err != nil {
		t.Fatal(err)
	}
	c := openfeature.NewClient("llm")
	def := Variant{Name: "default", Model: "claude-opus-5"}
	if v := VariantOF(context.Background(), c, "answer", "acme", "u1", def); v.Name != "v13" || v.MaxTokens != 800 {
		t.Fatalf("got %+v", v)
	}
	if v := VariantOF(context.Background(), c, "missing", "acme", "u1", def); v != def {
		t.Fatalf("неизвестный флаг → default, got %+v", v)
	}
}
