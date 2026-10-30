package main

// Accounts with a sign-in: a name and a password (or a one-time sign-in code from the server's owner), so the
// same person on a new phone, or after reinstalling, comes back to the same account instead of a new one.
//
// Accounts made before 1.8.0 (or by older apps) have no password: they keep working with their token, and their
// owner can add a password at any time. Names with a password are unique on the server (case and Arabic letter
// variants ignored). Each phone signed in gets its own token (sessions), so signing in on a second phone does not
// sign the first one out.

import (
	"context"
	"crypto/pbkdf2"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode"
)

const sessionSchema = `
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
`

// run after the columns below exist
const accountIndexes = `CREATE UNIQUE INDEX IF NOT EXISTS users_login ON users(login) WHERE login IS NOT NULL;`

func init() {
	migrations = append(migrations,
		struct{ table, column, def string }{"users", "login", "TEXT"},
		struct{ table, column, def string }{"users", "pass_hash", "TEXT"},
		struct{ table, column, def string }{"users", "code_hash", "TEXT"},
		struct{ table, column, def string }{"users", "code_exp", "INTEGER NOT NULL DEFAULT 0"},
		struct{ table, column, def string }{"users", "reset_until", "INTEGER NOT NULL DEFAULT 0"},
	)
}

var (
	ErrNameTaken     = errors.New("name taken")
	ErrWrongPassword = errors.New("wrong name or password")
	ErrWeakPassword  = errors.New("password too short")
	ErrNeedsCurrent  = errors.New("current password needed")
	ErrNoPassword    = errors.New("no password")
)

const minPassword = 6

// loginKey is how names are compared: no tashkeel or tatweel, one alef, ة=ه, ى=ي, lower case, no spaces or marks.
func loginKey(name string) string {
	var b strings.Builder
	for _, r := range bare(name) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}

const pbkdfIter = 120_000

func hashPassword(pw string) string {
	salt := newToken()[:32]
	k, _ := pbkdf2.Key(sha256.New, pw, []byte(salt), pbkdfIter, 32)
	return fmt.Sprintf("pbkdf2-sha256$%d$%s$%s", pbkdfIter, salt, hex.EncodeToString(k))
}

func checkPassword(stored, pw string) bool {
	p := strings.Split(stored, "$")
	if len(p) != 4 || p[0] != "pbkdf2-sha256" {
		return false
	}
	iter, err := strconv.Atoi(p[1])
	if err != nil || iter < 1000 {
		return false
	}
	want, err := hex.DecodeString(p[3])
	if err != nil {
		return false
	}
	k, err := pbkdf2.Key(sha256.New, pw, []byte(p[2]), iter, len(want))
	return err == nil && subtle.ConstantTimeCompare(k, want) == 1
}

func validPassword(pw string) bool { return len([]rune(pw)) >= minPassword }

// ------------------------------------------------------------------ store

