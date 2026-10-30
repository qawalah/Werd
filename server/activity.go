package main

// The activity log the dashboard shows: what phones, families, the library, voices and the AI reader did, newest first.
// Events are kept in SQLite (the last few thousand) so a restart doesn't empty the dashboard. Each event has a code and
// arguments, so the dashboard can word it in Arabic or English.

import (
	"context"
	"encoding/json"
	"sync"
	"time"
)

const activitySchema = `
CREATE TABLE IF NOT EXISTS activity (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  at INTEGER NOT NULL,
  kind TEXT NOT NULL,
  level TEXT NOT NULL DEFAULT 'info',
  code TEXT NOT NULL,
  args TEXT NOT NULL DEFAULT '{}',
  actor TEXT NOT NULL DEFAULT '',
  count INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS activity_at ON activity(at);
`

// Kinds (the dashboard's filter chips).
const (
	KindPhone   = "phone"   // accounts, sync
	KindFamily  = "family"  // families, roles, werds sent
	KindLibrary = "library" // books, reports
	KindVoice   = "voice"   // clips, shared voices, book audio jobs
	KindAI      = "ai"      // page reading, model downloads
	KindServer  = "server"  // start, admin changes
)

type Event struct {
	ID    int64          `json:"id"`
	At    int64          `json:"at"`
	Kind  string         `json:"kind"`
	Level string         `json:"level"` // info | warn | error
	Code  string         `json:"code"`
	Args  map[string]any `json:"args"`
	Count int            `json:"count"` // repeated events folded into one row
}

type Activity struct {
	st *Store
	mu sync.Mutex
	// last row per (code, actor) for folding repeats (e.g. a phone syncing every few minutes)
	last map[string]struct {
		id int64
		at int64
	}
}

func NewActivity(st *Store) *Activity {
	_, _ = st.db.Exec(activitySchema)
	return &Activity{st: st, last: map[string]struct {
		id int64
		at int64
	}{}}
}

// Add records an event. With fold > 0, a repeat of the same code by the same actor within fold is merged into the
// previous row (its time moves forward and its count goes up) instead of filling the log.
func (a *Activity) Add(kind, level, code, actor string, args map[string]any, fold time.Duration) {
	if a == nil {
		return
	}
	if args == nil {
		args = map[string]any{}
	}
	b, _ := json.Marshal(args)
	t := now()
	a.mu.Lock()
	defer a.mu.Unlock()
	key := code + "|" + actor
	if fold > 0 && actor != "" {
		if p, ok := a.last[key]; ok && t-p.at < fold.Milliseconds() {
			if _, err := a.st.db.Exec(`UPDATE activity SET at = ?, count = count + 1, args = ? WHERE id = ?`, t, string(b), p.id); err == nil {
				a.last[key] = struct {
					id int64
					at int64
				}{p.id, t}
				return
			}
		}
	}
	r, err := a.st.db.Exec(`INSERT INTO activity(at, kind, level, code, args, actor) VALUES (?,?,?,?,?,?)`, t, kind, level, code, string(b), actor)
	if err != nil {
		return
	}
	id, _ := r.LastInsertId()
	if actor != "" {
		a.last[key] = struct {
			id int64
			at int64
		}{id, t}
	}
	if id%200 == 0 { // keep the last 5000
		_, _ = a.st.db.Exec(`DELETE FROM activity WHERE id <= ?`, id-5000)
	}
}

// List returns events newest first; kind "" = all, before = 0 = from now.
func (a *Activity) List(ctx context.Context, kind string, before int64, limit int) ([]Event, error) {
	if limit <= 0 || limit > 300 {
		limit = 100
	}
	q := `SELECT id, at, kind, level, code, args, count FROM activity WHERE (? = '' OR kind = ? OR (? = 'error' AND level = 'error'))`
	args := []any{kind, kind, kind}
	if before > 0 {
		q += ` AND id < ?`
		args = append(args, before)
	}
	q += ` ORDER BY id DESC LIMIT ?`
	args = append(args, limit)
	rows, err := a.st.db.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Event{}
	for rows.Next() {
		var e Event
		var raw string
		if err := rows.Scan(&e.ID, &e.At, &e.Kind, &e.Level, &e.Code, &raw, &e.Count); err != nil {
			return nil, err
		}
		_ = json.Unmarshal([]byte(raw), &e.Args)
		out = append(out, e)
	}
	return out, rows.Err()
}

// Counts per kind over the last 24 hours (errors counted apart), for the filter chips.
func (a *Activity) Counts(ctx context.Context) map[string]int {
	out := map[string]int{}
	rows, err := a.st.db.QueryContext(ctx, `SELECT kind, level, SUM(count) FROM activity WHERE at > ? GROUP BY kind, level`, now()-24*3600_000)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var k, l string
		var n int
		if rows.Scan(&k, &l, &n) == nil {
			out[k] += n
			out["all"] += n
			if l == "error" {
				out["error"] += n
			}
		}
	}
	return out
}

// userName finds a person's display name for an event ("" when unknown).
func (s *Server) userName(ctx context.Context, id string) string {
	var n string
	_ = s.st.db.QueryRowContext(ctx, `SELECT name FROM users WHERE id = ?`, id).Scan(&n)
	return n
}

func (s *Server) bookTitle(ctx context.Context, id string) string {
	var t string
	_ = s.st.db.QueryRowContext(ctx, `SELECT title FROM books WHERE id = ?`, id).Scan(&t)
	return t
}

func (s *Server) familyName(ctx context.Context, id string) string {
	var t string
	_ = s.st.db.QueryRowContext(ctx, `SELECT name FROM families WHERE id = ?`, id).Scan(&t)
	return t
}

type A = map[string]any
