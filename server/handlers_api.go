package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

func writeJSON(w http.ResponseWriter, status int, v interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func okResp(w http.ResponseWriter, data interface{}) {
	writeJSON(w, http.StatusOK, ApiResponse{Code: 0, Msg: "ok", Data: data})
}

func errResp(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, http.StatusOK, ApiResponse{Code: code, Msg: msg, Data: nil})
}

func (app *App) extractUser(r *http.Request) *User {
	auth := strings.TrimSpace(r.Header.Get("Authorization"))
	if auth != "" {
		token := strings.TrimPrefix(auth, "Bearer ")
		token = strings.TrimSpace(token)
		if u, err := app.store.VerifyToken(token); err == nil {
			return u
		}
	}
	if q := r.URL.Query().Get("token"); q != "" {
		if u, err := app.store.VerifyToken(q); err == nil {
			return u
		}
	}
	if c, err := r.Cookie("nastv_token"); err == nil && c.Value != "" {
		if u, err := app.store.VerifyToken(c.Value); err == nil {
			return u
		}
	}
	return nil
}

func (app *App) requireUser(w http.ResponseWriter, r *http.Request) *User {
	u := app.extractUser(r)
	if u == nil {
		writeJSON(w, http.StatusUnauthorized, ApiResponse{Code: -2, Msg: "未登录或令牌已过期"})
		return nil
	}
	return u
}

func (app *App) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		errResp(w, 405, "method not allowed")
		return
	}
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		errResp(w, 400, "请求格式错误")
		return
	}
	u, err := app.store.Authenticate(req.Username, req.Password)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	token, err := app.store.IssueToken(u)
	if err != nil {
		errResp(w, 500, "签发令牌失败")
		return
	}
	okResp(w, map[string]interface{}{
		"token":    token,
		"username": u.Username,
		"role":     u.Role,
		"user_id":  u.ID,
	})
}

func (app *App) handleSysVersion(w http.ResponseWriter, r *http.Request) {
	okResp(w, map[string]interface{}{
		"server":            "NASFun",
		"nastv_lite_server": true,
		"version_code":      ServerVersionCode,
		"version_name":      ServerVersionName,
		"has_ffmpeg":        app.transcoder.ffmpegPath != "",
		"has_vaapi":         app.transcoder.hasVAAPI,
		"dual_stack":        true,
	})
}

func (app *App) handleUserInfo(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	okResp(w, map[string]interface{}{
		"uid":          u.ID,
		"username":     u.Username,
		"nickname":     u.Username,
		"role":         u.Role,
		"allowed_libs": u.AllowedLibs,
	})
}

func (app *App) handleMediaDbList(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	libs := app.store.GetUserLibraries(u)
	sort.Slice(libs, func(i, j int) bool {
		return libs[i].CreatedAt < libs[j].CreatedAt
	})
	var res []MediaDbDTO
	for _, lib := range libs {
		cat := lib.Category
		if cat == "" {
			if lib.Mode == ModeFolder {
				cat = "Folder"
			} else if lib.Mode == ModeMusic {
				cat = "Music"
			} else {
				cat = "Movie"
			}
		}
		res = append(res, MediaDbDTO{
			Guid:          lib.ID,
			Title:         lib.Name,
			Category:      cat,
			Mode:          lib.Mode,
			AllowDownload: lib.AllowDownload,
			Poster:        "",
			Count:         lib.ItemCount,
		})
	}
	if res == nil {
		res = []MediaDbDTO{}
	}
	okResp(w, res)
}

