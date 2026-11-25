package main

import (
	"encoding/json"
	"fmt"
	"html"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const musicBrowserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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

var (
	biliCookieMu     sync.Mutex
	biliCookieCache  string
	biliCookieExpiry time.Time
	reHTMLTag        = regexp.MustCompile(`<[^>]+>`)
)

func getBilibiliCookie(forceRefresh bool) string {
	biliCookieMu.Lock()
	defer biliCookieMu.Unlock()
	if !forceRefresh && biliCookieCache != "" && time.Now().Before(biliCookieExpiry) {
		return biliCookieCache
	}
	client := &http.Client{Timeout: 8 * time.Second}
	req, _ := http.NewRequest("GET", "https://www.bilibili.com/", nil)
	req.Header.Set("User-Agent", musicBrowserUA)
	resp, err := client.Do(req)
	if err != nil {
		return biliCookieCache
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 256*1024))
	var parts []string
	for _, c := range resp.Cookies() {
		if c.Name != "" && c.Value != "" {
			parts = append(parts, c.Name+"="+c.Value)
		}
	}
	if len(parts) > 0 {
		biliCookieCache = strings.Join(parts, "; ")
		biliCookieExpiry = time.Now().Add(30 * time.Minute)
	}
	return biliCookieCache
}

func biliGetJSON(targetURL string) ([]byte, int, error) {
	cookie := getBilibiliCookie(false)
	for attempt := 0; attempt < 2; attempt++ {
		client := &http.Client{Timeout: 10 * time.Second}
		req, _ := http.NewRequest("GET", targetURL, nil)
		req.Header.Set("User-Agent", musicBrowserUA)
		req.Header.Set("Referer", "https://www.bilibili.com/")
		req.Header.Set("Origin", "https://www.bilibili.com")
		if cookie != "" {
			req.Header.Set("Cookie", cookie)
		}
		resp, err := client.Do(req)
		if err != nil {
			return nil, 0, err
		}
		body, readErr := io.ReadAll(io.LimitReader(resp.Body, 2*1024*1024))
		resp.Body.Close()
		if resp.StatusCode == 412 && attempt == 0 {
			cookie = getBilibiliCookie(true)
			continue
		}
		return body, resp.StatusCode, readErr
	}
	return nil, 0, fmt.Errorf("bilibili request failed")
}

func parseColonDuration(s string) int64 {
	parts := strings.Split(strings.TrimSpace(s), ":")
	var sec int64
	for _, p := range parts {
		v, err := strconv.ParseInt(strings.TrimSpace(p), 10, 64)
		if err != nil {
			return 0
		}
		sec = sec*60 + v
	}
	return sec
}

func searchBilibiliTracks(query string) []MusicSearchItem {
	if query == "" {
		return nil
	}
	u := "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=" + url.QueryEscape(query) + "&page=1&pagesize=12"
	raw, code, err := biliGetJSON(u)
	if err != nil || code != 200 {
		return nil
	}
	var parsed struct {
		Code int `json:"code"`
		Data struct {
			Result []struct {
				BVID     string `json:"bvid"`
				Title    string `json:"title"`
				Author   string `json:"author"`
				Pic      string `json:"pic"`
				Duration string `json:"duration"`
			} `json:"result"`
		} `json:"data"`
	}
	if json.Unmarshal(raw, &parsed) != nil || parsed.Code != 0 {
		return nil
	}
	var out []MusicSearchItem
	for _, r := range parsed.Data.Result {
		if r.BVID == "" || r.Title == "" {
			continue
		}
		title := strings.TrimSpace(html.UnescapeString(reHTMLTag.ReplaceAllString(r.Title, "")))
		cov := strings.TrimSpace(r.Pic)
		if strings.HasPrefix(cov, "//") {
			cov = "https:" + cov
		}
		out = append(out, MusicSearchItem{
			ID:       "bili_" + r.BVID,
			Title:    title,
			Artist:   strings.TrimSpace(r.Author),
			Album:    "Bilibili",
			Duration: parseColonDuration(r.Duration),
			CoverURL: cov,
			Source:   "bilibili",
		})
	}
	return out
}

