package main

// Packs: programs and voice models the server downloads on demand (the Piper voice engine and its voices,
// the graphics-card build of the AI engine). Each pack is fetched once into the data folder, with progress
// for the dashboard, and unpacked from .tar.bz2 or .zip, keeping only the files it needs.

import (
	"archive/tar"
	"archive/zip"
	"compress/bzip2"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// PackFile is one download of a pack.
type PackFile struct {
	URL  string
	Size int64  // approximate, for the progress bar
	Kind string // "tar.bz2", "zip" or "file"
	// Pick maps a path inside the archive to where it goes under the pack's folder ("" = skip it).
	Pick func(name string) string
	Name string // for Kind "file": the file name to save
}

type packState struct {
	Done, Total int64
	Phase       string // downloading | unpacking | installing
	Err         string
	running     bool
	cancel      context.CancelFunc
}

type Packs struct {
	dir string
	hc  *http.Client
	mu  sync.Mutex
	st  map[string]*packState
}

func NewPacks(dataDir string) *Packs {
	return &Packs{dir: dataDir, hc: &http.Client{}, st: map[string]*packState{}}
}

// PackProgress is what the dashboard shows for a pack being installed.
type PackProgress struct {
	ID    string `json:"id"`
	Done  int64  `json:"done"`
	Total int64  `json:"total"`
	Phase string `json:"phase"`
	Error string `json:"error,omitempty"`
}

func (p *Packs) State(id string) *PackProgress {
	p.mu.Lock()
	defer p.mu.Unlock()
	s := p.st[id]
	if s == nil {
		return nil
	}
	return &PackProgress{ID: id, Done: s.Done, Total: s.Total, Phase: s.Phase, Error: s.Err}
}

func (p *Packs) Running() []PackProgress {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := []PackProgress{}
	for id, s := range p.st {
		if s.running || s.Err != "" {
			out = append(out, PackProgress{ID: id, Done: s.Done, Total: s.Total, Phase: s.Phase, Error: s.Err})
		}
	}
	return out
}

func (p *Packs) Clear(id string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if s := p.st[id]; s != nil && s.cancel != nil {
		s.cancel()
	}
	delete(p.st, id)
}

// Install fetches files into dest (a folder under the data folder) in the background, then runs after (if any).
// A second call while it runs does nothing.
func (p *Packs) Install(id, dest string, files []PackFile, after func(ctx context.Context, phase func(string)) error, done func(error)) {
	p.mu.Lock()
	if s := p.st[id]; s != nil && s.running {
		p.mu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	var total int64
	for _, f := range files {
		total += f.Size
	}
	s := &packState{Total: total, Phase: "downloading", running: true, cancel: cancel}
	p.st[id] = s
	p.mu.Unlock()
	go func() {
		err := p.run(ctx, s, dest, files)
		if err == nil && after != nil {
			err = after(ctx, func(ph string) { p.mu.Lock(); s.Phase = ph; p.mu.Unlock() })
		}
		p.mu.Lock()
		s.running, s.cancel = false, nil
		if err != nil {
			s.Err = err.Error()
		} else {
			delete(p.st, id)
		}
		p.mu.Unlock()
		if done != nil {
			done(err)
		}
	}()
}

func (p *Packs) run(ctx context.Context, s *packState, dest string, files []PackFile) error {
	if err := os.MkdirAll(dest, 0o755); err != nil {
		return err
	}
	tmpDir := filepath.Join(p.dir, "downloads")
	_ = os.MkdirAll(tmpDir, 0o755)
	var base int64
	for i, f := range files {
		p.mu.Lock()
		s.Phase = "downloading"
		p.mu.Unlock()
		tmp := filepath.Join(tmpDir, fmt.Sprintf("%d-%d-%s", time.Now().UnixNano(), i, path.Base(strings.SplitN(f.URL, "?", 2)[0])))
		n, err := p.fetch(ctx, f, tmp, func(got, size int64) {
			p.mu.Lock()
			if size > 0 && size != f.Size {
				s.Total += size - f.Size
				f.Size = size
			}
			s.Done = base + got
			p.mu.Unlock()
		})
		if err != nil {
			os.Remove(tmp)
			return err
		}
		base += n
		p.mu.Lock()
		s.Phase = "unpacking"
		p.mu.Unlock()
		err = unpack(ctx, tmp, dest, f)
		os.Remove(tmp)
		if err != nil {
			return fmt.Errorf("unpacking %s: %w", path.Base(f.URL), err)
		}
	}
	return nil
}

func (p *Packs) fetch(ctx context.Context, f PackFile, dst string, progress func(got, size int64)) (int64, error) {
	req, _ := http.NewRequestWithContext(ctx, "GET", f.URL, nil)
	res, err := p.hc.Do(req)
	if err != nil {
		return 0, err
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return 0, fmt.Errorf("HTTP %d for %s", res.StatusCode, path.Base(f.URL))
	}
	out, err := os.Create(dst)
	if err != nil {
		return 0, err
	}
	defer out.Close()
	size := res.ContentLength
	var got int64
	buf := make([]byte, 1<<20)
	for {
		n, rerr := res.Body.Read(buf)
		if n > 0 {
			if _, werr := out.Write(buf[:n]); werr != nil {
				return got, werr
			}
			got += int64(n)
			progress(got, size)
		}
		if rerr == io.EOF {
			return got, nil
		}
		if rerr != nil {
			return got, rerr
		}
	}
}

func unpack(ctx context.Context, file, dest string, f PackFile) error {
	switch f.Kind {
	case "file":
		return copyFile(file, filepath.Join(dest, f.Name))
	case "zip":
		z, err := zip.OpenReader(file)
		if err != nil {
			return err
		}
		defer z.Close()
		for _, e := range z.File {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if e.FileInfo().IsDir() {
				continue
			}
			to := f.Pick(e.Name)
			if to == "" {
				continue
			}
			r, err := e.Open()
			if err != nil {
				return err
			}
			err = writeTo(filepath.Join(dest, to), r, e.Mode())
			r.Close()
			if err != nil {
				return err
			}
		}
		return nil
	case "tar.bz2":
		in, err := os.Open(file)
		if err != nil {
			return err
		}
		defer in.Close()
		tr := tar.NewReader(bzip2.NewReader(in))
		for {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			h, err := tr.Next()
			if err == io.EOF {
				return nil
			}
			if err != nil {
				return err
			}
			if h.Typeflag != tar.TypeReg {
				continue
			}
			to := f.Pick(h.Name)
			if to == "" {
				continue
			}
			if err := writeTo(filepath.Join(dest, to), tr, os.FileMode(h.Mode)); err != nil {
				return err
			}
		}
	}
	return errors.New("unknown pack kind " + f.Kind)
}

func writeTo(dst string, r io.Reader, mode os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	perm := os.FileMode(0o644)
	if mode&0o111 != 0 {
		perm = 0o755
	}
	out, err := os.OpenFile(dst+".tmp", os.O_CREATE|os.O_WRONLY|os.O_TRUNC, perm)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, r); err != nil {
		out.Close()
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	return os.Rename(dst+".tmp", dst)
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	return writeTo(dst, in, 0o755)
}

// stripTo keeps files under prefix (after the archive's top folder), placed relative to it.
func stripTo(prefix string) func(string) string {
	return func(name string) string {
		parts := strings.SplitN(strings.TrimPrefix(name, "./"), "/", 2)
		if len(parts) < 2 {
			return ""
		}
		rest := parts[1]
		if prefix == "" {
			return rest
		}
		if strings.HasPrefix(rest, prefix) {
			return strings.TrimPrefix(rest, prefix)
		}
		return ""
	}
}

// ------------------------------------------------------------------ small settings kept in the meta table

func (s *Store) getMeta(ctx context.Context, key string, v any) bool {
	var raw string
	if err := s.db.QueryRowContext(ctx, `SELECT value FROM meta WHERE key = ?`, key).Scan(&raw); err != nil {
		return false
	}
	return json.Unmarshal([]byte(raw), v) == nil
}

func (s *Store) setMeta(ctx context.Context, key string, v any) error {
	b, _ := json.Marshal(v)
	_, err := s.db.ExecContext(ctx, `INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)`, key, string(b))
	return err
}
