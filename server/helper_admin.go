package main

// Dashboard routes for local voices, the graphics card and the helping PC.

import (
	"context"
	"encoding/base64"
	"net/http"
	"os"
	"strings"
	"time"
)

// localVoiceList: the "local:" voices phones can choose, here or on the PC.
func (s *Server) localVoiceList(ctx context.Context) []Voice {
	out := []Voice{}
	st, _ := s.hub.Status()
	online := s.hub.Online()
	where := func(here bool) (string, string, bool) {
		if here {
			return "server", "", false
		}
		if online {
			return "pc", "your PC", false
		}
		return "pc", "your PC · asleep", true
	}
	for _, p := range PiperCatalog {
		here := s.voices.PiperInstalled(p.ID)
		if !here && !st.HasVoice(p.ID) {
			continue
		}
		prov, d, asleep := where(here)
		det := "Piper"
		if d != "" {
			det += " · " + d
		}
		out = append(out, Voice{ID: "local:" + p.ID, Provider: prov, Name: p.Name, Detail: det, Asleep: asleep})
	}
	here := s.voices.GPUReady()
	if here || st.HasVoice("cb") {
		prov, d, asleep := where(here)
		det := "Chatterbox"
		if d != "" {
			det += " · " + d
		}
		out = append(out, Voice{ID: "local:cb", Provider: prov, Name: "راوٍ عربي · Arabic narrator", Detail: det, Asleep: asleep})
		for _, r := range s.voices.Refs() {
			out = append(out, Voice{ID: "local:cb:" + r.ID, Provider: prov, Name: r.Name, Detail: det, Asleep: asleep})
		}
	}
	return out
}

// localVoiceTag: what a clip of a local voice depends on, so a changed voice sample makes new clips.
func (s *Server) localVoiceTag(name string) string {
	base, id, _ := strings.Cut(name, ":")
	if base != "cb" {
		return "piper-1"
	}
	if r := s.voices.Ref(id); r != nil {
		return "cb-0.1.7-" + r.Sha
	}
	return "cb-0.1.7"
}

type helperView struct {
	Configured bool          `json:"configured"`
	Key        string        `json:"key"`
	Wake       bool          `json:"wake"`
	Prefer     bool          `json:"prefer"`
	MAC        string        `json:"mac"`
	IP         string        `json:"ip"`
	Manual     bool          `json:"manual"`
	Online     bool          `json:"online"`
	Seen       int64         `json:"seen,omitempty"`
	Status     *HelperStatus `json:"status,omitempty"`
	Work       []HelperWork  `json:"work"`
}

func (s *Server) helperView(ctx context.Context) helperView {
	c := s.hub.Config(ctx)
	st, seen := s.hub.Status()
	v := helperView{Configured: c.Key != "", Key: c.Key, Wake: c.Wake, Prefer: c.Prefer, MAC: c.MAC, IP: c.IP, Manual: c.Manual,
		Online: s.hub.Online(), Status: st, Work: s.hub.Work()}
	if !seen.IsZero() {
		v.Seen = seen.UnixMilli()
	}
	if v.Status != nil {
		v.Status.Ifaces = nil
	}
	return v
}

