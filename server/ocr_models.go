package main

// Reading models the owner adds (any vision model as a GGUF file plus its projector, from a link or from a file
// already on this machine), the measured speed of each model, and a check of the engine's log that tells whether
// a model really loaded on the graphics card (the CUDA engine quietly falls back to the processor when the driver
// is too old or the card can't be used).

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// customModel is how an added model is kept in data/models/custom.json.
type customModel struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Prompt    string `json:"prompt"` // "arabic" | "qari" | the instruction itself
	ModelURL  string `json:"modelUrl,omitempty"`
	ProjURL   string `json:"projUrl,omitempty"`
	ModelPath string `json:"modelPath,omitempty"` // files already on this machine
	ProjPath  string `json:"projPath,omitempty"`
	ModelSize int64  `json:"modelSize,omitempty"`
	ProjSize  int64  `json:"projSize,omitempty"`
	Added     int64  `json:"added"`
}

var (
	customMu     sync.RWMutex
	customModels []customModel
)

func promptFor(p string) string {
	switch strings.TrimSpace(p) {
	case "", "arabic":
		return arabicPrompt
	case "qari":
		return "Free OCR."
	}
	return strings.TrimSpace(p)
}

func (c customModel) model() OCRModel {
	m := OCRModel{ID: c.ID, Name: c.Name, Detail: "Added by you", Prompt: promptFor(c.Prompt), Custom: true}
	m.Files = []OCRFile{
		{URL: c.ModelURL, Name: "model.gguf", Size: c.ModelSize, Local: c.ModelPath},
		{URL: c.ProjURL, Name: "mmproj.gguf", Size: c.ProjSize, Local: c.ProjPath, Proj: true},
	}
	if c.ModelPath != "" {
		m.Detail = "Added by you · " + filepath.Base(c.ModelPath)
	}
	return m
}

// allModels is the built-in list followed by the owner's own models.
func allModels() []OCRModel {
	out := append([]OCRModel{}, OCRCatalog...)
	customMu.RLock()
	for _, c := range customModels {
		out = append(out, c.model())
	}
	customMu.RUnlock()
	return out
}

func customModelByID(id string) *OCRModel {
	customMu.RLock()
	defer customMu.RUnlock()
	for _, c := range customModels {
		if c.ID == id {
			m := c.model()
			return &m
		}
	}
	return nil
}

func (o *OCR) customFile() string { return filepath.Join(o.dir, "custom.json") }

func (o *OCR) loadCustom() {
	b, err := os.ReadFile(o.customFile())
	if err != nil {
		return
	}
	var list []customModel
	if json.Unmarshal(b, &list) == nil {
		customMu.Lock()
		customModels = list
		customMu.Unlock()
	}
}

func (o *OCR) saveCustomLocked() error {
	b, _ := json.MarshalIndent(customModels, "", "  ")
	tmp := o.customFile() + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, o.customFile())
}

var errModelInput = errors.New("bad model")

var slugRe = regexp.MustCompile(`[^a-z0-9]+`)

// AddCustom adds a model from two links (downloaded now) or two files on this machine.
func (o *OCR) AddCustom(in customModel) (string, error) {
	in.Name = truncRunes(in.Name, 60)
	if in.Name == "" {
		return "", fmt.Errorf("%w: give the model a name", errModelInput)
	}
	in.ModelURL, in.ProjURL = strings.TrimSpace(in.ModelURL), strings.TrimSpace(in.ProjURL)
	in.ModelPath, in.ProjPath = strings.Trim(strings.TrimSpace(in.ModelPath), `"`), strings.Trim(strings.TrimSpace(in.ProjPath), `"`)
	byLink := in.ModelURL != "" || in.ProjURL != ""
	if byLink {
		for _, u := range []string{in.ModelURL, in.ProjURL} {
			if !strings.HasPrefix(u, "https://") && !strings.HasPrefix(u, "http://") {
				return "", fmt.Errorf("%w: both links are needed (the model and its projector, mmproj)", errModelInput)
			}
		}
		in.ModelPath, in.ProjPath = "", ""
		// Hugging Face page links (…/blob/…) download as a web page: use the file link
		in.ModelURL = strings.Replace(in.ModelURL, "/blob/", "/resolve/", 1)
		in.ProjURL = strings.Replace(in.ProjURL, "/blob/", "/resolve/", 1)
		in.ModelSize, in.ProjSize = o.remoteSize(in.ModelURL), o.remoteSize(in.ProjURL)
	} else {
		for _, p := range []string{in.ModelPath, in.ProjPath} {
			fi, err := os.Stat(p)
			if p == "" || err != nil || fi.IsDir() {
				return "", fmt.Errorf("%w: file not found on the server: %s", errModelInput, p)
			}
			if !strings.EqualFold(filepath.Ext(p), ".gguf") {
				return "", fmt.Errorf("%w: the files must be .gguf: %s", errModelInput, filepath.Base(p))
			}
		}
		a, _ := os.Stat(in.ModelPath)
		b, _ := os.Stat(in.ProjPath)
		in.ModelSize, in.ProjSize = a.Size(), b.Size()
	}
	if strings.Contains(strings.ToLower(filepath.Base(in.ModelURL+in.ModelPath)), "mmproj") {
		return "", fmt.Errorf("%w: the first file is the projector (mmproj); swap the two", errModelInput)
	}
	slug := strings.Trim(slugRe.ReplaceAllString(strings.ToLower(in.Name), "-"), "-")
	if len(slug) > 24 {
		slug = slug[:24]
	}
	in.ID = "my-" + slug + "-" + newToken()[:4]
	in.Added = now()
	customMu.Lock()
	customModels = append(customModels, in)
	err := o.saveCustomLocked()
	customMu.Unlock()
	if err != nil {
		return "", err
	}
	if byLink {
		return in.ID, o.Download(in.ID)
	}
	return in.ID, nil
}

