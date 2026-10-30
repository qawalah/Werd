package main

// AI page reading on the server: free vision models (QARI-OCR, Qwen2.5-VL, Gemma 3) run by llama.cpp's llama-server,
// with no internet once a model is downloaded. Phones and the web app send a page photo and get its text back,
// tashkeel included. The models live in data/models; llama-server is started on demand and stopped when idle.

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
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type OCRFile struct {
	URL   string `json:"-"`
	Name  string `json:"name"`
	Size  int64  `json:"size"`
	Proj  bool   `json:"-"` // the vision projector (--mmproj)
	Local string `json:"-"` // a file already on this machine (models the owner added)
}

type OCRModel struct {
	ID     string    `json:"id"`
	Name   string    `json:"name"`
	Detail string    `json:"detail"`
	Files  []OCRFile `json:"files"`
	Prompt string    `json:"-"`
	Custom bool      `json:"custom,omitempty"`
}

const hf = "https://huggingface.co/"

const arabicPrompt = "Transcribe all the Arabic text in this image exactly as written, letter by letter, keeping every diacritic (tashkeel) and the punctuation and brackets. Keep the line and paragraph breaks. Output only the text, with no explanation, translation or formatting."

// OCRCatalog lists the free models the admin can download. Sizes are approximate (for the progress bar).
var OCRCatalog = []OCRModel{
	{ID: "qari4", Name: "QARI-OCR 0.4 (4B)", Detail: "Made for Arabic with tashkeel · ~3.3 GB · best accuracy",
		Files: []OCRFile{
			{URL: hf + "marwan-osama/Qari-OCR-0.4.0-VL-4B-Instruct-GGUF/resolve/main/Qwen3-VL-4B-Instruct.Q4_K_M.gguf", Name: "model.gguf", Size: 2497e6},
			{URL: hf + "marwan-osama/Qari-OCR-0.4.0-VL-4B-Instruct-GGUF/resolve/main/Qwen3-VL-4B-Instruct.F16-mmproj.gguf", Name: "mmproj.gguf", Size: 836e6, Proj: true},
		}, Prompt: "Free OCR."},
	{ID: "qari2", Name: "QARI-OCR 0.2 (2B)", Detail: "Made for Arabic with tashkeel · ~2.4 GB · faster",
		Files: []OCRFile{
			{URL: hf + "mradermacher/Qari-OCR-0.2.2.1-VL-2B-Instruct-merged-GGUF/resolve/main/Qari-OCR-0.2.2.1-VL-2B-Instruct-merged.Q8_0.gguf", Name: "model.gguf", Size: 1647e6},
			{URL: hf + "mradermacher/Qari-OCR-0.2.2.1-VL-2B-Instruct-merged-GGUF/resolve/main/Qari-OCR-0.2.2.1-VL-2B-Instruct-merged.mmproj-Q8_0.gguf", Name: "mmproj.gguf", Size: 710e6, Proj: true},
		}, Prompt: "Below is the image of one page of a document, as well as some raw textual content that was previously extracted for it. Just return the plain text representation of this document as if you were reading it naturally. Do not hallucinate."},
	{ID: "qwen25vl3b", Name: "Qwen2.5-VL 3B", Detail: "General vision model · ~2.8 GB",
		Files: []OCRFile{
			{URL: hf + "ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", Name: "model.gguf", Size: 1930e6},
			{URL: hf + "ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf", Name: "mmproj.gguf", Size: 845e6, Proj: true},
		}, Prompt: arabicPrompt},
	{ID: "gemma3-4b", Name: "Gemma 3 4B", Detail: "General vision model (Google) · ~3.3 GB",
		Files: []OCRFile{
			{URL: hf + "ggml-org/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf", Name: "model.gguf", Size: 2490e6},
			{URL: hf + "ggml-org/gemma-3-4b-it-GGUF/resolve/main/mmproj-model-f16.gguf", Name: "mmproj.gguf", Size: 851e6, Proj: true},
		}, Prompt: arabicPrompt},
}

func ocrModel(id string) *OCRModel {
	for i := range OCRCatalog {
		if OCRCatalog[i].ID == id {
			return &OCRCatalog[i]
		}
	}
	return customModelByID(id)
}

type ocrDownload struct {
	Done, Total int64
	Err         string
	cancel      context.CancelFunc
}

