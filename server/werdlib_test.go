package main

import "testing"

func TestSharedWerds(t *testing.T) {
	c, _, _ := newTestServer(t)
	_, a := c.account("أبو حمزة")
	_, b := c.account("ابنه")
	_, z := c.account("غريب")
	// a and b in one family
	code, f := c.do("POST", "/v1/families", a, map[string]any{"name": "البيت"})
	if code != 201 && code != 200 {
		t.Fatalf("family: %d %v", code, f)
	}
	if code, o := c.do("POST", "/v1/families/join", b, map[string]any{"inviteCode": f["inviteCode"]}); code != 200 {
		t.Fatalf("join: %d %v", code, o)
	}
	werd := map[string]any{"title": "ورد الصباح", "note": "", "visibility": "family",
		"lines": []map[string]any{{"text": "سُبْحَانَ اللَّهِ", "count": 33}, {"text": "الحمد لله", "count": 33}},
		"werd":  map[string]any{"group": "الصباح", "reminders": []map[string]any{{"type": "DAILY", "hour": 6}}}}
	code, o := c.do("POST", "/v1/library/werds", a, werd)
	if code != 201 {
		t.Fatalf("share: %d %v", code, o)
	}
	id := o["id"].(string)
	if code, o := c.do("POST", "/v1/library/werds", a, werd); code != 409 || errCode(o) != "duplicate" {
		t.Fatalf("duplicate: %d %v", code, o)
	}
	// the family sees it with its settings; a stranger doesn't
	if code, o := c.do("GET", "/v1/library/werds/"+id+"?add=1", b, nil); code != 200 || o["werd"] == nil || o["lineCount"].(float64) != 2 {
		t.Fatalf("family get: %d %v", code, o)
	}
	if code, _ := c.do("GET", "/v1/library/werds/"+id, z, nil); code != 404 {
		t.Fatalf("stranger saw a family werd: %d", code)
	}
	if _, o := c.do("GET", "/v1/library/werds", z, nil); o["total"].(float64) != 0 {
		t.Fatalf("stranger list: %v", o)
	}
	// moved to public: everyone sees it; only the sharer can move or withdraw it
	if code, _ := c.do("PUT", "/v1/library/werds/"+id, b, map[string]any{"visibility": "public"}); code != 404 {
		t.Fatalf("someone else moved it: %d", code)
	}
	if code, _ := c.do("PUT", "/v1/library/werds/"+id, a, map[string]any{"visibility": "public"}); code != 200 {
		t.Fatalf("move: %d", code)
	}
	if _, o := c.do("GET", "/v1/library/werds?q=الصباح", "", nil); o["total"].(float64) != 1 {
		t.Fatalf("public search: %v", o)
	}
	if _, o := c.do("GET", "/v1/library/werds?scope=mine", a, nil); o["total"].(float64) != 1 || !o["werds"].([]any)[0].(map[string]any)["mine"].(bool) {
		t.Fatalf("mine: %v", o)
	}
	if code, _ := c.do("DELETE", "/v1/library/werds/"+id, z, nil); code != 404 {
		t.Fatalf("stranger withdrew it: %d", code)
	}
	if code, _ := c.do("DELETE", "/v1/library/werds/"+id, a, nil); code != 200 {
		t.Fatalf("withdraw: %d", code)
	}
	if code, _ := c.do("GET", "/v1/library/werds/"+id, a, nil); code != 404 {
		t.Fatalf("still there after withdraw: %d", code)
	}
}
