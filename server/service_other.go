//go:build !windows

package main

// Outside Windows there is no service manager to talk to: Docker, systemd or the NAS keeps the server running.
func serviceMain(args []string) (bool, error) { return false, nil }

func pauseIfDoubleClicked() {}

func startHint() {}