// CreateUserWithPassword makes an account whose name is unique on the server.
func (s *Store) CreateUserWithPassword(ctx context.Context, name, pw string) (id, token string, err error) {
	key := loginKey(name)
	if key == "" {
		return "", "", ErrWrongPassword
	}
	if !validPassword(pw) {
		return "", "", ErrWeakPassword
	}
	var n int
	_ = s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM users WHERE login = ?`, key).Scan(&n)
	if n > 0 {
		return "", "", ErrNameTaken
	}
	id, token = newID("u"), newToken()
	t := now()
	_, err = s.db.ExecContext(ctx, `INSERT INTO users(id, token_hash, name, created_at, seen_at, login, pass_hash) VALUES (?,?,?,?,?,?,?)`,
		id, hashToken(token), truncRunes(name, 40), t, t, key, hashPassword(pw))
	if err != nil && strings.Contains(err.Error(), "UNIQUE") {
		return "", "", ErrNameTaken
	}
	return
}

func (s *Store) newSession(ctx context.Context, userID string) (string, error) {
	tok := newToken()
	_, err := s.db.ExecContext(ctx, `INSERT INTO sessions(token_hash, user_id, created_at) VALUES (?,?,?)`, hashToken(tok), userID, now())
	return tok, err
}

// SignIn checks a name and password and returns a new token for this phone.
func (s *Store) SignIn(ctx context.Context, name, pw string) (*User, string, error) {
	var id, stored string
	var ph sql.NullString
	err := s.db.QueryRowContext(ctx, `SELECT id, pass_hash FROM users WHERE login = ?`, loginKey(name)).Scan(&id, &ph)
	if err != nil || !ph.Valid {
		hashPassword(pw) // same time whether or not the name exists
		return nil, "", ErrWrongPassword
	}
	stored = ph.String
	if !checkPassword(stored, pw) {
		return nil, "", ErrWrongPassword
	}
	tok, err := s.newSession(ctx, id)
	if err != nil {
		return nil, "", err
	}
	u, err := s.UserByToken(ctx, tok)
	return u, tok, err
}

// SignInWithCode uses a one-time sign-in code from the owner; afterwards the password can be set without the old one
// for 30 minutes.
func (s *Store) SignInWithCode(ctx context.Context, code string) (*User, string, error) {
	c := normalizeCode(code)
	if len(c) != 7 {
		return nil, "", ErrWrongPassword
	}
	var id string
	err := s.db.QueryRowContext(ctx, `SELECT id FROM users WHERE code_hash = ? AND code_exp > ?`, hashToken(c), now()).Scan(&id)
	if err != nil {
		return nil, "", ErrWrongPassword
	}
	if _, err := s.db.ExecContext(ctx, `UPDATE users SET code_hash = NULL, code_exp = 0, reset_until = ? WHERE id = ?`, now()+30*60_000, id); err != nil {
		return nil, "", err
	}
	tok, err := s.newSession(ctx, id)
	if err != nil {
		return nil, "", err
	}
	u, err := s.UserByToken(ctx, tok)
	return u, tok, err
}

type AccountInfo struct {
	UserID      string `json:"userId"`
	DisplayName string `json:"displayName"`
	HasPassword bool   `json:"hasPassword"`
	CanReset    bool   `json:"canReset"` // signed in with a code: may set a password without the old one
	Phones      int    `json:"phones"`
}

func (s *Store) Account(ctx context.Context, id string) (*AccountInfo, error) {
	a := &AccountInfo{UserID: id}
	var ph sql.NullString
	var reset int64
	err := s.db.QueryRowContext(ctx, `SELECT name, pass_hash, reset_until, (SELECT COUNT(*) FROM sessions WHERE user_id = users.id) + 1 FROM users WHERE id = ?`, id).
		Scan(&a.DisplayName, &ph, &reset, &a.Phones)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	a.HasPassword, a.CanReset = ph.Valid, reset > now()
	return a, err
}

// SetPassword adds or changes the account's password; the name becomes the sign-in name and must be free.
func (s *Store) SetPassword(ctx context.Context, id, current, pw string) error {
	if !validPassword(pw) {
		return ErrWeakPassword
	}
	var name string
	var ph sql.NullString
	var reset int64
	if err := s.db.QueryRowContext(ctx, `SELECT name, pass_hash, reset_until FROM users WHERE id = ?`, id).Scan(&name, &ph, &reset); err != nil {
		return ErrNotFound
	}
	if ph.Valid && reset < now() && !checkPassword(ph.String, current) {
		return ErrNeedsCurrent
	}
	key := loginKey(name)
	var other int
	_ = s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM users WHERE login = ? AND id <> ?`, key, id).Scan(&other)
	if other > 0 {
		return ErrNameTaken
	}
	_, err := s.db.ExecContext(ctx, `UPDATE users SET login = ?, pass_hash = ?, reset_until = 0 WHERE id = ?`, key, hashPassword(pw), id)
	return err
}

