package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Server struct {
	st     *Store
	cfg    *Config
	admin  string
	log    *log.Logger
	limits *Limiter
	tts    *TTS
	jobs   *TTSJobs
	ocr    *OCR
	act    *Activity
	sys    *System

	packs   *Packs
	gpu     *GPUWatch
	voices  *LocalVoices
	hub     *Hub          // a PC helping this server
	tasks   *Tasks        // pages being read now (cancellable)
	helperc *HelperClient // this machine helping another server
}

func NewServer(st *Store, cfg *Config, admin string, logger *log.Logger) *Server {
	s := &Server{st: st, cfg: cfg, admin: admin, log: logger, limits: NewLimiter(), tts: NewTTS(st, cfg.DataDir)}
	s.act = NewActivity(st)
	s.tasks = NewTasks()
	s.sys = NewSystem(cfg.DataDir)
	s.ocr = NewOCR(st, cfg.DataDir, logger.Printf)
	s.ocr.act = s.act
	s.sys.enginePID = s.ocr.EnginePID
	s.packs = NewPacks(cfg.DataDir)
	s.gpu = &GPUWatch{}
	go s.gpu.Run()
	s.ocr.packs, s.ocr.gpu = s.packs, s.gpu
	s.voices = NewLocalVoices(cfg.DataDir, s.packs, s.act, logger.Printf)
	s.voices.gpuInfo = s.gpu.Get
	s.voices.beforeGPU = s.ocr.StopIfGPU
	s.ocr.beforeGPU = s.voices.StopGPU
	s.hub = NewHub(st, s.act, logger.Printf)
	s.tts.local = s.speakLocal
	s.tts.localList = s.localVoiceList
	s.tts.localTag = s.localVoiceTag
	s.act.Add(KindServer, "info", "server.start", "", A{"version": version}, 0)
	s.startJobs(context.Background())
	s.startHelperClient(context.Background())
	return s
}

// ------------------------------------------------------------------ helpers

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, c, msg string, extra map[string]any) {
	e := map[string]any{"code": c, "message": msg}
	for k, v := range extra {
		e[k] = v
	}
	writeJSON(w, code, map[string]any{"error": e})
}

func (s *Server) internal(w http.ResponseWriter, err error) {
	s.log.Printf("error: %v", err)
	writeErr(w, 500, "server_error", "Something went wrong on the server.", nil)
}

// readJSON decodes the body into v; it answers 413 / 400 itself and returns false on failure.
func (s *Server) readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, s.cfg.MaxBodySize)
	b, err := io.ReadAll(r.Body)
	if err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			writeErr(w, 413, "too_large", "Too large.", nil)
		} else {
			writeErr(w, 400, "bad_request", "Could not read the request.", nil)
		}
		return false
	}
	if len(b) == 0 {
		b = []byte("{}")
	}
	if err := json.Unmarshal(b, v); err != nil {
		writeErr(w, 400, "bad_json", "Invalid JSON.", nil)
		return false
	}
	return true
}

