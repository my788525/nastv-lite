package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
)

type FileEntryDTO struct {
	Name    string `json:"name"`
	Path    string `json:"path"`
	IsDir   bool   `json:"is_dir"`
	Size    int64  `json:"size"`
	ModTime int64  `json:"mod_time"`
	Ext     string `json:"ext"`
}

func isSubPath(root, target string) bool {
	absRoot, err1 := filepath.Abs(root)
	absTarget, err2 := filepath.Abs(target)
	if err1 != nil || err2 != nil {
		return false
	}
	if runtime.GOOS == "windows" {
		absRoot = strings.ToLower(absRoot)
		absTarget = strings.ToLower(absTarget)
	}
	if absRoot == absTarget {
		return true
	}
	if !strings.HasSuffix(absRoot, string(filepath.Separator)) {
		absRoot += string(filepath.Separator)
	}
	return strings.HasPrefix(absTarget, absRoot)
}

func (app *App) canAccessFSPath(u *User, target string) bool {
	if u == nil {
		return false
	}
	updatesDir := filepath.Join(app.dataDir, "updates")
	if isSubPath(updatesDir, target) {
		return true
	}
	libs := app.store.GetUserLibraries(u)
	for _, lib := range libs {
		if !lib.AllowDownload {
			continue
		}
		for _, p := range lib.Paths {
			if isSubPath(p, target) {
				return true
			}
		}
	}
	return false
}