// RenameWithLogin renames an account; one with a password keeps a unique sign-in name.
func (s *Store) RenameWithLogin(ctx context.Context, id, name string) error {
	var ph sql.NullString
	if err := s.db.QueryRowContext(ctx, `SELECT pass_hash FROM users WHERE id = ?`, id).Scan(&ph); err != nil {
		return ErrNotFound
	}
	if !ph.Valid {
		return s.RenameUser(ctx, id, name)
	}
	key := loginKey(name)
	if key == "" {
		return ErrWrongPassword
	}
	var other int
	_ = s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM users WHERE login = ? AND id <> ?`, key, id).Scan(&other)
	if other > 0 {
		return ErrNameTaken
	}
	_, err := s.db.ExecContext(ctx, `UPDATE users SET name = ?, login = ? WHERE id = ?`, truncRunes(name, 40), key, id)
	return err
}

// SignOutAll ends every phone's sign-in and returns a new token for the phone asking.
func (s *Store) SignOutAll(ctx context.Context, id string) (string, error) {
	tok := newToken()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM sessions WHERE user_id = ?`, id); err != nil {
		return "", err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE users SET token_hash = ? WHERE id = ?`, hashToken(tok), id); err != nil {
		return "", err
	}
	return tok, tx.Commit()
}

// SignOut ends this phone's sign-in. An account without a password could never be reached again, so it can't.
func (s *Store) SignOut(ctx context.Context, id, token string) error {
	var ph sql.NullString
	if err := s.db.QueryRowContext(ctx, `SELECT pass_hash FROM users WHERE id = ?`, id).Scan(&ph); err != nil {
		return ErrNotFound
	}
	if !ph.Valid {
		return ErrNoPassword
	}
	h := hashToken(token)
	r, err := s.db.ExecContext(ctx, `DELETE FROM sessions WHERE token_hash = ?`, h)
	if err != nil {
		return err
	}
	if n, _ := r.RowsAffected(); n == 0 { // the account's first token: replace it with one nobody has
		_, err = s.db.ExecContext(ctx, `UPDATE users SET token_hash = ? WHERE id = ? AND token_hash = ?`, hashToken(newToken()), id, h)
	}
	return err
}

// IssueSignInCode makes a one-time code (valid a day) the owner reads out to someone who forgot their password.
func (s *Store) IssueSignInCode(ctx context.Context, id string) (string, int64, error) {
	code := newInviteCode()
	exp := now() + 24*3600_000
	r, err := s.db.ExecContext(ctx, `UPDATE users SET code_hash = ?, code_exp = ? WHERE id = ?`, hashToken(code), exp, id)
	if err == nil {
		if n, _ := r.RowsAffected(); n == 0 {
			return "", 0, ErrNotFound
		}
	}
	return code, exp, err
}

// ClearPassword removes the password (the account keeps working on the phones already signed in).
func (s *Store) ClearPassword(ctx context.Context, id string) error {
	r, err := s.db.ExecContext(ctx, `UPDATE users SET pass_hash = NULL, login = NULL WHERE id = ?`, id)
	if err == nil {
		if n, _ := r.RowsAffected(); n == 0 {
			return ErrNotFound
		}
	}
	return err
}

type DupGroup struct {
	Name  string        `json:"name"`
	Users []AdminMember `json:"users"`
}

// Duplicates lists names shared by more than one account (the same person signed up again before 1.8.0).
func (s *Store) Duplicates(ctx context.Context) ([]DupGroup, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT u.id, u.name, u.role, u.seen_at, u.sharing, u.created_at, u.pass_hash IS NOT NULL, COALESCE(f.name, ''),
		(SELECT COUNT(*) FROM books b WHERE b.publisher_id = u.id) + (SELECT COUNT(*) FROM shared_werds w WHERE w.publisher_id = u.id)
		FROM users u LEFT JOIN families f ON f.id = u.family_id ORDER BY u.created_at LIMIT 5000`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	by := map[string]*DupGroup{}
	order := []string{}
	for rows.Next() {
		var m AdminMember
		if err := rows.Scan(&m.ID, &m.Name, &m.Role, &m.SeenAt, &m.Shares, &m.CreatedAt, &m.HasPassword, &m.Family, &m.Shared); err != nil {
			return nil, err
		}
		k := loginKey(m.Name)
		if k == "" {
			continue
		}
		g := by[k]
		if g == nil {
			g = &DupGroup{Name: m.Name}
			by[k] = g
			order = append(order, k)
		}
		g.Users = append(g.Users, m)
	}
	out := []DupGroup{}
	for _, k := range order {
		if len(by[k].Users) > 1 {
			out = append(out, *by[k])
		}
	}
	return out, rows.Err()
}

