package main

// The PC's side of helping: this server (usually the Windows one) asks the main server (the NAS) for work,
// does it with its own AI reader and voices, and sends the result back. It reports its load with every request,
// so the NAS dashboard shows the PC. While it works it keeps Windows from sleeping; once idle, Windows sleeps as usual.

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type HelperClientConfig struct {
	On  bool   `json:"on"`
	URL string `json:"url"` // the main server's address(es), comma separated
	Key string `json:"key"`
}

type HelperClientState struct {
	Connected bool   `json:"connected"`
	Server    string `json:"server,omitempty"` // the address that answered
	Error     string `json:"error,omitempty"`
	Since     int64  `json:"since,omitempty"`
	Done      int    `json:"done"`
	Doing     string `json:"doing,omitempty"`
}

type HelperClient struct {
	s       *Server
	hc      *http.Client
	busy    atomic.Bool
	changed chan struct{}
	mu      sync.Mutex
	state   HelperClientState
	good    string
	running map[string]context.CancelFunc // jobs being done, so the main server can cancel them
}

func (s *Server) startHelperClient(ctx context.Context) {
	c := &HelperClient{s: s, hc: &http.Client{Timeout: 45 * time.Second}, changed: make(chan struct{}, 1), running: map[string]context.CancelFunc{}}
	s.helperc = c
	s.voices.fetchRef = c.fetchRef
	go c.loop(ctx)
}

func (c *HelperClient) Config(ctx context.Context) HelperClientConfig {
	var v HelperClientConfig
	c.s.st.getMeta(ctx, "helper_client", &v)
	return v
}

func (c *HelperClient) SetConfig(ctx context.Context, v HelperClientConfig) error {
	v.URL = strings.TrimRight(strings.TrimSpace(v.URL), "/")
	v.Key = strings.TrimSpace(v.Key)
	if err := c.s.st.setMeta(ctx, "helper_client", v); err != nil {
		return err
	}
	c.mu.Lock()
	c.state = HelperClientState{}
	c.good = ""
	c.mu.Unlock()
	select {
	case c.changed <- struct{}{}:
	default:
	}
	return nil
}

func (c *HelperClient) State() HelperClientState {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.state
}

func (c *HelperClient) set(f func(*HelperClientState)) {
	c.mu.Lock()
	f(&c.state)
	c.mu.Unlock()
}

func (c *HelperClient) bases(cfg HelperClientConfig) []string {
	out := []string{}
	c.mu.Lock()
	good := c.good
	c.mu.Unlock()
	if good != "" {
		out = append(out, good)
	}
	for _, u := range strings.Split(cfg.URL, ",") {
		u = strings.TrimRight(strings.TrimSpace(u), "/")
		if u == "" || u == good {
			continue
		}
		if !strings.Contains(u, "://") {
			u = "http://" + u
		}
		out = append(out, u)
	}
	return out
}

func (c *HelperClient) loop(ctx context.Context) {
	fails := 0
	for ctx.Err() == nil {
		cfg := c.Config(ctx)
		if !cfg.On || cfg.URL == "" || cfg.Key == "" {
			c.set(func(s *HelperClientState) { s.Connected = false })
			select {
			case <-c.changed:
			case <-time.After(10 * time.Second):
			case <-ctx.Done():
				return
			}
			continue
		}
		job, base, err := c.poll(ctx, cfg)
		if err != nil {
			fails++
			c.set(func(s *HelperClientState) {
				if s.Connected || s.Error == "" {
					s.Since = now()
				}
				s.Connected, s.Error = false, err.Error()
			})
			wait := time.Duration(min(fails, 5)) * 3 * time.Second
			select {
			case <-c.changed:
			case <-time.After(wait):
			case <-ctx.Done():
				return
			}
			continue
		}
		fails = 0
		c.set(func(s *HelperClientState) {
			if !s.Connected {
				s.Since = now()
			}
			s.Connected, s.Server, s.Error = true, base, ""
		})
		if job != nil {
			c.busy.Store(true)
			go c.do(ctx, cfg, base, job)
		}
	}
}

func (c *HelperClient) poll(ctx context.Context, cfg HelperClientConfig) (*helperJob, string, error) {
	st := c.s.helperStatus()
	st.Busy = c.busy.Load()
	body, _ := json.Marshal(st)
	var last error
	for _, base := range c.bases(cfg) {
		req, _ := http.NewRequestWithContext(ctx, "POST", base+"/v1/helper/poll", bytes.NewReader(body))
		req.Header.Set("Authorization", "Helper "+cfg.Key)
		req.Header.Set("Content-Type", "application/json")
		res, err := c.hc.Do(req)
		if err != nil {
			last = err
			continue
		}
		b, _ := io.ReadAll(io.LimitReader(res.Body, 64<<20))
		res.Body.Close()
		c.mu.Lock()
		c.good = base
		for _, id := range strings.Split(res.Header.Get("X-Werd-Cancel"), ",") {
			if f := c.running[strings.TrimSpace(id)]; f != nil {
				f() // the main server no longer wants it: stop the engine
			}
		}
		c.mu.Unlock()
		switch res.StatusCode {
		case 204:
			return nil, base, nil
		case 200:
			var j helperJob
			if err := json.Unmarshal(b, &j); err != nil {
				return nil, base, err
			}
			return &j, base, nil
		case 401:
			return nil, base, errors.New("the main server refused the key")
		case 404:
			return nil, base, errors.New("that address isn't a Werd server 1.6 or newer")
		default:
			return nil, base, fmt.Errorf("HTTP %d from the main server", res.StatusCode)
		}
	}
	if last == nil {
		last = errors.New("no address to try")
	}
	return nil, "", last
}

