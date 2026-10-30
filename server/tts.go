package main

// Read-aloud voices on the server: the NAS turns text into speech with a cloud voice (Gemini, Azure or ElevenLabs),
// keeps every clip in data/tts, and serves it to the family's phones. A line read once is never paid for again,
// and every phone hears the same voice.

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// TTSConfig is kept in the database (admin page); WERD_* environment variables fill it on first start.
type TTSConfig struct {
	Default      string `json:"default"`      // "gemini:Charon", "azure:ar-SA-HamedNeural", "eleven:<voice id>"
	AutoGenerate bool   `json:"autoGenerate"` // make a book's audio when it is published
	AutoVoice    string `json:"autoVoice"`    // "" = Default
	GeminiKey    string `json:"geminiKey"`
	GeminiModel  string `json:"geminiModel"`
	GeminiStyle  string `json:"geminiStyle"`
	AzureKey     string `json:"azureKey"`
	AzureRegion  string `json:"azureRegion"`
	ElevenKey    string `json:"elevenKey"`
	ElevenModel  string `json:"elevenModel"`
}

const (
	defaultGeminiModel = "gemini-3.8-flash-tts"
	defaultElevenModel = "eleven_multilingual_v2"
	maxTTSText         = 4000
)

var geminiVoices = []string{"Charon", "Kore", "Orus", "Puck", "Fenrir", "Leda", "Aoede", "Zephyr", "Enceladus", "Iapetus",
	"Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar",
	"Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat", "Callirrhoe", "Autonoe"}

type Voice struct {
	ID       string `json:"id"` // provider:voice
	Provider string `json:"provider"`
	Name     string `json:"name"`
	Detail   string `json:"detail,omitempty"`
	Asleep   bool   `json:"asleep,omitempty"` // made on the PC, which is asleep now: only lines made before play
}

type TTS struct {
	st   *Store
	dir  string
	mu   sync.Mutex
	hc   *http.Client
	list struct {
		sync.Mutex
		at     time.Time
		key    string
		voices []Voice
	}
	inflight sync.Map // cache key → *sync.Mutex, so two phones asking for the same line pay once

	// voices made with no cloud key, here or on the helping PC (set by the server)
	local     func(ctx context.Context, name, text string) ([]byte, string, error)
	localList func(ctx context.Context) []Voice
	localTag  func(name string) string // what changes a local voice's sound (so the cache knows)
}

func NewTTS(st *Store, dataDir string) *TTS {
	t := &TTS{st: st, dir: filepath.Join(dataDir, "tts"), hc: &http.Client{Timeout: 90 * time.Second}}
	_ = os.MkdirAll(t.dir, 0o755)
	// first start: take keys from the environment
	c := t.Config(context.Background())
	changed := false
	set := func(dst *string, env string) {
		if v := os.Getenv(env); v != "" && *dst == "" {
			*dst, changed = v, true
		}
	}
	set(&c.GeminiKey, "WERD_GEMINI_KEY")
	set(&c.AzureKey, "WERD_AZURE_KEY")
	set(&c.AzureRegion, "WERD_AZURE_REGION")
	set(&c.ElevenKey, "WERD_ELEVEN_KEY")
	set(&c.Default, "WERD_TTS_VOICE")
	if changed {
		_ = t.SetConfig(context.Background(), c)
	}
	return t
}

func (t *TTS) Config(ctx context.Context) TTSConfig {
	var c TTSConfig
	var raw string
	if err := t.st.db.QueryRowContext(ctx, `SELECT value FROM meta WHERE key = 'tts'`).Scan(&raw); err == nil {
		_ = json.Unmarshal([]byte(raw), &c)
	}
	if c.GeminiModel == "" {
		c.GeminiModel = defaultGeminiModel
	}
	if c.ElevenModel == "" {
		c.ElevenModel = defaultElevenModel
	}
	if c.AzureRegion == "" {
		c.AzureRegion = "uaenorth"
	}
	if c.Default == "" {
		switch {
		case c.GeminiKey != "":
			c.Default = "gemini:Charon"
		case c.AzureKey != "":
			c.Default = "azure:ar-SA-HamedNeural"
		}
	}
	return c
}

func (t *TTS) SetConfig(ctx context.Context, c TTSConfig) error {
	b, _ := json.Marshal(c)
	_, err := t.st.db.ExecContext(ctx, `INSERT OR REPLACE INTO meta(key, value) VALUES ('tts', ?)`, string(b))
	t.list.Lock()
	t.list.at = time.Time{}
	t.list.Unlock()
	return err
}

