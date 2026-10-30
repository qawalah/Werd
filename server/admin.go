package main

import (
	"crypto/subtle"
	_ "embed"
	"errors"
	"fmt"
	"html/template"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

//go:embed web/admin.html
var adminHTML []byte

//go:embed web/home.html
var homeHTML string

var homeTmpl = template.Must(template.New("home").Parse(homeHTML))

func (s *Server) home(w http.ResponseWriter, r *http.Request) {
	st, err := s.st.Stats(r.Context())
	if err != nil {
		s.internal(w, err)
		return
	}
	scheme := "http"
	if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
		scheme = "https"
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = homeTmpl.Execute(w, map[string]any{"Version": version, "S": st, "URL": scheme + "://" + r.Host})
}

// adminAuth checks the admin password (Authorization: Bearer <token>), slowing down guessing.
func (s *Server) adminAuth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		if tok == "" {
			tok = r.URL.Query().Get("token") // for the backup download link
		}
		if subtle.ConstantTimeCompare([]byte(tok), []byte(s.admin)) != 1 {
			if !s.limit(w, "admin-fail", s.clientIP(r), 30, 10) {
				return
			}
			time.Sleep(300 * time.Millisecond)
			writeErr(w, 401, "unauthorized", "Wrong admin password.", nil)
			return
		}
		h(w, r)
	}
}