func (app *App) handleFilesList(w http.ResponseWriter, r *http.Request) {
	u := app.requireUser(w, r)
	if u == nil {
		return
	}
	reqPath := strings.TrimSpace(r.URL.Query().Get("path"))

	// Only expose libraries with AllowDownload (文件共享属性) enabled + built-in updates dir
	if reqPath == "" {
		var entries []FileEntryDTO
		updatesDir := filepath.Join(app.dataDir, "updates")
		_ = os.MkdirAll(updatesDir, 0755)
		entries = append(entries, FileEntryDTO{
			Name:  "📦 NASFun 客户端安装包",
			Path:  updatesDir,
			IsDir: true,
		})
		libs := app.store.GetUserLibraries(u)
		for _, lib := range libs {
			if !lib.AllowDownload {
				continue
			}
			for _, p := range lib.Paths {
				entries = append(entries, FileEntryDTO{
					Name:  "📂 " + lib.Name + " (" + filepath.Base(p) + ")",
					Path:  p,
					IsDir: true,
				})
			}
		}
		if entries == nil {
			entries = []FileEntryDTO{}
		}
		okResp(w, map[string]interface{}{
			"current": "",
			"parent":  "",
			"entries": entries,
		})
		return
	}

	if !app.canAccessFSPath(u, reqPath) {
		errResp(w, 403, "无权访问该目录")
		return
	}

	if runtime.GOOS == "windows" && (reqPath == "/" || reqPath == "\\") {
		var entries []FileEntryDTO
		for c := 'C'; c <= 'Z'; c++ {
			root := string(c) + `:\`
			if _, err := os.Stat(root); err == nil {
				entries = append(entries, FileEntryDTO{
					Name:  "本地磁盘 (" + string(c) + ":)",
					Path:  root,
					IsDir: true,
				})
			}
		}
		okResp(w, map[string]interface{}{
			"current": "/",
			"parent":  "",
			"entries": entries,
		})
		return
	}

	dirEntries, err := os.ReadDir(reqPath)
	if err != nil {
		errResp(w, 400, "读取目录失败: "+err.Error())
		return
	}

	var entries []FileEntryDTO
	for _, de := range dirEntries {
		name := de.Name()
		if strings.HasPrefix(name, ".") || name == "@eaDir" || name == "#recycle" {
			continue
		}
		fullPath := filepath.Join(reqPath, name)
		info, err := de.Info()
		if err != nil {
			continue
		}
		ext := ""
		if !de.IsDir() {
			ext = strings.ToLower(strings.TrimPrefix(filepath.Ext(name), "."))
		}
		entries = append(entries, FileEntryDTO{
			Name:    name,
			Path:    fullPath,
			IsDir:   de.IsDir(),
			Size:    info.Size(),
			ModTime: info.ModTime().Unix(),
			Ext:     ext,
		})
	}

	sort.Slice(entries, func(i, j int) bool {
		if entries[i].IsDir != entries[j].IsDir {
			return entries[i].IsDir
		}
		return strings.ToLower(entries[i].Name) < strings.ToLower(entries[j].Name)
	})

	parent := filepath.Dir(reqPath)
	if parent == reqPath {
		if u.Role == "admin" && runtime.GOOS == "windows" {
			parent = "/"
		} else {
			parent = ""
		}
	} else if !app.canAccessFSPath(u, parent) {
		parent = ""
	}

	if entries == nil {
		entries = []FileEntryDTO{}
	}
	okResp(w, map[string]interface{}{
		"current": reqPath,
		"parent":  parent,
		"entries": entries,
	})
}

func (app *App) handleFilesDownload(w http.ResponseWriter, r *http.Request) {
	u := app.extractUser(r)
	if u == nil {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	reqPath := strings.TrimSpace(r.URL.Query().Get("path"))
	if reqPath == "" || !app.canAccessFSPath(u, reqPath) {
		http.Error(w, "Forbidden", http.StatusForbidden)
		return
	}
	f, err := os.Open(reqPath)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || st.IsDir() {
		http.NotFound(w, r)
		return
	}
	fname := filepath.Base(reqPath)
	w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+url.PathEscape(fname))
	http.ServeContent(w, r, fname, st.ModTime(), f)
}

func isSafeSingleName(name string) bool {
	if name == "" || name == "." || name == ".." {
		return false
	}
	return !strings.ContainsAny(name, "/\\\x00")
}

func (app *App) isSharedRootPath(u *User, target string) bool {
	absTarget, err := filepath.Abs(target)
	if err != nil {
		return true
	}
	norm := func(p string) string {
		a, e := filepath.Abs(p)
		if e != nil {
			return p
		}
		if runtime.GOOS == "windows" {
			return strings.ToLower(a)
		}
		return a
	}
	nt := norm(absTarget)
	if nt == norm(filepath.Join(app.dataDir, "updates")) {
		return true
	}
	for _, lib := range app.store.GetUserLibraries(u) {
		if !lib.AllowDownload {
			continue
		}
		for _, p := range lib.Paths {
			if nt == norm(p) {
				return true
			}
		}
	}
	return false
}

func (app *App) handleAdminFilesUpload(w http.ResponseWriter, r *http.Request) {
	u := app.requireAdmin(w, r)
	if u == nil {
		return
	}
	if r.Method != http.MethodPost {
		errResp(w, 405, "method not allowed")
		return
	}
	dir := strings.TrimSpace(r.URL.Query().Get("dir"))
	if dir == "" || !app.canAccessFSPath(u, dir) {
		errResp(w, 403, "无权上传到该目录（请选择已开启文件共享的目录或安装包目录）")
		return
	}
	mr, err := r.MultipartReader()
	if err != nil {
		errResp(w, 400, "解析上传请求失败: "+err.Error())
		return
	}
	_ = os.MkdirAll(dir, 0755)
	var uploaded []string
	for {
		part, err := mr.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			errResp(w, 400, "读取上传数据失败: "+err.Error())
			return
		}
		fn := filepath.Base(strings.TrimSpace(part.FileName()))
		if fn == "" || fn == "." || !isSafeSingleName(fn) {
			_ = part.Close()
			continue
		}
		dest := filepath.Join(dir, fn)
		tmp := dest + ".upload.tmp"
		f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
		if err != nil {
			_ = part.Close()
			errResp(w, 500, "创建文件失败: "+err.Error())
			return
		}
		_, copyErr := io.Copy(f, part)
		f.Close()
		part.Close()
		if copyErr != nil {
			_ = os.Remove(tmp)
			errResp(w, 500, "写入文件失败: "+copyErr.Error())
			return
		}
		if err := os.Rename(tmp, dest); err != nil {
			_ = os.Remove(tmp)
			errResp(w, 500, "保存文件失败: "+err.Error())
			return
		}
		uploaded = append(uploaded, fn)
	}
	if len(uploaded) == 0 {
		errResp(w, 400, "未接收到有效文件")
		return
	}
	okResp(w, map[string]interface{}{"uploaded": uploaded})
}

func (app *App) handleAdminFilesMkdir(w http.ResponseWriter, r *http.Request) {
	u := app.requireAdmin(w, r)
	if u == nil {
		return
	}
	if r.Method != http.MethodPost {
		errResp(w, 405, "method not allowed")
		return
	}
	var req struct {
		Dir  string `json:"dir"`
		Name string `json:"name"`
	}
	if json.NewDecoder(r.Body).Decode(&req) != nil {
		errResp(w, 400, "请求参数错误")
		return
	}
	req.Dir = strings.TrimSpace(req.Dir)
	req.Name = strings.TrimSpace(req.Name)
	if !app.canAccessFSPath(u, req.Dir) || !isSafeSingleName(req.Name) {
		errResp(w, 400, "目录路径或文件夹名称不合法")
		return
	}
	target := filepath.Join(req.Dir, req.Name)
	if err := os.MkdirAll(target, 0755); err != nil {
		errResp(w, 500, "创建文件夹失败: "+err.Error())
		return
	}
	okResp(w, map[string]string{"path": target})
}

func (app *App) handleAdminFilesRename(w http.ResponseWriter, r *http.Request) {
	u := app.requireAdmin(w, r)
	if u == nil {
		return
	}
	if r.Method != http.MethodPost {
		errResp(w, 405, "method not allowed")
		return
	}
	var req struct {
		Path    string `json:"path"`
		NewName string `json:"new_name"`
	}
	if json.NewDecoder(r.Body).Decode(&req) != nil {
		errResp(w, 400, "请求参数错误")
		return
	}
	req.Path = strings.TrimSpace(req.Path)
	req.NewName = strings.TrimSpace(req.NewName)
	if req.Path == "" || !app.canAccessFSPath(u, req.Path) || app.isSharedRootPath(u, req.Path) || !isSafeSingleName(req.NewName) {
		errResp(w, 400, "目标不可重命名或新名称不合法")
		return
	}
	dest := filepath.Join(filepath.Dir(req.Path), req.NewName)
	if err := os.Rename(req.Path, dest); err != nil {
		errResp(w, 500, "重命名失败: "+err.Error())
		return
	}
	okResp(w, map[string]string{"path": dest})
}

func (app *App) handleAdminFilesDelete(w http.ResponseWriter, r *http.Request) {
	u := app.requireAdmin(w, r)
	if u == nil {
		return
	}
	if r.Method != http.MethodPost {
		errResp(w, 405, "method not allowed")
		return
	}
	var req struct {
		Path string `json:"path"`
	}
	if json.NewDecoder(r.Body).Decode(&req) != nil {
		errResp(w, 400, "请求参数错误")
		return
	}
	req.Path = strings.TrimSpace(req.Path)
	if req.Path == "" || !app.canAccessFSPath(u, req.Path) || app.isSharedRootPath(u, req.Path) {
		errResp(w, 403, "不允许删除根目录或无权路径")
		return
	}
	if err := os.RemoveAll(req.Path); err != nil {
		errResp(w, 500, "删除失败: "+err.Error())
		return
	}
	okResp(w, map[string]bool{"deleted": true})
}
