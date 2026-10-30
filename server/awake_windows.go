//go:build windows

package main

import (
	"os/exec"
	"runtime"
	"sync"
	"syscall"

	"golang.org/x/sys/windows"
)

// stayAwake keeps Windows from sleeping while the PC works for the main server. The request belongs to a thread,
// so one locked goroutine holds it; calls nest (two jobs at once keep it until both finish).
var procSetThreadExecutionState = windows.NewLazySystemDLL("kernel32.dll").NewProc("SetThreadExecutionState")

const (
	esContinuous     = 0x80000000
	esSystemRequired = 0x00000001
)

var awake = struct {
	sync.Mutex
	n  int
	ch chan bool
}{}

func stayAwake(on bool) {
	awake.Lock()
	defer awake.Unlock()
	if awake.ch == nil {
		awake.ch = make(chan bool, 8)
		go func() {
			runtime.LockOSThread()
			for v := range awake.ch {
				if v {
					procSetThreadExecutionState.Call(uintptr(esContinuous | esSystemRequired))
				} else {
					procSetThreadExecutionState.Call(uintptr(esContinuous))
				}
			}
		}()
	}
	if on {
		awake.n++
		if awake.n == 1 {
			awake.ch <- true
		}
	} else if awake.n > 0 {
		awake.n--
		if awake.n == 0 {
			awake.ch <- false
		}
	}
}

// hideWindow keeps helper programs (Python, nvidia-smi) from flashing a console window.
func hideWindow(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW}
}
