package main

import (
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

	// Only expose libraries with AllowDownload (文件共享属性) enabled
	if reqPath == "" {
		var entries []FileEntryDTO
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
