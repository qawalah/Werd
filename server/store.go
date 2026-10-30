package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"

	_ "modernc.org/sqlite"
)

// Store is the server's SQLite database. One connection: SQLite serialises writes anyway, and this
// avoids "database is locked" errors without any tuning. Plenty for a family / community server.
type Store struct{ db *sql.DB }

var ErrNotFound = errors.New("not found")

const schema = `
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL DEFAULT '',
  family_id TEXT REFERENCES families(id) ON DELETE SET NULL,
  sharing INTEGER NOT NULL DEFAULT 1,
  progress TEXT,
  created_at INTEGER NOT NULL,
  seen_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS users_family ON users(family_id);
CREATE TABLE IF NOT EXISTS families (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  code TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS books (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  author TEXT NOT NULL DEFAULT '',
  search TEXT NOT NULL,
  content_key TEXT NOT NULL UNIQUE,
  doc TEXT NOT NULL,
  items INTEGER NOT NULL,
  checked INTEGER NOT NULL,
  downloads INTEGER NOT NULL DEFAULT 0,
  publisher_id TEXT NOT NULL,
  publisher_name TEXT NOT NULL,
  published_at INTEGER NOT NULL,
  hidden INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS books_downloads ON books(hidden, downloads DESC);
CREATE TABLE IF NOT EXISTS reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL,
  reason TEXT NOT NULL,
  item INTEGER,
  created_at INTEGER NOT NULL,
  resolved INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS inbox (
  id TEXT PRIMARY KEY,
  to_user TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  from_user TEXT NOT NULL,
  from_name TEXT NOT NULL,
  title TEXT NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  lines TEXT NOT NULL,
  sent_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS inbox_to ON inbox(to_user, sent_at);
CREATE TABLE IF NOT EXISTS progress_days (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day TEXT NOT NULL,
  data TEXT NOT NULL,
  PRIMARY KEY (user_id, day)
);
`

// columns added after 1.0.0; added to older databases at start-up
var migrations = []struct{ table, column, def string }{
	{"users", "role", "TEXT NOT NULL DEFAULT 'child'"},
	{"books", "visibility", "TEXT NOT NULL DEFAULT 'public'"},
	{"books", "family_id", "TEXT"},
	{"books", "madhhab", "TEXT NOT NULL DEFAULT ''"},
	{"inbox", "werd", "TEXT"},
	{"inbox", "auto_add", "INTEGER NOT NULL DEFAULT 0"},
}

func OpenStore(path string) (*Store, error) {
	dsn := "file:" + path + "?_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=busy_timeout(10000)&_pragma=synchronous(NORMAL)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema + ttsSchema + werdLibSchema + sessionSchema); err != nil {
		db.Close()
		return nil, fmt.Errorf("database: %w", err)
	}
	for _, m := range migrations {
		var n int
		if err := db.QueryRow(`SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?`, m.table, m.column).Scan(&n); err != nil {
			db.Close()
			return nil, err
		}
		if n == 0 {
			if _, err := db.Exec("ALTER TABLE " + m.table + " ADD COLUMN " + m.column + " " + m.def); err != nil {
				db.Close()
				return nil, fmt.Errorf("database upgrade: %w", err)
			}
		}
	}
	// families from before roles: the earliest member becomes the parent
	if _, err := db.Exec(`UPDATE users SET role = 'parent' WHERE id IN (
		SELECT (SELECT u.id FROM users u WHERE u.family_id = f.id ORDER BY u.created_at LIMIT 1) FROM families f
		WHERE NOT EXISTS (SELECT 1 FROM users p WHERE p.family_id = f.id AND p.role = 'parent'))`); err != nil {
		db.Close()
		return nil, err
	}
	if _, err := db.Exec(accountIndexes); err != nil {
		db.Close()
		return nil, fmt.Errorf("database upgrade: %w", err)
	}
	if _, err := db.Exec(`CREATE INDEX IF NOT EXISTS books_family ON books(family_id)`); err != nil {
		db.Close()
		return nil, err
	}
	if _, err := db.Exec(`INSERT OR REPLACE INTO meta(key, value) VALUES ('schema', '2')`); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func now() int64 { return time.Now().UnixMilli() }

// newID returns prefix_ + 16 random hex chars.
func newID(prefix string) string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return prefix + "_" + hex.EncodeToString(b)
}

