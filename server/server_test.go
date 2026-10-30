package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type tc struct {
	t   *testing.T
	url string
}

func newTestServer(t *testing.T) (*tc, *Store, string) {
	t.Helper()
	dir := t.TempDir()
	st, err := OpenStore(filepath.Join(dir, "werd.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	cfg := &Config{DataDir: dir, MaxBodySize: 8 << 20}
	srv := NewServer(st, cfg, "secret-admin", log.New(io.Discard, "", 0))
	hs := httptest.NewServer(srv.Handler())
	t.Cleanup(hs.Close)
	return &tc{t, hs.URL}, st, dir
}

func (c *tc) do(method, path, token string, body any) (int, map[string]any) {
	c.t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.url+path, rd)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer res.Body.Close()
	out := map[string]any{}
	raw, _ := io.ReadAll(res.Body)
	_ = json.Unmarshal(raw, &out)
	return res.StatusCode, out
}

func (c *tc) account(name string) (string, string) {
	code, o := c.do("POST", "/v1/accounts", "", map[string]any{"displayName": name})
	if code != 201 {
		c.t.Fatalf("account: %d %v", code, o)
	}
	return o["userId"].(string), o["token"].(string)
}

func book(title string, n int, checkedAll bool) map[string]any {
	items := []map[string]any{}
	for i := 0; i < n; i++ {
		items = append(items, map[string]any{"title": fmt.Sprintf("دعاء %d", i), "text": fmt.Sprintf("سُبْحَانَ اللَّهِ وَبِحَمْدِهِ %d", i),
			"count": i + 1, "occasion": "morning", "page": i + 1, "checked": checkedAll || i != 2})
	}
	return map[string]any{"format": "werd-book", "version": 1, "title": title, "author": "القحطاني", "language": "ar", "items": items}
}

func errCode(o map[string]any) string {
	if e, ok := o["error"].(map[string]any); ok {
		return e["code"].(string)
	}
	return ""
}

func TestLibrary(t *testing.T) {
	c, _, _ := newTestServer(t)
	_, tok := c.account("أبو محمد")

	if code, o := c.do("POST", "/v1/library/books", tok, book("حصن المسلم", 7, false)); code != 422 || errCode(o) != "not_proofread" {
		t.Fatalf("unproofread book accepted: %d %v", code, o)
	}
	if code, _ := c.do("POST", "/v1/library/books", "", book("حصن المسلم", 7, true)); code != 401 {
		t.Fatalf("publish without account: %d", code)
	}
	code, o := c.do("POST", "/v1/library/books", tok, book("حِصْنُ المُسْلِم", 7, true))
	if code != 201 {
		t.Fatalf("publish: %d %v", code, o)
	}
	id := o["id"].(string)
	// same content again (different tashkeel in the title) → 409 with the existing id
	code, o = c.do("POST", "/v1/library/books", tok, book("حصن المسلم", 7, true))
	if code != 409 || o["error"].(map[string]any)["id"] != id {
		t.Fatalf("duplicate: %d %v", code, o)
	}

	// search ignores tashkeel and hamza forms, matches the author too
	for _, q := range []string{"حصن", "المسلم", "القحطاني", ""} {
		_, o = c.do("GET", "/v1/library/books?q="+q, "", nil)
		if o["total"].(float64) != 1 {
			t.Fatalf("search %q: %v", q, o)
		}
	}
	_, o = c.do("GET", "/v1/library/books?q=لايوجد", "", nil)
	if o["total"].(float64) != 0 {
		t.Fatal("search miss")
	}
	b := func() map[string]any {
		_, o := c.do("GET", "/v1/library/books", "", nil)
		return o["books"].([]any)[0].(map[string]any)
	}
	if b()["publisher"] != "أبو محمد" || b()["items"].(float64) != 7 || b()["checked"].(float64) != 7 {
		t.Fatalf("summary: %v", b())
	}

	// preview: first 5, not a download
	_, o = c.do("GET", "/v1/library/books/"+id+"?preview=true", "", nil)
	if len(o["items"].([]any)) != 5 || o["itemCount"].(float64) != 7 || b()["downloads"].(float64) != 0 {
		t.Fatalf("preview: %v", o)
	}
	_, o = c.do("GET", "/v1/library/books/"+id, "", nil)
	if len(o["items"].([]any)) != 7 || o["format"] != "werd-book" || o["id"] != id || b()["downloads"].(float64) != 1 {
		t.Fatalf("download: %v", o)
	}

	// report → admin sees it → hides the book → gone from the library
	if code, _ := c.do("POST", "/v1/library/books/"+id+"/reports", tok, map[string]any{"reason": "كلمة ناقصة", "item": 3}); code != 201 {
		t.Fatal("report")
	}
	if code, _ := c.do("GET", "/admin/api/reports", "wrong", nil); code != 401 {
		t.Fatal("admin without password")
	}
	_, o = c.do("GET", "/admin/api/reports", "secret-admin", nil)
	r := o["reports"].([]any)[0].(map[string]any)
	if r["reason"] != "كلمة ناقصة" || !strings.Contains(r["itemText"].(string), "3") {
		t.Fatalf("reports: %v", o)
	}
	c.do("POST", "/admin/api/books/"+id+"/hide", "secret-admin", map[string]any{"hidden": true})
	if code, _ := c.do("GET", "/v1/library/books/"+id, "", nil); code != 404 {
		t.Fatal("hidden book still served")
	}
	_, o = c.do("GET", "/v1/library/books", "", nil)
	if o["total"].(float64) != 0 {
		t.Fatal("hidden book still listed")
	}
	_, o = c.do("GET", "/admin/api/stats", "secret-admin", nil)
	if o["stats"].(map[string]any)["hiddenBooks"].(float64) != 1 {
		t.Fatalf("stats: %v", o)
	}
}

func TestFamily(t *testing.T) {
	c, _, _ := newTestServer(t)
	abu, tA := c.account("أبو محمد")
	umm, tU := c.account("أم محمد")
	_, tS := c.account("غريب")

	if code, _ := c.do("GET", "/v1/families/me", tA, nil); code != 404 {
		t.Fatal("family before creating")
	}
	code, f := c.do("POST", "/v1/families", tA, map[string]any{"name": "عائلة أبي محمد"})
	if code != 201 || len(f["inviteCode"].(string)) != 7 {
		t.Fatalf("create: %d %v", code, f)
	}
	if code, _ := c.do("POST", "/v1/families", tA, map[string]any{"name": "أخرى"}); code != 409 {
		t.Fatal("second family")
	}
	if code, _ := c.do("POST", "/v1/families/join", tU, map[string]any{"inviteCode": "AAA-AAA"}); code != 404 {
		t.Fatal("unknown code")
	}
	// codes are forgiving: lower case, no dash
	lower := strings.ToLower(strings.ReplaceAll(f["inviteCode"].(string), "-", ""))
	if code, o := c.do("POST", "/v1/families/join", tU, map[string]any{"inviteCode": lower}); code != 200 || o["familyId"] != f["familyId"] {
		t.Fatalf("join: %d %v", code, o)
	}

	day := "2026-10-14"
	c.do("PUT", "/v1/progress", tU, map[string]any{"sharing": true, "day": day, "streak": 5, "due": 3, "done": 2,
		"werds": []any{map[string]any{"title": "أذكار الصباح", "done": 33, "total": 33, "completed": true}}})
	_, o := c.do("GET", "/v1/families/me", tA, nil)
	ms := o["members"].([]any)
	if len(ms) != 2 {
		t.Fatalf("members: %v", o)
	}
	var her map[string]any
	for _, m := range ms {
		if mm := m.(map[string]any); mm["userId"] == umm {
			her = mm
		} else if mm["isMe"] != true || mm["userId"] != abu {
			t.Fatalf("me: %v", mm)
		}
	}
	p := her["progress"].(map[string]any)
	if p["streak"].(float64) != 5 || p["werds"].([]any)[0].(map[string]any)["title"] != "أذكار الصباح" {
		t.Fatalf("progress: %v", her)
	}
	// stop sharing → hidden
	c.do("PUT", "/v1/progress", tU, map[string]any{"sharing": false})
	_, o = c.do("GET", "/v1/families/me", tA, nil)
	for _, m := range o["members"].([]any) {
		if mm := m.(map[string]any); mm["userId"] == umm && (mm["progress"] != nil || mm["sharing"] != false) {
			t.Fatalf("not hidden: %v", mm)
		}
	}

	// send a werd to her; strangers can't
	lines := []any{map[string]any{"text": "أستغفر الله", "count": 100}}
	if code, _ := c.do("POST", "/v1/families/me/members/"+umm+"/werds", tA, map[string]any{"title": "ورد السحر", "lines": lines}); code != 201 {
		t.Fatal("send")
	}
	if code, _ := c.do("POST", "/v1/families/me/members/"+umm+"/werds", tS, map[string]any{"title": "x", "lines": lines}); code != 404 {
		t.Fatal("stranger could send")
	}
	_, o = c.do("GET", "/v1/inbox", tU, nil)
	ws := o["werds"].([]any)
	w := ws[0].(map[string]any)
	if len(ws) != 1 || w["title"] != "ورد السحر" || w["from"].(map[string]any)["displayName"] != "أبو محمد" {
		t.Fatalf("inbox: %v", o)
	}
	c.do("DELETE", "/v1/inbox/"+w["id"].(string), tU, nil)
	_, o = c.do("GET", "/v1/inbox", tU, nil)
	if len(o["werds"].([]any)) != 0 {
		t.Fatal("ack")
	}

	// leaving; last one out deletes the family
	c.do("DELETE", "/v1/families/me/membership", tU, nil)
	_, o = c.do("GET", "/v1/families/me", tA, nil)
	if len(o["members"].([]any)) != 1 {
		t.Fatal("leave")
	}
	c.do("DELETE", "/v1/families/me/membership", tA, nil)
	if code, _ := c.do("POST", "/v1/families/join", tU, map[string]any{"inviteCode": f["inviteCode"]}); code != 404 {
		t.Fatal("family should be gone")
	}
}

func TestLimitsAndErrors(t *testing.T) {
	c, _, _ := newTestServer(t)
	if code, o := c.do("GET", "/v1/inbox", "nope", nil); code != 401 || errCode(o) != "unauthorized" {
		t.Fatal("bad token")
	}
	if code, o := c.do("GET", "/v1/nothing", "", nil); code != 404 {
		t.Fatalf("unknown route: %d %v", code, o)
	}
	// signups are rate limited per IP
	var last int
	for i := 0; i < 12; i++ {
		last, _ = c.do("POST", "/v1/accounts", "", map[string]any{"displayName": "x"})
	}
	if last != 429 {
		t.Fatalf("signup limit: %d", last)
	}
	if code, o := c.do("GET", "/v1/info", "", nil); code != 200 || o["api"].(float64) != 1 {
		t.Fatal("info")
	}
	// home page and admin page render
	for _, p := range []string{"/", "/admin"} {
		res, err := http.Get(c.url + p)
		if err != nil || res.StatusCode != 200 {
			t.Fatalf("%s: %v %v", p, err, res)
		}
		res.Body.Close()
	}
}

func TestTooLarge(t *testing.T) {
	c, _, _ := newTestServer(t)
	_, tok := c.account("x")
	big := book("كبير", 1, true)
	big["items"].([]map[string]any)[0]["text"] = strings.Repeat("سبحان الله ", 900_000)
	if code, o := c.do("POST", "/v1/library/books", tok, big); code != 413 {
		t.Fatalf("too large: %d %v", code, o)
	}
}

func TestBackupAndCodes(t *testing.T) {
	c, st, dir := newTestServer(t)
	c.account("x")
	p := filepath.Join(dir, "copy.db")
	if err := st.Backup(t.Context(), p); err != nil {
		t.Fatal(err)
	}
	cp, err := OpenStore(p)
	if err != nil {
		t.Fatal(err)
	}
	s, _ := cp.Stats(t.Context())
	cp.Close()
	if s.Users != 1 {
		t.Fatalf("backup: %+v", s)
	}
	for i := 0; i < 200; i++ {
		code := newInviteCode()
		if normalizeCode(strings.ToLower(strings.ReplaceAll(code, "-", " "))) != code || strings.ContainsAny(code, "01OIL") {
			t.Fatal(code)
		}
	}
	if bare("أَصْبَحْنَا وَأَصْبَحَ المُلْكُ لِلَّهِ") != "اصبحنا واصبح الملك لله" {
		t.Fatal(bare("أَصْبَحْنَا وَأَصْبَحَ المُلْكُ لِلَّهِ"))
	}
}

func TestAddresses(t *testing.T) {
	for in, want := range map[string]string{
		"http://192.168.1.20:8080":    "lan",
		"http://100.101.102.103:8080": "tailscale",
		"https://nas.tail1234.ts.net": "tailscale",
		"https://werd.example.com":    "public",
		"http://nas.local:8080":       "lan",
		"http://100.200.1.1:8080":     "public", // outside 100.64/10
		"http://ds920:8080":           "lan",
	} {
		if got := kindOf(in); got != want {
			t.Errorf("kindOf(%s) = %s, want %s", in, got, want)
		}
	}
	t.Setenv("WERD_URLS", "192.168.1.20:8080, http://100.101.102.103:8080/")
	t.Setenv("WERD_DOCKER", "1")
	c, err := parseConfig(nil)
	if err != nil {
		t.Fatal(err)
	}
	a := Addresses(c)
	if len(a) != 2 || a[0].URL != "http://192.168.1.20:8080" || a[0].Kind != "lan" || a[1].URL != "http://100.101.102.103:8080" || a[1].Kind != "tailscale" {
		t.Fatalf("addresses: %+v", a)
	}
	// /v1/info tells the app
	st, _ := OpenStore(filepath.Join(t.TempDir(), "w.db"))
	defer st.Close()
	ts := httptest.NewServer(NewServer(st, c, "admintoken123", log.New(io.Discard, "", 0)).Handler())
	defer ts.Close()
	res, err := http.Get(ts.URL + "/v1/info")
	if err != nil {
		t.Fatal(err)
	}
	var info struct {
		Addresses []Address `json:"addresses"`
	}
	_ = json.NewDecoder(res.Body).Decode(&info)
	res.Body.Close()
	if len(info.Addresses) != 2 || info.Addresses[1].Kind != "tailscale" {
		t.Fatalf("info: %+v", info)
	}
}

func TestParentsKidsAndFamilyBooks(t *testing.T) {
	c, _, _ := newTestServer(t)
	dad, tD := c.account("أبو محمد")
	kid, tK := c.account("محمد")
	_, tX := c.account("غريب")
	_, f := c.do("POST", "/v1/families", tD, map[string]any{"name": "بيتنا"})
	c.do("POST", "/v1/families/join", tK, map[string]any{"inviteCode": f["inviteCode"]})
	_, o := c.do("GET", "/v1/families/me", tK, nil)
	if o["myRole"] != "child" {
		t.Fatalf("joiner is a child: %v", o["myRole"])
	}
	// a child can't manage, a parent can
	if code, _ := c.do("PUT", "/v1/families/me/members/"+dad, tK, map[string]any{"role": "child"}); code != 403 {
		t.Fatal("child set role")
	}
	if code, o := c.do("PUT", "/v1/families/me/members/"+dad, tD, map[string]any{"role": "child"}); code != 404 && code != 409 {
		t.Fatalf("last parent demoted itself: %d %v", code, o)
	}
	// werd with settings: auto-added only parent → child
	w := map[string]any{"title": "ورد السحر", "lines": []any{map[string]any{"text": "أستغفر الله", "count": 100}},
		"werd": map[string]any{"goalDaysMask": 127, "reminders": []any{map[string]any{"type": "DAILY", "hour": 4, "minute": 30}}}, "autoAdd": true}
	c.do("POST", "/v1/families/me/members/"+kid+"/werds", tD, w)
	c.do("POST", "/v1/families/me/members/"+dad+"/werds", tK, w)
	_, in := c.do("GET", "/v1/inbox", tK, nil)
	got := in["werds"].([]any)[0].(map[string]any)
	if got["autoAdd"] != true || got["werd"].(map[string]any)["reminders"] == nil {
		t.Fatalf("kid inbox: %v", got)
	}
	_, in = c.do("GET", "/v1/inbox", tD, nil)
	if in["werds"].([]any)[0].(map[string]any)["autoAdd"] != false {
		t.Fatal("child auto-added to a parent")
	}
	// history: parent reads the kid's days, the kid can't read the parent's
	for _, d := range []string{"2026-10-13", "2026-10-14", "2026-10-14"} {
		c.do("PUT", "/v1/progress", tK, map[string]any{"sharing": true, "day": d, "streak": 1, "due": 2, "done": 1, "werds": []any{}})
	}
	if code, h := c.do("GET", "/v1/families/me/members/"+kid+"/history", tD, nil); code != 200 || len(h["days"].([]any)) != 2 ||
		h["days"].([]any)[0].(map[string]any)["day"] != "2026-10-14" {
		t.Fatalf("history: %d %v", code, h)
	}
	if code, _ := c.do("GET", "/v1/families/me/members/"+dad+"/history", tK, nil); code != 404 {
		t.Fatal("child read parent history")
	}
	// family-only book with a madhhab
	b := book("فقه الأذكار", 4, true)
	b["visibility"], b["madhhab"] = "family", "hanbali"
	code, p := c.do("POST", "/v1/library/books", tD, b)
	if code != 201 {
		t.Fatalf("publish family: %d %v", code, p)
	}
	id := p["id"].(string)
	for tok, want := range map[string]int{tK: 1, tX: 0, "": 0} {
		_, s := c.do("GET", "/v1/library/books?q=فقه", tok, nil)
		if int(s["total"].(float64)) != want {
			t.Fatalf("search as %q: %v", tok, s)
		}
	}
	if code, _ := c.do("GET", "/v1/library/books/"+id, tX, nil); code != 404 {
		t.Fatal("stranger downloaded a family book")
	}
	// the same book can still go public; the madhhab filter finds it
	b["visibility"] = "public"
	if code, _ := c.do("POST", "/v1/library/books", tD, b); code != 201 {
		t.Fatal("public copy")
	}
	_, s := c.do("GET", "/v1/library/books?madhhab=hanbali", tX, nil)
	_, s2 := c.do("GET", "/v1/library/books?madhhab=maliki", tX, nil)
	if s["total"].(float64) != 1 || s2["total"].(float64) != 0 || s["books"].([]any)[0].(map[string]any)["madhhab"] != "hanbali" {
		t.Fatalf("madhhab filter: %v %v", s, s2)
	}
	// parent removes the kid
	if code, _ := c.do("DELETE", "/v1/families/me/members/"+kid, tD, nil); code != 200 {
		t.Fatal("remove")
	}
	if code, _ := c.do("GET", "/v1/families/me", tK, nil); code != 404 {
		t.Fatal("removed kid still in family")
	}
}

type fakeVoices struct{ calls int }

func (f *fakeVoices) RoundTrip(r *http.Request) (*http.Response, error) {
	f.calls++
	resp := func(code int, ct, body string) *http.Response {
		return &http.Response{StatusCode: code, Header: http.Header{"Content-Type": {ct}}, Body: io.NopCloser(strings.NewReader(body)), Request: r}
	}
	switch {
	case strings.HasSuffix(r.URL.Path, "/voices/list"):
		return resp(200, "application/json", `[{"ShortName":"ar-SA-HamedNeural","LocalName":"حامد","Gender":"Male","Locale":"ar-SA","LocaleName":"Arabic (Saudi Arabia)"},{"ShortName":"en-US-Ava","Locale":"en-US"}]`), nil
	case strings.HasSuffix(r.URL.Path, "/cognitiveservices/v1"):
		b, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(b), `name="ar-SA-HamedNeural"`) || r.Header.Get("Ocp-Apim-Subscription-Key") != "az-key" {
			return resp(400, "text/plain", "bad"), nil
		}
		return resp(200, "audio/mpeg", "ID3-fake-mp3"), nil
	case strings.Contains(r.URL.Host, "generativelanguage"):
		pcm := base64.StdEncoding.EncodeToString([]byte{1, 0, 2, 0})
		return resp(200, "application/json", `{"steps":[{"type":"model_output","content":[{"type":"audio","data":"`+pcm+`"}]}]}`), nil
	}
	return resp(404, "text/plain", "?"), nil
}