func (s *Server) helperAdminRoutes(mux *http.ServeMux) {
	// ---- a PC helping this server
	mux.HandleFunc("GET /admin/api/helper", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"helper": s.helperView(r.Context()), "client": s.helperc.Config(r.Context()),
			"clientState": s.helperc.State(), "addresses": Addresses(s.cfg), "docker": os.Getenv("WERD_DOCKER") != ""})
	}))
	mux.HandleFunc("PUT /admin/api/helper", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Wake, Prefer *bool
			MAC          *string
			NewKey       bool
		}
		if !s.readJSON(w, r, &in) {
			return
		}
		c := s.hub.Config(r.Context())
		if in.NewKey || c.Key == "" {
			c.Key = "wh-" + randHex(12)
		}
		if in.Wake != nil {
			c.Wake = *in.Wake
		}
		if in.Prefer != nil {
			c.Prefer = *in.Prefer
		}
		if in.MAC != nil {
			m := strings.TrimSpace(*in.MAC)
			c.MAC, c.Manual = m, m != ""
		}
		if err := s.hub.SetConfig(r.Context(), c); err != nil {
			s.internal(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{"helper": s.helperView(r.Context())})
	}))
	mux.HandleFunc("POST /admin/api/helper/wake", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.hub.Wake(r.Context(), true); err != nil {
			writeErr(w, 422, "wake", err.Error(), nil)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))
	// download a reading model on the PC
	mux.HandleFunc("POST /admin/api/helper/models/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if ocrModel(id) == nil {
			writeErr(w, 404, "not_found", "No such model.", nil)
			return
		}
		if !s.hub.Online() {
			writeErr(w, 409, "pc_asleep", ErrPCAsleep.Error(), nil)
			return
		}
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
			defer cancel()
			_, _ = s.hub.Do(ctx, "download", "", modelName(id), map[string]string{"model": id})
		}()
		writeJSON(w, 202, map[string]any{})
	}))
	// ---- this machine helping another server
	mux.HandleFunc("PUT /admin/api/helper/client", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in HelperClientConfig
		if !s.readJSON(w, r, &in) {
			return
		}
		if err := s.helperc.SetConfig(r.Context(), in); err != nil {
			s.internal(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))

	// ---- voices made on this machine
	mux.HandleFunc("GET /admin/api/voices/local", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		st, _ := s.hub.Status()
		pc := []string{}
		if st != nil {
			pc = st.Voices
		}
		writeJSON(w, 200, map[string]any{"piper": s.voices.PiperStatus(), "piperSupported": s.voices.PiperSupported(),
			"gpu": s.voices.GPUStatus(), "refs": s.voices.Refs(), "pcVoices": pc, "pcOnline": s.hub.Online()})
	}))
	mux.HandleFunc("POST /admin/api/voices/piper/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.voices.InstallPiper(r.PathValue("id")); err != nil {
			writeErr(w, 404, "not_found", "No such voice for this machine.", nil)
			return
		}
		writeJSON(w, 202, map[string]any{})
	}))
	mux.HandleFunc("DELETE /admin/api/voices/piper/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.voices.RemovePiper(r.PathValue("id")); err != nil {
			writeErr(w, 404, "not_found", err.Error(), nil)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))
	mux.HandleFunc("POST /admin/api/voices/gpu", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.voices.InstallGPU(); err != nil {
			writeErr(w, 422, "gpu", err.Error(), nil)
			return
		}
		writeJSON(w, 202, map[string]any{})
	}))
	mux.HandleFunc("DELETE /admin/api/voices/gpu", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.voices.RemoveGPU(); err != nil {
			s.internal(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))
	mux.HandleFunc("POST /admin/api/voices/refs", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Name, Audio string }
		if !s.readJSON(w, r, &in) {
			return
		}
		wav, err := base64.StdEncoding.DecodeString(in.Audio)
		if err != nil {
			writeErr(w, 422, "audio", "The recording must be a WAV.", nil)
			return
		}
		ref, err := s.voices.AddRef(in.Name, wav)
		if err != nil {
			writeErr(w, 422, "audio", err.Error(), nil)
			return
		}
		s.act.Add(KindVoice, "info", "voice.ref", "", A{"voice": ref.Name}, 0)
		writeJSON(w, 200, ref)
	}))
	mux.HandleFunc("DELETE /admin/api/voices/refs/{id}", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.voices.DeleteRef(r.PathValue("id")); err != nil {
			writeErr(w, 404, "not_found", "No such voice sample.", nil)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))

	// ---- page reading on the graphics card
	mux.HandleFunc("POST /admin/api/ocr/cuda", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.ocr.InstallCUDA(); err != nil {
			writeErr(w, 422, "cuda", "No NVIDIA graphics card on this Windows machine.", nil)
			return
		}
		writeJSON(w, 202, map[string]any{})
	}))
	mux.HandleFunc("DELETE /admin/api/ocr/cuda", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		if err := s.ocr.RemoveCUDA(); err != nil {
			s.internal(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{})
	}))
}