func (s *Server) clientIP(r *http.Request) string {
	if s.cfg.TrustProxy {
		if xf := r.Header.Get("X-Forwarded-For"); xf != "" {
			return strings.TrimSpace(strings.Split(xf, ",")[0])
		}
		if xr := r.Header.Get("X-Real-IP"); xr != "" {
			return xr
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

type ctxKey int

const userKey ctxKey = 1

// auth wraps a handler that needs the caller's account.
func (s *Server) auth(h func(http.ResponseWriter, *http.Request, *User)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		if tok == "" {
			writeErr(w, 401, "unauthorized", "Sign in required.", nil)
			return
		}
		u, err := s.st.UserByToken(r.Context(), tok)
		if errors.Is(err, ErrNotFound) {
			writeErr(w, 401, "unauthorized", "Unknown account.", nil)
			return
		}
		if err != nil {
			s.internal(w, err)
			return
		}
		s.sys.Seen(u.ID)
		h(w, r.WithContext(context.WithValue(r.Context(), userKey, u)), u)
	}
}

// optionalUser returns the caller's account when the request carries a valid token, else nil (no error):
// browsing the library needs no account, but a family member also sees the family's own books.
func (s *Server) optionalUser(r *http.Request) *User {
	tok := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
	if tok == "" {
		return nil
	}
	u, err := s.st.UserByToken(r.Context(), tok)
	if err != nil {
		return nil
	}
	return u
}

func familyOf(u *User) string {
	if u != nil && u.FamilyID.Valid {
		return u.FamilyID.String
	}
	return ""
}

// limit rejects with 429 when key has used up its allowance.
func (s *Server) limit(w http.ResponseWriter, bucket string, key string, perHour float64, burst int) bool {
	ok, retry := s.limits.Allow(bucket+"|"+key, perHour, burst)
	if !ok {
		w.Header().Set("Retry-After", strconv.Itoa(int(retry.Seconds())+1))
		writeErr(w, 429, "rate_limited", "Too many requests. Try again later.", nil)
	}
	return ok
}

// ------------------------------------------------------------------ routes

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", s.home)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("ok")) })
	mux.HandleFunc("GET /v1/info", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"name": "werd-server", "version": version, "api": 1, "addresses": Addresses(s.cfg),
			"tts": s.tts.Config(r.Context()).Enabled() || len(s.localVoiceList(r.Context())) > 0})
	})

	mux.HandleFunc("POST /v1/accounts", s.createAccount)
	mux.HandleFunc("PUT /v1/accounts/me", s.auth(s.renameAccount))

	mux.HandleFunc("GET /v1/library/books", s.searchBooks)
	mux.HandleFunc("GET /v1/library/books/{id}", s.getBook)
	mux.HandleFunc("POST /v1/library/books", s.auth(s.publishBook))
	mux.HandleFunc("POST /v1/library/books/{id}/reports", s.auth(s.reportBook))

	mux.HandleFunc("POST /v1/families", s.auth(s.createFamily))
	mux.HandleFunc("POST /v1/families/join", s.auth(s.joinFamily))
	mux.HandleFunc("GET /v1/families/me", s.auth(s.myFamily))
	mux.HandleFunc("DELETE /v1/families/me/membership", s.auth(s.leaveFamily))
	mux.HandleFunc("PUT /v1/progress", s.auth(s.putProgress))
	mux.HandleFunc("POST /v1/families/me/members/{userId}/werds", s.auth(s.sendWerd))
	mux.HandleFunc("PUT /v1/families/me/members/{userId}", s.auth(s.setRole))
	mux.HandleFunc("DELETE /v1/families/me/members/{userId}", s.auth(s.removeMember))
	mux.HandleFunc("GET /v1/families/me/members/{userId}/history", s.auth(s.history))
	mux.HandleFunc("GET /v1/inbox", s.auth(s.inbox))
	mux.HandleFunc("GET /v1/tts/voices", s.auth(s.ttsVoices))
	mux.HandleFunc("POST /v1/tts", s.auth(s.ttsSpeak))
	mux.HandleFunc("POST /v1/tts/clips", s.auth(s.ttsShare))
	mux.HandleFunc("DELETE /v1/inbox/{id}", s.auth(s.ackInbox))
	mux.HandleFunc("GET /v1/ocr/models", s.auth(s.ocrModels))
	mux.HandleFunc("POST /v1/ocr", s.auth(s.ocrRead))
	mux.HandleFunc("DELETE /v1/tasks/{tag}", s.auth(s.cancelTask))
	mux.HandleFunc("POST /v1/helper/poll", s.helperPoll)
	mux.HandleFunc("POST /v1/helper/jobs/{id}", s.helperResult)
	mux.HandleFunc("GET /v1/helper/refs/{id}", s.helperRef)

	s.adminRoutes(mux)
	s.ocrAdminRoutes(mux)
	s.helperAdminRoutes(mux)
	s.taskAdminRoutes(mux)
	s.werdLibRoutes(mux)
	s.accountRoutes(mux)
	s.customModelRoutes(mux)
	s.appRoutes(mux)

	return s.middleware(mux)
}

// middleware: per-IP flood protection, unknown routes as JSON errors, panics as 500s, access log of errors.
func (s *Server) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				s.log.Printf("panic: %v", v)
				writeErr(w, 500, "server_error", "Something went wrong on the server.", nil)
			}
		}()
		if strings.HasPrefix(r.URL.Path, "/v1/") && !s.limit(w, "ip", s.clientIP(r), 3600, 300) {
			return
		}
		s.sys.Request()
		rec := &statusRecorder{ResponseWriter: w, code: 200}
		start := time.Now()
		next.ServeHTTP(rec, r)
		if rec.code >= 500 {
			s.log.Printf("%s %s → %d (%s)", r.Method, r.URL.Path, rec.code, time.Since(start).Round(time.Millisecond))
		}
	})
}

