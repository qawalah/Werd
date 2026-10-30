package main

// Voices made on this machine, with no cloud key:
//   - Piper voices (Kareem, Miro, Dii), run by sherpa-onnx on the processor: light enough for a NAS.
//   - The graphics-card voice: Chatterbox Multilingual (Resemble AI) on an NVIDIA card, installed with its own
//     Python by the server itself (uv), and able to speak in the voice of a short recording ("voice samples").
// Voice ids: "local:kareem", "local:miro", "local:dii", "local:cb" (the default Arabic narrator) and "local:cb:<sample id>".

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	_ "embed"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

//go:embed voiceengine/engine.py
var engineScript []byte

// A short Arabic narration (from SILMA TTS, MIT licence) the graphics-card voice imitates by default.
//
//go:embed voiceengine/ar-ref.wav
var defaultRef []byte

const (
	sherpaVersion = "v1.13.8"
	uvVersion     = "0.11.0"
	chatterboxPkg = "chatterbox-tts==0.1.7"
	torchIndex    = "https://download.pytorch.org/whl/cu124"
	gh            = "https://github.com/"
)

type PiperVoice struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Detail string `json:"detail"`
	pack   string
	Size   int64 `json:"size"`
}

var PiperCatalog = []PiperVoice{
	{ID: "kareem", Name: "Kareem · كريم", Detail: "Piper · Jordanian, male · clear, steady", pack: "vits-piper-ar_JO-kareem-medium", Size: 67e6},
	{ID: "miro", Name: "Miro · ميرو", Detail: "Piper · Saudi · high quality", pack: "vits-piper-ar_JO-SA_miro_V2-high", Size: 67e6},
	{ID: "dii", Name: "Dii · دي", Detail: "Piper · Saudi · high quality", pack: "vits-piper-ar_JO-SA_dii-high", Size: 67e6},
}

func piperVoice(id string) *PiperVoice {
	for i := range PiperCatalog {
		if PiperCatalog[i].ID == id {
			return &PiperCatalog[i]
		}
	}
	return nil
}

// sherpaAsset is the sherpa-onnx download for this machine ("" = Piper isn't offered here).
// Windows is left out: the program reads its text through the system code page, which mangles Arabic.
func sherpaAsset() (string, int64) {
	if runtime.GOOS != "linux" {
		return "", 0
	}
	switch runtime.GOARCH {
	case "amd64":
		return "linux-x64-static", 404e6
	case "arm64":
		return "linux-aarch64-static", 358e6
	case "arm":
		return "linux-arm-gnueabihf-static", 314e6
	}
	return "", 0
}

var ErrPCAsleep = errors.New("this voice runs on your PC, which is asleep or offline")
var ErrNoLocalVoice = errors.New("this voice isn't installed on the server")

type LocalVoices struct {
	dir   string // data/voices
	packs *Packs
	act   *Activity
	log   func(string, ...any)
	hc    *http.Client

	gpuInfo   func() *GPUInfo
	beforeGPU func() // frees the graphics card (unloads the AI reader) before the voice engine starts

	gpu struct {
		sync.Mutex
		cmd  *exec.Cmd
		port int
		last time.Time
	}
	gpuPID atomic.Int64
	refMu  sync.Mutex
	// fetchRef downloads a voice sample from the main server (set when this machine helps another server).
	fetchRef func(ctx context.Context, id string) ([]byte, error)
}

func NewLocalVoices(dataDir string, packs *Packs, act *Activity, logf func(string, ...any)) *LocalVoices {
	v := &LocalVoices{dir: filepath.Join(dataDir, "voices"), packs: packs, act: act, log: logf, hc: &http.Client{Timeout: 10 * time.Minute}}
	_ = os.MkdirAll(filepath.Join(v.dir, "refs"), 0o755)
	go v.idleStopper()
	return v
}

// ------------------------------------------------------------------ Piper

func (v *LocalVoices) PiperSupported() bool { a, _ := sherpaAsset(); return a != "" }

func (v *LocalVoices) piperEngine() string {
	p := filepath.Join(v.dir, "engine", "sherpa-onnx-offline-tts")
	if fi, err := os.Stat(p); err == nil && fi.Size() > 1e6 {
		return p
	}
	return ""
}