func TestVoices(t *testing.T) {
	c, st, dir := newTestServer(t)
	_, tok := c.account("أبو محمد")
	if code, o := c.do("POST", "/v1/tts", tok, map[string]any{"text": "سبحان الله"}); code != 404 || errCode(o) != "tts_off" {
		t.Fatalf("off: %d %v", code, o)
	}
	// configure through the admin API, with the providers faked
	srv := NewServer(st, &Config{DataDir: dir, MaxBodySize: 8 << 20}, "secret-admin", log.New(io.Discard, "", 0))
	fv := &fakeVoices{}
	srv.tts.hc = &http.Client{Transport: fv}
	hs := httptest.NewServer(srv.Handler())
	defer hs.Close()
	c2 := &tc{t, hs.URL}
	if code, _ := c2.do("PUT", "/admin/api/tts", "secret-admin", map[string]any{"default": "azure:ar-SA-HamedNeural",
		"azureKey": "az-key", "azureRegion": "uaenorth", "geminiKey": "gm-key"}); code != 200 {
		t.Fatal("config")
	}
	_, v := c2.do("GET", "/v1/tts/voices", tok, nil)
	if v["enabled"] != true || v["default"] != "azure:ar-SA-HamedNeural" || len(v["voices"].([]any)) != len(geminiVoices)+1 {
		t.Fatalf("voices: %v", v)
	}
	_, a := c2.do("GET", "/admin/api/tts", "secret-admin", nil)
	if strings.Contains(a["azureKey"].(string), "az-key") && len(a["azureKey"].(string)) > 3 && a["azureKey"] == "az-key" {
		t.Fatal("key not masked")
	}
	speak := func(voice string) (*http.Response, []byte) {
		b, _ := json.Marshal(map[string]any{"text": "سبحان الله", "voice": voice})
		req, _ := http.NewRequest("POST", hs.URL+"/v1/tts", bytes.NewReader(b))
		req.Header.Set("Authorization", "Bearer "+tok)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		body, _ := io.ReadAll(res.Body)
		return res, body
	}
	res, body := speak("")
	if res.StatusCode != 200 || string(body) != "ID3-fake-mp3" || res.Header.Get("X-Werd-Cached") != "false" || res.Header.Get("Content-Type") != "audio/mpeg" {
		t.Fatalf("speak: %d %s %v", res.StatusCode, body, res.Header)
	}
	calls := fv.calls
	res, body = speak("")
	if res.Header.Get("X-Werd-Cached") != "true" || fv.calls != calls || string(body) != "ID3-fake-mp3" {
		t.Fatal("second time should come from the NAS cache")
	}
	res, body = speak("gemini:Kore")
	if res.StatusCode != 200 || string(body[:4]) != "RIFF" || len(body) != 44+4 {
		t.Fatalf("gemini wav: %d %d", res.StatusCode, len(body))
	}
	if n, _ := srv.tts.CacheSize(); n != 2 {
		t.Fatalf("cache: %d", n)
	}
}

