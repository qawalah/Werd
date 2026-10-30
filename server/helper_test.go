package main

import (
	"context"
	"io"
	"log"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func newSrv(t *testing.T) (*Server, string) {
	t.Helper()
	dir := t.TempDir()
	st, err := OpenStore(filepath.Join(dir, "werd.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	srv := NewServer(st, &Config{DataDir: dir, MaxBodySize: 8 << 20}, "secret-admin", log.New(io.Discard, "", 0))
	hs := httptest.NewServer(srv.Handler())
	t.Cleanup(hs.Close)
	return srv, hs.URL
}

// A PC helper with a Piper voice makes clips for a main server that has none (WERD_TEST_PIPER points at an
// unpacked sherpa-onnx folder with bin/sherpa-onnx-offline-tts and a vits-piper-ar_JO-kareem-medium folder beside it).
func TestHelperMakesVoice(t *testing.T) {
	root := os.Getenv("WERD_TEST_PIPER")
	if root == "" {
		t.Skip("WERD_TEST_PIPER not set")
	}
	main, url := newSrv(t)
	pc, _ := newSrv(t)
	// install Piper on the PC by hand
	vd := pc.voices.dir
	_ = os.MkdirAll(filepath.Join(vd, "engine"), 0o755)
	if out, err := exec.Command("cp", filepath.Join(root, "sherpa-onnx-v1.13.8-linux-x64-static/bin/sherpa-onnx-offline-tts"), filepath.Join(vd, "engine")).CombinedOutput(); err != nil {
		t.Fatal(string(out))
	}
	_ = os.MkdirAll(filepath.Join(vd, "piper"), 0o755)
	if out, err := exec.Command("cp", "-r", filepath.Join(root, "vits-piper-ar_JO-kareem-medium"), filepath.Join(vd, "piper", "kareem")).CombinedOutput(); err != nil {
		t.Fatal(string(out))
	}
	if !pc.voices.PiperInstalled("kareem") {
		t.Fatal("kareem not installed on the PC")
	}
	ctx := context.Background()
	_ = main.hub.SetConfig(ctx, HelperConfig{Key: "wh-test"})
	if err := pc.helperc.SetConfig(ctx, HelperClientConfig{On: true, URL: url, Key: "wh-test"}); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(10 * time.Second)
	for !main.hub.Online() && time.Now().Before(deadline) {
		time.Sleep(100 * time.Millisecond)
	}
	if !main.hub.Online() {
		t.Fatalf("PC never came online: %+v", pc.helperc.State())
	}
	found := false
	for _, v := range main.tts.Voices(ctx) {
		if v.ID == "local:kareem" && v.Provider == "pc" {
			found = true
		}
	}
	if !found {
		t.Fatalf("main server doesn't list the PC's voice: %+v", main.tts.Voices(ctx))
	}
	text := "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ"
	b, ext, cached, err := main.tts.Speak(ctx, text, "local:kareem")
	if err != nil || ext != "wav" || cached || len(b) < 1000 || !strings.HasPrefix(string(b), "RIFF") {
		t.Fatalf("speak via PC: err=%v ext=%s cached=%v len=%d", err, ext, cached, len(b))
	}
	_, _, cached, err = main.tts.Speak(ctx, text, "local:kareem")
	if err != nil || !cached {
		t.Fatalf("second time should come from the cache: %v %v", err, cached)
	}
	// the PC goes away: a new line is refused quickly (the phone uses its own voice), cached lines still play
	_ = pc.helperc.SetConfig(ctx, HelperClientConfig{On: false})
	main.hub.mu.Lock()
	main.hub.seen = time.Now().Add(-time.Minute)
	main.hub.mu.Unlock()
	if _, _, _, err := main.tts.Speak(ctx, "الْحَمْدُ لِلَّهِ", "local:kareem"); err != ErrPCAsleep {
		t.Fatalf("want ErrPCAsleep, got %v", err)
	}
	if _, _, cached, err := main.tts.Speak(ctx, text, "local:kareem"); err != nil || !cached {
		t.Fatalf("cached line while asleep: %v %v", err, cached)
	}
}

func TestWakePacket(t *testing.T) {
	if err := sendWOL("aa:bb:cc:dd:ee:ff", "192.168.1.20"); err != nil {
		t.Fatal(err)
	}
	if err := sendWOL("nope", ""); err == nil {
		t.Fatal("bad MAC accepted")
	}
}

func TestWavSeconds(t *testing.T) {
	b, err := os.ReadFile("voiceengine/ar-ref.wav")
	if err != nil {
		t.Fatal(err)
	}
	if s := wavSeconds(b); s < 7 || s > 8.5 {
		t.Fatalf("ar-ref.wav: %v s", s)
	}
}