// RemoveCustom forgets an added model and deletes what was downloaded for it (never the owner's own files).
func (o *OCR) RemoveCustom(id string) error {
	if customModelByID(id) == nil {
		return ErrNotFound
	}
	_ = o.Remove(id)
	customMu.Lock()
	defer customMu.Unlock()
	for i, c := range customModels {
		if c.ID == id {
			customModels = append(customModels[:i], customModels[i+1:]...)
			break
		}
	}
	return o.saveCustomLocked()
}

func (o *OCR) remoteSize(u string) int64 {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, "HEAD", u, nil)
	res, err := o.hc.Do(req)
	if err != nil {
		return 0
	}
	res.Body.Close()
	if res.ContentLength > 0 {
		return res.ContentLength
	}
	if v, _ := strconv.ParseInt(res.Header.Get("X-Linked-Size"), 10, 64); v > 0 {
		return v
	}
	return 0
}

// ------------------------------------------------------------------ speed

func (o *OCR) noteSpeed(id string, d time.Duration) {
	o.workMu.Lock()
	if o.speed == nil {
		o.speed = map[string]float64{}
	}
	o.speed[id] = d.Seconds()
	o.workMu.Unlock()
}

func (o *OCR) Speed(id string) float64 {
	o.workMu.Lock()
	defer o.workMu.Unlock()
	return o.speed[id]
}

// ------------------------------------------------------------------ is it really on the card?

// EngineReport is what the engine's log says about where the model loaded.
type EngineReport struct {
	Checked int64  `json:"checked"`
	CUDA    bool   `json:"cuda"`   // the CUDA engine was used
	OnCard  bool   `json:"onCard"` // the model's layers are on the graphics card
	Device  string `json:"device,omitempty"`
	Layers  string `json:"layers,omitempty"` // "37/37"
	Problem string `json:"problem,omitempty"`
}

var (
	reDevices = regexp.MustCompile(`ggml_cuda_init: found (\d+) CUDA devices?`)
	reDevice  = regexp.MustCompile(`Device 0: ([^,\n]+)`)
	reLayers  = regexp.MustCompile(`offloaded (\d+)/(\d+) layers to GPU`)
)

// parseEngineLog reads llama-server's start-up log.
func parseEngineLog(log string, cuda bool) EngineReport {
	r := EngineReport{Checked: now(), CUDA: cuda}
	if m := reDevice.FindStringSubmatch(log); m != nil {
		r.Device = strings.TrimSpace(m[1])
	}
	devices := -1
	if m := reDevices.FindStringSubmatch(log); m != nil {
		devices, _ = strconv.Atoi(m[1])
	}
	on, total := -1, 0
	if m := reLayers.FindStringSubmatch(log); m != nil {
		on, _ = strconv.Atoi(m[1])
		total, _ = strconv.Atoi(m[2])
		r.Layers = m[1] + "/" + m[2]
	}
	low := strings.ToLower(log)
	if !cuda {
		return r
	}
	switch {
	case strings.Contains(low, "driver version is insufficient"):
		r.Problem = "The NVIDIA driver is too old for this CUDA engine. Update the driver, then restart the server or reinstall the CUDA engine."
	case strings.Contains(low, "out of memory") || strings.Contains(low, "cudamalloc failed") || strings.Contains(low, "failed to allocate cuda"):
		r.Problem = "Not enough graphics memory for this model. Close games or other programs using the card, or choose a smaller model."
	case strings.Contains(low, "failed to initialize cuda") || strings.Contains(low, "no cuda-capable device"):
		r.Problem = "CUDA couldn't start on this card. Update the NVIDIA driver and restart the computer."
	case strings.Contains(low, "no usable gpu found") || devices == 0:
		r.Problem = "The engine found no graphics card it can use."
	case on == 0:
		r.Problem = "The model loaded on the processor (no layers on the card)."
	case on > 0 && on < total:
		r.OnCard = true
		r.Problem = fmt.Sprintf("Only %d of %d layers fit on the card; the rest run on the processor.", on, total)
	case on > 0:
		r.OnCard = true
	case devices > 0:
		r.OnCard = true // older engines don't print the layer count
	default:
		r.Problem = "The engine's log doesn't say it used the graphics card."
	}
	return r
}