type OCR struct {
	st   *Store
	dir  string // data/models
	data string
	hc   *http.Client
	log  func(string, ...any)

	mu        sync.Mutex
	downloads map[string]*ocrDownload
	act       *Activity
	packs     *Packs
	gpu       *GPUWatch
	beforeGPU func() // frees the graphics card (stops the voice engine) before a model loads on it

	workMu  sync.Mutex
	work    *OCRWork // the page being read now
	waiting int      // pages queued behind it
	pid     atomic.Int64

	speed  map[string]float64 // seconds the last page took, per model
	report *EngineReport      // where the last model loaded (card or processor)

	run struct {
		sync.Mutex // one page at a time (the models use all the CPU anyway)
		model      string
		cmd        *exec.Cmd
		port       int
		last       time.Time
	}
}

// OCRWork is what the AI reader is doing right now (for the dashboard).
type OCRWork struct {
	Model   string `json:"model"`
	Name    string `json:"name"`  // who sent the page
	Phase   string `json:"phase"` // loading (the model) | reading
	Started int64  `json:"startedAt"`
	Waiting int    `json:"waiting"`
}

func (o *OCR) Work() *OCRWork {
	o.workMu.Lock()
	defer o.workMu.Unlock()
	if o.work == nil {
		if o.waiting > 0 {
			return &OCRWork{Phase: "waiting", Waiting: o.waiting}
		}
		return nil
	}
	w := *o.work
	w.Waiting = o.waiting
	return &w
}

func (o *OCR) setPhase(p string) {
	o.workMu.Lock()
	if o.work != nil {
		o.work.Phase = p
	}
	o.workMu.Unlock()
}

// EnginePID is the running llama-server's process id (0 when no model is loaded). Never waits for a page being read.
func (o *OCR) EnginePID() int { return int(o.pid.Load()) }

// Loaded is the model held in memory now ("" = none). Never blocks behind a page being read.
func (o *OCR) Loaded() string {
	if o.run.TryLock() {
		defer o.run.Unlock()
		return o.run.model
	}
	o.workMu.Lock()
	defer o.workMu.Unlock()
	if o.work != nil {
		return o.work.Model
	}
	return ""
}

func NewOCR(st *Store, dataDir string, logf func(string, ...any)) *OCR {
	o := &OCR{st: st, data: dataDir, dir: filepath.Join(dataDir, "models"), hc: &http.Client{}, log: logf, downloads: map[string]*ocrDownload{}}
	_ = os.MkdirAll(o.dir, 0o755)
	o.loadCustom()
	go o.idleStopper()
	return o
}

// Engine finds llama-server: WERD_LLAMA_SERVER, next to this program, in the data folder, in the Docker image, or on PATH.
func (o *OCR) Engine() string {
	name := "llama-server"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	if c := o.cudaEngine(); c != "" && o.CUDASupported() {
		return c
	}
	cands := []string{os.Getenv("WERD_LLAMA_SERVER")}
	if exe, err := os.Executable(); err == nil {
		cands = append(cands, filepath.Join(filepath.Dir(exe), name), filepath.Join(filepath.Dir(exe), "llama", name))
	}
	cands = append(cands, filepath.Join(o.data, "engine", name), "/opt/llama/"+name)
	for _, c := range cands {
		if c == "" {
			continue
		}
		if fi, err := os.Stat(c); err == nil && !fi.IsDir() {
			return c
		}
	}
	if p, err := exec.LookPath(name); err == nil {
		return p
	}
	return ""
}

func (o *OCR) path(id, file string) string { return filepath.Join(o.dir, id, file) }

// fpath is where a model's file is: in data/models, or where the owner keeps it.
func (o *OCR) fpath(id string, f OCRFile) string {
	if f.Local != "" {
		return f.Local
	}
	return o.path(id, f.Name)
}

func (o *OCR) Installed(id string) bool {
	m := ocrModel(id)
	if m == nil {
		return false
	}
	for _, f := range m.Files {
		if fi, err := os.Stat(o.fpath(id, f)); err != nil || fi.Size() < 1e6 {
			return false
		}
	}
	return true
}

// Default is the admin's choice, else the first installed model.
func (o *OCR) Default(ctx context.Context) string {
	var v string
	_ = o.st.db.QueryRowContext(ctx, `SELECT value FROM meta WHERE key = 'ocr_default'`).Scan(&v)
	if v != "" && o.Installed(v) {
		return v
	}
	for _, m := range allModels() {
		if o.Installed(m.ID) {
			return m.ID
		}
	}
	return ""
}

func (o *OCR) SetDefault(ctx context.Context, id string) error {
	_, err := o.st.db.ExecContext(ctx, `INSERT OR REPLACE INTO meta(key, value) VALUES ('ocr_default', ?)`, id)
	return err
}

