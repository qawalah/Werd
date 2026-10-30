package main

import "testing"

func TestAccountsSignIn(t *testing.T) {
	c, _, _ := newTestServer(t)
	admin := "secret-admin"
	// a name with a password is unique (case, tashkeel and alef forms ignored)
	code, o := c.do("POST", "/v1/accounts", "", map[string]any{"displayName": "أبو حمزة", "password": "sabr1234"})
	if code != 201 {
		t.Fatalf("create: %d %v", code, o)
	}
	uid, tok1 := o["userId"].(string), o["token"].(string)
	if code, o := c.do("POST", "/v1/accounts", "", map[string]any{"displayName": "ابو  حَمزة", "password": "other123"}); code != 409 || errCode(o) != "name_taken" {
		t.Fatalf("same name again: %d %v", code, o)
	}
	if code, o := c.do("POST", "/v1/accounts", "", map[string]any{"displayName": "زيد", "password": "123"}); code != 422 || errCode(o) != "weak_password" {
		t.Fatalf("weak: %d %v", code, o)
	}
	// a new phone signs in and gets the same account; the first phone stays signed in
	if code, o := c.do("POST", "/v1/accounts/signin", "", map[string]any{"name": "ابو حمزه", "password": "wrong!!"}); code != 401 {
		t.Fatalf("wrong password: %d %v", code, o)
	}
	code, o = c.do("POST", "/v1/accounts/signin", "", map[string]any{"name": "ابو حمزه", "password": "sabr1234"})
	if code != 200 || o["userId"] != uid {
		t.Fatalf("sign in: %d %v", code, o)
	}
	tok2 := o["token"].(string)
	for _, tk := range []string{tok1, tok2} {
		if code, o := c.do("GET", "/v1/accounts/me", tk, nil); code != 200 || o["userId"] != uid || o["hasPassword"] != true {
			t.Fatalf("me: %d %v", code, o)
		}
	}
	// changing the password needs the current one
	if code, _ := c.do("PUT", "/v1/accounts/me/password", tok2, map[string]any{"password": "newpass1"}); code != 403 {
		t.Fatalf("change without current: %d", code)
	}
	if code, _ := c.do("PUT", "/v1/accounts/me/password", tok2, map[string]any{"password": "newpass1", "current": "sabr1234"}); code != 200 {
		t.Fatalf("change: %d", code)
	}
	// forgot it: the owner gives a one-time code, which signs in and allows a new password without the old one
	code, o = c.do("POST", "/admin/api/users/"+uid+"/signin-code", admin, nil)
	if code != 200 {
		t.Fatalf("code: %d %v", code, o)
	}
	sc := o["code"].(string)
	code, o = c.do("POST", "/v1/accounts/signin", "", map[string]any{"code": sc})
	if code != 200 || o["userId"] != uid || o["canReset"] != true {
		t.Fatalf("code sign in: %d %v", code, o)
	}
	tok3 := o["token"].(string)
	if code, _ := c.do("POST", "/v1/accounts/signin", "", map[string]any{"code": sc}); code != 401 {
		t.Fatalf("code used twice: %d", code)
	}
	if code, _ := c.do("PUT", "/v1/accounts/me/password", tok3, map[string]any{"password": "fresh-pass"}); code != 200 {
		t.Fatalf("reset: %d", code)
	}
	// sign out of all phones: only the asking phone keeps a (new) token
	code, o = c.do("POST", "/v1/accounts/me/signout-all", tok3, nil)
	if code != 200 {
		t.Fatalf("signout-all: %d", code)
	}
	for _, tk := range []string{tok1, tok2, tok3} {
		if code, _ := c.do("GET", "/v1/accounts/me", tk, nil); code != 401 {
			t.Fatalf("old token still works: %d", code)
		}
	}
	if code, _ := c.do("GET", "/v1/accounts/me", o["token"].(string), nil); code != 200 {
		t.Fatalf("new token: %d", code)
	}
	// an account without a password can't sign out (it could never come back)
	_, old := c.account("قديم")
	if code, o := c.do("DELETE", "/v1/accounts/me/session", old, nil); code != 409 || errCode(o) != "no_password" {
		t.Fatalf("sign out without password: %d %v", code, o)
	}
}

func TestMergeDuplicateAccounts(t *testing.T) {
	c, _, _ := newTestServer(t)
	admin := "secret-admin"
	// the same person signed up twice before passwords existed; the old account has a family and a shared werd
	oldID, oldTok := c.account("أم محمد")
	newID, newTok := c.account("ام محمد")
	code, f := c.do("POST", "/v1/families", oldTok, map[string]any{"name": "البيت"})
	if code != 201 && code != 200 {
		t.Fatalf("family: %d %v", code, f)
	}
	werd := map[string]any{"title": "ورد", "visibility": "public", "lines": []map[string]any{{"text": "سبحان الله", "count": 33}}}
	if code, o := c.do("POST", "/v1/library/werds", oldTok, werd); code != 201 {
		t.Fatalf("share: %d %v", code, o)
	}
	_, o := c.do("GET", "/admin/api/users/duplicates", admin, nil)
	gs := o["groups"].([]any)
	if len(gs) != 1 || len(gs[0].(map[string]any)["users"].([]any)) != 2 {
		t.Fatalf("duplicates: %v", o)
	}
	if code, o := c.do("POST", "/admin/api/users/"+oldID+"/merge", admin, map[string]any{"into": newID}); code != 200 {
		t.Fatalf("merge: %d %v", code, o)
	}
	// both phones are now the new account, which has the family and the werd
	for _, tk := range []string{oldTok, newTok} {
		if code, o := c.do("GET", "/v1/accounts/me", tk, nil); code != 200 || o["userId"] != newID {
			t.Fatalf("after merge: %d %v", code, o)
		}
	}
	if code, o := c.do("GET", "/v1/families/me", newTok, nil); code != 200 {
		t.Fatalf("family moved: %d %v", code, o)
	}
	if _, o := c.do("GET", "/v1/library/werds?scope=mine", newTok, nil); o["total"].(float64) != 1 {
		t.Fatalf("werd moved: %v", o)
	}
	if _, o := c.do("GET", "/admin/api/users/duplicates", admin, nil); len(o["groups"].([]any)) != 0 {
		t.Fatalf("still duplicated: %v", o)
	}
	// adding a password claims the name
	if code, _ := c.do("PUT", "/v1/accounts/me/password", newTok, map[string]any{"password": "family99"}); code != 200 {
		t.Fatalf("password: %d", code)
	}
	if code, o := c.do("POST", "/v1/accounts", "", map[string]any{"displayName": "أم مُحمّد", "password": "family99"}); code != 409 {
		t.Fatalf("claimed name: %d %v", code, o)
	}
}