func resolveBilibiliAudioURL(bvid string) string {
	bvid = strings.TrimSpace(strings.TrimPrefix(bvid, "bili_"))
	if bvid == "" {
		return ""
	}
	rawView, code, err := biliGetJSON("https://api.bilibili.com/x/web-interface/view?bvid=" + url.QueryEscape(bvid))
	if err != nil || code != 200 {
		return ""
	}
	var view struct {
		Code int `json:"code"`
		Data struct {
			Pages []struct {
				CID int64 `json:"cid"`
			} `json:"pages"`
		} `json:"data"`
	}
	if json.Unmarshal(rawView, &view) != nil || len(view.Data.Pages) == 0 {
		return ""
	}
	cid := view.Data.Pages[0].CID
	playURL := fmt.Sprintf("https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%d&fnval=16&fnver=0&fourk=1", url.QueryEscape(bvid), cid)
	rawPlay, code, err := biliGetJSON(playURL)
	if err != nil || code != 200 {
		return ""
	}
	var play struct {
		Data struct {
			Dash struct {
				Audio []struct {
					Bandwidth int64    `json:"bandwidth"`
					BaseURL   string   `json:"baseUrl"`
					BackupURL []string `json:"backupUrl"`
				} `json:"audio"`
			} `json:"dash"`
			Durl []struct {
				URL string `json:"url"`
			} `json:"durl"`
		} `json:"data"`
	}
	if json.Unmarshal(rawPlay, &play) != nil {
		return ""
	}
	bestBW := int64(-1)
	bestURL := ""
	for _, a := range play.Data.Dash.Audio {
		u := a.BaseURL
		if u == "" && len(a.BackupURL) > 0 {
			u = a.BackupURL[0]
		}
		if u != "" && a.Bandwidth > bestBW {
			bestBW = a.Bandwidth
			bestURL = u
		}
	}
	if bestURL != "" {
		return bestURL
	}
	if len(play.Data.Durl) > 0 {
		return play.Data.Durl[0].URL
	}
	return ""
}

// jsObjToJSON converts Kuwo's single-quoted JS object string into valid JSON.
func jsObjToJSON(s string) string {
	var b strings.Builder
	b.Grow(len(s) + 64)
	inSingle := false
	escaped := false
	for i := 0; i < len(s); i++ {
		ch := s[i]
		if inSingle {
			if escaped {
				if ch == '\'' {
					b.WriteByte('\'')
				} else {
					b.WriteByte('\\')
					b.WriteByte(ch)
				}
				escaped = false
				continue
			}
			if ch == '\\' {
				escaped = true
				continue
			}
			if ch == '\'' {
				inSingle = false
				b.WriteByte('"')
				continue
			}
			if ch == '"' {
				b.WriteString(`\"`)
				continue
			}
			b.WriteByte(ch)
		} else {
			if ch == '\'' {
				inSingle = true
				b.WriteByte('"')
			} else {
				b.WriteByte(ch)
			}
		}
	}
	return b.String()
}

func cleanKuwoText(s string) string {
	s = strings.ReplaceAll(s, "&nbsp;", " ")
	return strings.TrimSpace(html.UnescapeString(s))
}

func searchKuwoTracks(query string) []MusicSearchItem {
	if query == "" {
		return nil
	}
	client := &http.Client{Timeout: 8 * time.Second}
	u := "http://search.kuwo.cn/r.s?all=" + url.QueryEscape(query) + "&ft=music&itemset=web_2013&client=kt&pn=0&rn=12&rformat=json&encoding=utf8"
	req, _ := http.NewRequest("GET", u, nil)
	req.Header.Set("User-Agent", musicBrowserUA)
	resp, err := client.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 2*1024*1024))
	if err != nil {
		return nil
	}
	jsonBytes := []byte(jsObjToJSON(string(raw)))
	var parsed struct {
		Abslist []struct {
			MUSICRID       string `json:"MUSICRID"`
			SONGNAME       string `json:"SONGNAME"`
			NAME           string `json:"NAME"`
			ARTIST         string `json:"ARTIST"`
			ALBUM          string `json:"ALBUM"`
			DURATION       string `json:"DURATION"`
			WebAlbumpic    string `json:"web_albumpic_short"`
			HtsMvpic       string `json:"hts_MVPIC"`
			PicPath        string `json:"PICPATH"`
		} `json:"abslist"`
	}
	if json.Unmarshal(jsonBytes, &parsed) != nil {
		return nil
	}
	var out []MusicSearchItem
	for _, r := range parsed.Abslist {
		rid := strings.TrimPrefix(strings.TrimSpace(r.MUSICRID), "MUSIC_")
		title := cleanKuwoText(r.SONGNAME)
		if title == "" {
			title = cleanKuwoText(r.NAME)
		}
		if rid == "" || title == "" {
			continue
		}
		dur, _ := strconv.ParseInt(strings.TrimSpace(r.DURATION), 10, 64)
		cov := ""
		if r.WebAlbumpic != "" {
			p := strings.Replace(r.WebAlbumpic, "120/", "500/", 1)
			cov = "https://img4.kuwo.cn/star/albumcover/" + p
		} else if r.HtsMvpic != "" {
			cov = r.HtsMvpic
		}
		out = append(out, MusicSearchItem{
			ID:       "kuwo_" + rid,
			Title:    title,
			Artist:   cleanKuwoText(r.ARTIST),
			Album:    cleanKuwoText(r.ALBUM),
			Duration: dur,
			CoverURL: cov,
			Source:   "kuwo",
		})
	}
	return out
}