func newToken() string {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func hashToken(t string) string {
	h := sha256.Sum256([]byte(t))
	return hex.EncodeToString(h[:])
}

// Invite codes avoid look-alike characters (0/O, 1/I/L) so they can be read out over the phone.
const codeAlphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

func newInviteCode() string {
	b := make([]byte, 6)
	_, _ = rand.Read(b)
	out := make([]byte, 0, 7)
	for i, v := range b {
		if i == 3 {
			out = append(out, '-')
		}
		out = append(out, codeAlphabet[int(v)%len(codeAlphabet)])
	}
	return string(out)
}

// normalizeCode accepts "k7q4mx", "K7Q 4MX", "k7q-4mx".
func normalizeCode(c string) string {
	var b strings.Builder
	for _, r := range strings.ToUpper(c) {
		if strings.ContainsRune(codeAlphabet, r) {
			b.WriteRune(r)
		}
	}
	s := b.String()
	if len(s) == 6 {
		return s[:3] + "-" + s[3:]
	}
	return s
}

// bare makes Arabic text comparable: no tashkeel or tatweel, one form of alef, ة→ه, ى→ي, lower case.
// Same rules as the app's OfflineExtract.bare.
func bare(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch {
		case r >= 0x064B && r <= 0x065F, r == 0x0670, r == 0x0640:
			continue
		case r == 'أ' || r == 'إ' || r == 'آ' || r == 'ٱ':
			b.WriteRune('ا')
		case r == 'ة':
			b.WriteRune('ه')
		case r == 'ى':
			b.WriteRune('ي')
		default:
			b.WriteRune(unicode.ToLower(r))
		}
	}
	return strings.Join(strings.Fields(b.String()), " ")
}

func truncRunes(s string, n int) string {
	s = strings.TrimSpace(s)
	r := []rune(s)
	if len(r) > n {
		return string(r[:n])
	}
	return s
}

// ------------------------------------------------------------------ users

type User struct {
	ID       string
	Name     string
	FamilyID sql.NullString
	Sharing  bool
	Progress sql.NullString
	Role     string // parent or child (meaningful inside a family)
}

func (u *User) IsParent() bool { return u.FamilyID.Valid && u.Role == "parent" }

func (s *Store) CreateUser(ctx context.Context, name string) (id, token string, err error) {
	id, token = newID("u"), newToken()
	t := now()
	_, err = s.db.ExecContext(ctx, `INSERT INTO users(id, token_hash, name, created_at, seen_at) VALUES (?,?,?,?,?)`,
		id, hashToken(token), truncRunes(name, 40), t, t)
	return
}

func (s *Store) UserByToken(ctx context.Context, token string) (*User, error) {
	u := &User{}
	err := s.db.QueryRowContext(ctx, `SELECT id, name, family_id, sharing, progress, role FROM users
		WHERE token_hash = ?1 OR id = (SELECT user_id FROM sessions WHERE token_hash = ?1)`, hashToken(token)).
		Scan(&u.ID, &u.Name, &u.FamilyID, &u.Sharing, &u.Progress, &u.Role)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err == nil {
		_, _ = s.db.ExecContext(ctx, `UPDATE users SET seen_at = ? WHERE id = ? AND seen_at < ?`, now(), u.ID, now()-3600_000)
	}
	return u, err
}

