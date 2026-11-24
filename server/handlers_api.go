package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
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
	auth := r.Header.Get("Authorization")
	if strings.HasPrefix(auth, "Bearer ") {
		token := strings.TrimPrefix(auth, "Bearer ")
		if u, err := app.store.VerifyToken(token); err == nil {
			return u
		}
	}
	if q := r.URL.Query().Get("token"); q != "" {
		if u, err := app.store.VerifyToken(q); err == nil {
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
		"server":            "NASTV Lite",
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
			} else {
				cat = "Movie"
			}
		}
		res = append(res, MediaDbDTO{
			Guid:     lib.ID,
			Title:    lib.Name,
			Category: cat,
			Mode:     lib.Mode,
			Count:    lib.ItemCount,
		})
	}
	if res == nil {
		res = []MediaDbDTO{}
	}
	okResp(w, res)
}

func (app *App) toItemDTO(userID string, it *MediaItem, libMode string) PlayListItemDTO {
	poster := ""
	if it.PosterPath != "" || (it.Type != "Directory" && it.Type != "TV" && it.Type != "Audio") {
		poster = "/v/api/v1/img/" + it.Guid
	}
	return PlayListItemDTO{
		Guid:          it.Guid,
		ParentGuid:    it.ParentGuid,
		Title:         it.Title,
		TvTitle:       it.TvTitle,
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
		Types      []string `json:"types"`
		PageSize   int      `json:"page_size"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	targetParent := req.ParentGuid
	if targetParent == "" {
		targetParent = req.Ancestor
	}

	// Determine if targetParent is a library ID or a folder/show GUID
	var list []PlayListItemDTO
	app.store.mu.RLock()
	for _, it := range app.store.data.Items {
		if !u.CanAccessLibrary(it.LibID) {
			continue
		}
		if targetParent != "" && it.ParentGuid != targetParent {
			continue
		}
		libMode := ModeMovieTV
		if lib, ok := app.store.data.Libraries[it.LibID]; ok {
			libMode = lib.Mode
		}
		list = append(list, app.toItemDTO(u.ID, it, libMode))
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

	url := "/v/api/v1/media/range/" + it.Guid
	if req.Resolution != "" && req.Resolution != "original" && FindQualityProfile(req.Resolution) != nil {
		url = fmt.Sprintf("/v/api/v1/hls/%s/%s/index.m3u8", it.Guid, req.Resolution)
	}
	okResp(w, map[string]interface{}{
		"url":        url,
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
	sessDir, err := app.transcoder.EnsureHLS(it, quality)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	target := filepath.Join(sessDir, file)
	if strings.HasSuffix(file, ".m3u8") {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	} else if strings.HasSuffix(file, ".ts") {
		w.Header().Set("Content-Type", "video/mp2t")
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
	p := app.transcoder.EnsureThumbnail(it)
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
		if strings.Contains(strings.ToLower(it.Title), q) || strings.Contains(strings.ToLower(it.TvTitle), q) {
			mode := ModeMovieTV
			if lib, ok := app.store.data.Libraries[it.LibID]; ok {
				mode = lib.Mode
			}
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
