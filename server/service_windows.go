//go:build windows

package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

const svcName = "WerdServer"
const svcDisplay = "Werd Server"
const fwRule = "Werd Server"

// serviceMain handles "install", "uninstall", "start", "stop", "status", and running under the service manager.
func serviceMain(args []string) (bool, error) {
	// the installed service is registered as "werd-server.exe service <options>"
	if len(args) > 0 && args[0] == "service" {
		return true, svc.Run(svcName, &winService{args: args[1:]})
	}
	if is, err := svc.IsWindowsService(); err == nil && is {
		return true, svc.Run(svcName, &winService{args: args})
	}
	if len(args) == 0 {
		return false, nil
	}
	switch strings.ToLower(args[0]) {
	case "install":
		return true, install(args[1:])
	case "uninstall", "remove":
		return true, uninstall()
	case "start":
		return true, control(func(s *mgr.Service) error { return s.Start() }, "started")
	case "stop":
		return true, control(func(s *mgr.Service) error { _, err := s.Control(svc.Stop); return err }, "stopped")
	case "status":
		return true, status()
	case "help", "-h", "--help", "/?":
		usage()
		return true, nil
	}
	return false, nil
}

func usage() {
	fmt.Println(`Werd server

  werd-server.exe                 run in this window (Ctrl+C to stop)
  werd-server.exe install [opts]  install as a Windows service that starts with Windows (run as administrator)
  werd-server.exe uninstall       remove the service
  werd-server.exe start | stop | status

Options (also for install): -addr :8080   -data <folder>   -urls http://192.168.1.20:8080,http://100.x.y.z:8080
         -domain example.com   -tls-cert f -tls-key f   -trust-proxy`)
	pauseIfDoubleClicked()
}

func install(args []string) error {
	c, err := parseConfig(args)
	if err != nil {
		return err
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("could not reach the service manager — right-click the command prompt and choose \"Run as administrator\" (%v)", err)
	}
	defer m.Disconnect()
	if s, err := m.OpenService(svcName); err == nil {
		s.Close()
		return fmt.Errorf("the service is already installed (use \"werd-server.exe uninstall\" first)")
	}
	// keep the data folder explicit so the service and a console run share it
	full := append([]string{"service", "-data", c.DataDir}, args...)
	s, err := m.CreateService(svcName, exe, mgr.Config{
		DisplayName: svcDisplay,
		Description: "Shared library and family features for the Werd app.",
		StartType:   mgr.StartAutomatic,
	}, full...)
	if err != nil {
		return err
	}
	defer s.Close()
	_ = s.SetRecoveryActions([]mgr.RecoveryAction{
		{Type: mgr.ServiceRestart, Delay: 5 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 30 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 2 * time.Minute},
	}, 86400)
	// let phones on the network reach it
	for _, port := range ports(c) {
		_ = exec.Command("netsh", "advfirewall", "firewall", "add", "rule", "name="+fwRule, "dir=in", "action=allow",
			"protocol=TCP", "localport="+port).Run()
	}
	if err := s.Start(); err != nil {
		return fmt.Errorf("installed, but it did not start: %v", err)
	}
	fmt.Printf("Werd Server is installed and running. It starts with Windows.\nData: %s\n", c.DataDir)
	fmt.Printf("Admin password: in %s\n", filepath.Join(c.DataDir, "admin-token.txt"))
	for _, a := range lanAddresses(c) {
		fmt.Println("App server address:", a)
	}
	pauseIfDoubleClicked()
	return nil
}

func ports(c *Config) []string {
	_, p, err := net.SplitHostPort(c.Addr)
	if err != nil || p == "" {
		p = "8080"
	}
	out := []string{p}
	if len(c.Domains) > 0 {
		out = append(out, "80")
	}
	return out
}

func uninstall() error {
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("run as administrator (%v)", err)
	}
	defer m.Disconnect()
	s, err := m.OpenService(svcName)
	if err != nil {
		return fmt.Errorf("the service is not installed")
	}
	defer s.Close()
	_, _ = s.Control(svc.Stop)
	time.Sleep(2 * time.Second)
	if err := s.Delete(); err != nil {
		return err
	}
	_ = exec.Command("netsh", "advfirewall", "firewall", "delete", "rule", "name="+fwRule).Run()
	fmt.Println("Werd Server service removed. Your data folder was kept.")
	return nil
}

func control(f func(*mgr.Service) error, done string) error {
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("run as administrator (%v)", err)
	}
	defer m.Disconnect()
	s, err := m.OpenService(svcName)
	if err != nil {
		return fmt.Errorf("the service is not installed")
	}
	defer s.Close()
	if err := f(s); err != nil {
		return err
	}
	fmt.Println("Werd Server", done)
	return nil
}

func status() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	s, err := m.OpenService(svcName)
	if err != nil {
		fmt.Println("not installed")
		return nil
	}
	defer s.Close()
	q, err := s.Query()
	if err != nil {
		return err
	}
	states := map[svc.State]string{svc.Stopped: "stopped", svc.StartPending: "starting", svc.StopPending: "stopping", svc.Running: "running"}
	fmt.Println(states[q.State])
	return nil
}

type winService struct{ args []string }

func (w *winService) Execute(_ []string, req <-chan svc.ChangeRequest, st chan<- svc.Status) (bool, uint32) {
	st <- svc.Status{State: svc.StartPending}
	c, err := parseConfig(w.args)
	if err != nil {
		return true, 1
	}
	_ = os.MkdirAll(c.DataDir, 0o755)
	logf, _ := os.OpenFile(filepath.Join(c.DataDir, "werd-server.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	var out io.Writer = io.Discard
	if logf != nil {
		defer logf.Close()
		if fi, err := logf.Stat(); err == nil && fi.Size() > 10<<20 {
			_ = logf.Truncate(0)
		}
		out = logf
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- run(ctx, c, out) }()
	st <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	for {
		select {
		case r := <-req:
			switch r.Cmd {
			case svc.Interrogate:
				st <- r.CurrentStatus
			case svc.Stop, svc.Shutdown:
				st <- svc.Status{State: svc.StopPending}
				cancel()
				<-done
				return false, 0
			}
		case err := <-done:
			cancel()
			if err != nil {
				fmt.Fprintln(out, "error:", err)
				return true, 2
			}
			return false, 0
		}
	}
}

// doubleClicked: the console window belongs to this program alone (Explorer started it), not to a command prompt.
func doubleClicked() bool {
	proc := syscall.NewLazyDLL("kernel32.dll").NewProc("GetConsoleProcessList")
	ids := make([]uint32, 4)
	n, _, _ := proc.Call(uintptr(unsafe.Pointer(&ids[0])), uintptr(len(ids)))
	return n == 1
}

// pauseIfDoubleClicked keeps the console window open so the message can be read.
func pauseIfDoubleClicked() {
	if !doubleClicked() {
		return
	}
	fmt.Print("\nPress Enter to close…")
	_, _ = bufio.NewReader(os.Stdin).ReadString('\n')
}

// startHint is shown when the server runs in a console window.
func startHint() {
	fmt.Println("Werd Server is running in this window — closing the window stops it.")
	fmt.Println("To run it in the background and start it with Windows, open a command prompt as administrator and run:")
	fmt.Println("    werd-server.exe install")
	fmt.Println()
}
