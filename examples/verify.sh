#!/usr/bin/env bash
# Проверка примеров кода курса: каждый каталог с go.mod — отдельный Go-модуль.
# gofmt, go vet, go build, go test; Python-примеры — py_compile (запуск описан в шапке каждого файла).
set -uo pipefail
cd "$(dirname "$0")"
fail=0
for mod in */go.mod; do
  d=$(dirname "$mod")
  if (cd "$d" && test -z "$(gofmt -l .)" && go vet ./... && go build ./... && go test ./... >/dev/null); then
    echo "✓ go   $d"
  else
    echo "✗ go   $d"; fail=1
  fi
done
for py in */*.py; do
  [ -e "$py" ] || continue
  if python3 -m py_compile "$py"; then echo "✓ py   $py"; else echo "✗ py   $py"; fail=1; fi
done
exit $fail