func (s *Server) adminRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /admin", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(adminHTML)
	})
	mux.HandleFunc("GET /admin/api/stats", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		st, err := s.st.Stats(r.Context())
		if err != nil {
			s.internal(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{"stats": st, "version": version, "dataDir": s.cfg.DataDir})
	}))
	mux.HandleFunc("GET /admin/api/reports", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		rs, err := s.st.OpenReports(r.Context())
		if err != nil {
			s.internal(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{"reports": rs})
	}))
	mux.HandleFunc("POST /admin/api/reports/{id}/resolve", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
		if err := s.st.ResolveReport(r.Context(), id); err != nil {
			s.internal(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))
	mux.HandleFunc("GET /admin/api/tts", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		c := s.tts.Config(r.Context())
		mask := func(k string) string {
			if len(k) <= 6 {
				return strings.Repeat("•", len(k))
			}
			return k[:3] + "…" + k[len(k)-3:]
		}
		n, size := s.tts.CacheSize()
		writeJSON(w, 200, map[string]any{"default": c.Default, "autoGenerate": c.AutoGenerate, "autoVoice": c.AutoVoice,
			"library": s.tts.Library(r.Context()), "geminiKey": mask(c.GeminiKey), "geminiModel": c.GeminiModel,
			"geminiStyle": c.GeminiStyle, "azureKey": mask(c.AzureKey), "azureRegion": c.AzureRegion, "elevenKey": mask(c.ElevenKey),
			"elevenModel": c.ElevenModel, "voices": s.tts.Voices(r.Context()), "clips": n, "bytes": size})
	}))
	mux.HandleFunc("PUT /admin/api/tts", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in TTSConfig
		if !s.readJSON(w, r, &in) {
			return
		}
		c := s.tts.Config(r.Context())
		// masked keys come back unchanged: keep the stored ones; "" clears
		keep := func(dst *string, v string) {
			if !strings.Contains(v, "…") && !strings.Contains(v, "•") {
				*dst = strings.TrimSpace(v)
			}
		}
		keep(&c.GeminiKey, in.GeminiKey)
		keep(&c.AzureKey, in.AzureKey)
		keep(&c.ElevenKey, in.ElevenKey)
		c.Default, c.GeminiModel, c.GeminiStyle = strings.TrimSpace(in.Default), strings.TrimSpace(in.GeminiModel), strings.TrimSpace(in.GeminiStyle)
		c.AzureRegion, c.ElevenModel = strings.TrimSpace(in.AzureRegion), strings.TrimSpace(in.ElevenModel)
		c.AutoGenerate, c.AutoVoice = in.AutoGenerate, strings.TrimSpace(in.AutoVoice)
		if err := s.tts.SetConfig(r.Context(), c); err != nil {
			s.internal(w, err)
			return
		}
		s.act.Add(KindServer, "info", "server.voices", "", nil, 0)
		writeJSON(w, 200, map[string]any{})
	}))
	mux.HandleFunc("POST /admin/api/tts/test", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Text, Voice string }
		if !s.readJSON(w, r, &in) {
			return
		}
		if in.Text == "" {
			in.Text = "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ، سُبْحَانَ اللَّهِ الْعَظِيمِ"
		}
		data, ext, _, err := s.tts.Speak(r.Context(), in.Text, in.Voice)
		if err != nil {
			writeErr(w, 502, "tts_failed", err.Error(), nil)
			return
		}
		w.Header().Set("Content-Type", map[string]string{"mp3": "audio/mpeg", "wav": "audio/wav"}[ext])
		_, _ = w.Write(data)
	}))
	mux.HandleFunc("POST /admin/api/books/{id}/tts", s.adminAuth(s.adminQueueBook))
	famErr := func(w http.ResponseWriter, err error) {
		switch {
		case errors.Is(err, ErrNotFound):
			writeErr(w, 404, "not_found", "Not found (or not in a family).", nil)
		case errors.Is(err, ErrConflict):
			writeErr(w, 409, "last_parent", "The family needs at least one parent.", nil)
		case err != nil:
			s.internal(w, err)
		default:
			writeJSON(w, 200, map[string]any{})
		}
	}
	mux.HandleFunc("PUT /admin/api/users/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Role, Name string }
		if !s.readJSON(w, r, &in) {
			return
		}
		if in.Name != "" {
			if err := s.st.RenameUser(r.Context(), r.PathValue("id"), in.Name); err != nil {
				s.internal(w, err)
				return
			}
		}
		if in.Role == "" {
			writeJSON(w, 200, map[string]any{})
			return
		}
		if in.Role != "parent" && in.Role != "child" {
			writeErr(w, 422, "role", "role is parent or child.", nil)
			return
		}
		famErr(w, s.st.AdminSetRole(r.Context(), r.PathValue("id"), in.Role))
	}))
	mux.HandleFunc("DELETE /admin/api/users/{id}/family", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		famErr(w, s.st.AdminRemoveFromFamily(r.Context(), r.PathValue("id")))
	}))
	mux.HandleFunc("PUT /admin/api/families/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Name string }
		if !s.readJSON(w, r, &in) {
			return
		}
		if strings.TrimSpace(in.Name) == "" {
			writeErr(w, 422, "name", "The family needs a name.", nil)
			return
		}
		famErr(w, s.st.RenameFamily(r.Context(), r.PathValue("id"), in.Name))
	}))
	mux.HandleFunc("GET /admin/api/tts/jobs", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"jobs": s.Jobs()})
	}))
	mux.HandleFunc("DELETE /admin/api/tts/cache", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		s.tts.ClearCache()
		writeJSON(w, 200, map[string]any{})
	}))
	mux.HandleFunc("GET /admin/api/families", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		fams, err := s.st.Families(r.Context())
		if err != nil {
			s.internal(w, err)
			return
		}
		active := map[string]int64{}
		for id, at := range s.sys.Active(24 * time.Hour) {
			active[id] = at
		}
		writeJSON(w, 200, map[string]any{"families": fams, "active": active})
	}))
	mux.HandleFunc("GET /admin/api/books", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		books, total, err := s.st.SearchBooks(r.Context(), BookQuery{Q: r.URL.Query().Get("q"), Limit: 200, Admin: true})
		if err != nil {
			s.internal(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{"books": books, "total": total})
	}))
	mux.HandleFunc("GET /admin/api/books/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		doc, _, err := s.st.Book(r.Context(), r.PathValue("id"), false, true, "")
		if err != nil {
			writeErr(w, 404, "not_found", "No such book.", nil)
			return
		}
		writeJSON(w, 200, doc)
	}))
	mux.HandleFunc("POST /admin/api/books/{id}/hide", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Hidden bool `json:"hidden"`
		}
		if !s.readJSON(w, r, &in) {
			return
		}
		if err := s.st.SetBookHidden(r.Context(), r.PathValue("id"), in.Hidden); err != nil {
			writeErr(w, 404, "not_found", "No such book.", nil)
			return
		}
		s.log.Printf("admin: book %s hidden=%v", r.PathValue("id"), in.Hidden)
		s.act.Add(KindLibrary, "info", map[bool]string{true: "library.hidden", false: "library.shown"}[in.Hidden], "", A{"title": s.bookTitle(r.Context(), r.PathValue("id"))}, 0)
		writeJSON(w, 200, map[string]any{})
	}))
	mux.HandleFunc("DELETE /admin/api/books/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		title := s.bookTitle(r.Context(), r.PathValue("id"))
		if err := s.st.DeleteBook(r.Context(), r.PathValue("id")); err != nil {
			writeErr(w, 404, "not_found", "No such book.", nil)
			return
		}
		s.log.Printf("admin: book %s deleted", r.PathValue("id"))
		s.act.Add(KindLibrary, "warn", "library.deleted", "", A{"title": title}, 0)
		writeJSON(w, 200, map[string]any{})
	}))
	// Everything the dashboard shows, in one call (polled every few seconds).
	mux.HandleFunc("GET /admin/api/dashboard", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		st, err := s.st.Stats(r.Context())
		if err != nil {
			s.internal(w, err)
			return
		}
		type dl struct {
			ID    string `json:"id"`
			Name  string `json:"name"`
			Done  int64  `json:"done"`
			Total int64  `json:"total"`
			Error string `json:"error,omitempty"`
		}
		dls := []dl{}
		installed := 0
		for _, m := range s.ocr.Status() {
			if m.Installed {
				installed++
			}
			if !m.Installed && m.Total > 0 {
				dls = append(dls, dl{m.ID, m.Name, m.Done, m.Total, m.Error})
			}
		}
		loaded := s.ocr.Loaded()
		if m := ocrModel(loaded); m != nil {
			loaded = m.Name
		}
		acts, _ := s.act.List(r.Context(), r.URL.Query().Get("kind"), 0, 60)
		lib, err := s.st.LibrarySummary(r.Context())
		if err != nil {
			s.internal(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{"version": version, "stats": st, "system": s.sys.Now(), "jobs": s.Jobs(),
			"ocr": map[string]any{"work": s.ocr.Work(), "loaded": loaded, "downloads": dls, "installed": installed, "engine": s.ocr.Engine() != "",
				"onGpu": s.ocr.OnGPU(), "report": s.ocr.Report()}, "library": lib, "gpuHistory": s.gpu.History(),
			"counts": s.act.Counts(r.Context()), "activity": acts, "addresses": Addresses(s.cfg), "dataDir": s.cfg.DataDir,
			"helper": s.helperView(r.Context()), "gpu": s.gpu.Get(), "packs": s.packs.Running(), "helperClient": s.helperc.State(),
			"helperClientOn": s.helperc.Config(r.Context()).On, "tasks": s.tasks.List()})
	}))
	mux.HandleFunc("GET /admin/api/activity", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		before, _ := strconv.ParseInt(r.URL.Query().Get("before"), 10, 64)
		acts, err := s.act.List(r.Context(), r.URL.Query().Get("kind"), before, 60)
		if err != nil {
			s.internal(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{"activity": acts})
	}))
	// A consistent copy of the database, downloaded as a file.
	mux.HandleFunc("GET /admin/api/backup", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		tmp := filepath.Join(os.TempDir(), fmt.Sprintf("werd-backup-%d.db", time.Now().UnixNano()))
		defer os.Remove(tmp)
		if err := s.st.Backup(r.Context(), tmp); err != nil {
			s.internal(w, err)
			return
		}
		s.act.Add(KindServer, "info", "server.backup", "", nil, 0)
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="werd-%s.db"`, time.Now().Format("2006-01-02")))
		http.ServeFile(w, r, tmp)
	}))
}
