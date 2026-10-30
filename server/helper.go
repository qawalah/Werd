package main

// A PC that helps this server. The family server (usually the NAS, always on) keeps everything; a stronger PC
// running the Windows server in "helper" mode asks this server for work every few seconds and does the heavy
// part: AI page reading on its graphics card and the graphics-card voice. The PC connects out, so Windows
// needs no open port. When the PC is asleep, this server reads pages itself, phones fall back to their own
// voice, and big jobs (a whole book's audio) wake the PC with a Wake-on-LAN packet and wait for it.

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// HelperConfig lives on the main server.
type HelperConfig struct {
	Key    string `json:"key"`    // the password the PC uses
	Wake   bool   `json:"wake"`   // wake the PC for work it is needed for
	Prefer bool   `json:"prefer"` // send page reading to the PC whenever it is awake (it is faster)
	MAC    string `json:"mac"`    // learned from the PC (or typed in)
	IP     string `json:"ip"`     // its home-network address, for the wake packet
	Manual bool   `json:"manual"` // MAC typed by hand: don't replace it with the learned one
}

// HelperStatus is what the PC reports with every request for work.
type HelperStatus struct {
	Name    string     `json:"name"`
	Version string     `json:"version"`
	OS      string     `json:"os"`
	Cores   int        `json:"cores"`
	CPU     float64    `json:"cpu"`
	MemUsed int64      `json:"memUsed"`
	MemTot  int64      `json:"memTotal"`
	GPU     *GPUInfo   `json:"gpu,omitempty"`
	OCR     HelperOCR  `json:"ocr"`
	Voices  []string   `json:"voices"` // "kareem", "cb", …
	Busy    bool       `json:"busy"`
	Ifaces  []NetIface `json:"ifaces,omitempty"`
}

type HelperOCR struct {
	Engine     bool     `json:"engine"`
	GPU        bool     `json:"gpu"`
	GPUProblem string   `json:"gpuProblem,omitempty"` // the CUDA engine fell back to the processor, and why
	Models     []string `json:"models"`
	Default    string   `json:"default"`
}

type NetIface struct {
	Name string   `json:"name"`
	MAC  string   `json:"mac"`
	IPs  []string `json:"ips"`
}

type helperJob struct {
	ID      string          `json:"id"`
	Kind    string          `json:"kind"` // ocr | tts | download
	Body    json.RawMessage `json:"body"`
	Who     string          `json:"-"`
	What    string          `json:"-"`
	res     chan helperResult
	taken   time.Time
	created time.Time
}

type helperResult struct {
	Data []byte
	Ext  string
	Err  string
}

// HelperWork is a job the PC is doing (for the dashboard's Work table).
type HelperWork struct {
	Kind    string `json:"kind"`
	Who     string `json:"who"`
	What    string `json:"what"`
	State   string `json:"state"` // waiting | running
	Started int64  `json:"startedAt"`
}

type Hub struct {
	st  *Store
	act *Activity
	log func(string, ...any)

	mu       sync.Mutex
	status   *HelperStatus
	seen     time.Time
	addr     string
	queue    []*helperJob
	taken    map[string]*helperJob
	notify   chan struct{}
	cancels  []string // jobs the PC took that nobody wants any more: told on its next poll
	lastWake time.Time
	online   bool
}

func NewHub(st *Store, act *Activity, logf func(string, ...any)) *Hub {
	h := &Hub{st: st, act: act, log: logf, taken: map[string]*helperJob{}, notify: make(chan struct{}, 1)}
	var last struct {
		Status *HelperStatus `json:"status"`
		Seen   int64         `json:"seen"`
	}
	if st.getMeta(context.Background(), "helper_last", &last) && last.Status != nil {
		h.status, h.seen = last.Status, time.UnixMilli(last.Seen)
	}
	go h.watch()
	return h
}

func (h *Hub) Config(ctx context.Context) HelperConfig {
	var c HelperConfig
	h.st.getMeta(ctx, "helper", &c)
	return c
}