type statusRecorder struct {
	http.ResponseWriter
	code    int
	written bool
}

func (r *statusRecorder) WriteHeader(code int) {
	if !r.written {
		r.code, r.written = code, true
	}
	r.ResponseWriter.WriteHeader(code)
}

// ------------------------------------------------------------------ accounts

func (s *Server) createAccount(w http.ResponseWriter, r *http.Request) {
	if !s.limit(w, "signup", s.clientIP(r), 20, 10) {
		return
	}
	var in struct {
		DisplayName string `json:"displayName"`
		Password    string `json:"password"`
	}
	if !s.readJSON(w, r, &in) {
		return
	}
	var id, tok string
	var err error
	if in.Password != "" { // 1.15 apps: a name that is unique on the server, signed in to with its password
		id, tok, err = s.st.CreateUserWithPassword(r.Context(), in.DisplayName, in.Password)
	} else {
		id, tok, err = s.st.CreateUser(r.Context(), in.DisplayName)
	}
	if err != nil {
		s.accountErr(w, err)
		return
	}
	s.act.Add(KindPhone, "info", "phone.joined", "", A{"name": truncRunes(strings.TrimSpace(in.DisplayName), 40)}, 0)
	writeJSON(w, 201, map[string]any{"userId": id, "token": tok})
}

func (s *Server) renameAccount(w http.ResponseWriter, r *http.Request, u *User) {
	var in struct {
		DisplayName string `json:"displayName"`
	}
	if !s.readJSON(w, r, &in) {
		return
	}
	if err := s.st.RenameWithLogin(r.Context(), u.ID, in.DisplayName); err != nil {
		s.accountErr(w, err)
		return
	}
	writeJSON(w, 200, map[string]any{})
}

// ------------------------------------------------------------------ library

var occasions = map[string]bool{"morning": true, "evening": true, "morning_evening": true, "after_prayer": true, "sleep": true,
	"waking": true, "friday": true, "travel": true, "home": true, "food": true, "mosque": true, "distress": true, "ramadan": true, "general": true}

func (s *Server) searchBooks(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	offset, _ := strconv.Atoi(q.Get("offset"))
	limit, _ := strconv.Atoi(q.Get("limit"))
	if offset < 0 {
		offset = 0
	}
	if limit <= 0 || limit > 100 {
		limit = 30
	}
	madhhab := q.Get("madhhab")
	if !madhhabs[madhhab] {
		madhhab = ""
	}
	books, total, err := s.st.SearchBooks(r.Context(), BookQuery{Q: q.Get("q"), Madhhab: madhhab, FamilyID: familyOf(s.optionalUser(r)),
		Offset: offset, Limit: limit})
	if err != nil {
		s.internal(w, err)
		return
	}
	writeJSON(w, 200, map[string]any{"total": total, "books": books})
}

func (s *Server) getBook(w http.ResponseWriter, r *http.Request) {
	preview := r.URL.Query().Get("preview") == "true"
	doc, sum, err := s.st.Book(r.Context(), r.PathValue("id"), !preview, false, familyOf(s.optionalUser(r)))
	if errors.Is(err, ErrNotFound) {
		writeErr(w, 404, "not_found", "No such book.", nil)
		return
	}
	if err != nil {
		s.internal(w, err)
		return
	}
	doc["id"] = sum.ID
	if preview {
		if items, ok := doc["items"].([]any); ok && len(items) > 5 {
			doc["items"] = items[:5]
		}
		doc["itemCount"] = sum.Items
		doc["checked"] = sum.Checked
		doc["downloads"] = sum.Downloads
		doc["publisher"] = sum.Publisher
		doc["publishedAt"] = sum.PublishedAt
	}
	doc["visibility"] = sum.Visibility
	writeJSON(w, 200, doc)
}

// bookItem is one supplication of a .werdbook document, as the app writes it.
type bookItem struct {
	Title        string `json:"title"`
	Text         string `json:"text"`
	Count        int    `json:"count"`
	Occasion     string `json:"occasion"`
	OccasionText string `json:"occasionText"`
	Note         string `json:"note"`
	Page         int    `json:"page"`
	Checked      bool   `json:"checked"`
	TextOnly     bool   `json:"textOnly,omitempty"` // a text to read, no counter
	Section      string `json:"section,omitempty"`  // its chapter (1.15 apps)
	Footnote     string `json:"footnote,omitempty"` // the page's footnotes that belong to it
}

