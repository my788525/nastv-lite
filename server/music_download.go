package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type MusicSearchItem struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Artist   string `json:"artist"`
	Album    string `json:"album"`
	Duration int64  `json:"duration"` // seconds
	CoverURL string `json:"cover_url"`
	AudioURL string `json:"audio_url"`
	Source   string `json:"source"`
}

type MusicDownloadTask struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	Artist    string `json:"artist"`
	TargetDir string `json:"target_dir"`
	Status    string `json:"status"` // queued, downloading, enriching, transcoding, done, failed
	Message   string `json:"message"`
	FilePath  string `json:"file_path"`
	CreatedAt int64  `json:"created_at"`
}

var (
	musicTaskMu sync.Mutex
	musicTasks  []*MusicDownloadTask
)

func sanitizeFilename(s string) string {
	bad := []string{"/", "\\", ":", "*", "?", "\"", "<", ">", "|"}
	out := strings.TrimSpace(s)
	for _, b := range bad {
		out = strings.ReplaceAll(out, b, "_")
	}
	if out == "" {
		return "unknown"
	}
	return out
}

func addMusicTask(t *MusicDownloadTask) {
	musicTaskMu.Lock()
	defer musicTaskMu.Unlock()
	musicTasks = append([]*MusicDownloadTask{t}, musicTasks...)
	if len(musicTasks) > 50 {
		musicTasks = musicTasks[:50]
	}
}

func updateMusicTask(id, status, msg, filePath string) {
	musicTaskMu.Lock()
	defer musicTaskMu.Unlock()
	for _, t := range musicTasks {
		if t.ID == id {
			if status != "" {
				t.Status = status
			}
			if msg != "" {
				t.Message = msg
			}
			if filePath != "" {
				t.FilePath = filePath
			}
			break
		}
	}
}

func searchCustomMusicAPI(apiBase, query string) []MusicSearchItem {
	apiBase = strings.TrimRight(strings.TrimSpace(apiBase), "/")
	if apiBase == "" || query == "" {
		return nil
	}
	client := &http.Client{Timeout: 10 * time.Second}
	// Compatible with NeteaseCloudMusicApi (/cloudsearch or /search) and generic Listen1-style gateways
	endpoints := []string{
		apiBase + "/cloudsearch?keywords=" + url.QueryEscape(query) + "&limit=20",
		apiBase + "/search?keywords=" + url.QueryEscape(query) + "&limit=20",
		apiBase + "/search?q=" + url.QueryEscape(query),
	}
	for _, ep := range endpoints {
		resp, err := client.Get(ep)
		if err != nil {
			continue
		}
		if resp.StatusCode != 200 {
			resp.Body.Close()
			continue
		}
		raw, err := io.ReadAll(io.LimitReader(resp.Body, 2*1024*1024))
		resp.Body.Close()
		if err != nil {
			continue
		}

		// Try NeteaseCloudMusicApi JSON format: { result: { songs: [...] } }
		var ne struct {
			Result struct {
				Songs []struct {
					ID   int64  `json:"id"`
					Name string `json:"name"`
					DT   int64  `json:"dt"`
					Ar   []struct {
						Name string `json:"name"`
					} `json:"ar"`
					Artists []struct {
						Name string `json:"name"`
					} `json:"artists"`
					Al struct {
						Name   string `json:"name"`
						PicURL string `json:"picUrl"`
					} `json:"al"`
				} `json:"songs"`
			} `json:"result"`
		}
		if json.Unmarshal(raw, &ne) == nil && len(ne.Result.Songs) > 0 {
			var out []MusicSearchItem
			for _, s := range ne.Result.Songs {
				artist := ""
				if len(s.Ar) > 0 {
					artist = s.Ar[0].Name
				} else if len(s.Artists) > 0 {
					artist = s.Artists[0].Name
				}
				out = append(out, MusicSearchItem{
					ID:       fmt.Sprintf("%d", s.ID),
					Title:    s.Name,
					Artist:   artist,
					Album:    s.Al.Name,
					Duration: s.DT / 1000,
					CoverURL: s.Al.PicURL,
					Source:   "custom_api",
				})
			}
			return out
		}

		// Try generic array/data format: { data: [...] } or { tracks: [...] }
		var gen struct {
			Data   []MusicSearchItem `json:"data"`
			Tracks []MusicSearchItem `json:"tracks"`
		}
		if json.Unmarshal(raw, &gen) == nil {
			if len(gen.Data) > 0 {
				return gen.Data
			}
			if len(gen.Tracks) > 0 {
				return gen.Tracks
			}
		}
	}
	return nil
}

