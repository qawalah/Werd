package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestEngineLogReport(t *testing.T) {
	ok := "ggml_cuda_init: found 1 CUDA devices:\n  Device 0: NVIDIA GeForce RTX 3060 Ti, compute capability 8.6, VMM: yes\nload_tensors: offloaded 37/37 layers to GPU\n"
	if r := parseEngineLog(ok, true); !r.OnCard || r.Problem != "" || r.Layers != "37/37" || r.Device != "NVIDIA GeForce RTX 3060 Ti" {
		t.Fatalf("on the card: %+v", r)
	}
	old := "ggml_cuda_init: failed to initialize CUDA: CUDA driver version is insufficient for CUDA runtime version\nwarning: no usable GPU found, --gpu-layers option will be ignored\nload_tensors: offloaded 0/37 layers to GPU\n"
	if r := parseEngineLog(old, true); r.OnCard || !strings.Contains(r.Problem, "driver is too old") {
		t.Fatalf("old driver: %+v", r)
	}
	if r := parseEngineLog("ggml_cuda_init: found 1 CUDA devices:\nload_tensors: offloaded 20/37 layers to GPU\n", true); !r.OnCard || !strings.Contains(r.Problem, "20 of 37") {
		t.Fatalf("partial: %+v", r)
	}
	if r := parseEngineLog("load_tensors: offloaded 0/37 layers to GPU\n", true); r.OnCard || r.Problem == "" {
		t.Fatalf("cpu: %+v", r)
	}
	if r := parseEngineLog("anything", false); r.Problem != "" || r.CUDA {
		t.Fatalf("cpu engine: %+v", r)
	}
}

func TestCustomModelFromFiles(t *testing.T) {
	c, _, dir := newTestServer(t)
	admin := "secret-admin"
	t.Cleanup(func() { customMu.Lock(); customModels = nil; customMu.Unlock() })
	m, p := filepath.Join(dir, "mine.gguf"), filepath.Join(dir, "mine-mmproj.gguf")
	big := make([]byte, 2<<20)
	_ = os.WriteFile(m, big, 0o644)
	_ = os.WriteFile(p, big, 0o644)
	// the files swapped: refused
	if code, o := c.do("POST", "/admin/api/ocr/custom", admin, map[string]any{"name": "Mine", "modelPath": p, "projPath": m}); code != 422 {
		t.Fatalf("swapped: %d %v", code, o)
	}
	if code, o := c.do("POST", "/admin/api/ocr/custom", admin, map[string]any{"name": "Mine", "modelPath": filepath.Join(dir, "nope.gguf"), "projPath": p}); code != 422 {
		t.Fatalf("missing: %d %v", code, o)
	}
	code, o := c.do("POST", "/admin/api/ocr/custom", admin, map[string]any{"name": "My Arabic model", "modelPath": m, "projPath": p, "prompt": "qari"})
	if code != 201 {
		t.Fatalf("add: %d %v", code, o)
	}
	id := o["id"].(string)
	_, o = c.do("GET", "/admin/api/ocr", admin, nil)
	found := false
	for _, x := range o["models"].([]any) {
		mm := x.(map[string]any)
		if mm["id"] == id {
			found = mm["installed"] == true && mm["custom"] == true
		}
	}
	if !found {
		t.Fatalf("not listed as installed: %v", o["models"])
	}
	// phones see it, and it can be the default
	_, tok := c.account("قارئ")
	if _, o := c.do("GET", "/v1/ocr/models", tok, nil); !strings.Contains(stringify(o), id) {
		t.Fatalf("phone list: %v", o)
	}
	if code, _ := c.do("PUT", "/admin/api/ocr", admin, map[string]any{"default": id}); code != 200 {
		t.Fatalf("default: %d", code)
	}
	// kept across restarts (custom.json), and removing it never deletes the owner's own files
	if _, err := os.Stat(filepath.Join(dir, "models", "custom.json")); err != nil {
		t.Fatal(err)
	}
	if code, _ := c.do("DELETE", "/admin/api/ocr/custom/"+id, admin, nil); code != 200 {
		t.Fatalf("remove: %d", code)
	}
	if !fileExists(m) || !fileExists(p) {
		t.Fatal("removing the model deleted the owner's files")
	}
	// the dashboard has the library summary first
	_, o = c.do("GET", "/admin/api/dashboard", admin, nil)
	if o["library"] == nil {
		t.Fatalf("dashboard library: %v", o)
	}
}

func stringify(o map[string]any) string {
	var b strings.Builder
	var walk func(v any)
	walk = func(v any) {
		switch x := v.(type) {
		case map[string]any:
			for _, y := range x {
				walk(y)
			}
		case []any:
			for _, y := range x {
				walk(y)
			}
		case string:
			b.WriteString(x + " ")
		}
	}
	walk(o)
	return b.String()
}