// indexEntry is one line of the book's own table of contents (فهرس); item is the index of the supplication it opens, or -1.
type indexEntry struct {
	Title string `json:"title"`
	Page  int    `json:"page"`
	Item  int    `json:"item"`
}

type bookDoc struct {
	Format     string       `json:"format"`
	Version    int          `json:"version"`
	Title      string       `json:"title"`
	Author     string       `json:"author"`
	Language   string       `json:"language"`
	Provider   string       `json:"provider"`
	Model      string       `json:"model"`
	PageCount  int          `json:"pageCount"`
	ExportedAt int64        `json:"exportedAt"`
	Madhhab    string       `json:"madhhab,omitempty"`
	Visibility string       `json:"visibility,omitempty"` // only on publish: "public" (default) or "family"; not stored in the document
	Items      []bookItem   `json:"items"`
	Index      []indexEntry `json:"index,omitempty"`
}

// madhhabs a book can be tagged with ("" = not stated).
var madhhabs = map[string]bool{"": true, "general": true, "hanafi": true, "maliki": true, "shafii": true, "hanbali": true, "jafari": true, "other": true}

func (s *Server) publishBook(w http.ResponseWriter, r *http.Request, u *User) {
	if !s.limit(w, "publish", u.ID, 2, 20) {
		return
	}
	var d bookDoc
	if !s.readJSON(w, r, &d) {
		return
	}
	switch {
	case d.Format != "werd-book":
		writeErr(w, 422, "not_a_book", "Not a Werd book.", nil)
		return
	case len(d.Items) == 0 || len(d.Items) > 5000:
		writeErr(w, 422, "items", "A book needs between 1 and 5000 supplications.", nil)
		return
	case strings.TrimSpace(d.Title) == "":
		writeErr(w, 422, "title", "The book needs a title.", nil)
		return
	}
	checked := 0
	texts := make([]string, 0, len(d.Items))
	for i := range d.Items {
		it := &d.Items[i]
		if it.Checked {
			checked++
		}
		it.Text = strings.TrimSpace(it.Text)
		if it.Text == "" {
			writeErr(w, 422, "items", "Every supplication needs its text.", nil)
			return
		}
		if it.Count < 1 {
			it.Count = 1
		}
		if !occasions[it.Occasion] {
			it.Occasion = "general"
		}
		it.Title = truncRunes(it.Title, 200)
		it.OccasionText = truncRunes(it.OccasionText, 300)
		it.Note = truncRunes(it.Note, 600)
		it.Section = truncRunes(it.Section, 120)
		it.Footnote = truncRunes(it.Footnote, 1000)
		texts = append(texts, bare(it.Text))
	}
	if checked < len(d.Items) {
		writeErr(w, 422, "not_proofread", "Only fully proofread books can be published.", nil)
		return
	}
	if len(d.Index) > 2000 {
		d.Index = d.Index[:2000]
	}
	for i := range d.Index {
		e := &d.Index[i]
		e.Title = truncRunes(strings.TrimSpace(e.Title), 200)
		if e.Item < -1 || e.Item >= len(d.Items) {
			e.Item = -1
		}
	}
	d.Title, d.Author = truncRunes(d.Title, 200), truncRunes(d.Author, 200)
	if d.Language == "" {
		d.Language = "ar"
	}
	vis := d.Visibility
	d.Visibility = ""
	if vis != "family" {
		vis = "public"
	} else if !u.FamilyID.Valid {
		writeErr(w, 422, "no_family", "Join a family to share a book with it.", nil)
		return
	}
	if !madhhabs[d.Madhhab] {
		d.Madhhab = "other"
	}
	sort.Strings(texts)
	key := hashToken(bare(d.Title) + "\x00" + strings.Join(texts, "\x00"))
	if vis == "family" {
		key = hashToken(key + "\x00family:" + u.FamilyID.String) // the same book may be public and in a family
	}
	doc, _ := json.Marshal(d)
	id, err := s.st.PublishBook(r.Context(), u, doc, d.Title, d.Author, key, len(d.Items), checked, vis, d.Madhhab)
	var dup ErrDuplicate
	if errors.As(err, &dup) {
		writeErr(w, 409, "duplicate", "This book is already in the library.", map[string]any{"id": dup.ID})
		return
	}
	if err != nil {
		s.internal(w, err)
		return
	}
	s.log.Printf("published %s %q by %s", id, d.Title, u.ID)
	s.act.Add(KindLibrary, "info", "library.published", "", A{"name": u.Name, "title": d.Title, "visibility": vis, "madhhab": d.Madhhab, "lines": len(d.Items)}, 0)
	if c := s.tts.Config(r.Context()); c.AutoGenerate && (c.Enabled() || strings.HasPrefix(c.Default, "local:")) {
		if _, err := s.QueueBook(id, c.AutoVoice); err != nil {
			s.log.Printf("tts: could not queue %s: %v", id, err)
		}
	}
	writeJSON(w, 201, map[string]any{"id": id})
}