// checkEngine reads the log after a model loaded.
func (o *OCR) checkEngine(eng string) {
	b, err := os.ReadFile(filepath.Join(o.data, "llama-server.log"))
	if err != nil {
		return
	}
	if len(b) > 400_000 {
		b = b[:400_000]
	}
	r := parseEngineLog(string(b), eng == o.cudaEngine())
	o.workMu.Lock()
	o.report = &r
	o.workMu.Unlock()
	if r.CUDA && r.Problem != "" {
		o.log("ocr: graphics card: %s", r.Problem)
		if o.act != nil && !r.OnCard {
			o.act.Add(KindAI, "warn", "ai.cpuFallback", "", A{"error": r.Problem}, 0)
		}
	}
}

// Report is the last load's report (nil before any model loaded).
func (o *OCR) Report() *EngineReport {
	o.workMu.Lock()
	defer o.workMu.Unlock()
	if o.report == nil {
		return nil
	}
	r := *o.report
	return &r
}

// ------------------------------------------------------------------ HTTP

func (s *Server) customModelRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /admin/api/ocr/custom", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in customModel
		if !s.readJSON(w, r, &in) {
			return
		}
		id, err := s.ocr.AddCustom(in)
		if errors.Is(err, errModelInput) {
			writeErr(w, 422, "model", strings.TrimPrefix(err.Error(), errModelInput.Error()+": "), nil)
			return
		}
		if err != nil {
			s.internal(w, err)
			return
		}
		s.act.Add(KindAI, "info", "ai.added", "", A{"model": truncRunes(in.Name, 60)}, 0)
		writeJSON(w, 201, map[string]any{"id": id})
	}))
	mux.HandleFunc("POST /v1/ai/complete", s.auth(s.aiComplete))
	mux.HandleFunc("DELETE /admin/api/ocr/custom/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.ocr.RemoveCustom(r.PathValue("id")); err != nil {
			writeErr(w, 404, "not_found", "No such model.", nil)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))
}

// ------------------------------------------------------------------ text only (the proofreading helper)

// Complete answers a text prompt (no image) with model id ("" = the default): the vision models also read text,
// so the phone's proofreading helper can ask which word fits where a page was misread.
func (o *OCR) Complete(ctx context.Context, id, prompt string) (string, error) {
	if id == "" {
		id = o.Default(ctx)
	}
	if id == "" || !o.Installed(id) {
		return "", ErrNoModel
	}
	m := ocrModel(id)
	o.workMu.Lock()
	o.waiting++
	o.workMu.Unlock()
	o.run.Lock()
	defer o.run.Unlock()
	o.workMu.Lock()
	o.waiting--
	o.work = &OCRWork{Model: m.Name, Name: "proofreading", Phase: "reading", Started: now()}
	o.workMu.Unlock()
	defer func() { o.workMu.Lock(); o.work = nil; o.workMu.Unlock() }()
	if err := o.ensure(ctx, id); err != nil {
		return "", err
	}
	o.run.last = time.Now()
	body, _ := json.Marshal(map[string]any{
		"messages":    []any{map[string]any{"role": "user", "content": prompt}},
		"temperature": 0, "max_tokens": 800, "stream": false,
	})
	req, _ := http.NewRequestWithContext(ctx, "POST", fmt.Sprintf("http://127.0.0.1:%d/v1/chat/completions", o.run.port), strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	res, err := o.hc.Do(req)
	o.run.last = time.Now()
	if err != nil {
		if ctx.Err() != nil {
			o.stopLocked()
		}
		return "", err
	}
	defer res.Body.Close()
	var r struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.NewDecoder(res.Body).Decode(&r); err != nil || len(r.Choices) == 0 {
		return "", errors.New("AI engine: unexpected answer")
	}
	return strings.TrimSpace(r.Choices[0].Message.Content), nil
}

func (s *Server) aiComplete(w http.ResponseWriter, r *http.Request, u *User) {
	if !s.limit(w, "ocr", u.ID, 600, 200) {
		return
	}
	var in struct{ Prompt, Model string }
	if !s.readJSON(w, r, &in) {
		return
	}
	if strings.TrimSpace(in.Prompt) == "" || len(in.Prompt) > 20000 {
		writeErr(w, 422, "prompt", "prompt is 1–20000 characters.", nil)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Minute)
	defer cancel()
	text, err := s.ocr.Complete(ctx, in.Model, in.Prompt)
	switch {
	case errors.Is(err, ErrNoEngine), errors.Is(err, ErrNoModel):
		writeErr(w, 404, "ocr_off", err.Error(), nil)
	case err != nil:
		writeErr(w, 424, "ai_failed", truncRunes(err.Error(), 300), nil)
	default:
		writeJSON(w, 200, map[string]any{"text": text})
	}
}
