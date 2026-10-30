package main

// Resource load for the dashboard: processor, memory, disk, requests and phones, sampled every 5 seconds,
// with the last 15 minutes kept for the small traces under each number. Figures come from the operating system
// (inside Docker: the container's memory limit when one is set, otherwise the NAS's memory).

import (
	"os"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const sampleEvery = 5 * time.Second
const sampleKeep = 180 // 15 minutes

type Sample struct {
	At      int64   `json:"at"`
	CPU     float64 `json:"cpu"`     // % of all cores, -1 = unknown
	MemUsed uint64  `json:"memUsed"` // bytes
	ReqMin  int     `json:"reqMin"`  // requests in the last minute
}

type SystemNow struct {
	Sample
	MemTotal   uint64   `json:"memTotal"`
	MemScope   string   `json:"memScope"` // "container" (Docker limit) or "machine"
	DiskUsed   uint64   `json:"diskUsed"`
	DiskTotal  uint64   `json:"diskTotal"`
	ServerRSS  uint64   `json:"serverRss"`
	EngineRSS  uint64   `json:"engineRss"` // the AI reader (llama-server) while loaded
	Phones     int      `json:"phones"`    // accounts that talked to the server in the last hour
	Cores      int      `json:"cores"`
	Machine    string   `json:"machine"`
	OS         string   `json:"os"`
	Docker     bool     `json:"docker"`
	StartedAt  int64    `json:"startedAt"`
	History    []Sample `json:"history"`
	DataBytes  int64    `json:"dataBytes"` // database + clips + models
	ModelBytes int64    `json:"modelBytes"`
}

type System struct {
	dataDir   string
	started   time.Time
	requests  atomic.Int64
	enginePID func() int

	mu      sync.Mutex
	hist    []Sample
	reqs    []int64 // requests per sample slot, last 12 = one minute
	lastReq int64
	idle0   uint64
	total0  uint64
	machine string

	seenMu sync.Mutex
	seen   map[string]int64 // user id → last request (ms)

	sizeMu     sync.Mutex
	sizeAt     time.Time
	dataBytes  int64
	modelBytes int64
}

func NewSystem(dataDir string) *System {
	s := &System{dataDir: dataDir, started: time.Now(), seen: map[string]int64{}, machine: machineName()}
	s.idle0, s.total0, _ = cpuTimes()
	go s.loop()
	return s
}

func (s *System) Request() { s.requests.Add(1) }

func (s *System) Seen(userID string) {
	s.seenMu.Lock()
	s.seen[userID] = now()
	s.seenMu.Unlock()
}

// ActiveSince returns the user ids seen after t (ms).
func (s *System) Active(within time.Duration) map[string]int64 {
	cut := now() - within.Milliseconds()
	out := map[string]int64{}
	s.seenMu.Lock()
	for id, at := range s.seen {
		if at > cut {
			out[id] = at
		} else if at < now()-24*3600_000 {
			delete(s.seen, id)
		}
	}
	s.seenMu.Unlock()
	return out
}

func (s *System) loop() {
	for range time.Tick(sampleEvery) {
		s.sample()
	}
}

func (s *System) sample() {
	cpu := -1.0
	if idle, total, ok := cpuTimes(); ok {
		if total > s.total0 {
			cpu = 100 * (1 - float64(idle-s.idle0)/float64(total-s.total0))
			if cpu < 0 {
				cpu = 0
			}
		}
		s.idle0, s.total0 = idle, total
	}
	used, _, _ := memInfo()
	r := s.requests.Load()
	s.mu.Lock()
	s.reqs = append(s.reqs, r-s.lastReq)
	s.lastReq = r
	if len(s.reqs) > 12 {
		s.reqs = s.reqs[len(s.reqs)-12:]
	}
	var perMin int64
	for _, n := range s.reqs {
		perMin += n
	}
	s.hist = append(s.hist, Sample{At: now(), CPU: round1(cpu), MemUsed: used, ReqMin: int(perMin)})
	if len(s.hist) > sampleKeep {
		s.hist = s.hist[len(s.hist)-sampleKeep:]
	}
	s.mu.Unlock()
}

func round1(f float64) float64 { return float64(int(f*10+0.5)) / 10 }

func (s *System) sizes() (int64, int64) {
	s.sizeMu.Lock()
	defer s.sizeMu.Unlock()
	if time.Since(s.sizeAt) > time.Minute {
		s.dataBytes, s.modelBytes = dirSize(s.dataDir), dirSize(s.dataDir+string(os.PathSeparator)+"models")
		s.sizeAt = time.Now()
	}
	return s.dataBytes, s.modelBytes
}

func (s *System) Now() SystemNow {
	s.mu.Lock()
	hist := append([]Sample(nil), s.hist...)
	s.mu.Unlock()
	out := SystemNow{Cores: runtime.NumCPU(), Machine: s.machine, OS: runtime.GOOS + "/" + runtime.GOARCH,
		Docker: os.Getenv("WERD_DOCKER") != "", StartedAt: s.started.UnixMilli(), History: hist}
	if len(hist) > 0 {
		out.Sample = hist[len(hist)-1]
	} else {
		out.Sample = Sample{At: now(), CPU: -1}
	}
	out.MemUsed, out.MemTotal, out.MemScope = memInfo()
	out.DiskUsed, out.DiskTotal = diskInfo(s.dataDir)
	out.ServerRSS = procRSS(os.Getpid())
	if s.enginePID != nil {
		if pid := s.enginePID(); pid > 0 {
			out.EngineRSS = procRSS(pid)
		}
	}
	out.Phones = len(s.Active(time.Hour))
	out.DataBytes, out.ModelBytes = s.sizes()
	return out
}

func dirSize(dir string) int64 {
	var n int64
	var walk func(string, int)
	walk = func(d string, depth int) {
		if depth > 6 {
			return
		}
		ents, err := os.ReadDir(d)
		if err != nil {
			return
		}
		for _, e := range ents {
			p := d + string(os.PathSeparator) + e.Name()
			if e.IsDir() {
				walk(p, depth+1)
			} else if fi, err := e.Info(); err == nil {
				n += fi.Size()
			}
		}
	}
	walk(dir, 0)
	return n
}

func cleanMachine(s string) string {
	s = strings.Join(strings.Fields(s), " ")
	for _, junk := range []string{"(R)", "(TM)", "(tm)", " CPU", " Processor"} {
		s = strings.ReplaceAll(s, junk, "")
	}
	if i := strings.Index(s, " @ "); i > 0 {
		s = s[:i]
	}
	return strings.TrimSpace(s)
}
