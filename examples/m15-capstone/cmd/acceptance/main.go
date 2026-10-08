// acceptance — гейт приёмки капстоуна (модуль 15): читает JSON-отчёты шагов CI
// и печатает вердикт по каждому критерию. Exit-код: 0 — принято, 1 — нет, 2 — ошибка входа.
//
//	go run ./cmd/acceptance -reports reports
//	go run ./cmd/acceptance -reports reports -thresholds my-thresholds.json
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"aiec/examples/m15-capstone"
)

func main() {
	dir := flag.String("reports", "reports", "каталог с JSON-отчётами")
	cfg := flag.String("thresholds", "", "JSON с порогами; поля, которых нет в файле, берутся по умолчанию")
	flag.Parse()

	t := acceptance.Defaults
	if *cfg != "" {
		b, err := os.ReadFile(*cfg)
		if err == nil {
			err = json.Unmarshal(b, &t) // поверх значений по умолчанию
		}
		if err != nil {
			fmt.Fprintln(os.Stderr, "thresholds:", err)
			os.Exit(2)
		}
	}
	r, err := acceptance.LoadReports(*dir)
	if err != nil {
		fmt.Fprintln(os.Stderr, "reports:", err)
		os.Exit(2)
	}

	results := acceptance.Check(r, t)
	failed := 0
	for _, res := range results {
		mark := "✓"
		if !res.OK {
			mark, failed = "✗", failed+1
		}
		fmt.Printf("%s %-8s %s\n", mark, res.ID, res.Detail)
	}
	if failed > 0 {
		fmt.Printf("\nНЕ ПРИНЯТО: не выполнено %d из %d автоматических критериев\n", failed, len(results))
		os.Exit(1)
	}
	fmt.Println("\nПРИНЯТО: автоматические критерии выполнены; ADR, SLO и откат проверяются на защите")
}