func (h *Hub) SetConfig(ctx context.Context, c HelperConfig) error {
	return h.st.setMeta(ctx, "helper", c)
}

// Online: the PC asked for work in the last 45 seconds.
func (h *Hub) Online() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.onlineLocked()
}

func (h *Hub) onlineLocked() bool { return h.status != nil && time.Since(h.seen) < 45*time.Second }

func (h *Hub) Status() (*HelperStatus, time.Time) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.status == nil {
		return nil, time.Time{}
	}
	s := *h.status
	return &s, h.seen
}

// Known: a PC has helped before (it may be asleep now).
func (h *Hub) Known() bool { s, _ := h.Status(); return s != nil }

// watch notes when the PC comes and goes, for the activity feed.
func (h *Hub) watch() {
	for range time.Tick(10 * time.Second) {
		h.mu.Lock()
		on := h.onlineLocked()
		changed := on != h.online
		h.online = on
		name := ""
		if h.status != nil {
			name = h.status.Name
		}
		// jobs nobody picked up for too long fail, so phones aren't left waiting
		for i := 0; i < len(h.queue); i++ {
			j := h.queue[i]
			if time.Since(j.created) > 20*time.Minute {
				j.res <- helperResult{Err: ErrPCAsleep.Error()}
				h.queue = append(h.queue[:i], h.queue[i+1:]...)
				i--
			}
		}
		h.mu.Unlock()
		if changed && name != "" {
			if on {
				h.act.Add(KindServer, "info", "helper.online", "", A{"name": name}, 0)
			} else {
				h.act.Add(KindServer, "info", "helper.offline", "", A{"name": name}, 0)
			}
		}
	}
}

// HasModel: the PC has reading model id ("" = any).
func (s *HelperStatus) HasModel(id string) bool {
	if s == nil || !s.OCR.Engine {
		return false
	}
	if id == "" {
		return len(s.OCR.Models) > 0
	}
	for _, m := range s.OCR.Models {
		if m == id {
			return true
		}
	}
	return false
}

func (s *HelperStatus) HasVoice(name string) bool {
	if s == nil {
		return false
	}
	base, _, _ := strings.Cut(name, ":")
	for _, v := range s.Voices {
		if v == base {
			return true
		}
	}
	return false
}

// Do hands a job to the PC and waits for its answer.
func (h *Hub) Do(ctx context.Context, kind, who, what string, body any) (helperResult, error) {
	b, _ := json.Marshal(body)
	j := &helperJob{ID: randHex(8), Kind: kind, Body: b, Who: who, What: what, res: make(chan helperResult, 1), created: time.Now()}
	h.mu.Lock()
	h.queue = append(h.queue, j)
	h.mu.Unlock()
	select {
	case h.notify <- struct{}{}:
	default:
	}
	select {
	case r := <-j.res:
		if r.Err != "" {
			return r, errors.New(r.Err)
		}
		return r, nil
	case <-ctx.Done():
		h.mu.Lock()
		for i, q := range h.queue {
			if q == j {
				h.queue = append(h.queue[:i], h.queue[i+1:]...)
				break
			}
		}
		if _, ok := h.taken[j.ID]; ok { // the PC is working on it: tell it to stop
			h.cancels = append(h.cancels, j.ID)
			delete(h.taken, j.ID)
		}
		h.mu.Unlock()
		select {
		case h.notify <- struct{}{}:
		default:
		}
		return helperResult{}, ctx.Err()
	}
}

// Work lists what the PC is doing and what waits for it.
func (h *Hub) Work() []HelperWork {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := []HelperWork{}
	for _, j := range h.taken {
		out = append(out, HelperWork{Kind: j.Kind, Who: j.Who, What: j.What, State: "running", Started: j.taken.UnixMilli()})
	}
	for _, j := range h.queue {
		out = append(out, HelperWork{Kind: j.Kind, Who: j.Who, What: j.What, State: "waiting", Started: j.created.UnixMilli()})
	}
	return out
}

