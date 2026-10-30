//go:build !windows

package main

import "os/exec"

func stayAwake(bool) {}

func hideWindow(*exec.Cmd) {}