func (s *Store) RenameUser(ctx context.Context, id, name string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE users SET name = ? WHERE id = ?`, truncRunes(name, 40), id)
	return err
}

// SetProgress stores today's summary and keeps it in the day-by-day history; not sharing drops both.
func (s *Store) SetProgress(ctx context.Context, id string, sharing bool, day string, progress []byte) error {
	var p any
	if sharing && progress != nil {
		p = string(progress)
	}
	if _, err := s.db.ExecContext(ctx, `UPDATE users SET sharing = ?, progress = ? WHERE id = ?`, sharing, p, id); err != nil {
		return err
	}
	if !sharing {
		_, err := s.db.ExecContext(ctx, `DELETE FROM progress_days WHERE user_id = ?`, id)
		return err
	}
	if progress == nil || day == "" {
		return nil
	}
	_, err := s.db.ExecContext(ctx, `INSERT INTO progress_days(user_id, day, data) VALUES (?,?,?)
		ON CONFLICT(user_id, day) DO UPDATE SET data = excluded.data`, id, day, string(progress))
	return err
}

// History returns up to days of target's daily summaries, newest first. A parent may read any member of the
// family who shares progress; everyone may read their own.
func (s *Store) History(ctx context.Context, viewer *User, targetID string, days int) ([]json.RawMessage, error) {
	if viewer.ID != targetID {
		var fam sql.NullString
		var sharing bool
		err := s.db.QueryRowContext(ctx, `SELECT family_id, sharing FROM users WHERE id = ?`, targetID).Scan(&fam, &sharing)
		if errors.Is(err, sql.ErrNoRows) || !viewer.IsParent() || !fam.Valid || fam.String != viewer.FamilyID.String {
			return nil, ErrNotFound
		}
		if err != nil {
			return nil, err
		}
		if !sharing {
			return []json.RawMessage{}, nil
		}
	}
	rows, err := s.db.QueryContext(ctx, `SELECT data FROM progress_days WHERE user_id = ? ORDER BY day DESC LIMIT ?`, targetID, days)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []json.RawMessage{}
	for rows.Next() {
		var d string
		if err := rows.Scan(&d); err != nil {
			return nil, err
		}
		out = append(out, json.RawMessage(d))
	}
	return out, rows.Err()
}

// ------------------------------------------------------------------ library

type BookSummary struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Author      string `json:"author"`
	Language    string `json:"language"`
	Items       int    `json:"items"`
	Checked     int    `json:"checked"`
	Downloads   int    `json:"downloads"`
	Publisher   string `json:"publisher"`
	PublishedAt int64  `json:"publishedAt"`
	Visibility  string `json:"visibility"` // public or family
	Madhhab     string `json:"madhhab"`
	Hidden      bool   `json:"hidden,omitempty"`
	Reports     int    `json:"reports,omitempty"`
	FamilyID    string `json:"-"`
}

// BookQuery filters the library. FamilyID adds that family's private books; Admin sees everything.
type BookQuery struct {
	Q, Madhhab, FamilyID string
	Offset, Limit        int
	Admin                bool
}

func (s *Store) SearchBooks(ctx context.Context, bq BookQuery) ([]BookSummary, int, error) {
	q, offset, limit := bq.Q, bq.Offset, bq.Limit
	where := "1=1"
	args := []any{}
	if !bq.Admin {
		where += " AND hidden = 0 AND (visibility = 'public' OR family_id = ?)"
		args = append(args, bq.FamilyID)
	}
	if bq.Madhhab != "" {
		where += " AND madhhab = ?"
		args = append(args, bq.Madhhab)
	}
	if t := bare(q); t != "" {
		for _, w := range strings.Fields(t) {
			where += " AND search LIKE ? ESCAPE '\\'"
			args = append(args, "%"+strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(w)+"%")
		}
	}
	var total int
	if err := s.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM books WHERE "+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.db.QueryContext(ctx, `SELECT id, title, author, json_extract(doc, '$.language'), items, checked, downloads, publisher_name, published_at, visibility, madhhab, hidden,
		(SELECT COUNT(*) FROM reports r WHERE r.book_id = books.id AND r.resolved = 0)
		FROM books WHERE `+where+` ORDER BY downloads DESC, published_at DESC LIMIT ? OFFSET ?`, append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []BookSummary{}
	for rows.Next() {
		var b BookSummary
		var lang sql.NullString
		if err := rows.Scan(&b.ID, &b.Title, &b.Author, &lang, &b.Items, &b.Checked, &b.Downloads, &b.Publisher, &b.PublishedAt, &b.Visibility, &b.Madhhab, &b.Hidden, &b.Reports); err != nil {
			return nil, 0, err
		}
		b.Language = lang.String
		if b.Language == "" {
			b.Language = "ar"
		}
		out = append(out, b)
	}
	return out, total, rows.Err()
}

// Book returns the stored document and its summary. countDownload adds one to the download count.
// A family book is only found for that family (viewerFamily), or by the admin (includeHidden).
func (s *Store) Book(ctx context.Context, id string, countDownload, includeHidden bool, viewerFamily string) (map[string]any, *BookSummary, error) {
	var doc string
	var fam sql.NullString
	b := &BookSummary{ID: id}
	err := s.db.QueryRowContext(ctx, `SELECT doc, title, author, items, checked, downloads, publisher_name, published_at, visibility, madhhab, family_id, hidden FROM books WHERE id = ?`, id).
		Scan(&doc, &b.Title, &b.Author, &b.Items, &b.Checked, &b.Downloads, &b.Publisher, &b.PublishedAt, &b.Visibility, &b.Madhhab, &fam, &b.Hidden)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && !includeHidden && (b.Hidden || (b.Visibility == "family" && fam.String != viewerFamily))) {
		return nil, nil, ErrNotFound
	}
	if err != nil {
		return nil, nil, err
	}
	if countDownload {
		_, _ = s.db.ExecContext(ctx, `UPDATE books SET downloads = downloads + 1 WHERE id = ?`, id)
		b.Downloads++
	}
	m := map[string]any{}
	if err := json.Unmarshal([]byte(doc), &m); err != nil {
		return nil, nil, err
	}
	return m, b, nil
}

// ErrDuplicate carries the id of the book already published with the same content.
type ErrDuplicate struct{ ID string }

func (e ErrDuplicate) Error() string { return "duplicate of " + e.ID }

func (s *Store) PublishBook(ctx context.Context, u *User, doc []byte, title, author, contentKey string, items, checked int, visibility, madhhab string) (string, error) {
	var existing string
	err := s.db.QueryRowContext(ctx, `SELECT id FROM books WHERE content_key = ?`, contentKey).Scan(&existing)
	if err == nil {
		return "", ErrDuplicate{existing}
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", err
	}
	id := newID("b")
	var fam any
	if visibility == "family" {
		fam = u.FamilyID.String
	}
	_, err = s.db.ExecContext(ctx, `INSERT INTO books(id, title, author, search, content_key, doc, items, checked, publisher_id, publisher_name, published_at, visibility, family_id, madhhab)
		VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, id, title, author, bare(title+" "+author), contentKey, string(doc), items, checked, u.ID, u.Name, now(), visibility, fam, madhhab)
	return id, err
}