func (v *LocalVoices) piperModel(id string) (model, tokens, data string) {
	dir := filepath.Join(v.dir, "piper", id)
	m, _ := filepath.Glob(filepath.Join(dir, "*.onnx"))
	if len(m) == 0 {
		return "", "", ""
	}
	return m[0], filepath.Join(dir, "tokens.txt"), filepath.Join(dir, "espeak-ng-data")
}

func (v *LocalVoices) PiperInstalled(id string) bool {
	m, t, d := v.piperModel(id)
	if m == "" || v.piperEngine() == "" {
		return false
	}
	_, e1 := os.Stat(t)
	_, e2 := os.Stat(d)
	return e1 == nil && e2 == nil
}

type PiperStatus struct {
	PiperVoice
	Installed bool          `json:"installed"`
	Progress  *PackProgress `json:"progress,omitempty"`
}

func (v *LocalVoices) PiperStatus() []PiperStatus {
	out := []PiperStatus{}
	if !v.PiperSupported() {
		return out
	}
	for _, p := range PiperCatalog {
		s := PiperStatus{PiperVoice: p, Installed: v.PiperInstalled(p.ID), Progress: v.packs.State("piper-" + p.ID)}
		if !s.Installed && v.piperEngine() == "" {
			_, es := sherpaAsset()
			s.Size += es
		}
		out = append(out, s)
	}
	return out
}

func (v *LocalVoices) InstallPiper(id string) error {
	p := piperVoice(id)
	asset, esize := sherpaAsset()
	if p == nil || asset == "" {
		return ErrNotFound
	}
	files := []PackFile{}
	if v.piperEngine() == "" {
		files = append(files, PackFile{Kind: "tar.bz2", Size: esize,
			URL: gh + "k2-fsa/sherpa-onnx/releases/download/" + sherpaVersion + "/sherpa-onnx-" + sherpaVersion + "-" + asset + ".tar.bz2",
			Pick: func(name string) string {
				if strings.HasSuffix(name, "/bin/sherpa-onnx-offline-tts") {
					return "engine/sherpa-onnx-offline-tts"
				}
				return ""
			}})
	}
	files = append(files, PackFile{Kind: "tar.bz2", Size: p.Size,
		URL: gh + "k2-fsa/sherpa-onnx/releases/download/tts-models/" + p.pack + ".tar.bz2",
		Pick: func(name string) string {
			if r := stripTo("")(name); r != "" {
				return "piper/" + id + "/" + r
			}
			return ""
		}})
	v.act.Add(KindVoice, "info", "voice.install.started", "", A{"voice": p.Name}, 0)
	v.packs.Install("piper-"+id, v.dir, files, nil, func(err error) {
		if err != nil {
			v.log("voices: installing %s: %v", id, err)
			v.act.Add(KindVoice, "error", "voice.install.failed", "", A{"voice": p.Name, "error": truncRunes(err.Error(), 200)}, 0)
			return
		}
		v.act.Add(KindVoice, "info", "voice.install.done", "", A{"voice": p.Name}, 0)
	})
	return nil
}

func (v *LocalVoices) RemovePiper(id string) error {
	if piperVoice(id) == nil {
		return ErrNotFound
	}
	v.packs.Clear("piper-" + id)
	return os.RemoveAll(filepath.Join(v.dir, "piper", id))
}

func (v *LocalVoices) speakPiper(ctx context.Context, id, text string) ([]byte, error) {
	model, tokens, data := v.piperModel(id)
	eng := v.piperEngine()
	if model == "" || eng == "" {
		return nil, ErrNoLocalVoice
	}
	tmp := filepath.Join(os.TempDir(), "werd-"+randHex(8)+".wav")
	defer os.Remove(tmp)
	threads := runtime.NumCPU() / 2
	if threads < 1 {
		threads = 1
	}
	if threads > 4 {
		threads = 4
	}
	cmd := exec.CommandContext(ctx, eng, "--vits-model="+model, "--vits-tokens="+tokens, "--vits-data-dir="+data,
		fmt.Sprintf("--num-threads=%d", threads), "--output-filename="+tmp, text)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("piper: %v %s", err, truncRunes(string(out), 300))
	}
	b, err := os.ReadFile(tmp)
	if err != nil || len(b) < 100 {
		return nil, fmt.Errorf("piper made no audio: %s", truncRunes(string(out), 300))
	}
	return b, nil
}