// WaitOnline waits until the PC is back (after a wake), up to d.
func (h *Hub) WaitOnline(ctx context.Context, d time.Duration) bool {
	end := time.Now().Add(d)
	for time.Now().Before(end) {
		if h.Online() {
			return true
		}
		select {
		case <-ctx.Done():
			return false
		case <-time.After(2 * time.Second):
		}
	}
	return h.Online()
}

// Wake sends the wake packet if waking is on and a MAC is known (at most once a minute).
func (h *Hub) Wake(ctx context.Context, force bool) error {
	c := h.Config(ctx)
	if !c.Wake && !force {
		return errors.New("waking the PC is off")
	}
	if c.MAC == "" {
		return errors.New("the PC's network card isn't known yet: connect the PC once, or type its MAC address")
	}
	h.mu.Lock()
	if !force && time.Since(h.lastWake) < time.Minute {
		h.mu.Unlock()
		return nil
	}
	h.lastWake = time.Now()
	h.mu.Unlock()
	if err := sendWOL(c.MAC, c.IP); err != nil {
		return err
	}
	name := "PC"
	if s, _ := h.Status(); s != nil {
		name = s.Name
	}
	h.act.Add(KindServer, "info", "helper.wake", "", A{"name": name}, 5*time.Minute)
	return nil
}

// ------------------------------------------------------------------ HTTP: the PC's side of the conversation

func (s *Server) helperAuth(w http.ResponseWriter, r *http.Request) bool {
	c := s.hub.Config(r.Context())
	k := strings.TrimPrefix(r.Header.Get("Authorization"), "Helper ")
	if c.Key == "" || k == "" || !constEq(k, c.Key) {
		writeErr(w, 401, "unauthorized", "Wrong helper key (dashboard → Settings → PC helper).", nil)
		return false
	}
	return true
}

func constEq(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var v byte
	for i := 0; i < len(a); i++ {
		v |= a[i] ^ b[i]
	}
	return v == 0
}

// POST /v1/helper/poll — the PC's heartbeat; answers with a job, or 204 after up to 25 seconds.
func (s *Server) helperPoll(w http.ResponseWriter, r *http.Request) {
	if !s.helperAuth(w, r) {
		return
	}
	var st HelperStatus
	if !s.readJSON(w, r, &st) {
		return
	}
	h := s.hub
	ip := s.clientIP(r)
	h.mu.Lock()
	first := h.status == nil
	h.status, h.seen, h.addr = &st, time.Now(), ip
	h.mu.Unlock()
	if first || time.Now().Unix()%60 < 6 {
		_ = s.st.setMeta(r.Context(), "helper_last", map[string]any{"status": st, "seen": now()})
	}
	s.learnMAC(r.Context(), &st, ip)
	// jobs to abandon go out with every answer
	tellCancels := func() {
		h.mu.Lock()
		if len(h.cancels) > 0 {
			w.Header().Set("X-Werd-Cancel", strings.Join(h.cancels, ","))
			h.cancels = nil
		}
		h.mu.Unlock()
	}
	hasCancels := func() bool { h.mu.Lock(); defer h.mu.Unlock(); return len(h.cancels) > 0 }
	if st.Busy { // a heartbeat while it works: no new job, but a cancel is passed on at once
		end := time.Now().Add(20 * time.Second)
		for !hasCancels() {
			left := time.Until(end)
			if left <= 0 || !h.waitNotify(r.Context(), left) {
				break
			}
		}
		tellCancels()
		w.WriteHeader(204)
		return
	}
	deadline := time.Now().Add(25 * time.Second)
	for {
		tellCancels()
		h.mu.Lock()
		if len(h.queue) > 0 {
			j := h.queue[0]
			h.queue = h.queue[1:]
			j.taken = time.Now()
			h.taken[j.ID] = j
			h.mu.Unlock()
			writeJSON(w, 200, j)
			return
		}
		h.mu.Unlock()
		left := time.Until(deadline)
		if left <= 0 || !h.waitNotify(r.Context(), left) {
			w.WriteHeader(204)
			return
		}
	}
}

