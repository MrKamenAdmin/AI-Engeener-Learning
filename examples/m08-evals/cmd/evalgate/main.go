// evalgate сравнивает results.jsonl из PR с baseline из main и пишет Markdown-отчёт.
// Код выхода 1 — гейт красный. Нет файла baseline — проверяются только абсолютные пороги.
//
//	go run ./cmd/evalgate -base baseline/results.jsonl -cur out/results.jsonl -out out/report.md
package main

import (
	"errors"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"os"
	"strings"

	evals "aiec/examples/m08-evals"
)

func main() {
	basePath := flag.String("base", "baseline/results.jsonl", "результаты main")
	curPath := flag.String("cur", "out/results.jsonl", "результаты PR")
	out := flag.String("out", "out/report.md", "куда записать отчёт")
	cfg := evals.GateConfig{Resamples: 10000, Seed: 1}
	flag.Float64Var(&cfg.MinPassRate, "min-pass-rate", 0.85, "абсолютный порог")
	flag.Float64Var(&cfg.NoiseFloor, "noise-floor", 0.03, "разброс повторов baseline")
	flag.Float64Var(&cfg.MaxErrorRate, "max-error-rate", 0.1, "доля инфраструктурных ошибок")
	critical := flag.String("critical", "safety,adversarial", "срезы с порогом 100%")
	flag.Parse()
	cfg.CriticalTags = strings.Split(*critical, ",")

	cur, err := read(*curPath)
	if err != nil {
		log.Fatal(err)
	}
	base, err := read(*basePath)
	if errors.Is(err, fs.ErrNotExist) {
		log.Printf("baseline %s not found: absolute thresholds only", *basePath)
	} else if err != nil {
		log.Fatal(err)
	}

	rep := evals.Compare(base, cur, cfg)
	md := rep.Markdown()
	if err := os.WriteFile(*out, []byte(md), 0o644); err != nil {
		log.Fatal(err)
	}
	fmt.Print(md)
	if !rep.Passed() {
		os.Exit(1)
	}
}

func read(path string) ([]evals.CaseResult, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return evals.ReadResults(f)
}
