// Command agentgate — Stop-хук Claude Code: см. пакет agentgate.
package main

import (
	"os"
	"os/exec"

	"aiec/examples/m04-tools/agentgate"
)

func main() {
	base := os.Getenv("AGENTGATE_BASE")
	if base == "" {
		base = "main"
	}
	run := func(name string, args ...string) (string, error) {
		out, err := exec.Command(name, args...).CombinedOutput()
		return string(out), err
	}
	os.Exit(agentgate.Gate(run, base, os.Stderr))
}
