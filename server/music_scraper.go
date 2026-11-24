package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var (
	reTrackPrefix = regexp.MustCompile(`^\s*\d{1,3}[\s.\-_、]+`)
	reFeatSuffix  = regexp.MustCompile(`(?i)\s*[\(\[（](feat\.|ft\.|live|remix|伴奏|纯音乐|hq|sq|flac|mp3|320k|无损).*[\)\]）]`)
)

func parseTrackTitleArtist(rawTitle, relPath string) (title, artist string) {
	s := strings.TrimSpace(rawTitle)
	s = reTrackPrefix.ReplaceAllString(s, "")
	s = reFeatSuffix.ReplaceAllString(s, "")
	s = strings.TrimSpace(s)

	if strings.Contains(s, " - ") {
		parts := strings.SplitN(s, " - ", 2)
		a := strings.TrimSpace(parts[0])
		t := strings.TrimSpace(parts[1])
		if a != "" && t != "" {
			return t, a
		}
	}
	if strings.Contains(s, "-") && !strings.HasPrefix(s, "-") {
		parts := strings.SplitN(s, "-", 2)
		a := strings.TrimSpace(parts[0])
		t := strings.TrimSpace(parts[1])
		if len([]rune(a)) >= 2 && len([]rune(t)) >= 1 {
			return t, a
		}
	}
	// Fallback: check parent folder name as possible artist
	dir := filepath.Base(filepath.Dir(relPath))
	if dir != "" && dir != "." && dir != "/" {
		artist = dir
	}
	if s == "" {
		s = strings.TrimSpace(rawTitle)
	}
	return s, artist
}

type lrcLibResult struct {
	TrackName    string  `json:"trackName"`
	ArtistName   string  `json:"artistName"`
	AlbumName    string  `json:"albumName"`
	Duration     float64 `json:"duration"`
	SyncedLyrics string  `json:"syncedLyrics"`
	PlainLyrics  string  `json:"plainLyrics"`
}

func scrapeLrcLib(title, artist string) (lrc, trackName, artistName, albumName string, duration int64) {
	if strings.TrimSpace(title) == "" {
		return
	}
	client := &http.Client{Timeout: 8 * time.Second}
	queries := []string{}
	if artist != "" {
		queries = append(queries, fmt.Sprintf("https://lrclib.net/api/search?track_name=%s&artist_name=%s",
			url.QueryEscape(title), url.QueryEscape(artist)))
	}
	queries = append(queries, fmt.Sprintf("https://lrclib.net/api/search?q=%s",
		url.QueryEscape(strings.TrimSpace(artist+" "+title))))

	for _, u := range queries {
		req, err := http.NewRequest("GET", u, nil)
		if err != nil {
			continue
		}
		req.Header.Set("User-Agent", "NASFun/1.5 (https://github.com/my788525/nastv-lite)")
		resp, err := client.Do(req)
		if err != nil {
			continue
		}
		if resp.StatusCode != 200 {
			resp.Body.Close()
			continue
		}
		var list []lrcLibResult
		err = json.NewDecoder(resp.Body).Decode(&list)
		resp.Body.Close()
		if err != nil || len(list) == 0 {
			continue
		}
		// Prefer entries with syncedLyrics
		best := list[0]
		for _, item := range list {
			if strings.TrimSpace(item.SyncedLyrics) != "" {
				best = item
				break
			}
		}
		lrc = strings.TrimSpace(best.SyncedLyrics)
		if lrc == "" {
			lrc = strings.TrimSpace(best.PlainLyrics)
		}
		if lrc != "" || best.ArtistName != "" {
			return lrc, best.TrackName, best.ArtistName, best.AlbumName, int64(best.Duration)
		}
	}
	return
}

func scrapeITunesMusic(title, artist string) (trackName, artistName, albumName, artworkURL string) {
	q := strings.TrimSpace(artist + " " + title)
	if q == "" {
		return
	}
	client := &http.Client{Timeout: 8 * time.Second}
	urls := []string{
		fmt.Sprintf("https://itunes.apple.com/search?term=%s&media=music&entity=song&limit=3&country=CN", url.QueryEscape(q)),
		fmt.Sprintf("https://itunes.apple.com/search?term=%s&media=music&entity=song&limit=3", url.QueryEscape(q)),
	}
	for _, u := range urls {
		resp, err := client.Get(u)
		if err != nil {
			continue
		}
		if resp.StatusCode != 200 {
			resp.Body.Close()
			continue
		}
		var parsed struct {
			Results []struct {
				TrackName      string `json:"trackName"`
				ArtistName     string `json:"artistName"`
				CollectionName string `json:"collectionName"`
				ArtworkUrl100  string `json:"artworkUrl100"`
			} `json:"results"`
		}
		err = json.NewDecoder(resp.Body).Decode(&parsed)
		resp.Body.Close()
		if err != nil || len(parsed.Results) == 0 {
			continue
		}
		r := parsed.Results[0]
		art := strings.Replace(r.ArtworkUrl100, "100x100bb", "600x600bb", 1)
		return r.TrackName, r.ArtistName, r.CollectionName, art
	}
	return
}

