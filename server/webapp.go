package main

// Werd on the web: the app in a browser, served from this server at /app/ (see web/app).

import (
	"embed"
	"io/fs"
	"mime"
	"net/http"
)

//go:embed web/app
var appFiles embed.FS

func init() {
	_ = mime.AddExtensionType(".webmanifest", "application/manifest+json")
	_ = mime.AddExtensionType(".ttf", "font/ttf")
	_ = mime.AddExtensionType(".js", "text/javascript; charset=utf-8")
}

func (s *Server) appRoutes(mux *http.ServeMux) {
	sub, err := fs.Sub(appFiles, "web/app")
	if err != nil {
		panic(err)
	}
	files := http.StripPrefix("/app/", http.FileServer(http.FS(sub)))
	mux.Handle("GET /app/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// always check for a newer version (the files are small; fonts may be kept a day)
		if len(r.URL.Path) > 4 && r.URL.Path[len(r.URL.Path)-4:] == ".ttf" {
			w.Header().Set("Cache-Control", "public, max-age=86400")
		} else {
			w.Header().Set("Cache-Control", "no-cache")
		}
		files.ServeHTTP(w, r)
	}))
	mux.HandleFunc("GET /app", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/app/", http.StatusMovedPermanently)
	})
}