// ------------------------------------------------------------------ the graphics-card voice (Chatterbox)

func (v *LocalVoices) gpuDir() string { return filepath.Join(v.dir, "gpu") }

func (v *LocalVoices) python() string {
	if runtime.GOOS == "windows" {
		return filepath.Join(v.gpuDir(), "venv", "Scripts", "python.exe")
	}
	return filepath.Join(v.gpuDir(), "venv", "bin", "python")
}

// GPUSupported: an NVIDIA card on Windows (64-bit Intel/AMD). Docker on a NAS has no graphics card.
func (v *LocalVoices) GPUSupported() bool {
	if runtime.GOOS != "windows" || runtime.GOARCH != "amd64" {
		return os.Getenv("WERD_GPU_VOICE") == "1"
	}
	g := v.gpuInfo()
	return g != nil && g.Name != ""
}

func (v *LocalVoices) GPUReady() bool {
	_, err := os.Stat(filepath.Join(v.gpuDir(), "ready"))
	_, err2 := os.Stat(v.python())
	return err == nil && err2 == nil
}

type GPUVoiceStatus struct {
	Supported bool          `json:"supported"`
	Ready     bool          `json:"ready"`
	Running   bool          `json:"running"`
	GPU       string        `json:"gpu,omitempty"`
	Progress  *PackProgress `json:"progress,omitempty"`
	Log       string        `json:"log,omitempty"`
}

func (v *LocalVoices) GPUStatus() GPUVoiceStatus {
	s := GPUVoiceStatus{Supported: v.GPUSupported(), Ready: v.GPUReady(), Running: v.gpuPID.Load() != 0, Progress: v.packs.State("gpu-voice")}
	if g := v.gpuInfo(); g != nil {
		s.GPU = g.Name
	}
	if s.Progress != nil {
		if b, err := os.ReadFile(filepath.Join(v.gpuDir(), "install.log")); err == nil {
			if len(b) > 1500 {
				b = b[len(b)-1500:]
			}
			s.Log = string(b)
		}
	}
	return s
}

