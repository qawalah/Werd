// Werd server: the shared library and family features of the Werd app (docs/server-api.md).
//
// One program for every platform:
//   - Windows: werd-server.exe (double-click to run, or "werd-server.exe install" to run as a service)
//   - Docker / NAS: the same program in a small Linux image (see Dockerfile, docker-compose.yml)
//
// Data (one SQLite file plus the admin token) lives in the data folder; back it up by copying it,
// or download a consistent copy from the admin page.
package main

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"

	"golang.org/x/crypto/acme/autocert"
)

// Set at build time: -ldflags "-X main.version=1.0.0"
var version = "dev"

type Config struct {
	Addr        string // listen address, e.g. ":8080"
	DataDir     string
	AdminToken  string
	TLSCert     string
	TLSKey      string
	Domains     []string // automatic HTTPS (Let's Encrypt) for these names
	ACMEEmail   string
	TrustProxy  bool     // take the client IP from X-Forwarded-For (behind a reverse proxy)
	URLs        []string // addresses phones can use (home, Tailscale…), told to the app in /v1/info
	MaxBodySize int64
}

func env(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}

func defaultDataDir() string {
	if d := os.Getenv("WERD_DATA"); d != "" {
		return d
	}
	if runtime.GOOS == "windows" {
		if pd := os.Getenv("ProgramData"); pd != "" {
			return filepath.Join(pd, "Werd Server")
		}
	}
	if exe, err := os.Executable(); err == nil {
		return filepath.Join(filepath.Dir(exe), "data")
	}
	return "data"
}

// parseConfig reads flags (with WERD_* environment variables as defaults) from args.
func parseConfig(args []string) (*Config, error) {
	fs := flag.NewFlagSet("werd-server", flag.ContinueOnError)
	c := &Config{}
	fs.StringVar(&c.Addr, "addr", env("WERD_ADDR", ""), "listen address (default :8080, or :443 with -domain)")
	fs.StringVar(&c.DataDir, "data", defaultDataDir(), "data folder (database, admin token, certificates)")
	fs.StringVar(&c.AdminToken, "admin-token", env("WERD_ADMIN_TOKEN", ""), "admin password for /admin (generated on first start if empty)")
	fs.StringVar(&c.TLSCert, "tls-cert", env("WERD_TLS_CERT", ""), "HTTPS certificate file (PEM)")
	fs.StringVar(&c.TLSKey, "tls-key", env("WERD_TLS_KEY", ""), "HTTPS private key file (PEM)")
	domains := fs.String("domain", env("WERD_DOMAIN", ""), "public domain name(s), comma separated: enables automatic HTTPS from Let's Encrypt (needs ports 80 and 443)")
	fs.StringVar(&c.ACMEEmail, "acme-email", env("WERD_ACME_EMAIL", ""), "email for Let's Encrypt notices (optional)")
	urls := fs.String("urls", env("WERD_URLS", ""), "addresses phones can reach this server at, comma separated, e.g. http://192.168.1.20:8080,http://100.101.102.103:8080 (the app switches between them)")
	trust := fs.Bool("trust-proxy", env("WERD_TRUST_PROXY", "") == "1" || env("WERD_TRUST_PROXY", "") == "true", "behind a reverse proxy: use X-Forwarded-For for client IPs")
	if err := fs.Parse(args); err != nil {
		return nil, err
	}
	c.TrustProxy = *trust
	for _, u := range strings.Split(*urls, ",") {
		if u = strings.TrimRight(strings.TrimSpace(u), "/"); u != "" {
			if !strings.Contains(u, "://") {
				u = "http://" + u
			}
			c.URLs = append(c.URLs, u)
		}
	}
	for _, d := range strings.Split(*domains, ",") {
		if d = strings.TrimSpace(d); d != "" {
			c.Domains = append(c.Domains, d)
		}
	}
	if c.Addr == "" {
		if len(c.Domains) > 0 {
			c.Addr = ":443"
		} else {
			c.Addr = ":8080"
		}
	}
	if (c.TLSCert == "") != (c.TLSKey == "") {
		return nil, errors.New("-tls-cert and -tls-key go together")
	}
	c.MaxBodySize = 8 << 20
	return c, nil
}

