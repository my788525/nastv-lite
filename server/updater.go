package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

const (
	ServerVersionCode = 30
	ServerVersionName = "1.5.23"
	UpdateManifestURL = "https://raw.githubusercontent.com/my788525/nastv-lite/main/update.json"
)

var manifestMirrorURLs = []string{
	"https://raw.githubusercontent.com/my788525/nastv-lite/main/update.json",
	"https://jsd.onmicrosoft.cn/gh/my788525/nastv-lite@main/update.json",
	"https://cdn.jsdelivr.net/gh/my788525/nastv-lite@main/update.json",
	"https://fastly.jsdelivr.net/gh/my788525/nastv-lite@main/update.json",
}

var (
	updateCacheMu      sync.Mutex
	cachedManifestRaw  []byte
	cachedManifestTime time.Time
	apkDownloadMu      sync.Mutex
)

type ServerUpdateInfo struct {
	VersionCode  int    `json:"versionCode"`
	VersionName  string `json:"versionName"`
	BinaryURL    string `json:"binaryUrl"`
	WinBinaryURL string `json:"winBinaryUrl"`
	FpkURL       string `json:"fpkUrl"`
	Changelog    string `json:"changelog"`
}

type UnifiedUpdateManifest struct {
	Server  ServerUpdateInfo `json:"server"`
	Android json.RawMessage  `json:"android"`
}

type UpdateCheckResult struct {
	CurrentCode int              `json:"current_code"`
	CurrentName string           `json:"current_name"`
	HasUpdate   bool             `json:"has_update"`
	Latest      ServerUpdateInfo `json:"latest"`
}

func CheckServerUpdate() (*UpdateCheckResult, error) {
	client := &http.Client{Timeout: 12 * time.Second}
	resp, err := client.Get(UpdateManifestURL + "?t=" + fmt.Sprintf("%d", time.Now().Unix()))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var m UnifiedUpdateManifest
	if err := json.NewDecoder(resp.Body).Decode(&m); err != nil {
		return nil, err
	}
	return &UpdateCheckResult{
		CurrentCode: ServerVersionCode,
		CurrentName: ServerVersionName,
		HasUpdate:   m.Server.VersionCode > ServerVersionCode,
		Latest:      func() ServerUpdateInfo {
			if runtime.GOOS == "windows" && m.Server.WinBinaryURL != "" {
				m.Server.BinaryURL = m.Server.WinBinaryURL
			}
			return m.Server
		}(),
	}, nil
}

func PerformServerSelfUpdate(binaryURL string) error {
	if binaryURL == "" {
		return fmt.Errorf("empty binaryUrl")
	}
	exePath, err := os.Executable()
	if err != nil {
		return err
	}
	exePath, _ = filepath.EvalSymlinks(exePath)

	client := &http.Client{Timeout: 120 * time.Second}
	resp, err := client.Get(binaryURL)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("download HTTP %d", resp.StatusCode)
	}

	tmpPath := exePath + ".new"
	f, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, resp.Body); err != nil {
		f.Close()
		_ = os.Remove(tmpPath)
		return err
	}
	f.Close()

	if runtime.GOOS == "windows" {
		oldPath := exePath + ".old"
		_ = os.Remove(oldPath)
		if err := os.Rename(exePath, oldPath); err != nil {
			_ = os.Remove(tmpPath)
			return err
		}
	}
	if err := os.Rename(tmpPath, exePath); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}

	// Schedule restart in background
	go func() {
		time.Sleep(800 * time.Millisecond)
		args := os.Args[1:]
		cmd := exec.Command(exePath, args...)
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Start(); err == nil {
			os.Exit(0)
		}
	}()
	return nil
}

// ==================== Built-in Update Mirror Server ====================

func sanitizeVersion(v string) string {
	var b strings.Builder
	for _, r := range v {
		if (r >= '0' && r <= '9') || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || r == '.' || r == '_' || r == '-' {
			b.WriteRune(r)
		}
	}
	res := b.String()
	if res == "" {
		return "latest"
	}
	return res
}