func (c *HelperClient) do(ctx context.Context, cfg HelperClientConfig, base string, j *helperJob) {
	defer c.busy.Store(false)
	stayAwake(true)
	defer stayAwake(false)
	jctx, cancel := context.WithTimeout(ctx, 15*time.Minute)
	defer cancel()
	c.mu.Lock()
	c.running[j.ID] = cancel
	c.mu.Unlock()
	defer func() { c.mu.Lock(); delete(c.running, j.ID); c.mu.Unlock() }()
	var data []byte
	ext := ""
	var err error
	switch j.Kind {
	case "ocr":
		var in struct{ Image, Model, Who string }
		_ = json.Unmarshal(j.Body, &in)
		c.set(func(s *HelperClientState) { s.Doing = "ocr" })
		img, derr := base64.StdEncoding.DecodeString(in.Image)
		if derr != nil {
			err = derr
			break
		}
		var text, id string
		text, id, err = c.s.ocr.Read(jctx, in.Model, img, in.Who)
		if err == nil {
			c.s.act.Add(KindAI, "info", "ai.read", "", A{"name": in.Who, "model": modelName(id), "secs": 0, "chars": len([]rune(text)), "for": "main"}, 0)
		}
		data, _ = json.Marshal(map[string]string{"text": text, "model": id})
	case "tts":
		var in struct{ Voice, Sha, Text string }
		_ = json.Unmarshal(j.Body, &in)
		c.set(func(s *HelperClientState) { s.Doing = "tts" })
		data, err = c.s.voices.Speak(jctx, in.Voice, in.Sha, in.Text)
		ext = "wav"
	case "download":
		var in struct{ Model string }
		_ = json.Unmarshal(j.Body, &in)
		err = c.s.ocr.Download(in.Model)
	default:
		err = errors.New("this PC doesn't know the job " + j.Kind + " (update it)")
	}
	c.set(func(s *HelperClientState) { s.Doing = "" })
	if jctx.Err() != nil && ctx.Err() == nil && errors.Is(jctx.Err(), context.Canceled) {
		c.s.act.Add(KindAI, "info", "task.cancelled", "", A{"name": "", "n": 1, "by": "main"}, 0)
		return // cancelled by the main server: nobody waits for an answer
	}
	req, _ := http.NewRequestWithContext(ctx, "POST", base+"/v1/helper/jobs/"+j.ID, bytes.NewReader(data))
	req.Header.Set("Authorization", "Helper "+cfg.Key)
	if ext != "" {
		req.Header.Set("X-Werd-Ext", ext)
	}
	if err != nil {
		req.Header.Set("X-Werd-Error", truncRunes(strings.ReplaceAll(err.Error(), "\n", " "), 300))
	}
	hc := &http.Client{Timeout: 2 * time.Minute}
	if res, perr := hc.Do(req); perr == nil {
		res.Body.Close()
	}
	if err == nil {
		c.set(func(s *HelperClientState) { s.Done++ })
	}
}

func modelName(id string) string {
	if m := ocrModel(id); m != nil {
		return m.Name
	}
	return id
}

// fetchRef downloads a voice sample from the main server.
func (c *HelperClient) fetchRef(ctx context.Context, id string) ([]byte, error) {
	cfg := c.Config(ctx)
	for _, base := range c.bases(cfg) {
		req, _ := http.NewRequestWithContext(ctx, "GET", base+"/v1/helper/refs/"+id, nil)
		req.Header.Set("Authorization", "Helper "+cfg.Key)
		res, err := c.hc.Do(req)
		if err != nil {
			continue
		}
		b, _ := io.ReadAll(io.LimitReader(res.Body, 20<<20))
		res.Body.Close()
		if res.StatusCode == 200 {
			return b, nil
		}
		return nil, fmt.Errorf("voice sample: HTTP %d", res.StatusCode)
	}
	return nil, errors.New("couldn't reach the main server for the voice sample")
}

// helperStatus describes this machine for the main server.
func (s *Server) helperStatus() HelperStatus {
	name, _ := os.Hostname()
	n := s.sys.Now()
	st := HelperStatus{Name: name, Version: version, OS: runtime.GOOS + "/" + runtime.GOARCH, Cores: runtime.NumCPU(),
		CPU: n.CPU, MemUsed: int64(n.MemUsed), MemTot: int64(n.MemTotal), GPU: s.gpu.Get(), Voices: s.voices.Here()}
	st.OCR.Engine = s.ocr.Engine() != ""
	st.OCR.GPU = s.ocr.OnGPU()
	if r := s.ocr.Report(); r != nil && r.CUDA {
		st.OCR.GPUProblem = r.Problem
	}
	st.OCR.Default = s.ocr.Default(context.Background())
	st.OCR.Models = []string{}
	for _, m := range allModels() {
		if s.ocr.Installed(m.ID) {
			st.OCR.Models = append(st.OCR.Models, m.ID)
		}
	}
	if ifs, err := net.Interfaces(); err == nil {
		for _, i := range ifs {
			low := strings.ToLower(i.Name)
			if i.Flags&net.FlagLoopback != 0 || len(i.HardwareAddr) != 6 || strings.Contains(low, "vethernet") ||
				strings.Contains(low, "virtual") || strings.Contains(low, "vmware") || strings.Contains(low, "docker") || strings.Contains(low, "wsl") {
				continue
			}
			ni := NetIface{Name: i.Name, MAC: i.HardwareAddr.String()}
			addrs, _ := i.Addrs()
			for _, a := range addrs {
				if ip, ok := a.(*net.IPNet); ok {
					ni.IPs = append(ni.IPs, ip.IP.String())
				}
			}
			if len(ni.IPs) > 0 {
				st.Ifaces = append(st.Ifaces, ni)
			}
		}
	}
	return st
}