// loadAdminToken returns the configured token, or the one stored in the data folder (creating it once).
func loadAdminToken(c *Config) (string, bool, error) {
	if c.AdminToken != "" {
		return c.AdminToken, false, nil
	}
	p := filepath.Join(c.DataDir, "admin-token.txt")
	if b, err := os.ReadFile(p); err == nil && len(strings.TrimSpace(string(b))) >= 12 {
		return strings.TrimSpace(string(b)), false, nil
	}
	buf := make([]byte, 12)
	if _, err := rand.Read(buf); err != nil {
		return "", false, err
	}
	tok := hex.EncodeToString(buf)
	if err := os.WriteFile(p, []byte(tok+"\n"), 0o600); err != nil {
		return "", false, err
	}
	return tok, true, nil
}

// run starts the server and blocks until ctx is cancelled.
func run(ctx context.Context, c *Config, logw io.Writer) error {
	logger := log.New(logw, "", log.LstdFlags)
	if err := os.MkdirAll(c.DataDir, 0o755); err != nil {
		return fmt.Errorf("data folder: %w", err)
	}
	st, err := OpenStore(filepath.Join(c.DataDir, "werd.db"))
	if err != nil {
		return err
	}
	defer st.Close()
	admin, created, err := loadAdminToken(c)
	if err != nil {
		return fmt.Errorf("admin token: %w", err)
	}
	srv := NewServer(st, c, admin, logger)

	httpSrv := &http.Server{
		Addr:              c.Addr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 15 * time.Second,
		ReadTimeout:       2 * time.Minute,
		WriteTimeout:      5 * time.Minute,
		IdleTimeout:       2 * time.Minute,
	}

	logger.Printf("Werd server %s — data in %s", version, c.DataDir)
	if created {
		logger.Printf("Admin password (saved in %s): %s", filepath.Join(c.DataDir, "admin-token.txt"), admin)
	} else {
		logger.Printf("Admin page: /admin (password in %s or WERD_ADMIN_TOKEN)", filepath.Join(c.DataDir, "admin-token.txt"))
	}

	errc := make(chan error, 2)
	switch {
	case len(c.Domains) > 0:
		m := &autocert.Manager{
			Prompt:     autocert.AcceptTOS,
			HostPolicy: autocert.HostWhitelist(c.Domains...),
			Cache:      autocert.DirCache(filepath.Join(c.DataDir, "certs")),
			Email:      c.ACMEEmail,
		}
		httpSrv.TLSConfig = &tls.Config{GetCertificate: m.GetCertificate, MinVersion: tls.VersionTLS12, NextProtos: []string{"h2", "http/1.1", "acme-tls/1"}}
		// port 80: Let's Encrypt challenges, everything else redirected to HTTPS
		go func() { errc <- http.ListenAndServe(":80", m.HTTPHandler(nil)) }()
		logger.Printf("HTTPS for %s on %s (certificates from Let's Encrypt)", strings.Join(c.Domains, ", "), c.Addr)
		go func() { errc <- httpSrv.ListenAndServeTLS("", "") }()
	case c.TLSCert != "":
		httpSrv.TLSConfig = &tls.Config{MinVersion: tls.VersionTLS12}
		logger.Printf("HTTPS on %s", c.Addr)
		go func() { errc <- httpSrv.ListenAndServeTLS(c.TLSCert, c.TLSKey) }()
	default:
		logger.Printf("HTTP on %s — put it behind HTTPS (reverse proxy, -domain, or -tls-cert) before using it over the internet", c.Addr)
		go func() { errc <- httpSrv.ListenAndServe() }()
	}

	addrs := Addresses(c)
	for _, a := range addrs {
		logger.Printf("In the app, set the server address to: %s  (%s)", a.URL, a.Kind)
	}
	if os.Getenv("WERD_DOCKER") != "" && len(addrs) == 0 {
		// inside a container the only addresses we see are Docker's own
		_, port, _ := net.SplitHostPort(c.Addr)
		logger.Printf("In the app, set the server address to http://<your NAS address>:<published port> (container port %s)", port)
		logger.Printf("Tip: list the NAS's home and Tailscale addresses in WERD_URLS so phones switch between them by themselves")
	}
	go srv.Housekeeping(ctx)

	select {
	case <-ctx.Done():
		sctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		logger.Printf("stopping")
		return httpSrv.Shutdown(sctx)
	case err := <-errc:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

// Address is one way phones can reach this server.
type Address struct {
	URL  string `json:"url"`
	Kind string `json:"kind"` // lan, tailscale or public
}

var dockerNet = &net.IPNet{IP: net.IPv4(172, 16, 0, 0), Mask: net.CIDRMask(12, 32)}

var tailnet = &net.IPNet{IP: net.IPv4(100, 64, 0, 0), Mask: net.CIDRMask(10, 32)}

func kindOf(rawURL string) string {
	u, err := url.Parse(rawURL)
	if err != nil {
		return "public"
	}
	h := u.Hostname()
	if strings.HasSuffix(h, ".ts.net") {
		return "tailscale"
	}
	if ip := net.ParseIP(h); ip != nil {
		switch {
		case tailnet.Contains(ip):
			return "tailscale"
		case ip.IsPrivate() || ip.IsLoopback():
			return "lan"
		}
		return "public"
	}
	if strings.HasSuffix(h, ".local") || !strings.Contains(h, ".") {
		return "lan"
	}
	return "public"
}

// Addresses lists the addresses phones can use: WERD_URLS first, then (outside Docker) this machine's own.
// The app gets them from /v1/info and switches between them (home Wi-Fi ↔ Tailscale) by itself.
func Addresses(c *Config) []Address {
	seen := map[string]bool{}
	out := []Address{}
	add := func(u string) {
		if !seen[u] {
			seen[u] = true
			out = append(out, Address{URL: u, Kind: kindOf(u)})
		}
	}
	for _, u := range c.URLs {
		add(u)
	}
	docker := os.Getenv("WERD_DOCKER") != ""
	if docker && len(c.URLs) > 0 {
		return out // the owner listed the NAS's addresses: those are the ones
	}
	for _, u := range lanAddresses(c) {
		// in a container on Docker's own network the addresses are Docker's (172.16–31.x): no phone can use them.
		// With network_mode: host (the compose file's default since 1.6) they are the NAS's real ones.
		if docker {
			if pu, err := url.Parse(u); err == nil {
				if ip := net.ParseIP(pu.Hostname()); ip != nil && dockerNet.Contains(ip) {
					continue
				}
			}
		}
		add(u)
	}
	return out
}

// virtual interfaces whose addresses no phone can reach
var skipIface = []string{"docker", "br-", "veth", "virbr", "vethernet", "vmnet", "virtualbox", "vboxnet", "lxc", "cni", "flannel", "wsl", "hyper-v"}

// lanAddresses lists this machine's addresses phones can use to reach this server.
func lanAddresses(c *Config) []string {
	if len(c.Domains) > 0 {
		return []string{"https://" + c.Domains[0]}
	}
	scheme := "http"
	if c.TLSCert != "" {
		scheme = "https"
	}
	host, port, err := net.SplitHostPort(c.Addr)
	if err != nil {
		port = "8080"
	}
	if host != "" && host != "0.0.0.0" && host != "::" {
		return []string{scheme + "://" + net.JoinHostPort(host, port)}
	}
	var lan, ts []string
	ifaces, _ := net.Interfaces()
next:
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		name := strings.ToLower(ifc.Name)
		for _, p := range skipIface {
			if strings.HasPrefix(name, p) {
				continue next
			}
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() != nil && !ipn.IP.IsLinkLocalUnicast() {
				u := scheme + "://" + net.JoinHostPort(ipn.IP.String(), port)
				if tailnet.Contains(ipn.IP) || strings.Contains(name, "tailscale") {
					ts = append(ts, u)
				} else {
					lan = append(lan, u)
				}
			}
		}
	}
	return append(lan, ts...)
}

func main() {
	args := os.Args[1:]
	// Windows service commands (install, uninstall, start, stop), and running as a service.
	if handled, err := serviceMain(args); handled {
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			pauseIfDoubleClicked()
			os.Exit(1)
		}
		return
	}
	if len(args) > 0 && args[0] == "healthcheck" {
		// for Docker's HEALTHCHECK (the image has no curl): exit 0 when /healthz answers
		c, _ := parseConfig(args[1:])
		_, port, err := net.SplitHostPort(c.Addr)
		if err != nil || port == "" {
			port = "8080"
		}
		scheme := "http"
		if c.TLSCert != "" || len(c.Domains) > 0 {
			scheme = "https"
		}
		cl := &http.Client{Timeout: 5 * time.Second, Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}
		res, err := cl.Get(scheme + "://127.0.0.1:" + port + "/healthz")
		if err != nil || res.StatusCode != 200 {
			os.Exit(1)
		}
		return
	}
	if len(args) > 0 && (args[0] == "version" || args[0] == "-version" || args[0] == "--version") {
		fmt.Println(version)
		return
	}
	c, err := parseConfig(args)
	if err != nil {
		os.Exit(2)
	}
	startHint()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, c, os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		pauseIfDoubleClicked()
		os.Exit(1)
	}
}