func (c TTSConfig) has(provider string) bool {
	switch provider {
	case "gemini":
		return c.GeminiKey != ""
	case "azure":
		return c.AzureKey != "" && c.AzureRegion != ""
	case "eleven":
		return c.ElevenKey != ""
	}
	return false
}

func (c TTSConfig) Enabled() bool { return c.has("gemini") || c.has("azure") || c.has("eleven") }

// ErrNotInLibrary: a voice shared by phones has no clip of this line yet (the server can't make it itself).
var ErrNotInLibrary = errors.New("this line is not in the shared voice's library yet")

// Voices lists the voices of every configured provider (Azure and ElevenLabs lists are fetched, then kept a day).
func (t *TTS) Voices(ctx context.Context) []Voice {
	c := t.Config(ctx)
	key := c.GeminiKey + "|" + c.AzureKey + c.AzureRegion + "|" + c.ElevenKey
	t.list.Lock()
	defer t.list.Unlock()
	if t.list.key == key && time.Since(t.list.at) < 24*time.Hour && t.list.voices != nil {
		return t.withLocal(ctx, t.list.voices)
	}
	out := []Voice{}
	if c.has("gemini") {
		for _, v := range geminiVoices {
			out = append(out, Voice{ID: "gemini:" + v, Provider: "gemini", Name: v})
		}
	}
	if c.has("azure") {
		if vs, err := t.azureVoices(ctx, c); err == nil {
			out = append(out, vs...)
		}
	}
	if c.has("eleven") {
		if vs, err := t.elevenVoices(ctx, c); err == nil {
			out = append(out, vs...)
		}
	}
	t.list.key, t.list.at, t.list.voices = key, time.Now(), out
	return t.withLocal(ctx, out)
}

func (t *TTS) withLocal(ctx context.Context, cloud []Voice) []Voice {
	if t.localList == nil {
		return cloud
	}
	return append(append([]Voice{}, t.localList(ctx)...), cloud...)
}

func (t *TTS) azureVoices(ctx context.Context, c TTSConfig) ([]Voice, error) {
	req, _ := http.NewRequestWithContext(ctx, "GET", "https://"+c.AzureRegion+".tts.speech.microsoft.com/cognitiveservices/voices/list", nil)
	req.Header.Set("Ocp-Apim-Subscription-Key", c.AzureKey)
	res, err := t.hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("azure voices: HTTP %d", res.StatusCode)
	}
	var list []struct {
		ShortName, LocalName, Gender, Locale, LocaleName string
	}
	if err := json.NewDecoder(res.Body).Decode(&list); err != nil {
		return nil, err
	}
	out := []Voice{}
	for _, v := range list {
		if strings.HasPrefix(v.Locale, "ar-") {
			out = append(out, Voice{ID: "azure:" + v.ShortName, Provider: "azure", Name: v.LocalName, Detail: v.LocaleName + " · " + v.Gender})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}

func (t *TTS) elevenVoices(ctx context.Context, c TTSConfig) ([]Voice, error) {
	req, _ := http.NewRequestWithContext(ctx, "GET", "https://api.elevenlabs.io/v1/voices", nil)
	req.Header.Set("xi-api-key", c.ElevenKey)
	res, err := t.hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("elevenlabs voices: HTTP %d", res.StatusCode)
	}
	var body struct {
		Voices []struct {
			VoiceID  string            `json:"voice_id"`
			Name     string            `json:"name"`
			Category string            `json:"category"`
			Labels   map[string]string `json:"labels"`
		} `json:"voices"`
	}
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		return nil, err
	}
	out := []Voice{}
	for _, v := range body.Voices {
		d := strings.TrimSpace(v.Labels["gender"] + " " + v.Labels["accent"])
		out = append(out, Voice{ID: "eleven:" + v.VoiceID, Provider: "eleven", Name: v.Name, Detail: d})
	}
	return out, nil
}

// ErrTTSOff: no provider is set up on the server.
var ErrTTSOff = errors.New("no voice is set up on the server")

