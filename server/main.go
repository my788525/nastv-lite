package main

import (
	_ "embed"
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

//go:embed web/index.html
var adminIndexHTML []byte

//go:embed web/player.html
var webPlayerHTML []byte

type App struct {
	port       string
	dataDir    string
	store      *Store
	transcoder *Transcoder
}

func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, Range")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func main() {
	port := flag.String("port", "5678", "HTTP listen port (dual-stack IPv4/IPv6)")
	defaultData := filepath.Join(".", "data")
	if v := os.Getenv("TRIM_PKGVAR"); v != "" {
		defaultData = v
	}
	dataDir := flag.String("data", defaultData, "Persistent data directory")
	flag.Parse()

	store, err := NewStore(*dataDir)
	if err != nil {
		log.Fatalf("Failed to init store: %v", err)
	}
	transcoder := NewTranscoder(*dataDir)

	app := &App{
		port:       *port,
		dataDir:    *dataDir,
		store:      store,
		transcoder: transcoder,
	}

	mux := http.NewServeMux()

	// Web Admin UI
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" && !strings.HasPrefix(r.URL.Path, "/index.html") {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(adminIndexHTML)
	})

	// Web Player Client (/play & /web)
	servePlayer := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(webPlayerHTML)
	}
	mux.HandleFunc("/play", servePlayer)
	mux.HandleFunc("/web", servePlayer)

	// Client Media API (/v/api/v1/...)
	mux.HandleFunc("/v/api/v1/login", app.handleLogin)
	mux.HandleFunc("/v/api/v1/sys/version", app.handleSysVersion)
	mux.HandleFunc("/v/api/v1/user/info", app.handleUserInfo)
	mux.HandleFunc("/v/api/v1/mediadb/list", app.handleMediaDbList)
	mux.HandleFunc("/v/api/v1/item/list", app.handleItemList)
	mux.HandleFunc("/v/api/v1/episode/list/", app.handleEpisodeList)
	mux.HandleFunc("/v/api/v1/play/info", app.handlePlayInfo)
	mux.HandleFunc("/v/api/v1/play/quality", app.handlePlayQuality)
	mux.HandleFunc("/v/api/v1/play/play", app.handlePlayLink)
	mux.HandleFunc("/v/api/v1/stream", app.handlePlayLink)
	mux.HandleFunc("/v/api/v1/media/range/", app.handleMediaRange)
	mux.HandleFunc("/v/api/v1/hls/", app.handleHLS)
	mux.HandleFunc("/v/api/v1/img/", app.handleImg)
	mux.HandleFunc("/v/api/v1/audio/lyrics", app.handleAudioLyrics)
	mux.HandleFunc("/v/api/v1/subtitle/dl/", app.handleSubtitleDl)
	mux.HandleFunc("/v/api/v1/play/record", app.handlePlayRecord)
	mux.HandleFunc("/v/api/v1/play/list", app.handlePlayList)
	mux.HandleFunc("/v/api/v1/search/list", app.handleSearch)
	mux.HandleFunc("/v/api/v1/files/list", app.handleFilesList)
	mux.HandleFunc("/v/api/v1/files/download", app.handleFilesDownload)

	// Admin API (/api/admin/...)
	mux.HandleFunc("/api/admin/status", app.handleAdminStatus)
	mux.HandleFunc("/api/admin/fs", app.handleAdminFS)
	mux.HandleFunc("/api/admin/libraries", app.handleAdminLibraries)
	mux.HandleFunc("/api/admin/libraries/scan", app.handleAdminScan)
	mux.HandleFunc("/api/admin/libraries/scrape", app.handleAdminScrape)
	mux.HandleFunc("/api/admin/users", app.handleAdminUsers)
	mux.HandleFunc("/api/admin/settings", app.handleAdminSettings)
	mux.HandleFunc("/api/admin/cache/clear", app.handleAdminCacheClear)
	mux.HandleFunc("/api/admin/logs", app.handleAdminLogs)
	mux.HandleFunc("/api/admin/ddns", app.handleAdminDDNS)
	mux.HandleFunc("/api/admin/ddns/run", app.handleAdminDDNSRun)
	mux.HandleFunc("/api/admin/ddns/ip", app.handleAdminDDNSIP)
	mux.HandleFunc("/api/admin/update/check", app.handleAdminUpdateCheck)
	mux.HandleFunc("/api/admin/update/apply", app.handleAdminUpdateApply)

	StartDDNSScheduler(app.store)

	// Background auto-scan scheduler
	go func() {
		lastScan := time.Now()
		for {
			time.Sleep(5 * time.Minute)
			hours := app.store.GetSettings().AutoScanHours
			if hours > 0 && time.Since(lastScan) >= time.Duration(hours)*time.Hour {
				lastScan = time.Now()
				app.store.mu.RLock()
				var ids []string
				for id := range app.store.data.Libraries {
					ids = append(ids, id)
				}
				app.store.mu.RUnlock()
				for _, id := range ids {
					_, _ = ScanLibrary(app.store, id)
				}
			}
		}
	}()

	// Listen on dual-stack "tcp" (binds [::]:port on Linux, serving both IPv4 & IPv6)
	ln, err := net.Listen("tcp", ":"+*port)
	if err != nil {
		log.Fatalf("Listen failed on :%s: %v", *port, err)
	}

	v4, v6 := getLocalIPs()
	log.Printf("NASFun Server v%s started on :%s (IPv4/IPv6 dual-stack)", ServerVersionName, *port)
	log.Printf("Data dir: %s | FFmpeg: %s | VAAPI: %v", *dataDir, transcoder.ffmpegPath, transcoder.hasVAAPI)
	if len(v4) > 0 {
		log.Printf("IPv4 URLs: http://%s:%s", strings.Join(v4, ", http://"), *port)
	}
	if len(v6) > 0 {
		log.Printf("IPv6 URLs: http://[%s]:%s", strings.Join(v6, "], http://["), *port)
	}

	if runtime.GOOS == "windows" {
		go func() {
			time.Sleep(500 * time.Millisecond)
			_ = exec.Command("rundll32", "url.dll,FileProtocolHandler", "http://127.0.0.1:"+*port).Start()
		}()
	}

	if err := http.Serve(ln, corsMiddleware(mux)); err != nil {
		log.Fatalf("Server error: %v", err)
	}
}
