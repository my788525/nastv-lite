package main

import (
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

func (app *App) requireAdmin(w http.ResponseWriter, r *http.Request) *User {
	u := app.requireUser(w, r)
	if u == nil {
		return nil
	}
	if u.Role != "admin" {
		errResp(w, 403, "需要管理员权限")
		return nil
	}
	return u
}

func getLocalIPs() (v4 []string, v6 []string) {
	ifaces, err := net.Interfaces()
	if err != nil {
		return
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			var ip net.IP
			switch v := a.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
				continue
			}
			if ip.To4() != nil {
				v4 = append(v4, ip.String())
			} else {
				v6 = append(v6, ip.String())
			}
		}
	}
	return
}

func (app *App) handleAdminStatus(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	v4, v6 := getLocalIPs()
	app.store.mu.RLock()
	libCount := len(app.store.data.Libraries)
	userCount := len(app.store.data.Users)
	itemCount := len(app.store.data.Items)
	app.store.mu.RUnlock()

	activeHLS, thumbCnt, cacheBytes := app.transcoder.GetCacheStats()
	settings := app.store.GetSettings()

	okResp(w, map[string]interface{}{
		"version_code":    ServerVersionCode,
		"version_name":    ServerVersionName,
		"server_name":     settings.ServerName,
		"port":            app.port,
		"ipv4":            v4,
		"ipv6":            v6,
		"has_ffmpeg":      app.transcoder.ffmpegPath != "",
		"has_vaapi":       app.transcoder.hasVAAPI,
		"lib_count":       libCount,
		"user_count":      userCount,
		"item_count":      itemCount,
		"active_hls":      activeHLS,
		"thumb_count":     thumbCnt,
		"cache_bytes":     cacheBytes,
		"auto_scan_hours": settings.AutoScanHours,
	})
}