// Speak returns the clip for text in voice ("" = the default), from the cache or freshly made. ext is "wav" or "mp3".
func (t *TTS) Speak(ctx context.Context, text, voice string) (data []byte, ext string, cached bool, err error) {
	c := t.Config(ctx)
	if voice == "" {
		voice = c.Default
	}
	if strings.HasPrefix(voice, "shared:") {
		// a voice phones made and shared: only what is in the library
		if b, e, ok := t.findClip(sharedKey(voice, text)); ok {
			return b, e, true, nil
		}
		return nil, "", false, ErrNotInLibrary
	}
	provider, name, _ := strings.Cut(voice, ":")
	if provider == "local" && t.local != nil {
		h := sha256.Sum256([]byte(voice + "\x00" + t.localTag(name) + "\x00" + text))
		key := hex.EncodeToString(h[:])
		if b, e, ok := t.findClip(key); ok {
			return b, e, true, nil
		}
		m, _ := t.inflight.LoadOrStore(key, &sync.Mutex{})
		mu := m.(*sync.Mutex)
		mu.Lock()
		defer mu.Unlock()
		if b, e, ok := t.findClip(key); ok {
			return b, e, true, nil
		}
		data, ext, err = t.local(ctx, name, text)
		if err != nil {
			return nil, "", false, err
		}
		t.saveClip(ctx, key, voice, ext, data)
		return data, ext, false, nil
	}
	if !c.has(provider) || name == "" {
		if !c.has(strings.SplitN(c.Default, ":", 2)[0]) {
			return nil, "", false, ErrTTSOff
		}
		voice = c.Default
		provider, name, _ = strings.Cut(voice, ":")
	}
	model := ""
	switch provider {
	case "gemini":
		model = c.GeminiModel + "|" + c.GeminiStyle
	case "eleven":
		model = c.ElevenModel
	}
	h := sha256.Sum256([]byte(voice + "\x00" + model + "\x00" + text))
	key := hex.EncodeToString(h[:])
	if b, e, ok := t.findClip(key); ok {
		return b, e, true, nil
	}
	m, _ := t.inflight.LoadOrStore(key, &sync.Mutex{})
	mu := m.(*sync.Mutex)
	mu.Lock()
	defer mu.Unlock()
	if b, e, ok := t.findClip(key); ok { // made while we waited?
		return b, e, true, nil
	}
	switch provider {
	case "gemini":
		data, err = t.gemini(ctx, c, name, text)
		ext = "wav"
	case "azure":
		data, err = t.azure(ctx, c, name, text)
		ext = "mp3"
	case "eleven":
		data, err = t.eleven(ctx, c, name, text)
		ext = "mp3"
	default:
		return nil, "", false, ErrTTSOff
	}
	if err != nil {
		return nil, "", false, err
	}
	t.saveClip(ctx, key, voice, ext, data)
	return data, ext, false, nil
}

// CacheSize returns the number of clips and their total size.
func (t *TTS) CacheSize() (int, int64) {
	n, size := 0, int64(0)
	entries, _ := os.ReadDir(t.dir)
	for _, e := range entries {
		if fi, err := e.Info(); err == nil && !e.IsDir() {
			n++
			size += fi.Size()
		}
	}
	return n, size
}

func (t *TTS) ClearCache() {
	entries, _ := os.ReadDir(t.dir)
	for _, e := range entries {
		_ = os.Remove(filepath.Join(t.dir, e.Name()))
	}
	_, _ = t.st.db.Exec(`DELETE FROM tts_clips`)
	_, _ = t.st.db.Exec(`DELETE FROM tts_voices`)
}

func apiErr(provider string, res *http.Response) error {
	b, _ := io.ReadAll(io.LimitReader(res.Body, 600))
	return fmt.Errorf("%s: HTTP %d %s", provider, res.StatusCode, strings.TrimSpace(string(b)))
}