func isValidCachedAPK(path string) bool {
	st, err := os.Stat(path)
	if err != nil || st.Size() < 1024*1024 {
		return false
	}
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	var hdr [2]byte
	if _, err := io.ReadFull(f, hdr[:]); err != nil {
		return false
	}
	return hdr[0] == 'P' && hdr[1] == 'K'
}

func fetchManifestBytes(dataDir string, forceRefresh bool) ([]byte, error) {
	updateCacheMu.Lock()
	if !forceRefresh && len(cachedManifestRaw) > 0 && time.Since(cachedManifestTime) < 10*time.Minute {
		out := make([]byte, len(cachedManifestRaw))
		copy(out, cachedManifestRaw)
		updateCacheMu.Unlock()
		return out, nil
	}
	updateCacheMu.Unlock()

	client := &http.Client{Timeout: 8 * time.Second}
	ts := fmt.Sprintf("%d", time.Now().Unix()/60)
	var fetched []byte
	for _, base := range manifestMirrorURLs {
		u := base
		if !strings.Contains(u, "raw.githubusercontent.com") {
			u += "?t=" + ts
		}
		resp, err := client.Get(u)
		if err != nil {
			continue
		}
		if resp.StatusCode == 200 {
			body, err := io.ReadAll(io.LimitReader(resp.Body, 512*1024))
			resp.Body.Close()
			if err == nil && json.Valid(body) {
				fetched = body
				break
			}
		} else {
			resp.Body.Close()
		}
	}

	updatesDir := filepath.Join(dataDir, "updates")
	diskManifest := filepath.Join(updatesDir, "update.json")
	if len(fetched) > 0 {
		_ = os.MkdirAll(updatesDir, 0755)
		_ = os.WriteFile(diskManifest, fetched, 0644)
		updateCacheMu.Lock()
		cachedManifestRaw = fetched
		cachedManifestTime = time.Now()
		updateCacheMu.Unlock()
		return fetched, nil
	}

	// Fallback to disk cache if upstream unreachable
	if raw, err := os.ReadFile(diskManifest); err == nil && json.Valid(raw) {
		return raw, nil
	}
	return nil, fmt.Errorf("failed to fetch update manifest from all mirrors")
}

func precacheAPKIfNeeded(dataDir, versionName, apkURL string) {
	if apkURL == "" || versionName == "" {
		return
	}
	safeVer := sanitizeVersion(versionName)
	updatesDir := filepath.Join(dataDir, "updates")
	localPath := filepath.Join(updatesDir, "NASFun_v"+safeVer+".apk")
	if isValidCachedAPK(localPath) {
		return
	}
	apkDownloadMu.Lock()
	defer apkDownloadMu.Unlock()
	if isValidCachedAPK(localPath) {
		return
	}
	_ = os.MkdirAll(updatesDir, 0755)
	tmpPath := localPath + ".pre.tmp"
	client := &http.Client{Timeout: 180 * time.Second}
	resp, err := client.Get(apkURL)
	if err != nil || resp.StatusCode != 200 {
		if resp != nil {
			resp.Body.Close()
		}
		return
	}
	defer resp.Body.Close()
	f, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
	if err != nil {
		return
	}
	_, copyErr := io.Copy(f, resp.Body)
	f.Close()
	if copyErr == nil && isValidCachedAPK(tmpPath) {
		_ = os.Rename(tmpPath, localPath)
		log.Printf("[UpdateMirror] Pre-cached APK v%s -> %s", safeVer, localPath)
	} else {
		_ = os.Remove(tmpPath)
	}
}

func (a *App) prewarmUpdateMirror() {
	time.Sleep(5 * time.Second)
	raw, err := fetchManifestBytes(a.dataDir, true)
	if err != nil {
		return
	}
	var m struct {
		Android struct {
			VersionName string `json:"versionName"`
			ApkURL      string `json:"apkUrl"`
		} `json:"android"`
	}
	if json.Unmarshal(raw, &m) == nil && m.Android.ApkURL != "" {
		precacheAPKIfNeeded(a.dataDir, m.Android.VersionName, m.Android.ApkURL)
	}
}

