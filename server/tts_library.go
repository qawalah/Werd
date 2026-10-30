package main

// The server's sound library: every clip it keeps, which voice it belongs to, voices phones share with it,
// and background jobs that make a whole book's audio ahead of time.

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const ttsSchema = `
CREATE TABLE IF NOT EXISTS tts_clips (
  key TEXT PRIMARY KEY,
  voice TEXT NOT NULL,
  ext TEXT NOT NULL,
  bytes INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS tts_clips_voice ON tts_clips(voice);
CREATE TABLE IF NOT EXISTS tts_voices (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  shared_by TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
`

// sharedKey is where a shared voice's clip of text is kept.
func sharedKey(voice, text string) string {
	h := sha256.Sum256([]byte(voice + "\x00\x00" + strings.TrimSpace(text)))
	return hex.EncodeToString(h[:])
}

func (t *TTS) findClip(key string) ([]byte, string, bool) {
	for _, e := range []string{"mp3", "wav"} {
		if b, err := os.ReadFile(filepath.Join(t.dir, key+"."+e)); err == nil && len(b) > 0 {
			return b, e, true
		}
	}
	return nil, "", false
}

func (t *TTS) saveClip(ctx context.Context, key, voice, ext string, data []byte) {
	tmp := filepath.Join(t.dir, key+".tmp")
	if os.WriteFile(tmp, data, 0o644) != nil {
		return
	}
	if os.Rename(tmp, filepath.Join(t.dir, key+"."+ext)) != nil {
		return
	}
	_, _ = t.st.db.ExecContext(ctx, `INSERT OR REPLACE INTO tts_clips(key, voice, ext, bytes, created_at) VALUES (?,?,?,?,?)`,
		key, voice, ext, len(data), now())
}

// ShareClip stores a clip a phone made with its own voice (on-phone or with its own key), so other phones can use it.
func (t *TTS) ShareClip(ctx context.Context, u *User, voiceID, voiceName, text, ext string, data []byte) error {
	if _, err := t.st.db.ExecContext(ctx, `INSERT INTO tts_voices(id, name, shared_by, created_at) VALUES (?,?,?,?)
		ON CONFLICT(id) DO UPDATE SET name = excluded.name`, voiceID, truncRunes(voiceName, 80), u.Name, now()); err != nil {
		return err
	}
	key := sharedKey(voiceID, text)
	if _, _, ok := t.findClip(key); ok {
		return nil
	}
	t.saveClip(ctx, key, voiceID, ext, data)
	return nil
}

// LibraryVoice is a voice with clips on the server.
type LibraryVoice struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Shared bool   `json:"shared"` // made by phones (the server can't add to it)
	Clips  int    `json:"clips"`
	Bytes  int64  `json:"bytes"`
}

func (t *TTS) Library(ctx context.Context) []LibraryVoice {
	out := []LibraryVoice{}
	rows, err := t.st.db.QueryContext(ctx, `SELECT c.voice, COALESCE(v.name, ''), v.id IS NOT NULL, COUNT(*), SUM(c.bytes)
		FROM tts_clips c LEFT JOIN tts_voices v ON v.id = c.voice GROUP BY c.voice ORDER BY COUNT(*) DESC`)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var v LibraryVoice
		if rows.Scan(&v.ID, &v.Name, &v.Shared, &v.Clips, &v.Bytes) == nil {
			if v.Name == "" {
				v.Name = v.ID
			}
			out = append(out, v)
		}
	}
	return out
}

// sharedVoices lists the voices phones shared, for the voice picker.
func (t *TTS) sharedVoices(ctx context.Context) []Voice {
	out := []Voice{}
	for _, v := range t.Library(ctx) {
		if v.Shared {
			out = append(out, Voice{ID: v.ID, Provider: "shared", Name: v.Name, Detail: itoa(v.Clips)})
		}
	}
	return out
}

func itoa(n int) string {
	b, _ := json.Marshal(n)
	return string(b)
}

// ------------------------------------------------------------------ background jobs: a whole book's audio

type TTSJob struct {
	ID       int64  `json:"id"`
	BookID   string `json:"bookId"`
	Title    string `json:"title"`
	Voice    string `json:"voice"`
	Total    int    `json:"total"`
	Done     int    `json:"done"`
	Made     int    `json:"made"` // new clips (the rest were already in the library)
	State    string `json:"state"`
	Error    string `json:"error,omitempty"`
	Started  int64  `json:"startedAt,omitempty"`
	Finished int64  `json:"finishedAt,omitempty"`
	cancel   context.CancelFunc
}

