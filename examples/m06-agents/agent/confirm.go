package agent

import (
	"bufio"
	"fmt"
	"os"
	"strings"
	"sync"
)

func StdinConfirm() Confirm {
	var mu sync.Mutex
	in := bufio.NewReader(os.Stdin)
	return func(action string) bool {
		mu.Lock()
		defer mu.Unlock()
		fmt.Fprintf(os.Stderr, "Агент хочет выполнить:\n  %s\nРазрешить? [y/N] ", action)
		line, _ := in.ReadString('\n')
		return strings.TrimSpace(strings.ToLower(line)) == "y"
	}
}