func resolveKuwoAudioURL(rid string) string {
	rid = strings.TrimPrefix(strings.TrimSpace(rid), "kuwo_")
	rid = strings.TrimPrefix(rid, "MUSIC_")
	if rid == "" {
		return ""
	}
	client := &http.Client{Timeout: 8 * time.Second}
	u := "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=MUSIC_" + url.QueryEscape(rid) + "&format=mp3&response=url"
	req, _ := http.NewRequest("GET", u, nil)
	req.Header.Set("User-Agent", musicBrowserUA)
	resp, err := client.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return ""
	}
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	res := strings.TrimSpace(string(b))
	if strings.HasPrefix(res, "http://") || strings.HasPrefix(res, "https://") {
		return res
	}
	return ""
}

func searchNeteasePublicTracks(query string) []MusicSearchItem {
	if query == "" {
		return nil
	}
	client := &http.Client{Timeout: 8 * time.Second}
	form := url.Values{}
	form.Set("s", query)
	form.Set("type", "1")
	form.Set("limit", "10")
	req, _ := http.NewRequest("POST", "https://music.163.com/api/search/get/web", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("User-Agent", musicBrowserUA)
	req.Header.Set("Referer", "https://music.163.com/")
	resp, err := client.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil
	}
	var parsed struct {
		Result struct {
			Songs []struct {
				ID      int64  `json:"id"`
				Name    string `json:"name"`
				Dur     int64  `json:"duration"`
				Artists []struct {
					Name string `json:"name"`
				} `json:"artists"`
				Album struct {
					Name   string `json:"name"`
					PicURL string `json:"picUrl"`
				} `json:"album"`
			} `json:"songs"`
		} `json:"result"`
	}
	if json.NewDecoder(resp.Body).Decode(&parsed) != nil {
		return nil
	}
	var out []MusicSearchItem
	for _, s := range parsed.Result.Songs {
		if s.ID == 0 || s.Name == "" {
			continue
		}
		artist := ""
		if len(s.Artists) > 0 {
			artist = s.Artists[0].Name
		}
		out = append(out, MusicSearchItem{
			ID:       fmt.Sprintf("netease_%d", s.ID),
			Title:    s.Name,
			Artist:   artist,
			Album:    s.Album.Name,
			Duration: s.Dur / 1000,
			CoverURL: s.Album.PicURL,
			Source:   "netease",
		})
	}
	return out
}