type TTSJobs struct {
	mu   sync.Mutex
	next int64
	jobs []*TTSJob
	wake chan struct{}
}

func (s *Server) startJobs(ctx context.Context) {
	s.jobs = &TTSJobs{wake: make(chan struct{}, 1)}
	go s.runJobs(ctx)
}

// QueueBook adds a job making every supplication of a library book in voice ("" = default). Duplicate waiting jobs are skipped.
func (s *Server) QueueBook(bookID, voice string) (*TTSJob, error) {
	_, sum, err := s.st.Book(context.Background(), bookID, false, true, "")
	if err != nil {
		return nil, err
	}
	if voice == "" {
		voice = s.tts.Config(context.Background()).Default
	}
	if voice == "" || strings.HasPrefix(voice, "shared:") {
		return nil, ErrTTSOff
	}
	j := s.jobs
	j.mu.Lock()
	defer j.mu.Unlock()
	for _, x := range j.jobs {
		if x.BookID == bookID && x.Voice == voice && (x.State == "queued" || x.State == "running") {
			return x, nil
		}
	}
	j.next++
	job := &TTSJob{ID: j.next, BookID: bookID, Title: sum.Title, Voice: voice, State: "queued"}
	j.jobs = append(j.jobs, job)
	if len(j.jobs) > 50 { // keep the last few
		j.jobs = j.jobs[len(j.jobs)-50:]
	}
	select {
	case j.wake <- struct{}{}:
	default:
	}
	return job, nil
}

func (s *Server) Jobs() []TTSJob {
	s.jobs.mu.Lock()
	defer s.jobs.mu.Unlock()
	out := make([]TTSJob, 0, len(s.jobs.jobs))
	for i := len(s.jobs.jobs) - 1; i >= 0; i-- {
		out = append(out, *s.jobs.jobs[i])
	}
	return out
}

// CancelJob stops a book audio job (id), or every waiting and running one (id 0). Returns how many.
func (s *Server) CancelJob(id int64) int {
	s.jobs.mu.Lock()
	n := 0
	var stopped []*TTSJob
	for _, x := range s.jobs.jobs {
		if (id == 0 || x.ID == id) && (x.State == "queued" || x.State == "running") {
			if x.cancel != nil {
				x.cancel()
			}
			x.State, x.Finished, x.Error = "cancelled", now(), ""
			stopped = append(stopped, x)
			n++
		}
	}
	s.jobs.mu.Unlock()
	for _, x := range stopped {
		s.act.Add(KindVoice, "info", "voice.job.cancelled", "", A{"title": x.Title, "voice": x.Voice, "done": x.Done, "total": x.Total}, 0)
	}
	return n
}

func (s *Server) nextJob() *TTSJob {
	s.jobs.mu.Lock()
	defer s.jobs.mu.Unlock()
	for _, x := range s.jobs.jobs {
		if x.State == "queued" {
			x.State, x.Started = "running", now()
			return x
		}
	}
	return nil
}

func (s *Server) update(job *TTSJob, f func(*TTSJob)) {
	s.jobs.mu.Lock()
	f(job)
	s.jobs.mu.Unlock()
}

func (s *Server) runJobs(ctx context.Context) {
	for {
		job := s.nextJob()
		if job == nil {
			select {
			case <-ctx.Done():
				return
			case <-s.jobs.wake:
				continue
			case <-time.After(30 * time.Second):
				continue
			}
		}
		s.runJob(ctx, job)
	}
}