func resolveCustomAudioURL(apiBase, songID string) string {
	apiBase = strings.TrimRight(strings.TrimSpace(apiBase), "/")
	if apiBase == "" || songID == "" {
		return ""
	}
	client := &http.Client{Timeout: 10 * time.Second}
	endpoints := []string{
		apiBase + "/song/url/v1?id=" + url.QueryEscape(songID) + "&level=exhigh",
		apiBase + "/song/url?id=" + url.QueryEscape(songID),
	}
	for _, ep := range endpoints {
		resp, err := client.Get(ep)
		if err != nil {
			continue
		}
		if resp.StatusCode != 200 {
			resp.Body.Close()
			continue
		}
		var out struct {
			Data []struct {
				URL string `json:"url"`
			} `json:"data"`
		}
		err = json.NewDecoder(resp.Body).Decode(&out)
		resp.Body.Close()
		if err == nil && len(out.Data) > 0 && out.Data[0].URL != "" {
			return out.Data[0].URL
		}
	}
	return ""
}

func searchITunesTracks(query string) []MusicSearchItem {
	if query == "" {
		return nil
	}
	client := &http.Client{Timeout: 8 * time.Second}
	u := "https://itunes.apple.com/search?media=music&entity=song&limit=15&term=" + url.QueryEscape(query)
	resp, err := client.Get(u)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil
	}
	var parsed struct {
		Results []struct {
			TrackID        int64  `json:"trackId"`
			TrackName      string `json:"trackName"`
			ArtistName     string `json:"artistName"`
			CollectionName string `json:"collectionName"`
			TrackTimeMillis int64 `json:"trackTimeMillis"`
			ArtworkUrl100  string `json:"artworkUrl100"`
			PreviewUrl     string `json:"previewUrl"`
		} `json:"results"`
	}
	if json.NewDecoder(resp.Body).Decode(&parsed) != nil {
		return nil
	}
	var out []MusicSearchItem
	for _, r := range parsed.Results {
		if r.TrackName == "" {
			continue
		}
		cov := strings.ReplaceAll(r.ArtworkUrl100, "100x100bb", "600x600bb")
		out = append(out, MusicSearchItem{
			ID:       fmt.Sprintf("itunes_%d", r.TrackID),
			Title:    r.TrackName,
			Artist:   r.ArtistName,
			Album:    r.CollectionName,
			Duration: r.TrackTimeMillis / 1000,
			CoverURL: cov,
			AudioURL: r.PreviewUrl,
			Source:   "itunes_preview",
		})
	}
	return out
}

func (app *App) handleAdminMusicSearch(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if q == "" {
		errResp(w, 400, "请输入搜索关键词")
		return
	}
	cfg := app.store.GetSettings()
	var results []MusicSearchItem
	if cfg.MusicSourceAPI != "" {
		results = searchCustomMusicAPI(cfg.MusicSourceAPI, q)
	}
	if len(results) == 0 {
		results = searchITunesTracks(q)
	}
	if results == nil {
		results = []MusicSearchItem{}
	}
	okResp(w, map[string]interface{}{
		"results":           results,
		"has_custom_source": cfg.MusicSourceAPI != "",
	})
}

