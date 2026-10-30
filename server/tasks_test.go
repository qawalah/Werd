package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// A phone cancels its book scan while the page is being read on the PC: the phone's request ends at once,
// the PC is told to stop, and further pages of that scan are refused.
func TestCancelPageOnPC(t *testing.T) {
	srv, url := newSrv(t)
	ctx := context.Background()
	_ = srv.hub.SetConfig(ctx, HelperConfig{Key: "wh-k"})
	c := &tc{t, url}
	_, tok := c.account("أبو حمزة")

	poll := func(st HelperStatus) (*http.Response, []byte) {
		b, _ := json.Marshal(st)
		req, _ := http.NewRequest("POST", url+"/v1/helper/poll", bytes.NewReader(b))
		req.Header.Set("Authorization", "Helper wh-k")
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(res.Body)
		res.Body.Close()
		return res, body
	}
	pc := HelperStatus{Name: "PC"}
	pc.OCR.Engine, pc.OCR.Models = true, []string{"qari2"}
	poll(HelperStatus{Name: "PC", Busy: true}) // comes online (busy: answers within 20 s)
	pc.Busy = false

	// the phone sends a page (no model here, so it goes to the PC)
	type ans struct {
		code int
		body map[string]any
		at   time.Time
	}
	got := make(chan ans, 1)
	go func() {
		code, o := c.do("POST", "/v1/ocr", tok, map[string]any{"image": strings.Repeat("QUFB", 100), "model": "qari2", "task": "book:7", "title": "كتاب"})
		got <- ans{code, o, time.Now()}
	}()
	res, body := poll(pc)
	if res.StatusCode != 200 || !strings.Contains(string(body), `"kind":"ocr"`) {
		t.Fatalf("PC didn't get the page: %d %s", res.StatusCode, body)
	}
	var job helperJob
	_ = json.Unmarshal(body, &job)
	if l := srv.tasks.List(); len(l) != 1 || l[0].Where != "pc" || l[0].Tag != "book:7" || l[0].What != "كتاب" {
		t.Fatalf("task list: %+v", l)
	}

	// the phone cancels; the PC, busy with the page, hears about it on its heartbeat
	beat := make(chan string, 1)
	go func() {
		res, _ := poll(HelperStatus{Name: "PC", Busy: true})
		beat <- res.Header.Get("X-Werd-Cancel")
	}()
	time.Sleep(200 * time.Millisecond)
	start := time.Now()
	if code, o := c.do("DELETE", "/v1/tasks/book:7", tok, nil); code != 200 || o["cancelled"].(float64) != 1 {
		t.Fatalf("cancel: %d %v", code, o)
	}
	a := <-got
	if a.code != 409 || errCode(a.body) != "cancelled" || a.at.Sub(start) > 2*time.Second {
		t.Fatalf("phone's page: %d %v after %v", a.code, a.body, a.at.Sub(start))
	}
	select {
	case h := <-beat:
		if h != job.ID {
			t.Fatalf("PC told to cancel %q, want %q", h, job.ID)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the PC's heartbeat didn't pass on the cancel")
	}
	if len(srv.tasks.List()) != 0 {
		t.Fatalf("task still listed: %+v", srv.tasks.List())
	}
	// the next page of the same scan is refused
	if code, o := c.do("POST", "/v1/ocr", tok, map[string]any{"image": strings.Repeat("QUFB", 100), "task": "book:7"}); code != 409 {
		t.Fatalf("page after cancel: %d %v", code, o)
	}
}

// The dashboard stops a book's audio job.
func TestCancelVoiceJob(t *testing.T) {
	srv, _ := newSrv(t)
	srv.jobs.mu.Lock()
	j := &TTSJob{ID: 9, Title: "ورد", State: "queued"}
	srv.jobs.jobs = append(srv.jobs.jobs, j)
	srv.jobs.mu.Unlock()
	hs := httptest.NewServer(srv.Handler())
	defer hs.Close()
	req, _ := http.NewRequest("DELETE", hs.URL+"/admin/api/tts/jobs/9", nil)
	req.Header.Set("Authorization", "Bearer secret-admin")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 200 || srv.Jobs()[0].State != "cancelled" {
		t.Fatalf("cancel job: %d %+v", res.StatusCode, srv.Jobs()[0])
	}
}