func TestSoundLibrary(t *testing.T) {
	c, st, dir := newTestServer(t)
	_ = c
	srv := NewServer(st, &Config{DataDir: dir, MaxBodySize: 8 << 20}, "secret-admin", log.New(io.Discard, "", 0))
	fv := &fakeVoices{}
	srv.tts.hc = &http.Client{Transport: fv}
	hs := httptest.NewServer(srv.Handler())
	defer hs.Close()
	c2 := &tc{t, hs.URL}
	_, tok := c2.account("أبو محمد")

	// a phone shares a clip of its own voice; another phone can use it, but the server can't add lines to it
	wav := "RIFF" + strings.Repeat("\x00", 60)
	if code, o := c2.do("POST", "/v1/tts/clips", tok, map[string]any{"voice": "shared:piper-kareem", "name": "Piper · Kareem",
		"text": "سبحان الله", "ext": "wav", "audio": base64.StdEncoding.EncodeToString([]byte(wav))}); code != 201 {
		t.Fatalf("share: %d %v", code, o)
	}
	if code, _ := c2.do("POST", "/v1/tts/clips", tok, map[string]any{"voice": "shared:x", "text": "a", "ext": "wav", "audio": base64.StdEncoding.EncodeToString([]byte("not audio at all, really not audio at all"))}); code != 422 {
		t.Fatal("non-audio accepted")
	}
	_, v := c2.do("GET", "/v1/tts/voices", tok, nil)
	if v["enabled"] != true || len(v["voices"].([]any)) != 1 || v["voices"].([]any)[0].(map[string]any)["name"] != "Piper · Kareem" {
		t.Fatalf("shared voice listed: %v", v)
	}
	speak := func(voice, text string) int {
		b, _ := json.Marshal(map[string]any{"text": text, "voice": voice})
		req, _ := http.NewRequest("POST", hs.URL+"/v1/tts", bytes.NewReader(b))
		req.Header.Set("Authorization", "Bearer "+tok)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res.StatusCode
	}
	if speak("shared:piper-kareem", "سبحان الله") != 200 || speak("shared:piper-kareem", "الحمد لله") != 404 {
		t.Fatal("shared voice lookups")
	}

	// a whole book's audio, made in the background; and automatically when a book is published
	c2.do("PUT", "/admin/api/tts", "secret-admin", map[string]any{"default": "azure:ar-SA-HamedNeural", "azureKey": "az-key", "azureRegion": "uaenorth"})
	_, p := c2.do("POST", "/v1/library/books", tok, book("حصن المسلم", 6, true))
	if code, j := c2.do("POST", "/admin/api/books/"+p["id"].(string)+"/tts", "secret-admin", map[string]any{}); code != 202 || j["total"] == nil {
		t.Fatalf("queue: %d %v", code, j)
	}
	waitJobs := func(n int) []any {
		for i := 0; i < 100; i++ {
			_, o := c2.do("GET", "/admin/api/tts/jobs", "secret-admin", nil)
			js := o["jobs"].([]any)
			done := 0
			for _, x := range js {
				if s := x.(map[string]any)["state"]; s == "done" || s == "failed" {
					done++
				}
			}
			if len(js) >= n && done == len(js) {
				return js
			}
			time.Sleep(50 * time.Millisecond)
		}
		t.Fatal("jobs never finished")
		return nil
	}
	js := waitJobs(1)
	j := js[0].(map[string]any)
	if j["state"] != "done" || j["done"].(float64) != 6 || j["made"].(float64) != 6 {
		t.Fatalf("job: %v", j)
	}
	c2.do("PUT", "/admin/api/tts", "secret-admin", map[string]any{"default": "azure:ar-SA-HamedNeural", "azureKey": "az-key", "azureRegion": "uaenorth", "autoGenerate": true})
	c2.do("POST", "/v1/library/books", tok, book("الأذكار", 4, true))
	js = waitJobs(2)
	if js[0].(map[string]any)["title"] != "الأذكار" || js[0].(map[string]any)["done"].(float64) != 4 {
		t.Fatalf("auto job: %v", js[0])
	}
	_, a := c2.do("GET", "/admin/api/tts", "secret-admin", nil)
	lib := a["library"].([]any)
	if len(lib) != 2 {
		t.Fatalf("library voices: %v", lib)
	}
}