func (s *Server) runJob(parent context.Context, job *TTSJob) {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	s.update(job, func(j *TTSJob) { j.cancel = cancel })
	stopped := func() bool {
		s.jobs.mu.Lock()
		defer s.jobs.mu.Unlock()
		return job.State == "cancelled"
	}
	doc, _, err := s.st.Book(ctx, job.BookID, false, true, "")
	if err != nil {
		s.update(job, func(j *TTSJob) { j.State, j.Error, j.Finished = "failed", err.Error(), now() })
		return
	}
	s.act.Add(KindVoice, "info", "voice.job.started", "", A{"title": job.Title, "voice": job.Voice}, 0)
	var texts []string
	seen := map[string]bool{}
	if items, ok := doc["items"].([]any); ok {
		for _, it := range items {
			if m, ok := it.(map[string]any); ok {
				if t, _ := m["text"].(string); strings.TrimSpace(t) != "" && !seen[strings.TrimSpace(t)] {
					seen[strings.TrimSpace(t)] = true
					texts = append(texts, strings.TrimSpace(t))
				}
			}
		}
	}
	s.update(job, func(j *TTSJob) { j.Total = len(texts) })
	fails := 0
	for _, t := range texts {
		if ctx.Err() != nil || stopped() {
			return
		}
		_, _, cached, err := s.tts.Speak(patient(ctx), t, job.Voice)
		for tries := 0; errors.Is(err, ErrPCAsleep) && tries < 120 && ctx.Err() == nil; tries++ {
			// the voice is made on the PC: wait for it (it is woken if waking is on), up to two hours
			s.update(job, func(j *TTSJob) { j.Error = "waiting for your PC" })
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Minute):
			}
			_, _, cached, err = s.tts.Speak(patient(ctx), t, job.Voice)
		}
		if stopped() {
			return
		}
		s.update(job, func(j *TTSJob) { j.Error = "" })
		if err != nil {
			fails++
			s.log.Printf("tts job %d: %v", job.ID, err)
			if fails >= 3 { // the key, quota or voice is wrong: stop rather than burn through the book
				s.update(job, func(j *TTSJob) { j.State, j.Error, j.Finished = "failed", truncRunes(err.Error(), 300), now() })
				s.act.Add(KindVoice, "error", "voice.job.failed", "", A{"title": job.Title, "voice": job.Voice, "done": job.Done, "total": job.Total, "error": truncRunes(err.Error(), 200)}, 0)
				return
			}
			continue
		}
		fails = 0
		s.update(job, func(j *TTSJob) {
			j.Done++
			if !cached {
				j.Made++
			}
		})
	}
	if stopped() {
		return
	}
	s.update(job, func(j *TTSJob) { j.State, j.Finished = "done", now() })
	s.act.Add(KindVoice, "info", "voice.job.done", "", A{"title": job.Title, "voice": job.Voice, "total": job.Total, "made": job.Made}, 0)
}

// ------------------------------------------------------------------ HTTP

// POST /v1/tts/clips — a phone shares a clip it made: {"voice": "shared:<id>", "name": "Piper · Kareem", "text": "…", "ext": "wav", "audio": "<base64>"}
func (s *Server) ttsShare(w http.ResponseWriter, r *http.Request, u *User) {
	if !s.limit(w, "ttsshare", u.ID, 6000, 2000) {
		return
	}
	var in struct {
		Voice, Name, Text, Ext, Audio string
	}
	if !s.readJSON(w, r, &in) {
		return
	}
	in.Text = strings.TrimSpace(in.Text)
	if !strings.HasPrefix(in.Voice, "shared:") || len(in.Voice) > 80 || in.Text == "" || len([]rune(in.Text)) > maxTTSText ||
		(in.Ext != "wav" && in.Ext != "mp3") {
		writeErr(w, 422, "clip", "voice must be shared:<id>, with text and a wav or mp3 clip.", nil)
		return
	}
	data, err := base64.StdEncoding.DecodeString(in.Audio)
	if err != nil || len(data) < 32 {
		writeErr(w, 422, "audio", "audio must be base64.", nil)
		return
	}
	head := string(data[:4])
	if (in.Ext == "wav" && head != "RIFF") || (in.Ext == "mp3" && head[:3] != "ID3" && data[0] != 0xFF) {
		writeErr(w, 422, "audio", "That is not a "+in.Ext+" file.", nil)
		return
	}
	if err := s.tts.ShareClip(r.Context(), u, in.Voice, in.Name, in.Text, in.Ext, data); err != nil {
		s.internal(w, err)
		return
	}
	s.act.Add(KindVoice, "info", "voice.shared", u.ID, A{"name": u.Name, "voice": in.Name}, 15*time.Minute)
	writeJSON(w, 201, map[string]any{})
}

func (s *Server) adminQueueBook(w http.ResponseWriter, r *http.Request) {
	var in struct{ Voice string }
	if !s.readJSON(w, r, &in) {
		return
	}
	job, err := s.QueueBook(r.PathValue("id"), in.Voice)
	switch {
	case errors.Is(err, ErrNotFound):
		writeErr(w, 404, "not_found", "No such book.", nil)
	case errors.Is(err, ErrTTSOff):
		writeErr(w, 422, "tts_off", "Set up a voice (Gemini, Azure or ElevenLabs key) first.", nil)
	case err != nil:
		s.internal(w, err)
	default:
		writeJSON(w, 202, job)
	}
}