func (app *App) handleAdminMusicPreview(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	id := strings.TrimSpace(r.URL.Query().Get("id"))
	if id == "" {
		errResp(w, 400, "缺少歌曲 ID")
		return
	}
	cfg := app.store.GetSettings()
	audioURL := resolveCustomAudioURL(cfg.MusicSourceAPI, id)
	if audioURL == "" {
		errResp(w, 404, "未获取到试听链接")
		return
	}
	okResp(w, map[string]string{"url": audioURL})
}

func (app *App) handleAdminMusicTasks(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	musicTaskMu.Lock()
	out := make([]*MusicDownloadTask, len(musicTasks))
	copy(out, musicTasks)
	musicTaskMu.Unlock()
	okResp(w, out)
}

func (app *App) handleAdminMusicDownload(w http.ResponseWriter, r *http.Request) {
	u := app.requireAdmin(w, r)
	if u == nil {
		return
	}
	if r.Method != http.MethodPost {
		errResp(w, 405, "method not allowed")
		return
	}
	var req struct {
		ID        string `json:"id"`
		Title     string `json:"title"`
		Artist    string `json:"artist"`
		Album     string `json:"album"`
		AudioURL  string `json:"audio_url"`
		CoverURL  string `json:"cover_url"`
		Lyrics    string `json:"lyrics"`
		TargetDir string `json:"target_dir"`
		LibID     string `json:"lib_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		errResp(w, 400, "请求参数错误")
		return
	}
	req.Title = strings.TrimSpace(req.Title)
	req.Artist = strings.TrimSpace(req.Artist)
	req.TargetDir = strings.TrimSpace(req.TargetDir)
	if req.Title == "" || req.TargetDir == "" {
		errResp(w, 400, "歌名与保存目录不能为空")
		return
	}
	// Verify targetDir belongs to a configured library
	allowed := false
	for _, lib := range app.store.GetUserLibraries(u) {
		for _, p := range lib.Paths {
			if isSubPath(p, req.TargetDir) {
				allowed = true
				if req.LibID == "" {
					req.LibID = lib.ID
				}
				break
			}
		}
		if allowed {
			break
		}
	}
	if !allowed {
		errResp(w, 403, "保存目录必须在已配置的音乐媒体库路径内")
		return
	}

	taskID := fmt.Sprintf("dl_%d", time.Now().UnixNano())
	task := &MusicDownloadTask{
		ID:        taskID,
		Title:     req.Title,
		Artist:    req.Artist,
		TargetDir: req.TargetDir,
		Status:    "queued",
		Message:   "排队准备下载...",
		CreatedAt: time.Now().Unix(),
	}
	addMusicTask(task)

	go app.runMusicDownloadTask(taskID, req.ID, req.Title, req.Artist, req.Album, req.AudioURL, req.CoverURL, req.Lyrics, req.TargetDir, req.LibID)
	okResp(w, task)
}

func (app *App) runMusicDownloadTask(taskID, songID, title, artist, album, audioURL, coverURL, lyrics, targetDir, libID string) {
	cfg := app.store.GetSettings()
	if audioURL == "" && songID != "" && cfg.MusicSourceAPI != "" {
		updateMusicTask(taskID, "downloading", "正在向音源接口解析下载地址...", "")
		audioURL = resolveCustomAudioURL(cfg.MusicSourceAPI, songID)
	}
	if audioURL == "" {
		updateMusicTask(taskID, "failed", "未能获取到有效音频下载链接（请粘贴直链或配置自定义音源接口）", "")
		return
	}

	updateMusicTask(taskID, "downloading", "正在下载音频文件到 NAS...", "")
	client := &http.Client{Timeout: 120 * time.Second}
	resp, err := client.Get(audioURL)
	if err != nil {
		updateMusicTask(taskID, "failed", "下载失败: "+err.Error(), "")
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		updateMusicTask(taskID, "failed", fmt.Sprintf("下载失败 HTTP %d", resp.StatusCode), "")
		return
	}

	ext := ".mp3"
	ct := strings.ToLower(resp.Header.Get("Content-Type"))
	lowURL := strings.ToLower(audioURL)
	if strings.Contains(ct, "flac") || strings.Contains(lowURL, ".flac") {
		ext = ".flac"
	} else if strings.Contains(ct, "mp4") || strings.Contains(ct, "aac") || strings.Contains(lowURL, ".m4a") {
		ext = ".m4a"
	} else if strings.Contains(ct, "wav") || strings.Contains(lowURL, ".wav") {
		ext = ".wav"
	}

	baseName := sanitizeFilename(title)
	if artist != "" {
		baseName = sanitizeFilename(artist + " - " + title)
	}
	_ = os.MkdirAll(targetDir, 0755)
	destPath := filepath.Join(targetDir, baseName+ext)
	tmpPath := destPath + ".dl.tmp"

	f, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
	if err != nil {
		updateMusicTask(taskID, "failed", "写入文件失败: "+err.Error(), "")
		return
	}
	_, copyErr := io.Copy(f, resp.Body)
	f.Close()
	if copyErr != nil {
		_ = os.Remove(tmpPath)
		updateMusicTask(taskID, "failed", "下载传输中断: "+copyErr.Error(), "")
		return
	}
	if err := os.Rename(tmpPath, destPath); err != nil {
		_ = os.Remove(tmpPath)
		updateMusicTask(taskID, "failed", "保存文件失败: "+err.Error(), "")
		return
	}

	// 写入同步歌词文件 (.lrc)
	updateMusicTask(taskID, "enriching", "正在补齐歌词、封面与标签...", destPath)
	lrcText := strings.TrimSpace(lyrics)
	if lrcText == "" {
		sLrc, _, _, _, _ := scrapeLrcLib(title, artist)
		lrcText = sLrc
	}
	if lrcText != "" {
		lrcPath := filepath.Join(targetDir, baseName+".lrc")
		_ = os.WriteFile(lrcPath, []byte(lrcText), 0644)
	}

	// 触发媒体库扫描入库并补齐元数据 + 320K HLS 预转码
	if libID != "" {
		_, _ = ScanLibrary(app.store, libID)
	}
	// 找到刚入库的 MediaItem
	var targetItem *MediaItem
	app.store.mu.RLock()
	for _, it := range app.store.data.Items {
		if it != nil && it.FilePath == destPath {
			cp := *it
			targetItem = &cp
			break
		}
	}
	app.store.mu.RUnlock()

	if targetItem != nil {
		if coverURL != "" && targetItem.PosterPath == "" {
			audThumb := filepath.Join(app.transcoder.thumbDir, "aud_"+targetItem.Guid+".jpg")
			if downloadPosterToCache(coverURL, audThumb) {
				app.store.mu.Lock()
				if cur, ok := app.store.data.Items[targetItem.Guid]; ok {
					cur.PosterPath = audThumb
					if artist != "" && cur.Artist == "" {
						cur.Artist = artist
					}
					if album != "" && cur.Album == "" {
						cur.Album = album
					}
					if lrcText != "" && cur.Lyrics == "" {
						cur.Lyrics = lrcText
					}
					_ = app.store.saveLocked()
				}
				app.store.mu.Unlock()
			}
		}
		EnrichSingleAudioTrack(app.store, app.transcoder.thumbDir, app.transcoder.ffprobePath, app.transcoder.ffmpegPath, targetItem, false)
		_ = app.store.Save()

		updateMusicTask(taskID, "transcoding", "正在预转码为 320K HLS 缓存...", destPath)
		_, _ = app.transcoder.EnsureHLS(targetItem, "audio_320", app.store.GetSettings(), HLSOptions{})
	}

	log.Printf("[MusicDownload] 入库并预转码完成: %s -> %s", title, destPath)
	updateMusicTask(taskID, "done", "已下载入库、补齐歌词封面并完成 320K 缓存", destPath)
}
