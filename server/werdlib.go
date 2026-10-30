package main

// Shared awrad: a single werd (not a whole book) shared to the server's library, for everyone on the server
// ("public") or only the sharer's family ("family"). It may carry its settings — group, days, reminders — as the
// sharer chose; whoever adds it decides which of those to keep. Progress and history are never part of it.

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
)

const werdLibSchema = `
CREATE TABLE IF NOT EXISTS shared_werds (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  search TEXT NOT NULL,
  lines TEXT NOT NULL,
  werd TEXT,
  text_only INTEGER NOT NULL DEFAULT 0,
  content_key TEXT NOT NULL,
  visibility TEXT NOT NULL DEFAULT 'public',
  family_id TEXT,
  publisher_id TEXT NOT NULL,
  publisher_name TEXT NOT NULL,
  published_at INTEGER NOT NULL,
  downloads INTEGER NOT NULL DEFAULT 0,
  hidden INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS shared_werds_list ON shared_werds(hidden, downloads DESC);
CREATE INDEX IF NOT EXISTS shared_werds_key ON shared_werds(content_key);
`

type SharedWerd struct {
	ID          string          `json:"id"`
	Title       string          `json:"title"`
	Note        string          `json:"note"`
	Lines       []Line          `json:"lines,omitempty"`
	LineCount   int             `json:"lineCount"`
	Werd        json.RawMessage `json:"werd,omitempty"` // settings as shared (group, days, colour, reminders)
	HasSettings bool            `json:"hasSettings"`
	TextOnly    bool            `json:"textOnly"`
	Visibility  string          `json:"visibility"`
	Publisher   string          `json:"publisher"`
	PublishedAt int64           `json:"publishedAt"`
	Downloads   int             `json:"downloads"`
	Mine        bool            `json:"mine"`
	Hidden      bool            `json:"hidden,omitempty"`
	publisherID string
}

type WerdQuery struct {
	Q             string
	Scope         string // "" all I can see | public | family | mine
	UserID, Fam   string
	Offset, Limit int
	Admin         bool
}

func werdKey(lines []Line, vis, fam string) string {
	var b strings.Builder
	for _, l := range lines {
		b.WriteString(bare(l.Text))
		b.WriteString("|" + strconv.Itoa(l.Count) + "\n")
	}
	h := sha256.Sum256([]byte(vis + "\x00" + fam + "\x00" + b.String()))
	return hex.EncodeToString(h[:])
}