// InstallGPU sets up Python, PyTorch for CUDA and Chatterbox in data/voices/gpu (about 6 GB with the model).
func (v *LocalVoices) InstallGPU() error {
	if !v.GPUSupported() {
		return errors.New("no NVIDIA graphics card found on this machine")
	}
	dir := v.gpuDir()
	_ = os.MkdirAll(dir, 0o755)
	_ = os.Remove(filepath.Join(dir, "ready"))
	uvName, uvAsset := "uv", "uv-x86_64-unknown-linux-gnu.tar.gz"
	kind := "zip"
	if runtime.GOOS == "windows" {
		uvName, uvAsset = "uv.exe", "uv-x86_64-pc-windows-msvc.zip"
	} else {
		kind = "tar.gz-unsupported"
	}
	if kind != "zip" {
		return errors.New("the graphics-card voice installs on Windows only")
	}
	files := []PackFile{{Kind: "zip", Size: 20e6, URL: gh + "astral-sh/uv/releases/download/" + uvVersion + "/" + uvAsset,
		Pick: func(name string) string {
			if filepath.Base(name) == uvName {
				return "uv/" + uvName
			}
			return ""
		}}}
	v.act.Add(KindVoice, "info", "voice.install.started", "", A{"voice": "Chatterbox"}, 0)
	after := func(ctx context.Context, phase func(string)) error {
		logf, err := os.OpenFile(filepath.Join(dir, "install.log"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
		if err != nil {
			return err
		}
		defer logf.Close()
		uv := filepath.Join(dir, "uv", uvName)
		env := append(os.Environ(), "UV_PYTHON_INSTALL_DIR="+filepath.Join(dir, "python"), "UV_CACHE_DIR="+filepath.Join(dir, "cache"),
			"HF_HOME="+filepath.Join(dir, "hf"), "PYTHONUTF8=1", "UV_LINK_MODE=copy", "UV_NO_PROGRESS=1")
		step := func(name string, prog string, args ...string) error {
			phase(name)
			fmt.Fprintf(logf, "\n== %s\n", name)
			cmd := exec.CommandContext(ctx, prog, args...)
			cmd.Env, cmd.Dir, cmd.Stdout, cmd.Stderr = env, dir, logf, logf
			hideWindow(cmd)
			if err := cmd.Run(); err != nil {
				return fmt.Errorf("%s: %v (see voices/gpu/install.log in the data folder)", name, err)
			}
			return nil
		}
		venv := filepath.Join(dir, "venv")
		if err := step("python", uv, "python", "install", "3.11"); err != nil {
			return err
		}
		if _, err := os.Stat(v.python()); err != nil {
			if err := step("venv", uv, "venv", "--python", "3.11", venv); err != nil {
				return err
			}
		}
		if err := step("torch", uv, "pip", "install", "--python", v.python(), "torch==2.6.0", "torchaudio==2.6.0", "--index-url", torchIndex); err != nil {
			return err
		}
		// resemble-perth (the watermark) still imports pkg_resources, which newer setuptools dropped
		if err := step("chatterbox", uv, "pip", "install", "--python", v.python(), chatterboxPkg, "setuptools<81"); err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(dir, "engine.py"), engineScript, 0o644); err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(dir, "default-ref.wav"), defaultRef, 0o644); err != nil {
			return err
		}
		if err := step("model", v.python(), filepath.Join(dir, "engine.py"), "--warmup"); err != nil {
			return err
		}
		return os.WriteFile(filepath.Join(dir, "ready"), []byte(time.Now().Format(time.RFC3339)), 0o644)
	}
	v.packs.Install("gpu-voice", dir, files, after, func(err error) {
		if err != nil {
			v.log("voices: graphics-card voice: %v", err)
			v.act.Add(KindVoice, "error", "voice.install.failed", "", A{"voice": "Chatterbox", "error": truncRunes(err.Error(), 200)}, 0)
			return
		}
		v.act.Add(KindVoice, "info", "voice.install.done", "", A{"voice": "Chatterbox"}, 0)
	})
	return nil
}

func (v *LocalVoices) RemoveGPU() error {
	v.packs.Clear("gpu-voice")
	v.gpu.Lock()
	v.stopGPULocked()
	v.gpu.Unlock()
	return os.RemoveAll(v.gpuDir())
}

func (v *LocalVoices) stopGPULocked() {
	if v.gpu.cmd != nil && v.gpu.cmd.Process != nil {
		_ = v.gpu.cmd.Process.Kill()
		_, _ = v.gpu.cmd.Process.Wait()
	}
	v.gpu.cmd, v.gpu.port = nil, 0
	v.gpuPID.Store(0)
}

// StopGPU frees the graphics card (the AI page reader asks for it before loading a model).
func (v *LocalVoices) StopGPU() {
	v.gpu.Lock()
	v.stopGPULocked()
	v.gpu.Unlock()
}

func (v *LocalVoices) idleStopper() {
	for range time.Tick(time.Minute) {
		v.gpu.Lock()
		if v.gpu.cmd != nil && time.Since(v.gpu.last) > 10*time.Minute {
			v.log("voices: stopping the graphics-card voice (idle)")
			v.stopGPULocked()
		}
		v.gpu.Unlock()
	}
}