func (s *Server) reportBook(w http.ResponseWriter, r *http.Request, u *User) {
	if !s.limit(w, "report", u.ID, 2, 30) {
		return
	}
	var in struct {
		Reason string `json:"reason"`
		Item   *int   `json:"item"`
	}
	if !s.readJSON(w, r, &in) {
		return
	}
	if strings.TrimSpace(in.Reason) == "" {
		writeErr(w, 422, "reason", "Say what is wrong.", nil)
		return
	}
	err := s.st.Report(r.Context(), r.PathValue("id"), u.ID, in.Reason, in.Item)
	if errors.Is(err, ErrNotFound) {
		writeErr(w, 404, "not_found", "No such book.", nil)
		return
	}
	if err != nil {
		s.internal(w, err)
		return
	}
	s.act.Add(KindLibrary, "warn", "library.reported", "", A{"name": u.Name, "title": s.bookTitle(r.Context(), r.PathValue("id")), "reason": truncRunes(in.Reason, 120)}, 0)
	writeJSON(w, 201, map[string]any{})
}

// ------------------------------------------------------------------ family

func familyJSON(f *Family) map[string]any {
	return map[string]any{"familyId": f.ID, "name": f.Name, "inviteCode": f.Code}
}

func (s *Server) createFamily(w http.ResponseWriter, r *http.Request, u *User) {
	if !s.limit(w, "family", u.ID, 5, 10) {
		return
	}
	var in struct {
		Name string `json:"name"`
	}
	if !s.readJSON(w, r, &in) {
		return
	}
	if strings.TrimSpace(in.Name) == "" {
		writeErr(w, 422, "name", "The family needs a name.", nil)
		return
	}
	f, err := s.st.CreateFamily(r.Context(), u, in.Name)
	if errors.Is(err, ErrConflict) {
		writeErr(w, 409, "already_in_family", "Already in a family.", nil)
		return
	}
	if err != nil {
		s.internal(w, err)
		return
	}
	s.act.Add(KindFamily, "info", "family.created", "", A{"name": u.Name, "family": f.Name}, 0)
	writeJSON(w, 201, familyJSON(f))
}

func (s *Server) joinFamily(w http.ResponseWriter, r *http.Request, u *User) {
	// slow down code guessing
	if !s.limit(w, "join", s.clientIP(r), 20, 10) {
		return
	}
	var in struct {
		InviteCode string `json:"inviteCode"`
	}
	if !s.readJSON(w, r, &in) {
		return
	}
	f, err := s.st.JoinFamily(r.Context(), u, in.InviteCode)
	switch {
	case errors.Is(err, ErrNotFound):
		writeErr(w, 404, "unknown_code", "No family has this code.", nil)
	case errors.Is(err, ErrConflict):
		writeErr(w, 409, "already_in_family", "Already in a family.", nil)
	case err != nil:
		s.internal(w, err)
	default:
		s.act.Add(KindFamily, "info", "family.joined", "", A{"name": u.Name, "family": f.Name}, 0)
		writeJSON(w, 200, familyJSON(f))
	}
}

func (s *Server) myFamily(w http.ResponseWriter, r *http.Request, u *User) {
	f, err := s.st.FamilyOf(r.Context(), u)
	if errors.Is(err, ErrNotFound) {
		writeErr(w, 404, "no_family", "Not in a family.", nil)
		return
	}
	if err != nil {
		s.internal(w, err)
		return
	}
	rows, err := s.st.Members(r.Context(), f.ID)
	if err != nil {
		s.internal(w, err)
		return
	}
	members := make([]map[string]any, 0, len(rows))
	for _, m := range rows {
		var progress any
		if m.Sharing && m.Progress.Valid {
			progress = json.RawMessage(m.Progress.String)
		}
		members = append(members, map[string]any{"userId": m.ID, "displayName": m.Name, "isMe": m.ID == u.ID,
			"sharing": m.Sharing, "progress": progress, "role": m.Role, "seenAt": m.SeenAt})
	}
	out := familyJSON(f)
	out["members"] = members
	out["myRole"] = u.Role
	writeJSON(w, 200, out)
}