func (h *Hub) waitNotify(ctx context.Context, d time.Duration) bool {
	select {
	case <-h.notify:
		return true
	case <-time.After(d):
		return false
	case <-ctx.Done():
		return false
	}
}

// POST /v1/helper/jobs/{id} — the PC's answer: the audio or text as the body, or X-Werd-Error.
func (s *Server) helperResult(w http.ResponseWriter, r *http.Request) {
	if !s.helperAuth(w, r) {
		return
	}
	id := r.PathValue("id")
	s.hub.mu.Lock()
	j := s.hub.taken[id]
	delete(s.hub.taken, id)
	s.hub.mu.Unlock()
	if j == nil {
		writeErr(w, 404, "not_found", "No such job (it may have timed out).", nil)
		return
	}
	body, _ := io.ReadAll(io.LimitReader(r.Body, 80<<20))
	j.res <- helperResult{Data: body, Ext: r.Header.Get("X-Werd-Ext"), Err: r.Header.Get("X-Werd-Error")}
	writeJSON(w, 200, map[string]any{})
}

// GET /v1/helper/refs/{id} — a voice sample for the graphics-card voice.
func (s *Server) helperRef(w http.ResponseWriter, r *http.Request) {
	if !s.helperAuth(w, r) {
		return
	}
	id := r.PathValue("id")
	if s.voices.Ref(id) == nil {
		writeErr(w, 404, "not_found", "No such voice sample.", nil)
		return
	}
	http.ServeFile(w, r, s.voices.RefPath(id))
}

// learnMAC remembers the PC's network card so it can be woken (the one it used to reach this server).
func (s *Server) learnMAC(ctx context.Context, st *HelperStatus, ip string) {
	c := s.hub.Config(ctx)
	if c.Manual && c.MAC != "" {
		return
	}
	var pick *NetIface
	for i := range st.Ifaces {
		for _, a := range st.Ifaces[i].IPs {
			if a == ip {
				pick = &st.Ifaces[i]
			}
		}
	}
	if pick == nil { // came over Tailscale: take its home-network card
		for i := range st.Ifaces {
			for _, a := range st.Ifaces[i].IPs {
				if p := net.ParseIP(a); p != nil && p.To4() != nil && p.IsPrivate() && pick == nil {
					pick = &st.Ifaces[i]
				}
			}
		}
	}
	if pick == nil || pick.MAC == "" {
		return
	}
	ipv4 := ""
	for _, a := range pick.IPs {
		if p := net.ParseIP(a); p != nil && p.To4() != nil {
			ipv4 = a
			break
		}
	}
	if c.MAC != pick.MAC || c.IP != ipv4 {
		c.MAC, c.IP = pick.MAC, ipv4
		_ = s.hub.SetConfig(ctx, c)
	}
}

// ------------------------------------------------------------------ routing: here or on the PC

type waitKey struct{}

// patient marks work that may wait for the PC to wake (a book's audio, not a phone waiting for one line).
func patient(ctx context.Context) context.Context { return context.WithValue(ctx, waitKey{}, true) }
func isPatient(ctx context.Context) bool          { v, _ := ctx.Value(waitKey{}).(bool); return v }