func (s *Store) Report(ctx context.Context, bookID, userID, reason string, item *int) error {
	var x int
	if err := s.db.QueryRowContext(ctx, `SELECT 1 FROM books WHERE id = ? AND hidden = 0`, bookID).Scan(&x); err != nil {
		return ErrNotFound
	}
	var it any
	if item != nil {
		it = *item
	}
	_, err := s.db.ExecContext(ctx, `INSERT INTO reports(book_id, user_id, reason, item, created_at) VALUES (?,?,?,?,?)`,
		bookID, userID, truncRunes(reason, 1000), it, now())
	return err
}

// ------------------------------------------------------------------ families

type Family struct {
	ID, Name, Code string
}

var ErrConflict = errors.New("conflict")

func (s *Store) CreateFamily(ctx context.Context, u *User, name string) (*Family, error) {
	if u.FamilyID.Valid {
		return nil, ErrConflict
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	f := &Family{ID: newID("f"), Name: truncRunes(name, 60)}
	for i := 0; ; i++ {
		f.Code = newInviteCode()
		_, err = tx.ExecContext(ctx, `INSERT INTO families(id, name, code, created_at) VALUES (?,?,?,?)`, f.ID, f.Name, f.Code, now())
		if err == nil || i > 5 {
			break
		}
	}
	if err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE users SET family_id = ?, role = 'parent' WHERE id = ?`, f.ID, u.ID); err != nil {
		return nil, err
	}
	return f, tx.Commit()
}

func (s *Store) JoinFamily(ctx context.Context, u *User, code string) (*Family, error) {
	f := &Family{}
	err := s.db.QueryRowContext(ctx, `SELECT id, name, code FROM families WHERE code = ?`, normalizeCode(code)).Scan(&f.ID, &f.Name, &f.Code)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if u.FamilyID.Valid {
		return nil, ErrConflict
	}
	_, err = s.db.ExecContext(ctx, `UPDATE users SET family_id = ?, role = 'child' WHERE id = ?`, f.ID, u.ID)
	return f, err
}

func (s *Store) FamilyOf(ctx context.Context, u *User) (*Family, error) {
	if !u.FamilyID.Valid {
		return nil, ErrNotFound
	}
	f := &Family{}
	err := s.db.QueryRowContext(ctx, `SELECT id, name, code FROM families WHERE id = ?`, u.FamilyID.String).Scan(&f.ID, &f.Name, &f.Code)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return f, err
}

type MemberRow struct {
	ID, Name, Role string
	Sharing        bool
	Progress       sql.NullString
	SeenAt         int64
}

func (s *Store) Members(ctx context.Context, familyID string) ([]MemberRow, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, name, role, sharing, progress, seen_at FROM users WHERE family_id = ? ORDER BY role = 'child', created_at`, familyID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MemberRow
	for rows.Next() {
		var m MemberRow
		if err := rows.Scan(&m.ID, &m.Name, &m.Role, &m.Sharing, &m.Progress, &m.SeenAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// LeaveFamily removes the user; the last member leaving deletes the family.
func (s *Store) LeaveFamily(ctx context.Context, u *User) error {
	if !u.FamilyID.Valid {
		return ErrNotFound
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := removeFromFamily(ctx, tx, u.ID, u.FamilyID.String); err != nil {
		return err
	}
	return tx.Commit()
}

// removeFromFamily takes userID out of familyID; a family left without a parent gets its earliest member
// as parent, and an empty family is deleted.
func removeFromFamily(ctx context.Context, tx *sql.Tx, userID, familyID string) error {
	if _, err := tx.ExecContext(ctx, `UPDATE users SET family_id = NULL, role = 'child' WHERE id = ?`, userID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE users SET role = 'parent' WHERE id = (SELECT id FROM users WHERE family_id = ? ORDER BY created_at LIMIT 1)
		AND NOT EXISTS (SELECT 1 FROM users WHERE family_id = ? AND role = 'parent')`, familyID, familyID); err != nil {
		return err
	}
	_, err := tx.ExecContext(ctx, `DELETE FROM families WHERE id = ? AND NOT EXISTS (SELECT 1 FROM users WHERE family_id = ?)`, familyID, familyID)
	return err
}

var ErrForbidden = errors.New("forbidden")

// familyTarget checks that actor is a parent and target is another member of the same family.
func (s *Store) familyTarget(ctx context.Context, tx *sql.Tx, actor *User, targetID string) (role string, err error) {
	if !actor.IsParent() {
		return "", ErrForbidden
	}
	var fam sql.NullString
	err = tx.QueryRowContext(ctx, `SELECT family_id, role FROM users WHERE id = ?`, targetID).Scan(&fam, &role)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && (!fam.Valid || fam.String != actor.FamilyID.String)) {
		return "", ErrNotFound
	}
	return role, err
}

// SetRole lets a parent make another member a parent or a child. The family always keeps one parent.
func (s *Store) SetRole(ctx context.Context, actor *User, targetID, role string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	cur, err := s.familyTarget(ctx, tx, actor, targetID)
	if err != nil {
		return err
	}
	if cur == "parent" && role == "child" {
		var parents int
		_ = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM users WHERE family_id = ? AND role = 'parent'`, actor.FamilyID.String).Scan(&parents)
		if parents <= 1 {
			return ErrConflict
		}
	}
	if _, err := tx.ExecContext(ctx, `UPDATE users SET role = ? WHERE id = ?`, role, targetID); err != nil {
		return err
	}
	return tx.Commit()
}

// RemoveMember lets a parent take someone out of the family.
func (s *Store) RemoveMember(ctx context.Context, actor *User, targetID string) error {
	if targetID == actor.ID {
		return ErrConflict
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := s.familyTarget(ctx, tx, actor, targetID); err != nil {
		return err
	}
	if err := removeFromFamily(ctx, tx, targetID, actor.FamilyID.String); err != nil {
		return err
	}
	_, _ = tx.ExecContext(ctx, `DELETE FROM inbox WHERE to_user = ? AND from_user IN (SELECT id FROM users WHERE family_id = ?)`, targetID, actor.FamilyID.String)
	return tx.Commit()
}

// ------------------------------------------------------------------ inbox

type Line struct {
	Text  string `json:"text"`
	Count int    `json:"count"`
}

func (s *Store) SendWerd(ctx context.Context, from *User, toID, title, note string, lines []Line, werd json.RawMessage, autoAdd bool) (string, error) {
	var fam sql.NullString
	var role string
	err := s.db.QueryRowContext(ctx, `SELECT family_id, role FROM users WHERE id = ?`, toID).Scan(&fam, &role)
	if errors.Is(err, sql.ErrNoRows) || !from.FamilyID.Valid || !fam.Valid || fam.String != from.FamilyID.String {
		return "", ErrNotFound
	}
	if err != nil {
		return "", err
	}
	b, _ := json.Marshal(lines)
	id := newID("w")
	var wd any
	if len(werd) > 0 && string(werd) != "null" {
		wd = string(werd)
	}
	// only a parent can put a werd straight on a child's list
	auto := autoAdd && from.IsParent() && role == "child"
	_, err = s.db.ExecContext(ctx, `INSERT INTO inbox(id, to_user, from_user, from_name, title, note, lines, sent_at, werd, auto_add) VALUES (?,?,?,?,?,?,?,?,?,?)`,
		id, toID, from.ID, from.Name, truncRunes(title, 200), truncRunes(note, 300), string(b), now(), wd, auto)
	return id, err
}

type InboxItem struct {
	ID      string          `json:"id"`
	From    Person          `json:"from"`
	Title   string          `json:"title"`
	Note    string          `json:"note"`
	Lines   []Line          `json:"lines"`
	SentAt  int64           `json:"sentAt"`
	Werd    json.RawMessage `json:"werd,omitempty"`
	AutoAdd bool            `json:"autoAdd"`
}

type Person struct {
	UserID      string `json:"userId"`
	DisplayName string `json:"displayName"`
}

func (s *Store) Inbox(ctx context.Context, userID string) ([]InboxItem, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, from_user, from_name, title, note, lines, sent_at, werd, auto_add FROM inbox WHERE to_user = ? ORDER BY sent_at`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []InboxItem{}
	for rows.Next() {
		var it InboxItem
		var lines string
		var werd sql.NullString
		if err := rows.Scan(&it.ID, &it.From.UserID, &it.From.DisplayName, &it.Title, &it.Note, &lines, &it.SentAt, &werd, &it.AutoAdd); err != nil {
			return nil, err
		}
		if werd.Valid {
			it.Werd = json.RawMessage(werd.String)
		}
		_ = json.Unmarshal([]byte(lines), &it.Lines)
		out = append(out, it)
	}
	return out, rows.Err()
}

func (s *Store) AckInbox(ctx context.Context, userID, id string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM inbox WHERE id = ? AND to_user = ?`, id, userID)
	return err
}

// ------------------------------------------------------------------ admin

type Stats struct {
	Users        int `json:"users"`
	ActiveWeek   int `json:"activeLastWeek"`
	Families     int `json:"families"`
	Books        int `json:"books"`
	HiddenBooks  int `json:"hiddenBooks"`
	Downloads    int `json:"downloads"`
	OpenReports  int `json:"openReports"`
	PendingInbox int `json:"pendingInbox"`
}

func (s *Store) Stats(ctx context.Context) (Stats, error) {
	var st Stats
	err := s.db.QueryRowContext(ctx, `SELECT
		(SELECT COUNT(*) FROM users), (SELECT COUNT(*) FROM users WHERE seen_at > ?), (SELECT COUNT(*) FROM families),
		(SELECT COUNT(*) FROM books WHERE hidden = 0), (SELECT COUNT(*) FROM books WHERE hidden = 1),
		(SELECT COALESCE(SUM(downloads), 0) FROM books), (SELECT COUNT(*) FROM reports WHERE resolved = 0), (SELECT COUNT(*) FROM inbox)`,
		now()-7*24*3600_000).Scan(&st.Users, &st.ActiveWeek, &st.Families, &st.Books, &st.HiddenBooks, &st.Downloads, &st.OpenReports, &st.PendingInbox)
	return st, err
}

type ReportRow struct {
	ID        int64  `json:"id"`
	BookID    string `json:"bookId"`
	BookTitle string `json:"bookTitle"`
	Reason    string `json:"reason"`
	Item      *int   `json:"item,omitempty"`
	ItemText  string `json:"itemText,omitempty"`
	CreatedAt int64  `json:"createdAt"`
}

func (s *Store) OpenReports(ctx context.Context) ([]ReportRow, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT r.id, r.book_id, b.title, r.reason, r.item, r.created_at,
		CASE WHEN r.item IS NULL THEN '' ELSE COALESCE(json_extract(b.doc, '$.items[' || r.item || '].text'), '') END
		FROM reports r JOIN books b ON b.id = r.book_id WHERE r.resolved = 0 ORDER BY r.created_at DESC LIMIT 500`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ReportRow{}
	for rows.Next() {
		var r ReportRow
		var item sql.NullInt64
		if err := rows.Scan(&r.ID, &r.BookID, &r.BookTitle, &r.Reason, &item, &r.CreatedAt, &r.ItemText); err != nil {
			return nil, err
		}
		if item.Valid {
			v := int(item.Int64)
			r.Item = &v
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func (s *Store) ResolveReport(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx, `UPDATE reports SET resolved = 1 WHERE id = ?`, id)
	return err
}

func (s *Store) SetBookHidden(ctx context.Context, id string, hidden bool) error {
	r, err := s.db.ExecContext(ctx, `UPDATE books SET hidden = ? WHERE id = ?`, hidden, id)
	if err == nil {
		if n, _ := r.RowsAffected(); n == 0 {
			return ErrNotFound
		}
	}
	return err
}

func (s *Store) DeleteBook(ctx context.Context, id string) error {
	r, err := s.db.ExecContext(ctx, `DELETE FROM books WHERE id = ?`, id)
	if err == nil {
		if n, _ := r.RowsAffected(); n == 0 {
			return ErrNotFound
		}
	}
	return err
}

type AdminMember struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Role        string `json:"role"`
	SeenAt      int64  `json:"seenAt"`
	Shares      bool   `json:"sharing"`
	CreatedAt   int64  `json:"createdAt,omitempty"`
	HasPassword bool   `json:"hasPassword"`
	Family      string `json:"family,omitempty"`
	Shared      int    `json:"shared,omitempty"` // books and awrad this account shared to the library
}

type AdminFamily struct {
	ID      string        `json:"id"`
	Name    string        `json:"name"`
	Code    string        `json:"code"`
	Members []AdminMember `json:"members"`
}

// Families lists every family with its members, then the accounts not in a family as one more group.
func (s *Store) Families(ctx context.Context) ([]AdminFamily, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT COALESCE(f.id, ''), COALESCE(f.name, ''), COALESCE(f.code, ''), u.id, u.name, u.role, u.seen_at, u.sharing,
		u.created_at, u.pass_hash IS NOT NULL FROM users u LEFT JOIN families f ON f.id = u.family_id ORDER BY f.id IS NULL, f.created_at, u.role = 'child', u.created_at LIMIT 2000`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []AdminFamily{}
	for rows.Next() {
		var f AdminFamily
		var m AdminMember
		if err := rows.Scan(&f.ID, &f.Name, &f.Code, &m.ID, &m.Name, &m.Role, &m.SeenAt, &m.Shares, &m.CreatedAt, &m.HasPassword); err != nil {
			return nil, err
		}
		if f.ID == "" {
			m.Role = ""
		}
		if n := len(out); n > 0 && out[n-1].ID == f.ID {
			out[n-1].Members = append(out[n-1].Members, m)
		} else {
			f.Members = []AdminMember{m}
			out = append(out, f)
		}
	}
	return out, rows.Err()
}

// AdminSetRole makes a member a parent or a child (the family keeps at least one parent).
func (s *Store) AdminSetRole(ctx context.Context, userID, role string) error {
	var fam sql.NullString
	var cur string
	if err := s.db.QueryRowContext(ctx, `SELECT family_id, role FROM users WHERE id = ?`, userID).Scan(&fam, &cur); err != nil || !fam.Valid {
		return ErrNotFound
	}
	if cur == "parent" && role == "child" {
		var n int
		_ = s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM users WHERE family_id = ? AND role = 'parent'`, fam.String).Scan(&n)
		if n <= 1 {
			return ErrConflict
		}
	}
	_, err := s.db.ExecContext(ctx, `UPDATE users SET role = ? WHERE id = ?`, role, userID)
	return err
}

// AdminRemoveFromFamily takes a user out of their family.
func (s *Store) AdminRemoveFromFamily(ctx context.Context, userID string) error {
	var fam sql.NullString
	if err := s.db.QueryRowContext(ctx, `SELECT family_id FROM users WHERE id = ?`, userID).Scan(&fam); err != nil || !fam.Valid {
		return ErrNotFound
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := removeFromFamily(ctx, tx, userID, fam.String); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) RenameFamily(ctx context.Context, id, name string) error {
	r, err := s.db.ExecContext(ctx, `UPDATE families SET name = ? WHERE id = ?`, truncRunes(name, 60), id)
	if err == nil {
		if n, _ := r.RowsAffected(); n == 0 {
			return ErrNotFound
		}
	}
	return err
}

// Backup writes a consistent copy of the database to path.
func (s *Store) Backup(ctx context.Context, path string) error {
	_, err := s.db.ExecContext(ctx, `VACUUM INTO ?`, path)
	return err
}

// Cleanup drops inbox items nobody collected within 90 days.
func (s *Store) Cleanup(ctx context.Context) {
	_, _ = s.db.ExecContext(ctx, `DELETE FROM inbox WHERE sent_at < ?`, now()-90*24*3600_000)
	_, _ = s.db.ExecContext(ctx, `DELETE FROM progress_days WHERE day < ?`, time.Now().AddDate(0, 0, -120).Format("2006-01-02"))
}

// LibrarySummary is the top of the dashboard: what the people on this server share and use.
type LibrarySummary struct {
	Awrad      int          `json:"awrad"`
	AwradDl    int          `json:"awradDownloads"`
	Recent     []RecentItem `json:"recent"`
	Publishers []PublisherN `json:"publishers"`
	DBBytes    int64        `json:"dbBytes"`
	Passwords  int          `json:"withPassword"`
	Duplicates int          `json:"duplicateNames"`
}

type RecentItem struct {
	Kind  string `json:"kind"` // book | werd
	ID    string `json:"id"`
	Title string `json:"title"`
	By    string `json:"by"`
	At    int64  `json:"at"`
	Vis   string `json:"visibility"`
	Lines int    `json:"lines"`
}

type PublisherN struct {
	Name  string `json:"name"`
	Books int    `json:"books"`
	Awrad int    `json:"awrad"`
	Dl    int    `json:"downloads"`
}

func (s *Store) LibrarySummary(ctx context.Context) (*LibrarySummary, error) {
	l := &LibrarySummary{Recent: []RecentItem{}, Publishers: []PublisherN{}}
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*), COALESCE(SUM(downloads), 0) FROM shared_werds WHERE hidden = 0`).Scan(&l.Awrad, &l.AwradDl); err != nil {
		return nil, err
	}
	_ = s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM users WHERE pass_hash IS NOT NULL`).Scan(&l.Passwords)
	rows, err := s.db.QueryContext(ctx, `SELECT * FROM (
		SELECT 'book', id, title, publisher_name, published_at, visibility, items FROM books WHERE hidden = 0
		UNION ALL SELECT 'werd', id, title, publisher_name, published_at, visibility, json_array_length(lines) FROM shared_werds WHERE hidden = 0)
		ORDER BY 5 DESC LIMIT 6`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var r RecentItem
		if err := rows.Scan(&r.Kind, &r.ID, &r.Title, &r.By, &r.At, &r.Vis, &r.Lines); err != nil {
			rows.Close()
			return nil, err
		}
		l.Recent = append(l.Recent, r)
	}
	rows.Close()
	rows, err = s.db.QueryContext(ctx, `SELECT name, SUM(b), SUM(w), SUM(d) FROM (
		SELECT publisher_name AS name, 1 AS b, 0 AS w, downloads AS d FROM books WHERE hidden = 0
		UNION ALL SELECT publisher_name, 0, 1, downloads FROM shared_werds WHERE hidden = 0)
		GROUP BY name ORDER BY SUM(b) + SUM(w) DESC, SUM(d) DESC LIMIT 6`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var p PublisherN
		if err := rows.Scan(&p.Name, &p.Books, &p.Awrad, &p.Dl); err != nil {
			rows.Close()
			return nil, err
		}
		l.Publishers = append(l.Publishers, p)
	}
	rows.Close()
	var pages, size int64
	_ = s.db.QueryRowContext(ctx, `SELECT page_count, page_size FROM pragma_page_count(), pragma_page_size()`).Scan(&pages, &size)
	l.DBBytes = pages * size
	if d, err := s.Duplicates(ctx); err == nil {
		l.Duplicates = len(d)
	}
	return l, nil
}