func (app *App) handleAdminFS(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	dir := r.URL.Query().Get("path")
	if runtime.GOOS == "windows" {
		if dir == "" || dir == "/" || dir == "\\" {
			var drives []map[string]string
			for c := 'C'; c <= 'Z'; c++ {
				root := string(c) + `:\`
				if _, err := os.Stat(root); err == nil {
					drives = append(drives, map[string]string{
						"name": "💾 本地磁盘 (" + string(c) + ":)",
						"path": root,
					})
				}
			}
			okResp(w, map[string]interface{}{
				"current": "盘符总览",
				"parent":  "",
				"dirs":    drives,
			})
			return
		}
	} else {
		// Linux / fnOS: empty path shows all storage volumes (/vol1, /vol2, /vol3...) + /mnt + /
		if dir == "" {
			var vols []map[string]string
			if rootEntries, err := os.ReadDir("/"); err == nil {
				for _, e := range rootEntries {
					if !e.IsDir() {
						continue
					}
					n := e.Name()
					if strings.HasPrefix(n, "vol") && n != "vol00" {
						vols = append(vols, map[string]string{
							"name": "💾 存储空间 (" + "/" + n + ")",
							"path": "/" + n,
						})
					}
				}
			}
			sort.Slice(vols, func(i, j int) bool {
				return vols[i]["path"] < vols[j]["path"]
			})
			if _, err := os.Stat("/mnt"); err == nil {
				vols = append(vols, map[string]string{"name": "🔌 外接/挂载目录 (/mnt)", "path": "/mnt"})
			}
			vols = append(vols, map[string]string{"name": "🖥️ 系统根目录 (/)", "path": "/"})
			okResp(w, map[string]interface{}{
				"current": "存储空间总览",
				"parent":  "",
				"dirs":    vols,
			})
			return
		}
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		errResp(w, 400, "无法读取目录: "+err.Error())
		return
	}
	var dirs []map[string]string
	for _, e := range entries {
		name := e.Name()
		if !e.IsDir() || strings.HasPrefix(name, ".") || strings.HasPrefix(name, "@app") || name == "appcenter-downloads" {
			continue
		}
		dirs = append(dirs, map[string]string{
			"name": name,
			"path": filepath.Join(dir, name),
		})
	}
	sort.Slice(dirs, func(i, j int) bool {
		return dirs[i]["name"] < dirs[j]["name"]
	})
	parent := filepath.Dir(dir)
	if runtime.GOOS == "windows" {
		if parent == dir {
			parent = ""
		}
	} else {
		// From /vol1, /vol2, /vol3 or /, going up returns to Storage Volumes overview
		if parent == "/" && strings.HasPrefix(filepath.Base(dir), "vol") {
			parent = ""
		} else if dir == "/" {
			parent = ""
		}
	}
	okResp(w, map[string]interface{}{
		"current": dir,
		"parent":  parent,
		"dirs":    dirs,
	})
}

func (app *App) handleAdminLibraries(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	switch r.Method {
	case http.MethodGet:
		app.store.mu.RLock()
		var libs []*Library
		for _, lib := range app.store.data.Libraries {
			cp := *lib
			libs = append(libs, &cp)
		}
		app.store.mu.RUnlock()
		sort.Slice(libs, func(i, j int) bool {
			return libs[i].CreatedAt < libs[j].CreatedAt
		})
		if libs == nil {
			libs = []*Library{}
		}
		okResp(w, libs)

	case http.MethodPost:
		var req struct {
			ID       string   `json:"id"`
			Name     string   `json:"name"`
			Mode     string   `json:"mode"` // "movie_tv" or "folder"
			Paths    []string `json:"paths"`
			Category string   `json:"category"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			errResp(w, 400, "请求格式错误")
			return
		}
		if strings.TrimSpace(req.Name) == "" || len(req.Paths) == 0 {
			errResp(w, 400, "媒体库名称和路径不能为空")
			return
		}
		if req.Mode != ModeFolder {
			req.Mode = ModeMovieTV
		}
		if req.Category == "" {
			if req.Mode == ModeFolder {
				req.Category = "Folder"
			} else {
				req.Category = "Movie"
			}
		}

		app.store.mu.Lock()
		var lib *Library
		if req.ID != "" {
			if existing, ok := app.store.data.Libraries[req.ID]; ok {
				existing.Name = req.Name
				existing.Mode = req.Mode
				existing.Paths = req.Paths
				existing.Category = req.Category
				lib = existing
			}
		}
		if lib == nil {
			lib = &Library{
				ID:        "lib_" + randomHex(6),
				Name:      req.Name,
				Mode:      req.Mode,
				Paths:     req.Paths,
				Category:  req.Category,
				CreatedAt: time.Now().Unix(),
			}
			app.store.data.Libraries[lib.ID] = lib
		}
		_ = app.store.saveLocked()
		libCopy := *lib
		app.store.mu.Unlock()

		// Async scan right away
		go func(id string) {
			_, _ = ScanLibrary(app.store, id)
		}(libCopy.ID)

		okResp(w, libCopy)

	case http.MethodDelete:
		id := r.URL.Query().Get("id")
		if id == "" {
			errResp(w, 400, "缺少 id")
			return
		}
		app.store.mu.Lock()
		delete(app.store.data.Libraries, id)
		for guid, it := range app.store.data.Items {
			if it.LibID == id {
				delete(app.store.data.Items, guid)
			}
		}
		_ = app.store.saveLocked()
		app.store.mu.Unlock()
		okResp(w, map[string]bool{"deleted": true})

	default:
		errResp(w, 405, "method not allowed")
	}
}

func (app *App) handleAdminScan(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	id := r.URL.Query().Get("id")
	if id != "" {
		cnt, err := ScanLibrary(app.store, id)
		if err != nil {
			errResp(w, 400, err.Error())
			return
		}
		okResp(w, map[string]int{"scanned": cnt})
		return
	}
	app.store.mu.RLock()
	var ids []string
	for k := range app.store.data.Libraries {
		ids = append(ids, k)
	}
	app.store.mu.RUnlock()
	total := 0
	for _, lid := range ids {
		if cnt, err := ScanLibrary(app.store, lid); err == nil {
			total += cnt
		}
	}
	okResp(w, map[string]int{"scanned": total})
}

func (app *App) handleAdminUsers(w http.ResponseWriter, r *http.Request) {
	admin := app.requireAdmin(w, r)
	if admin == nil {
		return
	}
	switch r.Method {
	case http.MethodGet:
		app.store.mu.RLock()
		recCounts := make(map[string]int)
		for _, rec := range app.store.data.Records {
			recCounts[rec.UserID]++
		}
		var users []map[string]interface{}
		for _, u := range app.store.data.Users {
			users = append(users, map[string]interface{}{
				"id":           u.ID,
				"username":     u.Username,
				"role":         u.Role,
				"allowed_libs": u.AllowedLibs,
				"disabled":     u.Disabled,
				"watch_count":  recCounts[u.ID],
				"created_at":   u.CreatedAt,
			})
		}
		app.store.mu.RUnlock()
		okResp(w, users)

	case http.MethodPost:
		var req struct {
			ID          string   `json:"id"`
			Username    string   `json:"username"`
			Password    string   `json:"password"`
			Role        string   `json:"role"`
			AllowedLibs []string `json:"allowed_libs"`
			Disabled    bool     `json:"disabled"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			errResp(w, 400, "请求格式错误")
			return
		}
		if req.Role != "admin" {
			req.Role = "user"
		}
		if req.AllowedLibs == nil {
			req.AllowedLibs = []string{}
		}

		app.store.mu.Lock()
		if req.ID != "" {
			u, ok := app.store.data.Users[req.ID]
			if !ok {
				app.store.mu.Unlock()
				errResp(w, 404, "用户不存在")
				return
			}
			if req.Username != "" {
				u.Username = req.Username
			}
			if req.Password != "" {
				u.Salt = randomHex(8)
				u.Password = hashPassword(u.Salt, req.Password)
			}
			u.Role = req.Role
			u.AllowedLibs = req.AllowedLibs
			if u.ID != admin.ID {
				u.Disabled = req.Disabled
			}
		} else {
			if strings.TrimSpace(req.Username) == "" || strings.TrimSpace(req.Password) == "" {
				app.store.mu.Unlock()
				errResp(w, 400, "用户名和密码不能为空")
				return
			}
			for _, ex := range app.store.data.Users {
				if strings.EqualFold(ex.Username, req.Username) {
					app.store.mu.Unlock()
					errResp(w, 400, "用户名已存在")
					return
				}
			}
			nu := app.store.newUserObj(req.Username, req.Password, req.Role, req.AllowedLibs)
			app.store.data.Users[nu.ID] = nu
		}
		_ = app.store.saveLocked()
		app.store.mu.Unlock()
		okResp(w, map[string]bool{"saved": true})

	case http.MethodDelete:
		id := r.URL.Query().Get("id")
		if id == "" || id == admin.ID {
			errResp(w, 400, "不能删除当前管理员自身")
			return
		}
		app.store.mu.Lock()
		delete(app.store.data.Users, id)
		_ = app.store.saveLocked()
		app.store.mu.Unlock()
		okResp(w, map[string]bool{"deleted": true})

	default:
		errResp(w, 405, "method not allowed")
	}
}

func (app *App) handleAdminUpdateCheck(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	res, err := CheckServerUpdate()
	if err != nil {
		errResp(w, 500, "检查更新失败: "+err.Error())
		return
	}
	okResp(w, res)
}

func (app *App) handleAdminUpdateApply(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	var req struct {
		BinaryURL string `json:"binary_url"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	if req.BinaryURL == "" {
		res, err := CheckServerUpdate()
		if err != nil {
			errResp(w, 500, err.Error())
			return
		}
		if !res.HasUpdate {
			errResp(w, 400, "当前已是最新版本")
			return
		}
		req.BinaryURL = res.Latest.BinaryURL
	}
	if err := PerformServerSelfUpdate(req.BinaryURL); err != nil {
		errResp(w, 500, "更新失败: "+err.Error())
		return
	}
	okResp(w, map[string]string{"status": "updating_and_restarting"})
}

func (app *App) handleAdminSettings(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	if r.Method == http.MethodGet {
		okResp(w, app.store.GetSettings())
		return
	}
	if r.Method == http.MethodPost {
		var cfg ServerSettings
		if err := json.NewDecoder(r.Body).Decode(&cfg); err != nil {
			errResp(w, 400, "请求格式错误")
			return
		}
		if err := app.store.UpdateSettings(cfg); err != nil {
			errResp(w, 500, err.Error())
			return
		}
		okResp(w, app.store.GetSettings())
		return
	}
	errResp(w, 405, "method not allowed")
}

func (app *App) handleAdminCacheClear(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	app.transcoder.ClearCaches()
	okResp(w, map[string]bool{"cleared": true})
}

func (app *App) handleAdminLogs(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	logPath := filepath.Join(app.dataDir, "info.log")
	raw, err := os.ReadFile(logPath)
	if err != nil {
		okResp(w, map[string]string{"logs": "暂无运行日志（或当前运行于前台标准输出模式）"})
		return
	}
	lines := strings.Split(strings.TrimRight(string(raw), "\r\n"), "\n")
	if len(lines) > 120 {
		lines = lines[len(lines)-120:]
	}
	okResp(w, map[string]string{"logs": strings.Join(lines, "\n")})
}

func (app *App) handleAdminDDNS(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	switch r.Method {
	case http.MethodGet:
		app.store.mu.RLock()
		var list []*DDNSConfig
		for _, c := range app.store.data.DDNSConfigs {
			cp := *c
			if cp.APIKey != "" && len(cp.APIKey) > 6 {
				cp.APIKey = cp.APIKey[:3] + "****" + cp.APIKey[len(cp.APIKey)-3:]
			}
			cp.APISecret = ""
			list = append(list, &cp)
		}
		app.store.mu.RUnlock()
		if list == nil {
			list = []*DDNSConfig{}
		}
		okResp(w, list)

	case http.MethodPost:
		var req DDNSConfig
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			errResp(w, 400, "请求格式错误")
			return
		}
		if strings.TrimSpace(req.Domain) == "" && req.Provider != "custom" {
			errResp(w, 400, "域名不能为空")
			return
		}
		if req.IntervalMin <= 0 {
			req.IntervalMin = 5
		}
		if req.IPMode == "" {
			req.IPMode = "ipv6"
		}
		app.store.mu.Lock()
		var target *DDNSConfig
		if req.ID != "" {
			if ex, ok := app.store.data.DDNSConfigs[req.ID]; ok {
				ex.Name = req.Name
				ex.Provider = req.Provider
				ex.Enabled = req.Enabled
				ex.Domain = req.Domain
				ex.IPMode = req.IPMode
				ex.IntervalMin = req.IntervalMin
				if req.APIKey != "" && !strings.Contains(req.APIKey, "****") {
					ex.APIKey = req.APIKey
				}
				if req.APISecret != "" {
					ex.APISecret = req.APISecret
				}
				ex.ZoneID = req.ZoneID
				ex.Proxied = req.Proxied
				ex.CustomURL = req.CustomURL
				target = ex
			}
		}
		if target == nil {
			if req.Name == "" {
				req.Name = req.Domain
			}
			req.ID = "ddns_" + randomHex(6)
			target = &req
			app.store.data.DDNSConfigs[target.ID] = target
		}
		_ = app.store.saveLocked()
		cp := *target
		app.store.mu.Unlock()

		if cp.Enabled {
			go RunDDNSConfig(app.store, &cp)
		}
		okResp(w, cp)

	case http.MethodDelete:
		id := r.URL.Query().Get("id")
		app.store.mu.Lock()
		delete(app.store.data.DDNSConfigs, id)
		_ = app.store.saveLocked()
		app.store.mu.Unlock()
		okResp(w, map[string]bool{"deleted": true})

	default:
		errResp(w, 405, "method not allowed")
	}
}

func (app *App) handleAdminDDNSRun(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	id := r.URL.Query().Get("id")
	app.store.mu.RLock()
	cfg, ok := app.store.data.DDNSConfigs[id]
	var cp DDNSConfig
	if ok {
		cp = *cfg
	}
	app.store.mu.RUnlock()
	if !ok {
		errResp(w, 404, "DDNS 配置不存在")
		return
	}
	status := RunDDNSConfig(app.store, &cp)
	okResp(w, map[string]string{"status": status})
}

func (app *App) handleAdminDDNSIP(w http.ResponseWriter, r *http.Request) {
	if app.requireAdmin(w, r) == nil {
		return
	}
	okResp(w, map[string]string{
		"ipv4": DetectPublicIPv4(),
		"ipv6": DetectPublicIPv6(),
	})
}