// MergeUsers moves everything of account from into account into (books, shared awrad, inbox, history, phones, family)
// and deletes from. The phones signed in as from carry on as into.
func (s *Store) MergeUsers(ctx context.Context, from, into string) error {
	if from == into {
		return ErrConflict
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	type acct struct {
		tok   string
		fam   sql.NullString
		role  string
		login sql.NullString
		pass  sql.NullString
	}
	get := func(id string) (*acct, error) {
		a := &acct{}
		err := tx.QueryRowContext(ctx, `SELECT token_hash, family_id, role, login, pass_hash FROM users WHERE id = ?`, id).Scan(&a.tok, &a.fam, &a.role, &a.login, &a.pass)
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return a, err
	}
	f, err := get(from)
	if err != nil {
		return err
	}
	t, err := get(into)
	if err != nil {
		return err
	}
	stmts := []string{
		`UPDATE books SET publisher_id = ? WHERE publisher_id = ?`,
		`UPDATE shared_werds SET publisher_id = ? WHERE publisher_id = ?`,
		`UPDATE reports SET user_id = ? WHERE user_id = ?`,
		`UPDATE inbox SET to_user = ? WHERE to_user = ?`,
		`UPDATE inbox SET from_user = ? WHERE from_user = ?`,
		`UPDATE tts_voices SET shared_by = ? WHERE shared_by = ?`,
		`UPDATE sessions SET user_id = ? WHERE user_id = ?`,
		`INSERT OR IGNORE INTO progress_days(user_id, day, data) SELECT ?, day, data FROM progress_days WHERE user_id = ?`,
	}
	for _, q := range stmts {
		if _, err := tx.ExecContext(ctx, q, into, from); err != nil {
			return err
		}
	}
	// the other phone's token keeps working, now for into
	if _, err := tx.ExecContext(ctx, `INSERT OR IGNORE INTO sessions(token_hash, user_id, created_at) VALUES (?,?,?)`, f.tok, into, now()); err != nil {
		return err
	}
	if !t.fam.Valid && f.fam.Valid {
		if _, err := tx.ExecContext(ctx, `UPDATE users SET family_id = ?, role = ? WHERE id = ?`, f.fam.String, f.role, into); err != nil {
			return err
		}
	} else if f.fam.Valid && f.fam.String != t.fam.String {
		if err := removeFromFamily(ctx, tx, from, f.fam.String); err != nil {
			return err
		}
	} else if f.fam.Valid && f.role == "parent" && t.role != "parent" {
		if _, err := tx.ExecContext(ctx, `UPDATE users SET role = 'parent' WHERE id = ?`, into); err != nil {
			return err
		}
	}
	if !t.pass.Valid && f.pass.Valid {
		if _, err := tx.ExecContext(ctx, `UPDATE users SET login = NULL WHERE id = ?`, from); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE users SET login = ?, pass_hash = ? WHERE id = ?`, f.login, f.pass, into); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM users WHERE id = ?`, from); err != nil {
		return err
	}
	// a family emptied of the merged account
	if f.fam.Valid {
		if _, err := tx.ExecContext(ctx, `DELETE FROM families WHERE id = ? AND NOT EXISTS (SELECT 1 FROM users WHERE family_id = ?)`, f.fam.String, f.fam.String); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// ------------------------------------------------------------------ HTTP

func (s *Server) accountErr(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, ErrNameTaken):
		writeErr(w, 409, "name_taken", "This name already has an account on this server. Sign in with its password, or choose another name.", nil)
	case errors.Is(err, ErrWrongPassword):
		writeErr(w, 401, "wrong_password", "Wrong name or password.", nil)
	case errors.Is(err, ErrWeakPassword):
		writeErr(w, 422, "weak_password", fmt.Sprintf("The password needs at least %d characters.", minPassword), nil)
	case errors.Is(err, ErrNeedsCurrent):
		writeErr(w, 403, "current_password", "Enter the current password.", nil)
	case errors.Is(err, ErrNoPassword):
		writeErr(w, 409, "no_password", "Add a password first, or this account can't be signed in to again.", nil)
	case errors.Is(err, ErrNotFound):
		writeErr(w, 404, "not_found", "No such account.", nil)
	case errors.Is(err, ErrConflict):
		writeErr(w, 409, "same_account", "Choose two different accounts.", nil)
	default:
		s.internal(w, err)
	}
}

func (s *Server) accountRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/accounts/signin", s.signIn)
	mux.HandleFunc("GET /v1/accounts/me", s.auth(func(w http.ResponseWriter, r *http.Request, u *User) {
		a, err := s.st.Account(r.Context(), u.ID)
		if err != nil {
			s.accountErr(w, err)
			return
		}
		writeJSON(w, 200, a)
	}))
	mux.HandleFunc("PUT /v1/accounts/me/password", s.auth(func(w http.ResponseWriter, r *http.Request, u *User) {
		var in struct{ Password, Current string }
		if !s.readJSON(w, r, &in) {
			return
		}
		if err := s.st.SetPassword(r.Context(), u.ID, in.Current, in.Password); err != nil {
			s.accountErr(w, err)
			return
		}
		s.act.Add(KindPhone, "info", "phone.password", "", A{"name": u.Name}, 0)
		writeJSON(w, 200, map[string]any{})
	}))
	mux.HandleFunc("POST /v1/accounts/me/signout-all", s.auth(func(w http.ResponseWriter, r *http.Request, u *User) {
		tok, err := s.st.SignOutAll(r.Context(), u.ID)
		if err != nil {
			s.accountErr(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{"token": tok})
	}))
	mux.HandleFunc("DELETE /v1/accounts/me/session", s.auth(func(w http.ResponseWriter, r *http.Request, u *User) {
		tok := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		if err := s.st.SignOut(r.Context(), u.ID, tok); err != nil {
			s.accountErr(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))

	mux.HandleFunc("POST /admin/api/users/{id}/signin-code", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		code, exp, err := s.st.IssueSignInCode(r.Context(), r.PathValue("id"))
		if err != nil {
			s.accountErr(w, err)
			return
		}
		s.act.Add(KindServer, "info", "server.signinCode", "", A{"name": s.userName(r.Context(), r.PathValue("id"))}, 0)
		writeJSON(w, 200, map[string]any{"code": code, "expiresAt": exp})
	}))
	mux.HandleFunc("DELETE /admin/api/users/{id}/password", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.st.ClearPassword(r.Context(), r.PathValue("id")); err != nil {
			s.accountErr(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))
	mux.HandleFunc("GET /admin/api/users/duplicates", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		d, err := s.st.Duplicates(r.Context())
		if err != nil {
			s.internal(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{"groups": d})
	}))
	mux.HandleFunc("POST /admin/api/users/{id}/merge", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Into string }
		if !s.readJSON(w, r, &in) {
			return
		}
		name := s.userName(r.Context(), r.PathValue("id"))
		if err := s.st.MergeUsers(r.Context(), r.PathValue("id"), in.Into); err != nil {
			s.accountErr(w, err)
			return
		}
		s.act.Add(KindServer, "info", "server.merged", "", A{"name": name}, 0)
		writeJSON(w, 200, map[string]any{})
	}))
}

func (s *Server) signIn(w http.ResponseWriter, r *http.Request) {
	if !s.limit(w, "signin", s.clientIP(r), 30, 10) {
		return
	}
	var in struct{ Name, Password, Code string }
	if !s.readJSON(w, r, &in) {
		return
	}
	var u *User
	var tok string
	var err error
	if strings.TrimSpace(in.Code) != "" {
		u, tok, err = s.st.SignInWithCode(r.Context(), in.Code)
	} else {
		if !s.limit(w, "signin-name", loginKey(in.Name), 20, 8) {
			return
		}
		u, tok, err = s.st.SignIn(r.Context(), in.Name, in.Password)
	}
	if err != nil {
		if errors.Is(err, ErrWrongPassword) {
			time.Sleep(400 * time.Millisecond)
		}
		s.accountErr(w, err)
		return
	}
	s.act.Add(KindPhone, "info", "phone.signedIn", "", A{"name": u.Name}, 0)
	a, _ := s.st.Account(r.Context(), u.ID)
	out := map[string]any{"userId": u.ID, "token": tok, "displayName": u.Name}
	if a != nil {
		out["canReset"] = a.CanReset
	}
	writeJSON(w, 200, out)
}