func (v *LocalVoices) ensureGPU(ctx context.Context) error {
	if v.gpu.cmd != nil && v.gpu.cmd.ProcessState == nil {
		return nil
	}
	v.stopGPULocked()
	if !v.GPUReady() {
		return ErrNoLocalVoice
	}
	if v.beforeGPU != nil {
		v.beforeGPU()
	}
	port, err := freePort()
	if err != nil {
		return err
	}
	dir := v.gpuDir()
	cmd := exec.Command(v.python(), filepath.Join(dir, "engine.py"), "--port", fmt.Sprint(port))
	cmd.Env = append(os.Environ(), "HF_HOME="+filepath.Join(dir, "hf"), "PYTHONUTF8=1", "PYTHONIOENCODING=utf-8", "HF_HUB_OFFLINE=1")
	cmd.Dir = dir
	if lf, err := os.OpenFile(filepath.Join(dir, "engine.log"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644); err == nil {
		cmd.Stdout, cmd.Stderr = lf, lf
	}
	hideWindow(cmd)
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start the voice engine: %w", err)
	}
	go func() { _ = cmd.Wait() }()
	v.gpu.cmd, v.gpu.port, v.gpu.last = cmd, port, time.Now()
	v.gpuPID.Store(int64(cmd.Process.Pid))
	deadline := time.Now().Add(4 * time.Minute)
	for time.Now().Before(deadline) {
		if cmd.ProcessState != nil {
			v.stopGPULocked()
			return errors.New("the voice engine stopped while loading (see voices/gpu/engine.log in the data folder)")
		}
		if res, err := http.Get(fmt.Sprintf("http://127.0.0.1:%d/health", port)); err == nil {
			res.Body.Close()
			if res.StatusCode == 200 {
				return nil
			}
		}
		select {
		case <-ctx.Done():
			v.stopGPULocked()
			return ctx.Err()
		case <-time.After(time.Second):
		}
	}
	v.stopGPULocked()
	return errors.New("the voice engine took too long to load")
}

func (v *LocalVoices) speakGPU(ctx context.Context, ref, text string) ([]byte, error) {
	v.gpu.Lock()
	defer v.gpu.Unlock()
	if err := v.ensureGPU(ctx); err != nil {
		return nil, err
	}
	v.gpu.last = time.Now()
	body, _ := json.Marshal(map[string]any{"text": text, "ref": ref, "exaggeration": 0.45, "cfg": 0.5})
	req, _ := http.NewRequestWithContext(ctx, "POST", fmt.Sprintf("http://127.0.0.1:%d/speak", v.gpu.port), bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	res, err := v.hc.Do(req)
	v.gpu.last = time.Now()
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(res.Body, 60<<20))
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("voice engine: HTTP %d %s", res.StatusCode, truncRunes(string(b), 300))
	}
	return b, nil
}

// ------------------------------------------------------------------ voice samples (for the graphics-card voice)

type RefVoice struct {
	ID      string  `json:"id"`
	Name    string  `json:"name"`
	Sha     string  `json:"sha"`
	Secs    float64 `json:"secs"`
	Created int64   `json:"createdAt"`
}

func (v *LocalVoices) refsFile() string { return filepath.Join(v.dir, "refs", "refs.json") }

func (v *LocalVoices) Refs() []RefVoice {
	v.refMu.Lock()
	defer v.refMu.Unlock()
	return v.refsLocked()
}

func (v *LocalVoices) refsLocked() []RefVoice {
	var out []RefVoice
	if b, err := os.ReadFile(v.refsFile()); err == nil {
		_ = json.Unmarshal(b, &out)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Created < out[j].Created })
	return out
}

func (v *LocalVoices) Ref(id string) *RefVoice {
	for _, r := range v.Refs() {
		if r.ID == id {
			return &r
		}
	}
	return nil
}

func (v *LocalVoices) RefPath(id string) string { return filepath.Join(v.dir, "refs", id+".wav") }

// AddRef keeps a voice sample: a WAV of 3–30 seconds (the dashboard converts any recording to WAV first).
func (v *LocalVoices) AddRef(name string, wav []byte) (*RefVoice, error) {
	secs := wavSeconds(wav)
	if secs < 3 || secs > 40 {
		return nil, errors.New("the recording must be a WAV of 3 to 30 seconds")
	}
	name = strings.TrimSpace(name)
	if name == "" || len([]rune(name)) > 40 {
		return nil, errors.New("give the voice a name of up to 40 characters")
	}
	h := sha256.Sum256(wav)
	r := RefVoice{ID: randHex(4), Name: name, Sha: hex.EncodeToString(h[:6]), Secs: secs, Created: now()}
	v.refMu.Lock()
	defer v.refMu.Unlock()
	if err := os.WriteFile(v.RefPath(r.ID), wav, 0o644); err != nil {
		return nil, err
	}
	list := append(v.refsLocked(), r)
	b, _ := json.MarshalIndent(list, "", " ")
	return &r, os.WriteFile(v.refsFile(), b, 0o644)
}