// ------------------------------------------------------------------ downloads

type OCRStatus struct {
	OCRModel
	Installed bool    `json:"installed"`
	Done      int64   `json:"done,omitempty"`
	Total     int64   `json:"total,omitempty"`
	Error     string  `json:"error,omitempty"`
	Bytes     int64   `json:"bytes,omitempty"`
	Secs      float64 `json:"secsPerPage,omitempty"` // the last page's reading time
	Size      int64   `json:"size"`                  // all files
}

func (o *OCR) Status() []OCRStatus {
	o.mu.Lock()
	defer o.mu.Unlock()
	out := []OCRStatus{}
	for _, m := range allModels() {
		s := OCRStatus{OCRModel: m, Installed: o.Installed(m.ID), Secs: o.Speed(m.ID)}
		for _, f := range m.Files {
			s.Size += f.Size
		}
		if d := o.downloads[m.ID]; d != nil {
			s.Done, s.Total, s.Error = d.Done, d.Total, d.Err
		}
		if s.Installed {
			for _, f := range m.Files {
				if fi, err := os.Stat(o.fpath(m.ID, f)); err == nil {
					s.Bytes += fi.Size()
				}
			}
		}
		out = append(out, s)
	}
	return out
}

func (o *OCR) Download(id string) error {
	m := ocrModel(id)
	if m == nil {
		return ErrNotFound
	}
	o.mu.Lock()
	if d := o.downloads[id]; d != nil && d.cancel != nil {
		o.mu.Unlock()
		return nil // already downloading
	}
	ctx, cancel := context.WithCancel(context.Background())
	var total int64
	for _, f := range m.Files {
		total += f.Size
	}
	d := &ocrDownload{Total: total, cancel: cancel}
	o.downloads[id] = d
	o.mu.Unlock()
	o.act.Add(KindAI, "info", "ai.download.started", "", A{"model": m.Name, "bytes": total}, 0)
	go func() {
		err := o.fetch(ctx, m, d)
		o.mu.Lock()
		d.cancel = nil
		if err != nil {
			d.Err = err.Error()
		} else {
			delete(o.downloads, id)
		}
		o.mu.Unlock()
		if err != nil {
			o.log("ocr: download %s: %v", id, err)
			if ctx.Err() == nil {
				o.act.Add(KindAI, "error", "ai.download.failed", "", A{"model": m.Name, "error": truncRunes(err.Error(), 200)}, 0)
			}
		} else {
			o.act.Add(KindAI, "info", "ai.download.done", "", A{"model": m.Name, "bytes": total}, 0)
		}
	}()
	return nil
}

func (o *OCR) fetch(ctx context.Context, m *OCRModel, d *ocrDownload) error {
	_ = os.MkdirAll(filepath.Join(o.dir, m.ID), 0o755)
	var base int64
	for _, f := range m.Files {
		if f.Local != "" {
			if !fileExists(f.Local) {
				return fmt.Errorf("file not found: %s", f.Local)
			}
			continue
		}
		dst := o.fpath(m.ID, f)
		if fi, err := os.Stat(dst); err == nil && fi.Size() > 1e6 {
			base += fi.Size()
			continue
		}
		part := dst + ".part"
		var have int64
		if fi, err := os.Stat(part); err == nil {
			have = fi.Size()
		}
		req, _ := http.NewRequestWithContext(ctx, "GET", f.URL, nil)
		if have > 0 {
			req.Header.Set("Range", fmt.Sprintf("bytes=%d-", have))
		}
		res, err := o.hc.Do(req)
		if err != nil {
			return err
		}
		if res.StatusCode == 200 {
			have = 0
		} else if res.StatusCode != 206 {
			res.Body.Close()
			return fmt.Errorf("HTTP %d for %s", res.StatusCode, f.Name)
		}
		if res.ContentLength > 0 {
			o.mu.Lock()
			d.Total += have + res.ContentLength - f.Size
			o.mu.Unlock()
		}
		flags := os.O_CREATE | os.O_WRONLY
		if have > 0 {
			flags |= os.O_APPEND
		} else {
			flags |= os.O_TRUNC
		}
		out, err := os.OpenFile(part, flags, 0o644)
		if err != nil {
			res.Body.Close()
			return err
		}
		buf := make([]byte, 1<<20)
		for {
			n, rerr := res.Body.Read(buf)
			if n > 0 {
				if _, werr := out.Write(buf[:n]); werr != nil {
					out.Close()
					res.Body.Close()
					return werr
				}
				have += int64(n)
				o.mu.Lock()
				d.Done = base + have
				o.mu.Unlock()
			}
			if rerr == io.EOF {
				break
			}
			if rerr != nil {
				out.Close()
				res.Body.Close()
				return rerr
			}
		}
		out.Close()
		res.Body.Close()
		if err := os.Rename(part, dst); err != nil {
			return err
		}
		base += have
	}
	return nil
}

