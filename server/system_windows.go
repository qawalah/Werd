//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

var (
	k32                   = syscall.NewLazyDLL("kernel32.dll")
	pGetSystemTimes       = k32.NewProc("GetSystemTimes")
	pGlobalMemoryStatusEx = k32.NewProc("GlobalMemoryStatusEx")
	pGetDiskFreeSpaceExW  = k32.NewProc("GetDiskFreeSpaceExW")
	pK32GetProcessMemInfo = k32.NewProc("K32GetProcessMemoryInfo")
)

func ft(f syscall.Filetime) uint64 { return uint64(f.HighDateTime)<<32 | uint64(f.LowDateTime) }

func cpuTimes() (idle, total uint64, ok bool) {
	var i, k, u syscall.Filetime
	r, _, _ := pGetSystemTimes.Call(uintptr(unsafe.Pointer(&i)), uintptr(unsafe.Pointer(&k)), uintptr(unsafe.Pointer(&u)))
	if r == 0 {
		return 0, 0, false
	}
	return ft(i), ft(k) + ft(u), true // kernel time includes idle
}

type memStatusEx struct {
	Length, MemoryLoad                       uint32
	TotalPhys, AvailPhys                     uint64
	TotalPageFile, AvailPageFile             uint64
	TotalVirtual, AvailVirtual, AvailExtVirt uint64
}

func memInfo() (used, total uint64, scope string) {
	m := memStatusEx{}
	m.Length = uint32(unsafe.Sizeof(m))
	if r, _, _ := pGlobalMemoryStatusEx.Call(uintptr(unsafe.Pointer(&m))); r == 0 {
		return 0, 0, ""
	}
	return m.TotalPhys - m.AvailPhys, m.TotalPhys, "machine"
}

func diskInfo(path string) (used, total uint64) {
	p, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return 0, 0
	}
	var avail, tot, free uint64
	if r, _, _ := pGetDiskFreeSpaceExW.Call(uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(&avail)),
		uintptr(unsafe.Pointer(&tot)), uintptr(unsafe.Pointer(&free))); r == 0 {
		return 0, 0
	}
	return tot - free, tot
}

type procMemCounters struct {
	Cb, PageFaultCount                                 uint32
	PeakWorkingSetSize, WorkingSetSize                 uintptr
	QuotaPeakPagedPoolUsage, QuotaPagedPoolUsage       uintptr
	QuotaPeakNonPagedPoolUsage, QuotaNonPagedPoolUsage uintptr
	PagefileUsage, PeakPagefileUsage                   uintptr
}

func procRSS(pid int) uint64 {
	h, err := syscall.OpenProcess(0x1000|0x0400, false, uint32(pid)) // QUERY_LIMITED_INFORMATION | QUERY_INFORMATION
	if err != nil {
		h, err = syscall.OpenProcess(0x1000, false, uint32(pid))
		if err != nil {
			return 0
		}
	}
	defer syscall.CloseHandle(h)
	c := procMemCounters{}
	c.Cb = uint32(unsafe.Sizeof(c))
	if r, _, _ := pK32GetProcessMemInfo.Call(uintptr(h), uintptr(unsafe.Pointer(&c)), uintptr(c.Cb)); r == 0 {
		return 0
	}
	return uint64(c.WorkingSetSize)
}

func machineName() string {
	var k syscall.Handle
	p, _ := syscall.UTF16PtrFromString(`HARDWARE\DESCRIPTION\System\CentralProcessor\0`)
	if syscall.RegOpenKeyEx(syscall.HKEY_LOCAL_MACHINE, p, 0, syscall.KEY_READ, &k) != nil {
		return ""
	}
	defer syscall.RegCloseKey(k)
	name, _ := syscall.UTF16PtrFromString("ProcessorNameString")
	buf := make([]uint16, 256)
	n := uint32(len(buf) * 2)
	var typ uint32
	if syscall.RegQueryValueEx(k, name, nil, &typ, (*byte)(unsafe.Pointer(&buf[0])), &n) != nil {
		return ""
	}
	return cleanMachine(syscall.UTF16ToString(buf))
}