// gemini speaks with the Interactions API; older keys/models fall back to generateContent.
func (t *TTS) gemini(ctx context.Context, c TTSConfig, voice, text string) ([]byte, error) {
	part := map[string]any{"type": "text", "text": text}
	if c.GeminiStyle != "" {
		part["annotations"] = []any{map[string]any{"type": "speech_metadata", "style": c.GeminiStyle}}
	}
	body := map[string]any{
		"model":             c.GeminiModel,
		"input":             []any{map[string]any{"type": "user_input", "content": []any{part}}},
		"response_format":   map[string]any{"type": "audio"},
		"generation_config": map[string]any{"speech_config": []any{map[string]any{"voice": voice}}},
	}
	b, err := t.postJSON(ctx, "https://generativelanguage.googleapis.com/v1beta/interactions", map[string]string{"x-goog-api-key": c.GeminiKey}, body, "gemini")
	if err == nil {
		var r struct {
			Steps []struct {
				Type    string `json:"type"`
				Content []struct {
					Type string `json:"type"`
					Data string `json:"data"`
				} `json:"content"`
			} `json:"steps"`
		}
		if json.Unmarshal(b, &r) == nil {
			for i := len(r.Steps) - 1; i >= 0; i-- {
				for _, ct := range r.Steps[i].Content {
					if ct.Type == "audio" && ct.Data != "" {
						if pcm, err := base64.StdEncoding.DecodeString(ct.Data); err == nil {
							return asWav(pcm), nil
						}
					}
				}
			}
		}
	}
	// generateContent (the older way)
	prompt := text
	if c.GeminiStyle != "" {
		prompt = c.GeminiStyle + ": " + text
	}
	gc := map[string]any{
		"contents": []any{map[string]any{"parts": []any{map[string]any{"text": prompt}}}},
		"generationConfig": map[string]any{"responseModalities": []string{"AUDIO"},
			"speechConfig": map[string]any{"voiceConfig": map[string]any{"prebuiltVoiceConfig": map[string]any{"voiceName": voice}}}},
	}
	b2, err2 := t.postJSON(ctx, "https://generativelanguage.googleapis.com/v1beta/models/"+url.PathEscape(c.GeminiModel)+":generateContent",
		map[string]string{"x-goog-api-key": c.GeminiKey}, gc, "gemini")
	if err2 != nil {
		if err != nil {
			return nil, err
		}
		return nil, err2
	}
	var r2 struct {
		Candidates []struct {
			Content struct {
				Parts []struct {
					InlineData struct {
						Data string `json:"data"`
					} `json:"inlineData"`
				} `json:"parts"`
			} `json:"content"`
		} `json:"candidates"`
	}
	if err := json.Unmarshal(b2, &r2); err != nil {
		return nil, err
	}
	for _, cd := range r2.Candidates {
		for _, p := range cd.Content.Parts {
			if p.InlineData.Data != "" {
				pcm, err := base64.StdEncoding.DecodeString(p.InlineData.Data)
				if err != nil {
					return nil, err
				}
				return asWav(pcm), nil
			}
		}
	}
	return nil, errors.New("gemini: no audio in the answer")
}

// asWav adds a WAV header to raw 24 kHz 16-bit mono PCM (Gemini's format); WAV data is returned as is.
func asWav(b []byte) []byte {
	if len(b) >= 12 && string(b[:4]) == "RIFF" {
		return b
	}
	var h bytes.Buffer
	h.WriteString("RIFF")
	_ = binary.Write(&h, binary.LittleEndian, uint32(36+len(b)))
	h.WriteString("WAVEfmt ")
	_ = binary.Write(&h, binary.LittleEndian, uint32(16))
	_ = binary.Write(&h, binary.LittleEndian, uint16(1))
	_ = binary.Write(&h, binary.LittleEndian, uint16(1))
	_ = binary.Write(&h, binary.LittleEndian, uint32(24000))
	_ = binary.Write(&h, binary.LittleEndian, uint32(48000))
	_ = binary.Write(&h, binary.LittleEndian, uint16(2))
	_ = binary.Write(&h, binary.LittleEndian, uint16(16))
	h.WriteString("data")
	_ = binary.Write(&h, binary.LittleEndian, uint32(len(b)))
	h.Write(b)
	return h.Bytes()
}

func (t *TTS) azure(ctx context.Context, c TTSConfig, voice, text string) ([]byte, error) {
	lang := "ar-SA"
	if p := strings.Split(voice, "-"); len(p) >= 2 {
		lang = p[0] + "-" + p[1]
	}
	var esc bytes.Buffer
	_ = xml.EscapeText(&esc, []byte(text))
	ssml := `<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="` + lang + `"><voice name="` + voice + `">` + esc.String() + `</voice></speak>`
	req, _ := http.NewRequestWithContext(ctx, "POST", "https://"+c.AzureRegion+".tts.speech.microsoft.com/cognitiveservices/v1", strings.NewReader(ssml))
	req.Header.Set("Ocp-Apim-Subscription-Key", c.AzureKey)
	req.Header.Set("Content-Type", "application/ssml+xml")
	req.Header.Set("X-Microsoft-OutputFormat", "audio-24khz-96kbitrate-mono-mp3")
	req.Header.Set("User-Agent", "werd-server")
	res, err := t.hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, apiErr("azure", res)
	}
	return io.ReadAll(io.LimitReader(res.Body, 20<<20))
}