func (o *OCR) Remove(id string) error {
	if ocrModel(id) == nil {
		return ErrNotFound
	}
	o.mu.Lock()
	if d := o.downloads[id]; d != nil && d.cancel != nil {
		d.cancel()
	}
	delete(o.downloads, id)
	o.mu.Unlock()
	o.run.Lock()
	if o.run.model == id {
		o.stopLocked()
	}
	o.run.Unlock()
	return os.RemoveAll(filepath.Join(o.dir, id))
}

// ------------------------------------------------------------------ running llama-server

var ErrNoEngine = errors.New("the AI engine (llama-server) is not installed with this server")
var ErrNoModel = errors.New("no reading model is downloaded on the server yet (admin page → AI page reading)")

func (o *OCR) stopLocked() {
	if o.run.cmd != nil && o.run.cmd.Process != nil {
		_ = o.run.cmd.Process.Kill()
		_, _ = o.run.cmd.Process.Wait()
	}
	o.run.cmd, o.run.model, o.run.port = nil, "", 0
	o.pid.Store(0)
}

// OnGPU reports whether page reading runs on the graphics card here.
// A model that fell back to the processor (see EngineReport) doesn't count.
func (o *OCR) OnGPU() bool {
	e := o.Engine()
	if e == "" || e != o.cudaEngine() {
		return false
	}
	r := o.Report()
	return r == nil || !r.CUDA || r.OnCard
}

// StopIfGPU unloads a model held on the graphics card (the voice engine asks before it starts).
func (o *OCR) StopIfGPU() {
	if o.run.TryLock() {
		if o.run.cmd != nil && o.OnGPU() {
			o.stopLocked()
		}
		o.run.Unlock()
	}
}

func (o *OCR) idleStopper() {
	for range time.Tick(time.Minute) {
		o.run.Lock()
		if o.run.cmd != nil && time.Since(o.run.last) > 10*time.Minute {
			o.log("ocr: unloading %s (idle)", o.run.model)
			o.stopLocked()
		}
		o.run.Unlock()
	}
}

func freePort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

