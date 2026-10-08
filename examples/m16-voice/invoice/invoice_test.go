package invoice

import "testing"

func TestKopecks(t *testing.T) {
	for in, want := range map[string]int64{
		"12 345,67": 1234567, "12345.67": 1234567, "1 200": 120000,
		"0,5": 50, "-10,00": -1000, "-0,50": -50, " 7 ": 700,
	} {
		if got, err := Kopecks(in); err != nil || got != want {
			t.Errorf("Kopecks(%q) = %d, %v; want %d", in, got, err, want)
		}
	}
	for _, in := range []string{"", "12,345,67", "1.234", "abc", "12,-5"} {
		if _, err := Kopecks(in); err == nil {
			t.Errorf("Kopecks(%q): ждали ошибку", in)
		}
	}
}

func TestValidate(t *testing.T) {
	ok := Invoice{Number: "СЧ-118", Date: "2026-09-30", Total: "15 300,00",
		Lines: []Line{{"Лицензия", "12 000,00"}, {"Поддержка", "3 300,00"}}}
	if err := ok.Validate(); err != nil {
		t.Fatalf("валидный счёт: %v", err)
	}
	bad := ok
	bad.Lines = []Line{{"Лицензия", "12 000,00"}, {"Поддержка", "8 300,00"}} // VLM прочла 3 как 8
	if bad.Validate() == nil {
		t.Error("расхождение суммы не поймано")
	}
	bad = ok
	bad.Date = "30.09.2026"
	if bad.Validate() == nil {
		t.Error("формат даты не проверен")
	}
}