// readPage reads a page here or on the PC, whichever is right now.
func (s *Server) readPage(ctx context.Context, model string, img []byte, who string) (text, used string, where string, err error) {
	h := s.hub
	cfg := h.Config(ctx)
	st, _ := h.Status()
	localOK := s.ocr.Engine() != "" && ((model == "" && s.ocr.Default(ctx) != "") || (model != "" && s.ocr.Installed(model)))
	pcHas := st.HasModel(model)
	usePC := h.Online() && pcHas && (cfg.Prefer || !localOK)
	if !usePC && !localOK && pcHas && cfg.Wake {
		if err := h.Wake(ctx, false); err == nil && h.WaitOnline(ctx, 3*time.Minute) {
			usePC = true
		}
	}
	if k := taskOf(ctx); k != nil {
		where := "here"
		if usePC {
			where = "pc"
		}
		s.tasks.Set(k, "running", where)
	}
	if !usePC {
		t, id, err := s.ocr.Read(ctx, model, img, who)
		return t, id, "here", err
	}
	what := model
	if m := ocrModel(model); m != nil {
		what = m.Name
	}
	res, err := h.Do(ctx, "ocr", who, what, map[string]any{"image": base64.StdEncoding.EncodeToString(img), "model": model, "who": who})
	if err != nil {
		if localOK && ctx.Err() == nil { // the PC failed: try here
			t, id, err2 := s.ocr.Read(ctx, model, img, who)
			return t, id, "here", err2
		}
		return "", model, "pc", err
	}
	var out struct{ Text, Model string }
	_ = json.Unmarshal(res.Data, &out)
	return out.Text, out.Model, "pc", nil
}

// speakLocal makes a clip for a "local:" voice here or on the PC.
func (s *Server) speakLocal(ctx context.Context, name, text string) ([]byte, string, error) {
	sha := ""
	if _, id, ok := strings.Cut(name, ":"); ok {
		if r := s.voices.Ref(id); r != nil {
			sha = r.Sha
		} else {
			return nil, "", errors.New("this voice sample was deleted")
		}
	}
	if s.voices.Can(name) {
		b, err := s.voices.Speak(ctx, name, sha, text)
		return b, "wav", err
	}
	h := s.hub
	st, _ := h.Status()
	if !st.HasVoice(name) {
		return nil, "", ErrNoLocalVoice
	}
	if !h.Online() {
		cfg := h.Config(ctx)
		if cfg.Wake {
			_ = h.Wake(ctx, false)
		}
		if !cfg.Wake || !isPatient(ctx) || !h.WaitOnline(ctx, 4*time.Minute) {
			return nil, "", ErrPCAsleep
		}
	}
	res, err := h.Do(ctx, "tts", "", text, map[string]any{"voice": name, "sha": sha, "text": text})
	if err != nil {
		return nil, "", err
	}
	ext := res.Ext
	if ext == "" {
		ext = "wav"
	}
	return res.Data, ext, nil
}

// ------------------------------------------------------------------ Wake-on-LAN

// sendWOL sends the magic packet to every broadcast address this machine can reach, and to the PC's address.
func sendWOL(mac, ip string) error {
	hw, err := net.ParseMAC(mac)
	if err != nil || len(hw) != 6 {
		return fmt.Errorf("not a MAC address: %q", mac)
	}
	pkt := make([]byte, 0, 102)
	for i := 0; i < 6; i++ {
		pkt = append(pkt, 0xff)
	}
	for i := 0; i < 16; i++ {
		pkt = append(pkt, hw...)
	}
	targets := map[string]bool{"255.255.255.255": true}
	if p := net.ParseIP(ip); p != nil && p.To4() != nil {
		targets[ip] = true
		b := p.To4()
		targets[net.IPv4(b[0], b[1], b[2], 255).String()] = true // the usual /24 home network
	}
	if ifs, err := net.Interfaces(); err == nil {
		for _, i := range ifs {
			if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
				continue
			}
			addrs, _ := i.Addrs()
			for _, a := range addrs {
				if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil {
					ip4, m := n.IP.To4(), n.Mask
					if len(m) == 16 {
						m = m[12:]
					}
					bc := make(net.IP, 4)
					for k := 0; k < 4; k++ {
						bc[k] = ip4[k] | ^m[k]
					}
					targets[bc.String()] = true
				}
			}
		}
	}
	conn, err := net.ListenPacket("udp4", ":0")
	if err != nil {
		return err
	}
	defer conn.Close()
	sent := 0
	for t := range targets {
		for _, port := range []int{9, 7} {
			if _, err := conn.WriteTo(pkt, &net.UDPAddr{IP: net.ParseIP(t), Port: port}); err == nil {
				sent++
			}
		}
	}
	if sent == 0 {
		return errors.New("couldn't send the wake packet")
	}
	return nil
}