func (s *Server) leaveFamily(w http.ResponseWriter, r *http.Request, u *User) {
	fam := ""
	if u.FamilyID.Valid {
		fam = s.familyName(r.Context(), u.FamilyID.String)
	}
	err := s.st.LeaveFamily(r.Context(), u)
	if errors.Is(err, ErrNotFound) {
		writeErr(w, 404, "no_family", "Not in a family.", nil)
		return
	}
	if err != nil {
		s.internal(w, err)
		return
	}
	s.act.Add(KindFamily, "info", "family.left", "", A{"name": u.Name, "family": fam}, 0)
	writeJSON(w, 200, map[string]any{})
}

type werdProgress struct {
	Title     string `json:"title"`
	Done      int    `json:"done"`
	Total     int    `json:"total"`
	Completed bool   `json:"completed"`
}

func (s *Server) putProgress(w http.ResponseWriter, r *http.Request, u *User) {
	var in struct {
		Sharing *bool          `json:"sharing"`
		Day     string         `json:"day"`
		Streak  int            `json:"streak"`
		Due     int            `json:"due"`
		Done    int            `json:"done"`
		Werds   []werdProgress `json:"werds"`
	}
	if !s.readJSON(w, r, &in) {
		return
	}
	sharing := in.Sharing == nil || *in.Sharing
	var doc []byte
	if sharing {
		if _, err := time.Parse("2006-01-02", in.Day); err != nil {
			writeErr(w, 422, "day", "day must be YYYY-MM-DD.", nil)
			return
		}
		if len(in.Werds) > 200 {
			in.Werds = in.Werds[:200]
		}
		for i := range in.Werds {
			in.Werds[i].Title = truncRunes(in.Werds[i].Title, 200)
		}
		doc, _ = json.Marshal(map[string]any{"day": in.Day, "updatedAt": now(), "streak": in.Streak, "due": in.Due, "done": in.Done, "werds": in.Werds})
	}
	if err := s.st.SetProgress(r.Context(), u.ID, sharing, in.Day, doc); err != nil {
		s.internal(w, err)
		return
	}
	s.act.Add(KindPhone, "info", "phone.sync", u.ID, A{"name": u.Name, "done": in.Done, "due": in.Due, "streak": in.Streak}, 45*time.Minute)
	writeJSON(w, 200, map[string]any{})
}

func (s *Server) sendWerd(w http.ResponseWriter, r *http.Request, u *User) {
	if !s.limit(w, "send", u.ID, 60, 30) {
		return
	}
	var in struct {
		Title   string          `json:"title"`
		Note    string          `json:"note"`
		Lines   []Line          `json:"lines"`
		Werd    json.RawMessage `json:"werd"`    // the werd's settings (days, colour, reminders…), kept as sent
		AutoAdd bool            `json:"autoAdd"` // parent → child: added to the child's list without asking
	}
	if !s.readJSON(w, r, &in) {
		return
	}
	lines := make([]Line, 0, len(in.Lines))
	for _, l := range in.Lines {
		if t := strings.TrimSpace(l.Text); t != "" {
			if l.Count < 1 {
				l.Count = 1
			}
			lines = append(lines, Line{Text: truncRunes(t, 4000), Count: l.Count})
		}
	}
	if len(lines) == 0 || len(lines) > 500 {
		writeErr(w, 422, "lines", "A werd needs between 1 and 500 lines.", nil)
		return
	}
	if len(in.Werd) > 64<<10 {
		writeErr(w, 413, "too_large", "The werd's settings are too large.", nil)
		return
	}
	if len(in.Werd) > 0 && !json.Valid(in.Werd) {
		in.Werd = nil
	}
	id, err := s.st.SendWerd(r.Context(), u, r.PathValue("userId"), in.Title, in.Note, lines, in.Werd, in.AutoAdd)
	if errors.Is(err, ErrNotFound) {
		writeErr(w, 404, "not_in_family", "That person is not in your family.", nil)
		return
	}
	if err != nil {
		s.internal(w, err)
		return
	}
	s.act.Add(KindFamily, "info", "family.werd", "", A{"from": u.Name, "to": s.userName(r.Context(), r.PathValue("userId")), "title": in.Title, "lines": len(lines), "auto": in.AutoAdd}, 0)
	writeJSON(w, 201, map[string]any{"id": id})
}