func (s *Store) ShareWerd(ctx context.Context, u *User, w *SharedWerd) (string, error) {
	fam := ""
	if w.Visibility == "family" {
		fam = u.FamilyID.String
	}
	key := werdKey(w.Lines, w.Visibility, fam)
	var existing string
	if err := s.db.QueryRowContext(ctx, `SELECT id FROM shared_werds WHERE content_key = ? AND hidden = 0`, key).Scan(&existing); err == nil {
		return "", ErrDuplicate{existing}
	}
	lines, _ := json.Marshal(w.Lines)
	var settings any
	if len(w.Werd) > 0 {
		settings = string(w.Werd)
	}
	var famv any
	if fam != "" {
		famv = fam
	}
	id := newID("w")
	_, err := s.db.ExecContext(ctx, `INSERT INTO shared_werds(id, title, note, search, lines, werd, text_only, content_key, visibility, family_id,
		publisher_id, publisher_name, published_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		id, w.Title, w.Note, bare(w.Title+" "+w.Note+" "+firstLines(w.Lines)), string(lines), settings, w.TextOnly, key, w.Visibility, famv, u.ID, u.Name, now())
	return id, err
}

func firstLines(ls []Line) string {
	var b strings.Builder
	for i, l := range ls {
		if i >= 3 {
			break
		}
		b.WriteString(truncRunes(l.Text, 200) + " ")
	}
	return b.String()
}

const werdCols = `id, title, note, lines, werd, text_only, visibility, family_id, publisher_id, publisher_name, published_at, downloads, hidden`

func scanWerd(sc interface{ Scan(...any) error }, full bool) (*SharedWerd, string, error) {
	var w SharedWerd
	var lines string
	var settings, fam sql.NullString
	if err := sc.Scan(&w.ID, &w.Title, &w.Note, &lines, &settings, &w.TextOnly, &w.Visibility, &fam, &w.publisherID, &w.Publisher, &w.PublishedAt, &w.Downloads, &w.Hidden); err != nil {
		return nil, "", err
	}
	var ls []Line
	_ = json.Unmarshal([]byte(lines), &ls)
	w.LineCount = len(ls)
	w.HasSettings = settings.Valid && settings.String != ""
	if full {
		w.Lines = ls
		if w.HasSettings {
			w.Werd = json.RawMessage(settings.String)
		}
	} else if len(ls) > 0 {
		w.Lines = ls[:1] // a taste of it in the list
	}
	return &w, fam.String, nil
}

func (s *Store) SearchWerds(ctx context.Context, q WerdQuery) ([]SharedWerd, int, error) {
	where := "1=1"
	args := []any{}
	if !q.Admin {
		where += " AND hidden = 0 AND (visibility = 'public' OR (family_id IS NOT NULL AND family_id = ?) OR publisher_id = ?)"
		args = append(args, q.Fam, q.UserID)
	}
	switch q.Scope {
	case "public":
		where += " AND visibility = 'public'"
	case "family":
		where += " AND visibility = 'family'"
	case "mine":
		where += " AND publisher_id = ?"
		args = append(args, q.UserID)
	}
	for _, w := range strings.Fields(bare(q.Q)) {
		where += " AND search LIKE ? ESCAPE '\\'"
		args = append(args, "%"+strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(w)+"%")
	}
	var total int
	if err := s.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM shared_werds WHERE "+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.db.QueryContext(ctx, "SELECT "+werdCols+" FROM shared_werds WHERE "+where+" ORDER BY downloads DESC, published_at DESC LIMIT ? OFFSET ?",
		append(args, q.Limit, q.Offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []SharedWerd{}
	for rows.Next() {
		w, _, err := scanWerd(rows, false)
		if err != nil {
			return nil, 0, err
		}
		w.Mine = q.UserID != "" && w.publisherID == q.UserID
		out = append(out, *w)
	}
	return out, total, rows.Err()
}

// SharedWerd returns one werd if the viewer may see it (admin: any).
func (s *Store) SharedWerd(ctx context.Context, id, userID, fam string, admin, countDownload bool) (*SharedWerd, error) {
	w, wfam, err := scanWerd(s.db.QueryRowContext(ctx, "SELECT "+werdCols+" FROM shared_werds WHERE id = ?", id), true)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	mine := userID != "" && w.publisherID == userID
	if !admin && !mine && (w.Hidden || (w.Visibility == "family" && (wfam == "" || wfam != fam))) {
		return nil, ErrNotFound
	}
	w.Mine = mine
	if countDownload && !mine {
		_, _ = s.db.ExecContext(ctx, `UPDATE shared_werds SET downloads = downloads + 1 WHERE id = ?`, id)
		w.Downloads++
	}
	return w, nil
}

func (s *Store) SetWerdVisibility(ctx context.Context, id string, u *User, vis string) error {
	var fam any
	if vis == "family" {
		fam = u.FamilyID.String
	}
	res, err := s.db.ExecContext(ctx, `UPDATE shared_werds SET visibility = ?, family_id = ? WHERE id = ? AND publisher_id = ?`, vis, fam, id, u.ID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteSharedWerd withdraws a werd (its sharer, or the admin with userID "").
func (s *Store) DeleteSharedWerd(ctx context.Context, id, userID string) error {
	q, args := `DELETE FROM shared_werds WHERE id = ?`, []any{id}
	if userID != "" {
		q += ` AND publisher_id = ?`
		args = append(args, userID)
	}
	res, err := s.db.ExecContext(ctx, q, args...)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Store) HideSharedWerd(ctx context.Context, id string, hidden bool) error {
	res, err := s.db.ExecContext(ctx, `UPDATE shared_werds SET hidden = ? WHERE id = ?`, hidden, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// ------------------------------------------------------------------ HTTP

func viewer(u *User) (string, string) {
	if u == nil {
		return "", ""
	}
	return u.ID, familyOf(u)
}

// GET /v1/library/werds?q=&scope=public|family|mine&offset=&limit=
func (s *Server) searchWerds(w http.ResponseWriter, r *http.Request) {
	u := s.optionalUser(r)
	uid, fam := viewer(u)
	off, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	lim, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if lim <= 0 || lim > 100 {
		lim = 30
	}
	scope := r.URL.Query().Get("scope")
	if (scope == "mine" || scope == "family") && u == nil {
		writeJSON(w, 200, map[string]any{"werds": []SharedWerd{}, "total": 0})
		return
	}
	out, total, err := s.st.SearchWerds(r.Context(), WerdQuery{Q: r.URL.Query().Get("q"), Scope: scope, UserID: uid, Fam: fam, Offset: max(off, 0), Limit: lim})
	if err != nil {
		s.internal(w, err)
		return
	}
	writeJSON(w, 200, map[string]any{"werds": out, "total": total})
}

// GET /v1/library/werds/{id}[?add=1] — add=1 when the werd is being added (counted).
func (s *Server) getSharedWerd(w http.ResponseWriter, r *http.Request) {
	uid, fam := viewer(s.optionalUser(r))
	sw, err := s.st.SharedWerd(r.Context(), r.PathValue("id"), uid, fam, false, r.URL.Query().Get("add") == "1")
	if errors.Is(err, ErrNotFound) {
		writeErr(w, 404, "not_found", "No such werd.", nil)
		return
	}
	if err != nil {
		s.internal(w, err)
		return
	}
	writeJSON(w, 200, sw)
}

// POST /v1/library/werds {"title","note","lines":[{text,count}],"werd":{settings},"textOnly","visibility":"public"|"family"}
func (s *Server) shareWerd(w http.ResponseWriter, r *http.Request, u *User) {
	if !s.limit(w, "sharewerd", u.ID, 60, 20) {
		return
	}
	var in SharedWerd
	if !s.readJSON(w, r, &in) {
		return
	}
	in.Title = truncRunes(strings.TrimSpace(in.Title), 200)
	in.Note = truncRunes(strings.TrimSpace(in.Note), 2000)
	lines := make([]Line, 0, len(in.Lines))
	for _, l := range in.Lines {
		if t := strings.TrimSpace(l.Text); t != "" {
			lines = append(lines, Line{Text: truncRunes(t, 4000), Count: max(l.Count, 1)})
		}
	}
	switch {
	case in.Title == "":
		writeErr(w, 422, "title", "The werd needs a title.", nil)
		return
	case len(lines) == 0 || len(lines) > 500:
		writeErr(w, 422, "lines", "A werd needs between 1 and 500 lines.", nil)
		return
	case len(in.Werd) > 64<<10:
		writeErr(w, 413, "too_large", "The werd's settings are too large.", nil)
		return
	}
	if len(in.Werd) > 0 && (!json.Valid(in.Werd) || string(in.Werd) == "null") {
		in.Werd = nil
	}
	in.Lines = lines
	if in.Visibility != "family" {
		in.Visibility = "public"
	} else if !u.FamilyID.Valid {
		writeErr(w, 422, "no_family", "Join a family to share with it.", nil)
		return
	}
	id, err := s.st.ShareWerd(r.Context(), u, &in)
	var dup ErrDuplicate
	if errors.As(err, &dup) {
		writeErr(w, 409, "duplicate", "This werd is already in the library.", map[string]any{"id": dup.ID})
		return
	}
	if err != nil {
		s.internal(w, err)
		return
	}
	s.act.Add(KindLibrary, "info", "werd.shared", "", A{"name": u.Name, "title": in.Title, "lines": len(lines), "visibility": in.Visibility}, 0)
	writeJSON(w, 201, map[string]any{"id": id})
}

// PUT /v1/library/werds/{id} {"visibility"} — the sharer moves it between public and family.
func (s *Server) moveSharedWerd(w http.ResponseWriter, r *http.Request, u *User) {
	var in struct{ Visibility string }
	if !s.readJSON(w, r, &in) {
		return
	}
	if in.Visibility != "public" && in.Visibility != "family" {
		writeErr(w, 422, "visibility", "visibility is public or family.", nil)
		return
	}
	if in.Visibility == "family" && !u.FamilyID.Valid {
		writeErr(w, 422, "no_family", "Join a family to share with it.", nil)
		return
	}
	if err := s.st.SetWerdVisibility(r.Context(), r.PathValue("id"), u, in.Visibility); err != nil {
		if errors.Is(err, ErrNotFound) {
			writeErr(w, 404, "not_found", "Only the person who shared it can move it.", nil)
			return
		}
		s.internal(w, err)
		return
	}
	writeJSON(w, 200, map[string]any{})
}

// DELETE /v1/library/werds/{id} — the sharer withdraws it.
func (s *Server) withdrawSharedWerd(w http.ResponseWriter, r *http.Request, u *User) {
	if err := s.st.DeleteSharedWerd(r.Context(), r.PathValue("id"), u.ID); err != nil {
		if errors.Is(err, ErrNotFound) {
			writeErr(w, 404, "not_found", "Only the person who shared it can withdraw it.", nil)
			return
		}
		s.internal(w, err)
		return
	}
	writeJSON(w, 200, map[string]any{})
}

func (s *Server) werdLibRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/library/werds", s.searchWerds)
	mux.HandleFunc("GET /v1/library/werds/{id}", s.getSharedWerd)
	mux.HandleFunc("POST /v1/library/werds", s.auth(s.shareWerd))
	mux.HandleFunc("PUT /v1/library/werds/{id}", s.auth(s.moveSharedWerd))
	mux.HandleFunc("DELETE /v1/library/werds/{id}", s.auth(s.withdrawSharedWerd))

	mux.HandleFunc("GET /admin/api/werds", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		out, total, err := s.st.SearchWerds(r.Context(), WerdQuery{Q: r.URL.Query().Get("q"), Admin: true, Limit: 200})
		if err != nil {
			s.internal(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{"werds": out, "total": total})
	}))
	mux.HandleFunc("GET /admin/api/werds/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		sw, err := s.st.SharedWerd(r.Context(), r.PathValue("id"), "", "", true, false)
		if err != nil {
			writeErr(w, 404, "not_found", "No such werd.", nil)
			return
		}
		writeJSON(w, 200, sw)
	}))
	mux.HandleFunc("POST /admin/api/werds/{id}/hide", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Hidden bool }
		if !s.readJSON(w, r, &in) {
			return
		}
		if err := s.st.HideSharedWerd(r.Context(), r.PathValue("id"), in.Hidden); err != nil {
			writeErr(w, 404, "not_found", "No such werd.", nil)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))
	mux.HandleFunc("DELETE /admin/api/werds/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.st.DeleteSharedWerd(r.Context(), r.PathValue("id"), ""); err != nil {
			writeErr(w, 404, "not_found", "No such werd.", nil)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))
}