func (v *LocalVoices) DeleteRef(id string) error {
	v.refMu.Lock()
	defer v.refMu.Unlock()
	list := v.refsLocked()
	out := list[:0]
	found := false
	for _, r := range list {
		if r.ID == id {
			found = true
			continue
		}
		out = append(out, r)
	}
	if !found {
		return ErrNotFound
	}
	_ = os.Remove(v.RefPath(id))
	b, _ := json.MarshalIndent(out, "", " ")
	return os.WriteFile(v.refsFile(), b, 0o644)
}

// refFor finds the sample file for "cb" / "cb:<id>" on this machine, fetching it from the main server if needed.
func (v *LocalVoices) refFor(ctx context.Context, name, sha string) (string, error) {
	_, id, _ := strings.Cut(name, ":")
	if id == "" {
		p := filepath.Join(v.gpuDir(), "default-ref.wav")
		if _, err := os.Stat(p); err != nil {
			_ = os.WriteFile(p, defaultRef, 0o644)
		}
		return p, nil
	}
	if p := v.RefPath(id); fileExists(p) {
		return p, nil
	}
	cache := filepath.Join(v.dir, "refcache", id+"-"+sha+".wav")
	if fileExists(cache) {
		return cache, nil
	}
	if v.fetchRef == nil {
		return "", errors.New("this voice sample was deleted")
	}
	b, err := v.fetchRef(ctx, id)
	if err != nil {
		return "", err
	}
	_ = os.MkdirAll(filepath.Dir(cache), 0o755)
	return cache, os.WriteFile(cache, b, 0o644)
}

// ------------------------------------------------------------------ what this machine can say

// Here lists the local voice names this machine can make now: "kareem", "cb", …
func (v *LocalVoices) Here() []string {
	out := []string{}
	for _, p := range PiperCatalog {
		if v.PiperInstalled(p.ID) {
			out = append(out, p.ID)
		}
	}
	if v.GPUReady() {
		out = append(out, "cb")
	}
	return out
}

// Can reports whether this machine makes voice name ("kareem", "cb", "cb:<id>").
func (v *LocalVoices) Can(name string) bool {
	base, _, _ := strings.Cut(name, ":")
	if base == "cb" {
		return v.GPUReady()
	}
	return v.PiperInstalled(base)
}

// Speak makes a WAV of text in voice name on this machine. sha identifies a voice sample's version.
func (v *LocalVoices) Speak(ctx context.Context, name, sha, text string) ([]byte, error) {
	base, _, _ := strings.Cut(name, ":")
	if base == "cb" {
		ref, err := v.refFor(ctx, name, sha)
		if err != nil {
			return nil, err
		}
		return v.speakGPU(ctx, ref, text)
	}
	return v.speakPiper(ctx, base, text)
}

// ------------------------------------------------------------------ small helpers

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func fileExists(p string) bool { _, err := os.Stat(p); return err == nil }

// wavSeconds reads the length of a PCM WAV (0 if it isn't one).
func wavSeconds(b []byte) float64 {
	if len(b) < 44 || string(b[0:4]) != "RIFF" || string(b[8:12]) != "WAVE" {
		return 0
	}
	var rate, bytesPerSec uint32
	i := 12
	for i+8 <= len(b) {
		id := string(b[i : i+4])
		size := int(binary.LittleEndian.Uint32(b[i+4 : i+8]))
		body := i + 8
		if id == "fmt " && body+16 <= len(b) {
			rate = binary.LittleEndian.Uint32(b[body+4 : body+8])
			bytesPerSec = binary.LittleEndian.Uint32(b[body+8 : body+12])
		}
		if id == "data" {
			if bytesPerSec == 0 || rate == 0 {
				return 0
			}
			n := size
			if body+n > len(b) {
				n = len(b) - body
			}
			return float64(n) / float64(bytesPerSec)
		}
		i = body + size + size%2
	}
	return 0
}