func (s *Server) familyErr(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, ErrForbidden):
		writeErr(w, 403, "not_parent", "Only a parent can do this.", nil)
	case errors.Is(err, ErrNotFound):
		writeErr(w, 404, "not_in_family", "That person is not in your family.", nil)
	case errors.Is(err, ErrConflict):
		writeErr(w, 409, "last_parent", "The family needs at least one parent.", nil)
	default:
		s.internal(w, err)
	}
}

func (s *Server) setRole(w http.ResponseWriter, r *http.Request, u *User) {
	var in struct {
		Role string `json:"role"`
	}
	if !s.readJSON(w, r, &in) {
		return
	}
	if in.Role != "parent" && in.Role != "child" {
		writeErr(w, 422, "role", "role is parent or child.", nil)
		return
	}
	if err := s.st.SetRole(r.Context(), u, r.PathValue("userId"), in.Role); err != nil {
		s.familyErr(w, err)
		return
	}
	s.act.Add(KindFamily, "info", "family.role", "", A{"by": u.Name, "name": s.userName(r.Context(), r.PathValue("userId")), "role": in.Role}, 0)
	writeJSON(w, 200, map[string]any{})
}

func (s *Server) removeMember(w http.ResponseWriter, r *http.Request, u *User) {
	who := s.userName(r.Context(), r.PathValue("userId"))
	if err := s.st.RemoveMember(r.Context(), u, r.PathValue("userId")); err != nil {
		s.familyErr(w, err)
		return
	}
	s.act.Add(KindFamily, "info", "family.removed", "", A{"by": u.Name, "name": who}, 0)
	writeJSON(w, 200, map[string]any{})
}

func (s *Server) history(w http.ResponseWriter, r *http.Request, u *User) {
	days, _ := strconv.Atoi(r.URL.Query().Get("days"))
	if days <= 0 || days > 120 {
		days = 30
	}
	h, err := s.st.History(r.Context(), u, r.PathValue("userId"), days)
	if err != nil {
		s.familyErr(w, err)
		return
	}
	writeJSON(w, 200, map[string]any{"days": h})
}

func (s *Server) inbox(w http.ResponseWriter, r *http.Request, u *User) {
	items, err := s.st.Inbox(r.Context(), u.ID)
	if err != nil {
		s.internal(w, err)
		return
	}
	writeJSON(w, 200, map[string]any{"werds": items})
}

func (s *Server) ackInbox(w http.ResponseWriter, r *http.Request, u *User) {
	if err := s.st.AckInbox(r.Context(), u.ID, r.PathValue("id")); err != nil {
		s.internal(w, err)
		return
	}
	writeJSON(w, 200, map[string]any{})
}

// ------------------------------------------------------------------ background

func (s *Server) Housekeeping(ctx context.Context) {
	t := time.NewTicker(6 * time.Hour)
	defer t.Stop()
	for {
		s.st.Cleanup(ctx)
		s.limits.Prune()
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// ------------------------------------------------------------------ rate limiting

// Limiter is a set of token buckets kept in memory (they reset when the server restarts, which is fine).
type Limiter struct {
	mu sync.Mutex
	b  map[string]*bucket
}

type bucket struct {
	tokens float64
	last   time.Time
}

func NewLimiter() *Limiter { return &Limiter{b: map[string]*bucket{}} }

// Allow takes one token from key's bucket (refilling perHour per hour, holding at most burst).
func (l *Limiter) Allow(key string, perHour float64, burst int) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	t := time.Now()
	b := l.b[key]
	if b == nil {
		b = &bucket{tokens: float64(burst), last: t}
		l.b[key] = b
	}
	b.tokens += t.Sub(b.last).Hours() * perHour
	if b.tokens > float64(burst) {
		b.tokens = float64(burst)
	}
	b.last = t
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	return false, time.Duration((1 - b.tokens) / perHour * float64(time.Hour))
}

func (l *Limiter) Prune() {
	l.mu.Lock()
	defer l.mu.Unlock()
	for k, b := range l.b {
		if time.Since(b.last) > 24*time.Hour {
			delete(l.b, k)
		}
	}
}