func resolveNeteasePublicURL(nid string) string {
	nid = strings.TrimPrefix(strings.TrimSpace(nid), "netease_")
	if nid == "" {
		return ""
	}
	client := &http.Client{
		Timeout: 8 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	u := "https://music.163.com/song/media/outer/url?id=" + url.QueryEscape(nid) + ".mp3"
	req, _ := http.NewRequest("GET", u, nil)
	req.Header.Set("User-Agent", musicBrowserUA)
	req.Header.Set("Referer", "https://music.163.com/")
	resp, err := client.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	loc := resp.Header.Get("Location")
	if (resp.StatusCode == 301 || resp.StatusCode == 302) && loc != "" && !strings.Contains(loc, "/404") {
		return loc
	}
	return ""
}

func resolveAudioURLByID(musicSourceAPI, songID, title, artist string) (string, string) {
	if strings.HasPrefix(songID, "bili_") {
		return resolveBilibiliAudioURL(songID), "https://www.bilibili.com/"
	}
	if strings.HasPrefix(songID, "kuwo_") {
		return resolveKuwoAudioURL(songID), ""
	}
	if strings.HasPrefix(songID, "netease_") {
		if u := resolveNeteasePublicURL(songID); u != "" {
			return u, "https://music.163.com/"
		}
	} else if musicSourceAPI != "" && songID != "" {
		if u := resolveCustomAudioURL(musicSourceAPI, songID); u != "" {
			return u, ""
		}
	}
	// Listen1-style cross-platform fallback: try Kuwo then Bilibili by title + artist
	if q := strings.TrimSpace(title + " " + artist); q != "" {
		for _, k := range searchKuwoTracks(q) {
			if u := resolveKuwoAudioURL(k.ID); u != "" {
				return u, ""
			}
			break
		}
		for _, b := range searchBilibiliTracks(q) {
			if u := resolveBilibiliAudioURL(b.ID); u != "" {
				return u, "https://www.bilibili.com/"
			}
			break
		}
	}
	return "", ""
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
	source := strings.TrimSpace(r.URL.Query().Get("source"))
	if source == "" {
		source = "all"
	}
	cfg := app.store.GetSettings()
	var results []MusicSearchItem
	if cfg.MusicSourceAPI != "" && (source == "all" || source == "custom") {
		results = searchCustomMusicAPI(cfg.MusicSourceAPI, q)
	}
	if len(results) == 0 {
		var wg sync.WaitGroup
		var kuwoRes, biliRes, neRes []MusicSearchItem
		if source == "all" || source == "kuwo" {
			wg.Add(1)
			go func() {
				defer wg.Done()
				kuwoRes = searchKuwoTracks(q)
			}()
		}
		if source == "all" || source == "bilibili" {
			wg.Add(1)
			go func() {
				defer wg.Done()
				biliRes = searchBilibiliTracks(q)
			}()
		}
		if source == "all" || source == "netease" {
			wg.Add(1)
			go func() {
				defer wg.Done()
				neRes = searchNeteasePublicTracks(q)
			}()
		}
		wg.Wait()

		// Interleave results across platforms (Listen1-style aggregation)
		maxLen := len(kuwoRes)
		if len(biliRes) > maxLen {
			maxLen = len(biliRes)
		}
		if len(neRes) > maxLen {
			maxLen = len(neRes)
		}
		for i := 0; i < maxLen; i++ {
			if i < len(kuwoRes) {
				results = append(results, kuwoRes[i])
			}
			if i < len(neRes) {
				results = append(results, neRes[i])
			}
			if i < len(biliRes) {
				results = append(results, biliRes[i])
			}
		}
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
	title := strings.TrimSpace(r.URL.Query().Get("title"))
	artist := strings.TrimSpace(r.URL.Query().Get("artist"))
	if id == "" && title == "" {
		errResp(w, 400, "缺少歌曲 ID")
		return
	}
	cfg := app.store.GetSettings()
	audioURL, _ := resolveAudioURLByID(cfg.MusicSourceAPI, id, title, artist)
	if audioURL == "" {
		errResp(w, 404, "未获取到完整版试听链接")
		return
	}
	okResp(w, map[string]string{"url": audioURL})
}

func (app *App) handleAdminMusicStream(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") == "" {
		if tok := strings.TrimSpace(r.URL.Query().Get("token")); tok != "" {
			r.Header.Set("Authorization", "Bearer "+tok)
		}
	}
	if app.requireAdmin(w, r) == nil {
		return
	}
	id := strings.TrimSpace(r.URL.Query().Get("id"))
	title := strings.TrimSpace(r.URL.Query().Get("title"))
	artist := strings.TrimSpace(r.URL.Query().Get("artist"))
	rawURL := strings.TrimSpace(r.URL.Query().Get("url"))
	referer := ""
	if rawURL == "" {
		rawURL, referer = resolveAudioURLByID(app.store.GetSettings().MusicSourceAPI, id, title, artist)
	}
	if rawURL == "" {
		http.Error(w, "未获取到可播放的完整版音频", http.StatusNotFound)
		return
	}
	if referer == "" && (strings.Contains(rawURL, "bilivideo") || strings.Contains(rawURL, "bilibili")) {
		referer = "https://www.bilibili.com/"
	}
	client := &http.Client{Timeout: 60 * time.Second}
	req, err := http.NewRequest("GET", rawURL, nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	req.Header.Set("User-Agent", musicBrowserUA)
	if referer != "" {
		req.Header.Set("Referer", referer)
	}
	if rng := r.Header.Get("Range"); rng != "" {
		req.Header.Set("Range", rng)
	}
	resp, err := client.Do(req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	for _, h := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"} {
		if v := resp.Header.Get(h); v != "" {
			w.Header().Set(h, v)
		}
	}
	if w.Header().Get("Content-Type") == "" {
		w.Header().Set("Content-Type", "audio/mp4")
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
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
	referer := ""
	if audioURL == "" {
		updateMusicTask(taskID, "downloading", "正在解析完整版音频下载地址（支持跨平台自动匹配）...", "")
		audioURL, referer = resolveAudioURLByID(cfg.MusicSourceAPI, songID, title, artist)
	}
	if audioURL == "" {
		updateMusicTask(taskID, "failed", "未能获取到完整版音频下载链接（可能受版权/VIP限制，请换一个来源或使用直链导入）", "")
		return
	}

	updateMusicTask(taskID, "downloading", "正在下载完整版音频文件到 NAS...", "")
	client := &http.Client{Timeout: 120 * time.Second}
	req, err := http.NewRequest("GET", audioURL, nil)
	if err != nil {
		updateMusicTask(taskID, "failed", "下载失败: "+err.Error(), "")
		return
	}
	req.Header.Set("User-Agent", musicBrowserUA)
	if referer != "" {
		req.Header.Set("Referer", referer)
	} else if strings.Contains(audioURL, "bilivideo") || strings.Contains(audioURL, "bilibili") {
		req.Header.Set("Referer", "https://www.bilibili.com/")
	}
	resp, err := client.Do(req)
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