func (a *App) handleUpdateManifest(w http.ResponseWriter, r *http.Request) {
	force := r.URL.Query().Get("force") == "1"
	raw, err := fetchManifestBytes(a.dataDir, force)
	if err != nil {
		http.Error(w, `{"code":-1,"msg":"`+err.Error()+`"}`, http.StatusBadGateway)
		return
	}
	var doc map[string]interface{}
	if err := json.Unmarshal(raw, &doc); err == nil {
		if androidMap, ok := doc["android"].(map[string]interface{}); ok {
			ver, _ := androidMap["versionName"].(string)
			apkURL, _ := androidMap["apkUrl"].(string)
			safeVer := sanitizeVersion(ver)
			localPath := filepath.Join(a.dataDir, "updates", "NASFun_v"+safeVer+".apk")
			cached := isValidCachedAPK(localPath)
			androidMap["serverMirror"] = true
			androidMap["apkCached"] = cached
			if !cached && apkURL != "" {
				go precacheAPKIfNeeded(a.dataDir, ver, apkURL)
			}
			if enriched, err := json.Marshal(doc); err == nil {
				raw = enriched
			}
		}
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	_, _ = w.Write(raw)
}

func (a *App) handleUpdateAPK(w http.ResponseWriter, r *http.Request) {
	ver := r.URL.Query().Get("version")
	upstreamURL := r.URL.Query().Get("url")
	if ver == "" || upstreamURL == "" {
		if raw, err := fetchManifestBytes(a.dataDir, false); err == nil {
			var m struct {
				Android struct {
					VersionName string `json:"versionName"`
					ApkURL      string `json:"apkUrl"`
				} `json:"android"`
			}
			if json.Unmarshal(raw, &m) == nil {
				if ver == "" {
					ver = m.Android.VersionName
				}
				if upstreamURL == "" {
					upstreamURL = m.Android.ApkURL
				}
			}
		}
	}

	safeVer := sanitizeVersion(ver)
	updatesDir := filepath.Join(a.dataDir, "updates")
	localPath := filepath.Join(updatesDir, "NASFun_v"+safeVer+".apk")

	// 1. 本地已缓存：直接内网/直连满速下发（支持 Range 断点续传）
	if isValidCachedAPK(localPath) {
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="NASFun_v%s.apk"`, safeVer))
		http.ServeFile(w, r, localPath)
		return
	}

	if upstreamURL == "" {
		http.Error(w, "missing apk url", http.StatusBadRequest)
		return
	}

	// 2. 本地未缓存：流式从 GitHub 拉取并边写入本地缓存边转发给客户端
	_ = os.MkdirAll(updatesDir, 0755)
	client := &http.Client{Timeout: 180 * time.Second}
	resp, err := client.Get(upstreamURL)
	if err != nil {
		http.Error(w, "upstream error: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		http.Error(w, fmt.Sprintf("upstream status %d", resp.StatusCode), http.StatusBadGateway)
		return
	}

	tmpPath := fmt.Sprintf("%s.%d.tmp", localPath, time.Now().UnixNano())
	tmpFile, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)

	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="NASFun_v%s.apk"`, safeVer))
	if resp.ContentLength > 0 {
		w.Header().Set("Content-Length", fmt.Sprintf("%d", resp.ContentLength))
	}

	var dst io.Writer = w
	if err == nil {
		dst = io.MultiWriter(w, tmpFile)
	}
	_, copyErr := io.Copy(dst, resp.Body)
	if tmpFile != nil {
		tmpFile.Close()
		if copyErr == nil && isValidCachedAPK(tmpPath) {
			apkDownloadMu.Lock()
			if !isValidCachedAPK(localPath) {
				_ = os.Rename(tmpPath, localPath)
				log.Printf("[UpdateMirror] Cached APK v%s -> %s", safeVer, localPath)
			} else {
				_ = os.Remove(tmpPath)
			}
			apkDownloadMu.Unlock()
		} else {
			_ = os.Remove(tmpPath)
		}
	}
}
