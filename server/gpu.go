package main

// The graphics card: what nvidia-smi says about it (for the dashboard and the helper's heartbeat),
// and the CUDA build of the AI engine (llama.cpp), downloaded on demand so page reading runs on the card.

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

type GPUInfo struct {
	Name     string  `json:"name"`
	Util     float64 `json:"util"`           // %
	MemUsed  float64 `json:"memUsed"`        // MB
	MemTotal float64 `json:"memTotal"`       // MB
	Temp     float64 `json:"temp,omitempty"` // °C
}

// GPUSample is one point of the card's recent load (for the dashboard's graph).
type GPUSample struct {
	At   int64   `json:"at"`
	Util float64 `json:"util"`
	Mem  float64 `json:"mem"` // MB
}

type GPUWatch struct {
	mu    sync.Mutex
	info  *GPUInfo
	good  time.Time // the last answer from nvidia-smi
	at    time.Time
	smi   string
	tried time.Time
	hist  []GPUSample
}

// Run samples the card every 10 seconds, so the dashboard has the last 15 minutes to draw.
func (g *GPUWatch) Run() {
	for {
		if i := g.Get(); i != nil {
			g.mu.Lock()
			g.hist = append(g.hist, GPUSample{At: time.Now().UnixMilli(), Util: i.Util, Mem: i.MemUsed})
			if len(g.hist) > 90 {
				g.hist = g.hist[len(g.hist)-90:]
			}
			g.mu.Unlock()
		} else {
			g.mu.Lock()
			none := g.smi == "" && !g.tried.IsZero()
			g.mu.Unlock()
			if none {
				time.Sleep(5 * time.Minute) // no NVIDIA card here
			}
		}
		time.Sleep(10 * time.Second)
	}
}

// History is the last 15 minutes of the card's load.
func (g *GPUWatch) History() []GPUSample {
	g.mu.Lock()
	defer g.mu.Unlock()
	return append([]GPUSample{}, g.hist...)
}

func (g *GPUWatch) find() string {
	if p, err := exec.LookPath("nvidia-smi"); err == nil {
		return p
	}
	if runtime.GOOS == "windows" {
		for _, p := range []string{filepath.Join(os.Getenv("SystemRoot"), "System32", "nvidia-smi.exe"),
			`C:\Program Files\NVIDIA Corporation\NVSMI\nvidia-smi.exe`} {
			if fileExists(p) {
				return p
			}
		}
	}
	return ""
}

// Get returns the first NVIDIA card's name and load (nil without one). Cached for a few seconds.
func (g *GPUWatch) Get() *GPUInfo {
	g.mu.Lock()
	defer g.mu.Unlock()
	if time.Since(g.at) < 4*time.Second {
		return g.info
	}
	if g.smi == "" {
		if time.Since(g.tried) < 5*time.Minute {
			return nil
		}
		g.tried = time.Now()
		if g.smi = g.find(); g.smi == "" {
			return nil
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, g.smi, "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu", "--format=csv,noheader,nounits")
	hideWindow(cmd)
	out, err := cmd.Output()
	g.at = time.Now()
	if err != nil {
		// one slow or failed call (a busy machine) doesn't make the card disappear from the dashboard
		if g.info != nil && time.Since(g.good) < 30*time.Second {
			return g.info
		}
		g.info = nil
		return nil
	}
	g.good = time.Now()
	line := strings.SplitN(strings.TrimSpace(string(out)), "\n", 2)[0]
	f := strings.Split(line, ",")
	if len(f) < 4 {
		g.info = nil
		return nil
	}
	num := func(s string) float64 { v, _ := strconv.ParseFloat(strings.TrimSpace(s), 64); return v }
	g.info = &GPUInfo{Name: strings.TrimSpace(f[0]), Util: num(f[1]), MemUsed: num(f[2]), MemTotal: num(f[3])}
	if len(f) > 4 {
		g.info.Temp = num(f[4])
	}
	return g.info
}

// ------------------------------------------------------------------ the CUDA build of llama-server (Windows)

const llamaBuild = "b11800"

func (o *OCR) cudaDir() string { return filepath.Join(o.data, "engine-cuda") }

func (o *OCR) cudaEngine() string {
	p := filepath.Join(o.cudaDir(), "llama-server.exe")
	if runtime.GOOS == "windows" && fileExists(p) {
		return p
	}
	return ""
}

// CUDASupported: Windows with an NVIDIA card (the official CUDA builds are for Windows and need no setup).
func (o *OCR) CUDASupported() bool {
	return runtime.GOOS == "windows" && runtime.GOARCH == "amd64" && o.gpu != nil && o.gpu.Get() != nil
}

func (o *OCR) InstallCUDA() error {
	if !o.CUDASupported() {
		return ErrNotFound
	}
	base := gh + "ggml-org/llama.cpp/releases/download/" + llamaBuild + "/"
	flat := func(name string) string {
		if strings.Contains(name, "/") && !strings.HasPrefix(name, "./") {
			name = name[strings.LastIndex(name, "/")+1:]
		}
		low := strings.ToLower(name)
		if strings.HasSuffix(low, ".dll") || low == "llama-server.exe" {
			return name
		}
		return ""
	}
	files := []PackFile{
		{Kind: "zip", Size: 272e6, URL: base + "llama-" + llamaBuild + "-bin-win-cuda-12.4-x64.zip", Pick: flat},
		{Kind: "zip", Size: 391e6, URL: base + "cudart-llama-bin-win-cuda-12.4-x64.zip", Pick: flat},
	}
	o.act.Add(KindAI, "info", "ai.download.started", "", A{"model": "llama.cpp CUDA", "bytes": int64(663e6)}, 0)
	o.packs.Install("llama-cuda", o.cudaDir(), files, nil, func(err error) {
		if err != nil {
			o.act.Add(KindAI, "error", "ai.download.failed", "", A{"model": "llama.cpp CUDA", "error": truncRunes(err.Error(), 200)}, 0)
			return
		}
		o.run.Lock()
		o.stopLocked() // the next page loads on the card
		o.run.Unlock()
		o.act.Add(KindAI, "info", "ai.download.done", "", A{"model": "llama.cpp CUDA"}, 0)
	})
	return nil
}

func (o *OCR) RemoveCUDA() error {
	o.packs.Clear("llama-cuda")
	o.run.Lock()
	o.stopLocked()
	o.run.Unlock()
	return os.RemoveAll(o.cudaDir())
}
