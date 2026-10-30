//go:build linux

package main

import (
	"bufio"
	"os"
	"strconv"
	"strings"
	"syscall"
)

func cpuTimes() (idle, total uint64, ok bool) {
	f, err := os.Open("/proc/stat")
	if err != nil {
		return 0, 0, false
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	if !sc.Scan() {
		return 0, 0, false
	}
	fields := strings.Fields(sc.Text()) // cpu user nice system idle iowait irq softirq steal …
	if len(fields) < 5 || fields[0] != "cpu" {
		return 0, 0, false
	}
	for i, v := range fields[1:] {
		n, _ := strconv.ParseUint(v, 10, 64)
		if i >= 8 { // guest time is already in user
			break
		}
		total += n
		if i == 3 || i == 4 { // idle + iowait
			idle += n
		}
	}
	return idle, total, true
}

func readUint(path string) (uint64, bool) {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0, false
	}
	n, err := strconv.ParseUint(strings.TrimSpace(string(b)), 10, 64)
	return n, err == nil
}

// memInfo: the container's limit and use when Docker caps memory, else the machine's.
func memInfo() (used, total uint64, scope string) {
	var memTotal, memAvail uint64
	if f, err := os.Open("/proc/meminfo"); err == nil {
		sc := bufio.NewScanner(f)
		for sc.Scan() {
			fs := strings.Fields(sc.Text())
			if len(fs) < 2 {
				continue
			}
			n, _ := strconv.ParseUint(fs[1], 10, 64)
			switch fs[0] {
			case "MemTotal:":
				memTotal = n * 1024
			case "MemAvailable:":
				memAvail = n * 1024
			}
		}
		f.Close()
	}
	if lim, ok := readUint("/sys/fs/cgroup/memory.max"); ok && lim > 0 && lim < memTotal {
		if cur, ok := readUint("/sys/fs/cgroup/memory.current"); ok {
			return cur, lim, "container"
		}
	}
	if lim, ok := readUint("/sys/fs/cgroup/memory/memory.limit_in_bytes"); ok && lim > 0 && lim < memTotal {
		if cur, ok := readUint("/sys/fs/cgroup/memory/memory.usage_in_bytes"); ok {
			return cur, lim, "container"
		}
	}
	if memTotal == 0 {
		return 0, 0, ""
	}
	return memTotal - memAvail, memTotal, "machine"
}

func diskInfo(path string) (used, total uint64) {
	var st syscall.Statfs_t
	if syscall.Statfs(path, &st) != nil {
		return 0, 0
	}
	total = st.Blocks * uint64(st.Bsize)
	free := st.Bavail * uint64(st.Bsize)
	return total - free, total
}

func procRSS(pid int) uint64 {
	b, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/status")
	if err != nil {
		return 0
	}
	for _, l := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(l, "VmRSS:") {
			fs := strings.Fields(l)
			if len(fs) >= 2 {
				n, _ := strconv.ParseUint(fs[1], 10, 64)
				return n * 1024
			}
		}
	}
	return 0
}

// machineName: the Synology model when running on one, plus the processor.
func machineName() string {
	cpu := ""
	if b, err := os.ReadFile("/proc/cpuinfo"); err == nil {
		for _, l := range strings.Split(string(b), "\n") {
			if strings.HasPrefix(l, "model name") || strings.HasPrefix(l, "Model") {
				if i := strings.Index(l, ":"); i > 0 {
					cpu = cleanMachine(l[i+1:])
					break
				}
			}
		}
	}
	model := ""
	if b, err := os.ReadFile("/proc/sys/kernel/syno_hw_version"); err == nil {
		model = "Synology " + strings.TrimSpace(string(b))
	}
	switch {
	case model != "" && cpu != "":
		return model + " · " + cpu
	case model != "":
		return model
	default:
		return cpu
	}
}