func (t *TTS) eleven(ctx context.Context, c TTSConfig, voice, text string) ([]byte, error) {
	b, _ := json.Marshal(map[string]any{"text": text, "model_id": c.ElevenModel, "language_code": "ar"})
	req, _ := http.NewRequestWithContext(ctx, "POST", "https://api.elevenlabs.io/v1/text-to-speech/"+url.PathEscape(voice)+"?output_format=mp3_44100_128", bytes.NewReader(b))
	req.Header.Set("xi-api-key", c.ElevenKey)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "audio/mpeg")
	res, err := t.hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode == 422 || res.StatusCode == 400 {
		// some models don't take language_code: try once without it
		res.Body.Close()
		b, _ = json.Marshal(map[string]any{"text": text, "model_id": c.ElevenModel})
		req, _ = http.NewRequestWithContext(ctx, "POST", "https://api.elevenlabs.io/v1/text-to-speech/"+url.PathEscape(voice)+"?output_format=mp3_44100_128", bytes.NewReader(b))
		req.Header.Set("xi-api-key", c.ElevenKey)
		req.Header.Set("Content-Type", "application/json")
		if res, err = t.hc.Do(req); err != nil {
			return nil, err
		}
		defer res.Body.Close()
	}
	if res.StatusCode != 200 {
		return nil, apiErr("elevenlabs", res)
	}
	return io.ReadAll(io.LimitReader(res.Body, 20<<20))
}

func (t *TTS) postJSON(ctx context.Context, u string, headers map[string]string, body any, provider string) ([]byte, error) {
	b, _ := json.Marshal(body)
	req, _ := http.NewRequestWithContext(ctx, "POST", u, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	res, err := t.hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, apiErr(provider, res)
	}
	return io.ReadAll(io.LimitReader(res.Body, 40<<20))
}

// ------------------------------------------------------------------ HTTP

func (s *Server) ttsVoices(w http.ResponseWriter, r *http.Request, u *User) {
	c := s.tts.Config(r.Context())
	shared := s.tts.sharedVoices(r.Context())
	voices := append(append([]Voice{}, s.tts.Voices(r.Context())...), shared...)
	def := c.Default
	if !c.Enabled() && !strings.HasPrefix(def, "local:") {
		def = ""
	}
	writeJSON(w, 200, map[string]any{"enabled": len(voices) > 0, "voices": voices, "default": def})
}

func (s *Server) ttsSpeak(w http.ResponseWriter, r *http.Request, u *User) {
	var in struct {
		Text  string `json:"text"`
		Voice string `json:"voice"`
	}
	if !s.readJSON(w, r, &in) {
		return
	}
	in.Text = strings.TrimSpace(in.Text)
	if in.Text == "" || len([]rune(in.Text)) > maxTTSText {
		writeErr(w, 422, "text", "Text must be 1–4000 characters.", nil)
		return
	}
	// generous, but stops a runaway phone from running up the owner's voice bill
	if !s.limit(w, "tts", u.ID, 3000, 1000) {
		return
	}
	data, ext, cached, err := s.tts.Speak(r.Context(), in.Text, in.Voice)
	if errors.Is(err, ErrTTSOff) {
		writeErr(w, 404, "tts_off", "No voice is set up on the server (admin page → Voices).", nil)
		return
	}
	if errors.Is(err, ErrNotInLibrary) {
		writeErr(w, 404, "not_in_library", "This line isn't in that shared voice yet.", nil)
		return
	}
	if errors.Is(err, ErrPCAsleep) {
		writeErr(w, 409, "pc_asleep", "This voice is made on your PC, which is asleep. Lines it made before still play.", nil)
		return
	}
	if errors.Is(err, ErrNoLocalVoice) {
		writeErr(w, 404, "tts_off", "That voice isn't installed on the server any more.", nil)
		return
	}
	if err != nil {
		s.log.Printf("tts: %v", err)
		s.act.Add(KindVoice, "error", "voice.failed", "voicefail", A{"name": u.Name, "voice": in.Voice, "error": truncRunes(err.Error(), 200)}, 10*time.Minute)
		writeErr(w, 424, "tts_failed", "The voice service did not answer: "+truncRunes(err.Error(), 300), nil)
		return
	}
	if ext == "mp3" {
		w.Header().Set("Content-Type", "audio/mpeg")
	} else {
		w.Header().Set("Content-Type", "audio/wav")
	}
	w.Header().Set("Cache-Control", "private, max-age=31536000")
	w.Header().Set("X-Werd-Cached", fmt.Sprint(cached))
	if !cached {
		s.act.Add(KindVoice, "info", "voice.clip", u.ID, A{"name": u.Name, "voice": in.Voice}, 15*time.Minute)
	}
	_, _ = w.Write(data)
}