// ensure starts llama-server with model id (caller holds o.run).
func (o *OCR) ensure(ctx context.Context, id string) error {
	if o.run.cmd != nil && o.run.model == id {
		if o.run.cmd.ProcessState == nil {
			return nil
		}
	}
	o.stopLocked()
	eng := o.Engine()
	if eng == "" {
		return ErrNoEngine
	}
	m := ocrModel(id)
	var model, proj string
	for _, f := range m.Files {
		if f.Proj {
			proj = o.fpath(id, f)
		} else {
			model = o.fpath(id, f)
		}
	}
	port, err := freePort()
	if err != nil {
		return err
	}
	threads := runtime.NumCPU()
	layers := "0"
	if eng == o.cudaEngine() {
		layers = "99" // the whole model on the graphics card
		if o.beforeGPU != nil {
			o.beforeGPU()
		}
	}
	args := []string{"-m", model, "--mmproj", proj, "--host", "127.0.0.1", "--port", fmt.Sprint(port),
		"-c", "8192", "-np", "1", "-t", fmt.Sprint(threads), "-ngl", layers}
	cmd := exec.Command(eng, args...)
	logf, _ := os.OpenFile(filepath.Join(o.data, "llama-server.log"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if logf != nil {
		cmd.Stdout, cmd.Stderr = logf, logf
	}
	cmd.Dir = filepath.Dir(eng)
	cmd.Env = append(os.Environ(), "LD_LIBRARY_PATH="+filepath.Dir(eng))
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start llama-server: %w", err)
	}
	go func() { _ = cmd.Wait() }()
	o.run.cmd, o.run.model, o.run.port, o.run.last = cmd, id, port, time.Now()
	o.pid.Store(int64(cmd.Process.Pid))
	o.log("ocr: loading %s", id)
	deadline := time.Now().Add(5 * time.Minute)
	for time.Now().Before(deadline) {
		if cmd.ProcessState != nil {
			o.stopLocked()
			return errors.New("the AI engine stopped while loading the model (see llama-server.log in the data folder)")
		}
		res, err := http.Get(fmt.Sprintf("http://127.0.0.1:%d/health", port))
		if err == nil {
			res.Body.Close()
			if res.StatusCode == 200 {
				o.checkEngine(eng)
				return nil
			}
		}
		select {
		case <-ctx.Done():
			o.stopLocked()
			return ctx.Err()
		case <-time.After(700 * time.Millisecond):
		}
	}
	o.stopLocked()
	return errors.New("the AI engine took too long to load the model")
}

// Read returns the text of a page image (JPEG/PNG) read by model id ("" = default).
func (o *OCR) Read(ctx context.Context, id string, image []byte, who string) (string, string, error) {
	if id == "" {
		id = o.Default(ctx)
	}
	if id == "" || !o.Installed(id) {
		return "", "", ErrNoModel
	}
	m := ocrModel(id)
	o.workMu.Lock()
	o.waiting++
	o.workMu.Unlock()
	o.run.Lock()
	defer o.run.Unlock()
	if ctx.Err() != nil { // cancelled while waiting its turn
		o.workMu.Lock()
		o.waiting--
		o.workMu.Unlock()
		return "", id, ctx.Err()
	}
	o.workMu.Lock()
	o.waiting--
	o.work = &OCRWork{Model: m.Name, Name: who, Phase: "reading", Started: now()}
	o.workMu.Unlock()
	defer func() { o.workMu.Lock(); o.work = nil; o.workMu.Unlock() }()
	if o.run.cmd == nil || o.run.model != id || o.run.cmd.ProcessState != nil {
		o.setPhase("loading")
	}
	if err := o.ensure(ctx, id); err != nil {
		return "", id, err
	}
	o.setPhase("reading")
	o.run.last = time.Now()
	readStart := time.Now()
	mime := "image/jpeg"
	if bytes.HasPrefix(image, []byte("\x89PNG")) {
		mime = "image/png"
	}
	body, _ := json.Marshal(map[string]any{
		"messages": []any{map[string]any{"role": "user", "content": []any{
			map[string]any{"type": "image_url", "image_url": map[string]any{"url": "data:" + mime + ";base64," + base64.StdEncoding.EncodeToString(image)}},
			map[string]any{"type": "text", "text": m.Prompt},
		}}},
		"temperature": 0, "max_tokens": 3000, "repeat_penalty": 1.05, "stream": false,
	})
	req, _ := http.NewRequestWithContext(ctx, "POST", fmt.Sprintf("http://127.0.0.1:%d/v1/chat/completions", o.run.port), bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	res, err := o.hc.Do(req)
	o.run.last = time.Now()
	if err != nil {
		if ctx.Err() != nil {
			// cancelled: llama-server would finish the page anyway, so stop it (the next page reloads the model)
			o.log("ocr: page cancelled, stopping the engine")
			o.stopLocked()
			return "", id, ctx.Err()
		}
		return "", id, err
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	if res.StatusCode != 200 {
		return "", id, fmt.Errorf("AI engine: HTTP %d %s", res.StatusCode, truncRunes(string(raw), 300))
	}
	var r struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(raw, &r); err != nil || len(r.Choices) == 0 {
		return "", id, errors.New("AI engine: unexpected answer")
	}
	o.noteSpeed(id, time.Since(readStart))
	return cleanOCR(r.Choices[0].Message.Content), id, nil
}

// cleanOCR drops code fences and "Here is the text:" style lead-ins.
func cleanOCR(s string) string {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(s, "```text")
	s = strings.TrimPrefix(s, "```markdown")
	s = strings.TrimPrefix(s, "```")
	s = strings.TrimSuffix(s, "```")
	return strings.TrimSpace(s)
}

// ------------------------------------------------------------------ HTTP

func (s *Server) ocrModels(w http.ResponseWriter, r *http.Request, u *User) {
	out := []map[string]any{}
	for _, st := range s.ocr.Status() {
		if st.Installed {
			out = append(out, map[string]any{"id": st.ID, "name": st.Name, "detail": st.Detail})
		}
	}
	writeJSON(w, 200, map[string]any{"engine": s.ocr.Engine() != "", "default": s.ocr.Default(r.Context()), "models": out})
}

// POST /v1/ocr {"image": "<base64 jpeg/png>", "model": "qari4"|""} → {"text", "model", "ms"}
func (s *Server) ocrRead(w http.ResponseWriter, r *http.Request, u *User) {
	if !s.limit(w, "ocr", u.ID, 600, 200) {
		return
	}
	var in struct{ Image, Model, Task, Title string }
	if !s.readJSON(w, r, &in) {
		return
	}
	img, err := base64.StdEncoding.DecodeString(in.Image)
	if err != nil || len(img) < 100 {
		writeErr(w, 422, "image", "image must be a base64 JPEG or PNG.", nil)
		return
	}
	in.Task = truncRunes(strings.TrimSpace(in.Task), 80)
	if s.tasks.Cancelled(u.ID, in.Task) {
		writeErr(w, 409, "cancelled", "This job was cancelled.", nil)
		return
	}
	start := time.Now()
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Minute)
	defer cancel()
	what := truncRunes(strings.TrimSpace(in.Title), 80)
	ctx, task, done := s.tasks.Start(ctx, "read", u.ID, u.Name, in.Task, what)
	defer done()
	ctx = withTask(ctx, task)
	text, model, where, err := s.readPage(ctx, in.Model, img, u.Name)
	name := model
	if m := ocrModel(model); m != nil {
		name = m.Name
	}
	switch {
	case err != nil && errors.Is(ctx.Err(), context.Canceled) && r.Context().Err() == nil:
		writeErr(w, 409, "cancelled", "This job was cancelled.", nil)
	case errors.Is(err, ErrNoEngine), errors.Is(err, ErrNoModel):
		writeErr(w, 404, "ocr_off", err.Error(), nil)
	case err != nil:
		s.log.Printf("ocr: %v", err)
		s.act.Add(KindAI, "error", "ai.failed", "", A{"name": u.Name, "model": name, "error": truncRunes(err.Error(), 200)}, 0)
		writeErr(w, 424, "ocr_failed", truncRunes(err.Error(), 300), nil)
	default:
		secs := int(time.Since(start).Seconds())
		s.act.Add(KindAI, "info", "ai.read", "", A{"name": u.Name, "model": name, "secs": secs, "chars": len([]rune(text)), "on": where}, 0)
		writeJSON(w, 200, map[string]any{"text": text, "model": model, "ms": time.Since(start).Milliseconds()})
	}
}

func (s *Server) ocrAdminRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /admin/api/ocr", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"engine": s.ocr.Engine(), "default": s.ocr.Default(r.Context()), "models": s.ocr.Status(),
			"os": runtime.GOOS + "/" + runtime.GOARCH, "cpus": runtime.NumCPU(), "gpu": s.gpu.Get(), "onGpu": s.ocr.OnGPU(), "report": s.ocr.Report(),
			"cuda": map[string]any{"supported": s.ocr.CUDASupported(), "installed": s.ocr.cudaEngine() != "", "progress": s.packs.State("llama-cuda")}})
	}))
	mux.HandleFunc("POST /admin/api/ocr/models/{id}/download", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.ocr.Download(r.PathValue("id")); err != nil {
			writeErr(w, 404, "not_found", "No such model.", nil)
			return
		}
		writeJSON(w, 202, map[string]any{})
	}))
	mux.HandleFunc("DELETE /admin/api/ocr/models/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.ocr.Remove(r.PathValue("id")); err != nil {
			writeErr(w, 404, "not_found", err.Error(), nil)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))
	mux.HandleFunc("PUT /admin/api/ocr", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Default string }
		if !s.readJSON(w, r, &in) {
			return
		}
		if ocrModel(in.Default) == nil {
			writeErr(w, 422, "model", "Unknown model.", nil)
			return
		}
		if err := s.ocr.SetDefault(r.Context(), in.Default); err != nil {
			s.internal(w, err)
			return
		}
		s.act.Add(KindAI, "info", "ai.default", "", A{"model": ocrModel(in.Default).Name}, 0)
		writeJSON(w, 200, map[string]any{})
	}))
	mux.HandleFunc("POST /admin/api/ocr/test", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Image, Model string }
		if !s.readJSON(w, r, &in) {
			return
		}
		img, err := base64.StdEncoding.DecodeString(in.Image)
		if err != nil {
			writeErr(w, 422, "image", "Bad image.", nil)
			return
		}
		start := time.Now()
		text, model, _, err := s.readPage(r.Context(), in.Model, img, "admin")
		if err != nil {
			writeErr(w, 424, "ocr_failed", err.Error(), nil)
			return
		}
		writeJSON(w, 200, map[string]any{"text": text, "model": model, "ms": time.Since(start).Milliseconds()})
	}))
}
