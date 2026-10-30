//go:build !linux && !windows

package main

func cpuTimes() (idle, total uint64, ok bool)     { return 0, 0, false }
func memInfo() (used, total uint64, scope string) { return 0, 0, "" }
func diskInfo(path string) (used, total uint64)   { return 0, 0 }
func procRSS(pid int) uint64                      { return 0 }
func machineName() string                         { return "" }
