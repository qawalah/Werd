package main

// Running work that can be stopped: every page being read (here or on the PC) is a task with a cancel function.
// A phone cancels its book scan (DELETE /v1/tasks/{tag}); the dashboard cancels one task or all of them.
// A phone that drops its request cancels its task too (the request's context ends).

import (
	"context"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

type Task struct {
	ID      string `json:"id"`
	Kind    string `json:"kind"` // read (a page) | voice (book audio)
	Who     string `json:"who"`
	What    string `json:"what"`
	Tag     string `json:"tag,omitempty"` // the phone's name for the job (e.g. "book:12"), so it can cancel it
	State   string `json:"state"`         // waiting | running
	Where   string `json:"where,omitempty"`
	Started int64  `json:"startedAt"`
	user    string
	cancel  context.CancelFunc
}

type Tasks struct {
	mu   sync.Mutex
	m    map[string]*Task
	dead map[string]time.Time // user+tag cancelled recently: further pages of that job are refused
}

func NewTasks() *Tasks { return &Tasks{m: map[string]*Task{}, dead: map[string]time.Time{}} }

// Start registers work; done() must be called when it ends.
func (t *Tasks) Start(ctx context.Context, kind, user, who, tag, what string) (context.Context, *Task, func()) {
	ctx, cancel := context.WithCancel(ctx)
	k := &Task{ID: randHex(6), Kind: kind, Who: who, What: what, Tag: tag, State: "waiting", Started: now(), user: user, cancel: cancel}
	t.mu.Lock()
	t.m[k.ID] = k
	t.mu.Unlock()
	return ctx, k, func() {
		t.mu.Lock()
		delete(t.m, k.ID)
		t.mu.Unlock()
		cancel()
	}
}

func (t *Tasks) Set(k *Task, state, where string) {
	t.mu.Lock()
	k.State = state
	if where != "" {
		k.Where = where
	}
	t.mu.Unlock()
}

// Cancelled: the user stopped that job a moment ago.
func (t *Tasks) Cancelled(user, tag string) bool {
	if tag == "" {
		return false
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	at, ok := t.dead[user+"\x00"+tag]
	return ok && time.Since(at) < 3*time.Minute
}

// CancelTag stops every task of a phone's job (user + tag). Returns how many were running.
func (t *Tasks) CancelTag(user, tag string) int {
	t.mu.Lock()
	defer t.mu.Unlock()
	for k, at := range t.dead {
		if time.Since(at) > 10*time.Minute {
			delete(t.dead, k)
		}
	}
	t.dead[user+"\x00"+tag] = time.Now()
	n := 0
	for _, k := range t.m {
		if k.user == user && k.Tag == tag {
			k.cancel()
			n++
		}
	}
	return n
}

// Cancel stops one task (id) or, with id "", all of them.
func (t *Tasks) Cancel(id string) int {
	t.mu.Lock()
	defer t.mu.Unlock()
	n := 0
	for _, k := range t.m {
		if id == "" || k.ID == id {
			k.cancel()
			if k.Tag != "" {
				t.dead[k.user+"\x00"+k.Tag] = time.Now()
			}
			n++
		}
	}
	return n
}

func (t *Tasks) Get(id string) *Task {
	t.mu.Lock()
	defer t.mu.Unlock()
	if k := t.m[id]; k != nil {
		c := *k
		return &c
	}
	return nil
}

func (t *Tasks) List() []Task {
	t.mu.Lock()
	defer t.mu.Unlock()
	out := make([]Task, 0, len(t.m))
	for _, k := range t.m {
		out = append(out, *k)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Started < out[j].Started })
	return out
}

// ------------------------------------------------------------------ HTTP

// DELETE /v1/tasks/{tag} — a phone stops its job (all its pages being read or waiting).
func (s *Server) cancelTask(w http.ResponseWriter, r *http.Request, u *User) {
	tag := strings.TrimSpace(r.PathValue("tag"))
	if tag == "" || len(tag) > 80 {
		writeErr(w, 422, "tag", "Which job?", nil)
		return
	}
	n := s.tasks.CancelTag(u.ID, tag)
	if n > 0 {
		s.act.Add(KindAI, "info", "task.cancelled", "", A{"name": u.Name, "n": n, "by": "phone"}, 0)
	}
	writeJSON(w, 200, map[string]any{"cancelled": n})
}

func (s *Server) taskAdminRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /admin/api/tasks", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"tasks": s.tasks.List(), "jobs": s.Jobs()})
	}))
	// cancel one running page / voice task
	mux.HandleFunc("DELETE /admin/api/tasks/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		k := s.tasks.Get(r.PathValue("id"))
		if k == nil || s.tasks.Cancel(k.ID) == 0 {
			writeErr(w, 404, "not_found", "That task already finished.", nil)
			return
		}
		s.act.Add(KindAI, "info", "task.cancelled", "", A{"name": k.Who, "n": 1, "by": "admin"}, 0)
		writeJSON(w, 200, map[string]any{})
	}))
	// cancel everything: pages being read, pages waiting, book audio jobs
	mux.HandleFunc("DELETE /admin/api/tasks", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		n := s.tasks.Cancel("") + s.CancelJob(0)
		if n > 0 {
			s.act.Add(KindAI, "info", "task.cancelled", "", A{"name": "", "n": n, "by": "admin"}, 0)
		}
		writeJSON(w, 200, map[string]any{"cancelled": n})
	}))
	mux.HandleFunc("DELETE /admin/api/tts/jobs/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		id := atoi64(r.PathValue("id"))
		if id <= 0 || s.CancelJob(id) == 0 {
			writeErr(w, 404, "not_found", "That job already finished.", nil)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))
}

func atoi64(s string) int64 {
	var n int64
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0
		}
		n = n*10 + int64(c-'0')
	}
	return n
}

type taskKey struct{}

func withTask(ctx context.Context, t *Task) context.Context {
	return context.WithValue(ctx, taskKey{}, t)
}
func taskOf(ctx context.Context) *Task { t, _ := ctx.Value(taskKey{}).(*Task); return t }