func (app *App) toItemDTO(userID string, it *MediaItem, libMode string) PlayListItemDTO {
	poster := ""
	if it.PosterPath != "" || (it.Type != "Directory" && it.Type != "TV") {
		poster = "/v/api/v1/img/" + it.Guid
	}
	allowDl := false
	if lib, ok := app.store.GetLibrary(it.LibID); ok {
		allowDl = lib.AllowDownload
	}
	return PlayListItemDTO{
		Guid:          it.Guid,
		ParentGuid:    it.ParentGuid,
		Title:         it.Title,
		TvTitle:       it.TvTitle,
		Artist:        it.Artist,
		Album:         it.Album,
		Type:          it.Type,
		Category:      it.Category,
		Poster:        poster,
		Duration:      it.Duration,
		Ts:            app.store.GetWatchTs(userID, it.Guid),
		EpisodeNumber: it.EpisodeNumber,
		SeasonNumber:  it.SeasonNumber,
		Overview:      it.Overview,
		Year:          it.Year,
		Score:         it.Score,
		FileSize:      it.FileSize,
		UpdateTime:    it.ModTime,
		LibraryMode:   libMode,
		AllowDownload: allowDl,
	}
}

func (app *App) handleItemList(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	var req struct {
		ParentGuid string   `json:"parent_guid"`
		Ancestor   string   `json:"ancestor_guid"`
		ViewMode   string   `json:"view_mode"` // optional: "folder" or "movie_tv"
		Types      []string `json:"types"`
		PageSize   int      `json:"page_size"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	targetParent := req.ParentGuid
	if targetParent == "" {
		targetParent = req.Ancestor
	}

	// Determine if targetParent is a library ID or a folder/show GUID
	app.store.mu.RLock()
	libObj, isLibRoot := app.store.data.Libraries[targetParent]
	effMode := ModeMovieTV
	hasTargetModeItems := false
	if isLibRoot {
		effMode = libObj.Mode
		if req.ViewMode == ModeFolder || req.ViewMode == ModeMovieTV || req.ViewMode == ModeMusic {
			effMode = req.ViewMode
		}
		targetItemView := effMode
		if targetItemView == ModeMusic {
			targetItemView = ModeFolder
		}
		for _, it := range app.store.data.Items {
			if it.LibID == targetParent && it.ViewMode == targetItemView {
				hasTargetModeItems = true
				break
			}
		}
	}
	app.store.mu.RUnlock()

	// Auto-upgrade legacy pre-v1.0.5 libraries that don't have dual-view items yet
	if isLibRoot && !hasTargetModeItems && u.CanAccessLibrary(targetParent) {
		_, _ = ScanLibrary(app.store, targetParent)
	}

	var list []PlayListItemDTO
	app.store.mu.RLock()
	for _, it := range app.store.data.Items {
		if !u.CanAccessLibrary(it.LibID) {
			continue
		}
		libMode := ModeMovieTV
		if lib, ok := app.store.data.Libraries[it.LibID]; ok {
			libMode = lib.Mode
		}
		itemMode := libMode
		if req.ViewMode == ModeFolder || req.ViewMode == ModeMovieTV || req.ViewMode == ModeMusic {
			itemMode = req.ViewMode
		}
		if isLibRoot && itemMode == ModeMusic {
			if it.LibID != targetParent || it.ViewMode != ModeFolder || it.Type != "Audio" {
				continue
			}
			list = append(list, app.toItemDTO(u.ID, it, ModeMusic))
			continue
		}
		if targetParent != "" && it.ParentGuid != targetParent {
			continue
		}
		if isLibRoot && it.ViewMode != itemMode {
			continue
		}
		list = append(list, app.toItemDTO(u.ID, it, itemMode))
	}
	app.store.mu.RUnlock()

	sort.Slice(list, func(i, j int) bool {
		iDir := list[i].Type == "Directory"
		jDir := list[j].Type == "Directory"
		if iDir != jDir {
			return iDir
		}
		return strings.ToLower(list[i].Title) < strings.ToLower(list[j].Title)
	})

	if list == nil {
		list = []PlayListItemDTO{}
	}
	okResp(w, ItemListResponseDTO{List: list, Total: len(list)})
}

func (app *App) handleEpisodeList(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	tvGuid := strings.TrimPrefix(r.URL.Path, "/v/api/v1/episode/list/")
	var eps []*MediaItem
	app.store.mu.RLock()
	for _, it := range app.store.data.Items {
		if it.ParentGuid == tvGuid && u.CanAccessLibrary(it.LibID) && it.Type == "Episode" {
			cp := *it
			eps = append(eps, &cp)
		}
	}
	app.store.mu.RUnlock()

	sort.Slice(eps, func(i, j int) bool {
		if eps[i].SeasonNumber != eps[j].SeasonNumber {
			return eps[i].SeasonNumber < eps[j].SeasonNumber
		}
		if eps[i].EpisodeNumber != eps[j].EpisodeNumber {
			return eps[i].EpisodeNumber < eps[j].EpisodeNumber
		}
		return eps[i].Title < eps[j].Title
	})

	var res []PlayListItemDTO
	for _, ep := range eps {
		res = append(res, app.toItemDTO(u.ID, ep, ModeMovieTV))
	}
	if res == nil {
		res = []PlayListItemDTO{}
	}
	okResp(w, res)
}

func (app *App) handlePlayInfo(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	var req struct {
		ItemGuid string `json:"item_guid"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	it, ok := app.store.GetItem(req.ItemGuid)
	if !ok || !u.CanAccessLibrary(it.LibID) {
		errResp(w, 404, "媒体不存在或无权限")
		return
	}
	app.transcoder.ProbeItem(app.store, it)

	var subs []map[string]interface{}
	for _, sb := range it.Subtitles {
		subs = append(subs, map[string]interface{}{
			"guid":     sb.ID,
			"title":    sb.Title,
			"language": sb.Language,
			"format":   sb.Format,
		})
	}
	resStr := ""
	if it.Width > 0 && it.Height > 0 {
		resStr = fmt.Sprintf("%dx%d", it.Width, it.Height)
	}
	okResp(w, map[string]interface{}{
		"media_guid":      it.Guid,
		"video_guid":      it.Guid,
		"audio_guid":      it.Guid,
		"ts":              app.store.GetWatchTs(u.ID, it.Guid),
		"duration":        it.Duration,
		"resolution":      resStr,
		"v_codec":         it.VCodec,
		"a_codec":         it.ACodec,
		"bitrate":         it.Bitrate,
		"file_size":       it.FileSize,
		"subtitle_tracks": subs,
		"item":            app.toItemDTO(u.ID, it, ""),
	})
}

func (app *App) handlePlayQuality(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	var req struct {
		MediaGuid string `json:"media_guid"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	var list []map[string]interface{}
	for _, q := range QualityProfiles {
		if q.Key != "original" && app.transcoder.ffmpegPath == "" {
			continue
		}
		list = append(list, map[string]interface{}{
			"key":        q.Key,
			"label":      q.Label,
			"resolution": q.Key,
			"bitrate":    q.Bitrate,
			"url":        fmt.Sprintf("/v/api/v1/hls/%s/%s/index.m3u8", req.MediaGuid, q.Key),
		})
	}
	okResp(w, list)
}

func (app *App) handleStream(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	var req struct {
		MediaGuid string `json:"media_guid"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	it, ok := app.store.GetItem(req.MediaGuid)
	if !ok || !u.CanAccessLibrary(it.LibID) {
		errResp(w, 404, "媒体不存在或无权限")
		return
	}
	var qualities []map[string]interface{}
	if app.transcoder.ffmpegPath != "" {
		qualities = []map[string]interface{}{
			{"resolution": "auto", "bitrate": 1600000, "is_m3u8": true, "progressive": false},
			{"resolution": "1080", "bitrate": 4000000, "is_m3u8": true, "progressive": false},
			{"resolution": "720", "bitrate": 1600000, "is_m3u8": true, "progressive": false},
			{"resolution": "480", "bitrate": 900000, "is_m3u8": true, "progressive": false},
			{"resolution": "360", "bitrate": 500000, "is_m3u8": true, "progressive": false},
		}
	}
	okResp(w, map[string]interface{}{
		"qualities": qualities,
		"video_stream": map[string]interface{}{
			"guid":       it.Guid,
			"codec_name": it.VCodec,
			"width":      it.Width,
			"height":     it.Height,
			"bit_rate":   it.Bitrate,
		},
	})
}

func (app *App) handlePlayLink(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	var req struct {
		MediaGuid  string `json:"media_guid"`
		ItemGuid   string `json:"item_guid"`
		Resolution string `json:"resolution"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	guid := req.MediaGuid
	if guid == "" {
		guid = req.ItemGuid
	}
	it, ok := app.store.GetItem(guid)
	if !ok || !u.CanAccessLibrary(it.LibID) {
		errResp(w, 404, "媒体不存在或无权限")
		return
	}

	resKey := strings.ToLower(strings.TrimSpace(req.Resolution))
	if resKey == "1080" || resKey == "720" || resKey == "480" || resKey == "360" {
		resKey += "p"
	}
	url := "/v/api/v1/media/range/" + it.Guid
	if resKey == "auto" {
		url = fmt.Sprintf("/v/api/v1/hls/%s/auto/master.m3u8", it.Guid)
	} else if resKey != "" && resKey != "original" && FindQualityProfile(resKey) != nil {
		url = fmt.Sprintf("/v/api/v1/hls/%s/%s/index.m3u8", it.Guid, resKey)
	}
	okResp(w, map[string]interface{}{
		"url":        url,
		"play_link":  url,
		"media_guid": it.Guid,
	})
}

func (app *App) handleMediaRange(w http.ResponseWriter, r *http.Request) {
	// Token can be in Authorization header or ?token=
	u := app.extractUser(r)
	if u == nil {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	guid := strings.TrimPrefix(r.URL.Path, "/v/api/v1/media/range/")
	it, ok := app.store.GetItem(guid)
	if !ok || !u.CanAccessLibrary(it.LibID) {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(it.FilePath)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		http.NotFound(w, r)
		return
	}
	http.ServeContent(w, r, filepath.Base(it.FilePath), st.ModTime(), f)
}

func (app *App) handleHLS(w http.ResponseWriter, r *http.Request) {
	u := app.extractUser(r)
	if u == nil {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	// Path: /v/api/v1/hls/{guid}/{quality}/{file}
	rest := strings.TrimPrefix(r.URL.Path, "/v/api/v1/hls/")
	parts := strings.SplitN(rest, "/", 3)
	if len(parts) != 3 {
		http.NotFound(w, r)
		return
	}
	guid, quality, file := parts[0], parts[1], parts[2]
	if strings.Contains(file, "..") || strings.Contains(file, "/") {
		http.NotFound(w, r)
		return
	}
	it, ok := app.store.GetItem(guid)
	if !ok || !u.CanAccessLibrary(it.LibID) {
		http.NotFound(w, r)
		return
	}
	// 自适应多码率 (ABR) Master Playlist：让客户端根据实时网速自动在 720P / 480P / 360P 间无缝切换
	codecQ := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("codec")))
	containerQ := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("container")))
	segQ, _ := strconv.Atoi(r.URL.Query().Get("seg"))
	hwdecQ := strings.TrimSpace(r.URL.Query().Get("hwdecode"))
	if quality == "auto" && (file == "master.m3u8" || file == "index.m3u8") {
		var qs []string
		if t := r.URL.Query().Get("token"); t != "" {
			qs = append(qs, "token="+t)
		}
		if codecQ != "" {
			qs = append(qs, "codec="+codecQ)
		}
		if containerQ != "" {
			qs = append(qs, "container="+containerQ)
		}
		if segQ > 0 {
			qs = append(qs, fmt.Sprintf("seg=%d", segQ))
		}
		if hwdecQ != "" {
			qs = append(qs, "hwdecode="+hwdecQ)
		}
		suffix := ""
		if len(qs) > 0 {
			suffix = "?" + strings.Join(qs, "&")
		}
		bw720, bw480, bw360 := 1750000, 1020000, 580000
		if codecQ == "hevc" {
			bw720, bw480, bw360 = 1150000, 680000, 400000
		}
		master := fmt.Sprintf(
			"#EXTM3U\n#EXT-X-VERSION:3\n"+
				"#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=1280x720,NAME=\"720P\"\n/v/api/v1/hls/%s/720p/index.m3u8%s\n"+
				"#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=854x480,NAME=\"480P\"\n/v/api/v1/hls/%s/480p/index.m3u8%s\n"+
				"#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=640x360,NAME=\"360P\"\n/v/api/v1/hls/%s/360p/index.m3u8%s\n",
			bw720, guid, suffix, bw480, guid, suffix, bw360, guid, suffix)
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.Header().Set("Cache-Control", "no-cache")
		_, _ = w.Write([]byte(master))
		return
	}
	sessDir, err := app.transcoder.EnsureHLS(it, quality, app.store.GetSettings(), HLSOptions{
		Codec:      codecQ,
		Container:  containerQ,
		SegmentSec: segQ,
		HWDecode:   hwdecQ,
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	target := filepath.Join(sessDir, file)
	if strings.HasSuffix(file, ".m3u8") {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	} else if strings.HasSuffix(file, ".ts") {
		w.Header().Set("Content-Type", "video/mp2t")
	} else if strings.HasSuffix(file, ".m4s") {
		w.Header().Set("Content-Type", "video/iso.segment")
	} else if strings.HasSuffix(file, ".mp4") {
		w.Header().Set("Content-Type", "video/mp4")
	}
	http.ServeFile(w, r, target)
}

func (app *App) handleImg(w http.ResponseWriter, r *http.Request) {
	guid := strings.TrimPrefix(r.URL.Path, "/v/api/v1/img/")
	it, ok := app.store.GetItem(guid)
	if !ok {
		http.NotFound(w, r)
		return
	}
	p := app.transcoder.EnsureThumbnail(it, app.store.GetSettings().EnableThumbnails)
	if p == "" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=86400")
	http.ServeFile(w, r, p)
}

func (app *App) handleSubtitleDl(w http.ResponseWriter, r *http.Request) {
	subID := strings.TrimPrefix(r.URL.Path, "/v/api/v1/subtitle/dl/")
	var subPath string
	app.store.mu.RLock()
	for _, it := range app.store.data.Items {
		for _, sb := range it.Subtitles {
			if sb.ID == subID {
				subPath = sb.FilePath
				break
			}
		}
		if subPath != "" {
			break
		}
	}
	app.store.mu.RUnlock()
	if subPath == "" {
		http.NotFound(w, r)
		return
	}
	http.ServeFile(w, r, subPath)
}

func (app *App) handlePlayRecord(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	var req struct {
		ItemGuid string `json:"item_guid"`
		Ts       int64  `json:"ts"`
		Duration int64  `json:"duration"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		errResp(w, 400, "invalid body")
		return
	}
	app.store.RecordWatch(u.ID, req.ItemGuid, req.Ts, req.Duration)
	okResp(w, map[string]bool{"saved": true})
}

func (app *App) handlePlayList(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	type recPair struct {
		rec  WatchRecord
		item *MediaItem
		mode string
	}
	var pairs []recPair
	app.store.mu.RLock()
	for _, rec := range app.store.data.Records {
		if rec.UserID != u.ID || rec.Ts <= 0 {
			continue
		}
		it, ok := app.store.data.Items[rec.ItemGuid]
		if !ok || !u.CanAccessLibrary(it.LibID) {
			continue
		}
		mode := ModeMovieTV
		if lib, ok := app.store.data.Libraries[it.LibID]; ok {
			mode = lib.Mode
		}
		cp := *it
		pairs = append(pairs, recPair{rec: rec, item: &cp, mode: mode})
	}
	app.store.mu.RUnlock()

	sort.Slice(pairs, func(i, j int) bool {
		return pairs[i].rec.UpdatedAt > pairs[j].rec.UpdatedAt
	})

	var res []PlayListItemDTO
	for _, p := range pairs {
		dto := app.toItemDTO(u.ID, p.item, p.mode)
		dto.Ts = p.rec.Ts
		if dto.Duration == 0 && p.rec.Duration > 0 {
			dto.Duration = p.rec.Duration
		}
		res = append(res, dto)
	}
	if res == nil {
		res = []PlayListItemDTO{}
	}
	okResp(w, res)
}

func (app *App) handleSearch(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	q := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	if q == "" {
		okResp(w, []PlayListItemDTO{})
		return
	}
	var res []PlayListItemDTO
	app.store.mu.RLock()
	for _, it := range app.store.data.Items {
		if !u.CanAccessLibrary(it.LibID) {
			continue
		}
		mode := ModeMovieTV
		if lib, ok := app.store.data.Libraries[it.LibID]; ok {
			mode = lib.Mode
		}
		searchView := mode
		if searchView == ModeMusic {
			searchView = ModeFolder
		}
		if it.ViewMode != "" && it.ViewMode != searchView {
			continue
		}
		if strings.Contains(strings.ToLower(it.Title), q) || strings.Contains(strings.ToLower(it.TvTitle), q) || strings.Contains(strings.ToLower(it.Artist), q) {
			res = append(res, app.toItemDTO(u.ID, it, mode))
			if len(res) >= 100 {
				break
			}
		}
	}
	app.store.mu.RUnlock()
	if res == nil {
		res = []PlayListItemDTO{}
	}
	okResp(w, res)
}

func (app *App) handleAudioLyrics(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	guid := strings.TrimSpace(r.URL.Query().Get("guid"))
	if guid == "" {
		errResp(w, 400, "missing guid")
		return
	}
	it, ok := app.store.GetItem(guid)
	if !ok || !u.CanAccessLibrary(it.LibID) {
		errResp(w, 404, "item not found")
		return
	}

	title := it.Title
	artist := it.Artist
	album := it.Album
	duration := it.Duration
	lrc := ""

	// 1. Check sidecar .lrc / .txt next to audio file
	if it.FilePath != "" {
		base := strings.TrimSuffix(it.FilePath, filepath.Ext(it.FilePath))
		for _, ext := range []string{".lrc", ".LRC", ".txt"} {
			if raw, err := os.ReadFile(base + ext); err == nil && len(raw) > 0 {
				s := strings.TrimPrefix(string(raw), "\xef\xbb\xbf")
				lrc = strings.TrimSpace(s)
				break
			}
		}
	}

	// 2. Read embedded audio tags (artist/album/title/lyrics) via ffprobe if available
	if app.transcoder.ffprobePath != "" && it.FilePath != "" {
		cmd := exec.Command(app.transcoder.ffprobePath, "-v", "quiet", "-print_format", "json", "-show_format", it.FilePath)
		if out, err := cmd.Output(); err == nil {
			var probe struct {
				Format struct {
					Duration string            `json:"duration"`
					Tags     map[string]string `json:"tags"`
				} `json:"format"`
			}
			if json.Unmarshal(out, &probe) == nil {
				if duration <= 0 && probe.Format.Duration != "" {
					if d, err2 := strconv.ParseFloat(probe.Format.Duration, 64); err2 == nil && d > 0 {
						duration = int64(d)
					}
				}
				for k, v := range probe.Format.Tags {
					kl := strings.ToLower(k)
					v = strings.TrimSpace(v)
					if v == "" {
						continue
					}
					switch {
					case kl == "title":
						title = v
					case kl == "artist" || kl == "album_artist":
						if artist == "" || kl == "artist" {
							artist = v
						}
					case kl == "album":
						album = v
					case strings.Contains(kl, "lyric") || kl == "uslt" || kl == "sylt":
						if lrc == "" {
							lrc = v
						}
					}
				}
			}
		}
	}

	// 2.5 Fallback to cached scraped lyrics on NAS
	if lrc == "" && it.Lyrics != "" {
		lrc = it.Lyrics
	}

	// 3. On-demand server-side open-source music scraping (LrcLib) when missing
	scrapedNew := false
	if lrc == "" && app.store.GetSettings().EnableScraper {
		qTitle, qArtist := parseTrackTitleArtist(title, it.RelPath)
		if artist != "" {
			qArtist = artist
		}
		sLrc, _, sArtist, sAlbum, sDur := scrapeLrcLib(qTitle, qArtist)
		if sLrc != "" {
			lrc = sLrc
			scrapedNew = true
		}
		if artist == "" && sArtist != "" {
			artist = sArtist
			scrapedNew = true
		}
		if album == "" && sAlbum != "" {
			album = sAlbum
			scrapedNew = true
		}
		if duration <= 0 && sDur > 0 {
			duration = sDur
			scrapedNew = true
		}
	}

	// Cache artist/album/duration/lyrics in memory (and persist if newly scraped)
	if artist != "" || album != "" || duration > 0 || lrc != "" {
		app.store.mu.Lock()
		if cur, exists := app.store.data.Items[guid]; exists {
			if cur.Artist == "" && artist != "" {
				cur.Artist = artist
			}
			if cur.Album == "" && album != "" {
				cur.Album = album
			}
			if cur.Duration <= 0 && duration > 0 {
				cur.Duration = duration
			}
			if cur.Lyrics == "" && lrc != "" {
				cur.Lyrics = lrc
			}
		}
		app.store.mu.Unlock()
		if scrapedNew {
			go func() { _ = app.store.Save() }()
		}
	}

	okResp(w, map[string]interface{}{
		"guid":     guid,
		"title":    title,
		"artist":   artist,
		"album":    album,
		"duration": duration,
		"lrc":      lrc,
	})
}

func (app *App) handleAudioOffline(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	guid := strings.TrimPrefix(r.URL.Path, "/v/api/v1/audio/offline/")
	guid = strings.TrimSpace(guid)
	if guid == "" {
		http.NotFound(w, r)
		return
	}
	it, ok := app.store.GetItem(guid)
	if !ok || !u.CanAccessLibrary(it.LibID) || it.FilePath == "" {
		http.NotFound(w, r)
		return
	}
	if it.Type != "Audio" && it.Category != "Music" {
		errResp(w, 403, "仅支持音频离线缓存")
		return
	}

	bitrate := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("bitrate")))
	switch bitrate {
	case "96", "128", "192", "320", "original":
	default:
		bitrate = "192"
	}

	if bitrate == "original" || app.transcoder.ffmpegPath == "" {
		http.ServeFile(w, r, it.FilePath)
		return
	}

	// If source is already mp3/m4a/aac and its bitrate <= requested max bitrate, serve directly without re-encoding
	targetKbps, _ := strconv.Atoi(bitrate)
	ext := strings.ToLower(filepath.Ext(it.FilePath))
	if it.Bitrate > 0 && int64(targetKbps*1000) >= it.Bitrate && (ext == ".mp3" || ext == ".m4a" || ext == ".aac") {
		http.ServeFile(w, r, it.FilePath)
		return
	}

	cacheDir := filepath.Join(app.dataDir, "offline_audio")
	_ = os.MkdirAll(cacheDir, 0755)
	outPath := filepath.Join(cacheDir, guid+"_"+bitrate+".m4a")
	if st, err := os.Stat(outPath); err != nil || st.Size() == 0 {
		tmpPath := outPath + ".tmp.m4a"
		cmd := exec.Command(app.transcoder.ffmpegPath,
			"-y", "-v", "quiet",
			"-i", it.FilePath,
			"-vn", "-c:a", "aac", "-b:a", bitrate+"k",
			"-movflags", "+faststart",
			"-map_metadata", "0",
			tmpPath,
		)
		if err := cmd.Run(); err != nil {
			_ = os.Remove(tmpPath)
			// Fallback to original file if transcode fails
			http.ServeFile(w, r, it.FilePath)
			return
		}
		_ = os.Rename(tmpPath, outPath)
	}
	w.Header().Set("Content-Type", "audio/mp4")
	http.ServeFile(w, r, outPath)
}