func readAudioMetaViaFFprobe(ffprobePath, filePath string) (title, artist, album, lyrics string, duration int64) {
	if ffprobePath == "" || filePath == "" {
		return
	}
	cmd := exec.Command(ffprobePath, "-v", "quiet", "-print_format", "json", "-show_format", filePath)
	out, err := cmd.Output()
	if err != nil {
		return
	}
	var probe struct {
		Format struct {
			Duration string            `json:"duration"`
			Tags     map[string]string `json:"tags"`
		} `json:"format"`
	}
	if json.Unmarshal(out, &probe) != nil {
		return
	}
	if probe.Format.Duration != "" {
		if d, e := strconv.ParseFloat(probe.Format.Duration, 64); e == nil && d > 0 {
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
			if lyrics == "" {
				lyrics = v
			}
		}
	}
	return
}

func ScrapeMusicLibrary(s *Store, thumbDir, ffprobePath, ffmpegPath, libID string, force bool) int {
	lib, ok := s.GetLibrary(libID)
	if !ok {
		return 0
	}

	s.mu.RLock()
	var targets []*MediaItem
	for _, it := range s.data.Items {
		if it.LibID != libID || it.Type != "Audio" {
			continue
		}
		if !force && it.Lyrics != "" && it.PosterPath != "" && it.Artist != "" {
			continue
		}
		cp := *it
		targets = append(targets, &cp)
	}
	s.mu.RUnlock()

	updated := 0
	for _, it := range targets {
		qTitle, qArtist := parseTrackTitleArtist(it.Title, it.RelPath)
		if it.Artist != "" {
			qArtist = it.Artist
		}

		// 1. Check local sidecar .lrc/.txt
		localLrc := ""
		if it.FilePath != "" {
			base := strings.TrimSuffix(it.FilePath, filepath.Ext(it.FilePath))
			for _, ext := range []string{".lrc", ".LRC", ".txt"} {
				if raw, err := os.ReadFile(base + ext); err == nil && len(raw) > 0 {
					localLrc = strings.TrimSpace(strings.TrimPrefix(string(raw), "\xef\xbb\xbf"))
					break
				}
			}
		}

		// 2. Read embedded tags via ffprobe
		fTitle, fArtist, fAlbum, fLyrics, fDur := readAudioMetaViaFFprobe(ffprobePath, it.FilePath)
		if fTitle != "" {
			qTitle = fTitle
		}
		if fArtist != "" {
			qArtist = fArtist
		}

		lrc := localLrc
		if lrc == "" {
			lrc = fLyrics
		}
		artist := qArtist
		album := fAlbum
		if album == "" {
			album = it.Album
		}
		dur := fDur
		if dur <= 0 {
			dur = it.Duration
		}

		// 3. Scrape synced lyrics from LrcLib if still missing (or forced)
		if force || (lrc == "" && it.Lyrics == "") {
			sLrc, _, sArtist, sAlbum, sDur := scrapeLrcLib(qTitle, qArtist)
			if sLrc != "" {
				lrc = sLrc
			}
			if artist == "" && sArtist != "" {
				artist = sArtist
			}
			if album == "" && sAlbum != "" {
				album = sAlbum
			}
			if dur <= 0 && sDur > 0 {
				dur = sDur
			}
		}

		// 4. Scrape or extract cover art if missing
		posterLocal := it.PosterPath
		audThumb := filepath.Join(thumbDir, "aud_"+it.Guid+".jpg")
		if force || posterLocal == "" {
			if _, err := os.Stat(audThumb); err == nil && !force {
				posterLocal = audThumb
			} else {
				// Try embedded cover via ffmpeg first
				if ffmpegPath != "" && it.FilePath != "" {
					cmd := exec.Command(ffmpegPath, "-y", "-v", "quiet", "-i", it.FilePath, "-an", "-vcodec", "mjpeg", "-frames:v", "1", audThumb)
					if err := cmd.Run(); err == nil {
						if st, err2 := os.Stat(audThumb); err2 == nil && st.Size() > 0 {
							posterLocal = audThumb
						}
					}
				}
				// Fallback: public iTunes high-res cover art
				if posterLocal == "" {
					_, iArtist, iAlbum, artURL := scrapeITunesMusic(qTitle, artist)
					if artist == "" && iArtist != "" {
						artist = iArtist
					}
					if album == "" && iAlbum != "" {
						album = iAlbum
					}
					if artURL != "" && downloadPosterToCache(artURL, audThumb) {
						posterLocal = audThumb
					}
				}
			}
		}

		if lrc != "" || artist != "" || album != "" || posterLocal != "" || dur > 0 {
			s.mu.Lock()
			if cur, exists := s.data.Items[it.Guid]; exists {
				if artist != "" {
					cur.Artist = artist
				}
				if album != "" {
					cur.Album = album
				}
				if lrc != "" {
					cur.Lyrics = lrc
				}
				if posterLocal != "" {
					cur.PosterPath = posterLocal
				}
				if dur > 0 {
					cur.Duration = dur
				}
				updated++
			}
			s.mu.Unlock()
		}
		time.Sleep(150 * time.Millisecond)
	}

	if updated > 0 {
		_ = s.Save()
		log.Printf("[MusicScraper] Library %s scraped %d audio tracks", lib.Name, updated)
	}
	return updated
}
