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
	"regexp"
	"strconv"
	"strings"
	"time"
)

type scrapedMeta struct {
	Title     string
	Overview  string
	Year      int
	Score     float64
	PosterURL string
}

var reStripBrackets = regexp.MustCompile(`[\[\(（【][^\]\)）】]*[\]\)）】]`)

func cleanQueryTitle(raw string) (string, int) {
	year := 0
	if m := reYear.FindStringSubmatch(raw); len(m) >= 3 {
		ys := m[1]
		if ys == "" {
			ys = m[2]
		}
		year, _ = strconv.Atoi(ys)
	}
	s := reStripBrackets.ReplaceAllString(raw, " ")
	s = reTechTags.ReplaceAllString(s, " ")
	s = reCnTags.ReplaceAllString(s, " ")
	s = regexp.MustCompile(`[._\-]+`).ReplaceAllString(s, " ")
	s = strings.TrimSpace(regexp.MustCompile(`\s+`).ReplaceAllString(s, " "))
	if s == "" {
		s = strings.TrimSpace(raw)
	}
	return s, year
}

func scrapeTMDB(title string, year int, isTV bool, apiKey, mirror string) *scrapedMeta {
	if strings.TrimSpace(apiKey) == "" {
		return nil
	}
	base := strings.TrimRight(strings.TrimSpace(mirror), "/")
	if base == "" {
		base = "https://api.themoviedb.org/3"
	}
	endpoint := "search/movie"
	if isTV {
		endpoint = "search/tv"
	}
	u := fmt.Sprintf("%s/%s?api_key=%s&language=zh-CN&query=%s",
		base, endpoint, url.QueryEscape(strings.TrimSpace(apiKey)), url.QueryEscape(title))
	if year > 1900 {
		if isTV {
			u += fmt.Sprintf("&first_air_date_year=%d", year)
		} else {
			u += fmt.Sprintf("&year=%d", year)
		}
	}
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Get(u)
	if err != nil || resp.StatusCode != 200 {
		if resp != nil {
			resp.Body.Close()
		}
		return nil
	}
	defer resp.Body.Close()

	var parsed struct {
		Results []struct {
			Title        string  `json:"title"`
			Name         string  `json:"name"`
			Overview     string  `json:"overview"`
			ReleaseDate  string  `json:"release_date"`
			FirstAirDate string  `json:"first_air_date"`
			VoteAverage  float64 `json:"vote_average"`
			PosterPath   string  `json:"poster_path"`
		} `json:"results"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&parsed); err != nil || len(parsed.Results) == 0 {
		return nil
	}
	r := parsed.Results[0]
	resTitle := r.Title
	if resTitle == "" {
		resTitle = r.Name
	}
	dateStr := r.ReleaseDate
	if dateStr == "" {
		dateStr = r.FirstAirDate
	}
	resYear := year
	if len(dateStr) >= 4 {
		if y, err := strconv.Atoi(dateStr[:4]); err == nil {
			resYear = y
		}
	}
	posterURL := ""
	if r.PosterPath != "" {
		posterURL = "https://image.tmdb.org/t/p/w500" + r.PosterPath
	}
	return &scrapedMeta{
		Title:     resTitle,
		Overview:  r.Overview,
		Year:      resYear,
		Score:     r.VoteAverage,
		PosterURL: posterURL,
	}
}

// Keyless public fallback 1: Apple iTunes Movie Search (supports Chinese titles + high-res posters + descriptions)
func scrapeITunesMovie(title string) *scrapedMeta {
	u := "https://itunes.apple.com/search?entity=movie&country=cn&lang=zh_cn&limit=3&term=" + url.QueryEscape(title)
	client := &http.Client{Timeout: 8 * time.Second}
	resp, err := client.Get(u)
	if err != nil || resp.StatusCode != 200 {
		if resp != nil {
			resp.Body.Close()
		}
		return nil
	}
	defer resp.Body.Close()
	var parsed struct {
		Results []struct {
			TrackName       string `json:"trackName"`
			ArtworkUrl100   string `json:"artworkUrl100"`
			ReleaseDate     string `json:"releaseDate"`
			LongDescription string `json:"longDescription"`
		} `json:"results"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&parsed); err != nil || len(parsed.Results) == 0 {
		return nil
	}
	r := parsed.Results[0]
	yr := 0
	if len(r.ReleaseDate) >= 4 {
		yr, _ = strconv.Atoi(r.ReleaseDate[:4])
	}
	poster := strings.Replace(r.ArtworkUrl100, "100x100bb", "600x600bb", 1)
	return &scrapedMeta{
		Title:     r.TrackName,
		Overview:  r.LongDescription,
		Year:      yr,
		PosterURL: poster,
	}
}

// Keyless public fallback 2: TVMaze public API for TV shows
func scrapeTVMaze(title string) *scrapedMeta {
	u := "https://api.tvmaze.com/search/shows?q=" + url.QueryEscape(title)
	client := &http.Client{Timeout: 8 * time.Second}
	resp, err := client.Get(u)
	if err != nil || resp.StatusCode != 200 {
		if resp != nil {
			resp.Body.Close()
		}
		return nil
	}
	defer resp.Body.Close()
	var parsed []struct {
		Show struct {
			Name      string `json:"name"`
			Premiered string `json:"premiered"`
			Summary   string `json:"summary"`
			Rating    struct {
				Average float64 `json:"average"`
			} `json:"rating"`
			Image struct {
				Medium   string `json:"medium"`
				Original string `json:"original"`
			} `json:"image"`
		} `json:"show"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&parsed); err != nil || len(parsed) == 0 {
		return nil
	}
	sh := parsed[0].Show
	yr := 0
	if len(sh.Premiered) >= 4 {
		yr, _ = strconv.Atoi(sh.Premiered[:4])
	}
	summary := regexp.MustCompile(`<[^>]+>`).ReplaceAllString(sh.Summary, "")
	poster := sh.Image.Medium
	if poster == "" {
		poster = sh.Image.Original
	}
	return &scrapedMeta{
		Title:     sh.Name,
		Overview:  strings.TrimSpace(summary),
		Year:      yr,
		Score:     sh.Rating.Average,
		PosterURL: poster,
	}
}

func downloadPosterToCache(posterURL, destPath string) bool {
	if posterURL == "" {
		return false
	}
	client := &http.Client{Timeout: 12 * time.Second}
	resp, err := client.Get(posterURL)
	if err != nil || resp.StatusCode != 200 {
		if resp != nil {
			resp.Body.Close()
		}
		return false
	}
	defer resp.Body.Close()
	tmp := destPath + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return false
	}
	if _, err := io.Copy(f, io.LimitReader(resp.Body, 8*1024*1024)); err != nil {
		f.Close()
		_ = os.Remove(tmp)
		return false
	}
	f.Close()
	return os.Rename(tmp, destPath) == nil
}

func ScrapeLibrary(s *Store, thumbDir, libID string, force bool) int {
	lib, ok := s.GetLibrary(libID)
	if !ok || lib.Mode == ModeFolder {
		return 0
	}
	cfg := s.GetSettings()
	_ = os.MkdirAll(thumbDir, 0755)

	s.mu.RLock()
	var targets []*MediaItem
	for _, it := range s.data.Items {
		if it.LibID != libID {
			continue
		}
		if it.Type != "Movie" && it.Type != "TV" {
			continue
		}
		if !force && it.Overview != "" && it.PosterPath != "" {
			continue
		}
		cp := *it
		targets = append(targets, &cp)
	}
	s.mu.RUnlock()

	updated := 0
	for _, it := range targets {
		qTitle, qYear := cleanQueryTitle(it.Title)
		if qTitle == "" {
			continue
		}
		isTV := it.Type == "TV"
		var meta *scrapedMeta
		if cfg.TMDBApiKey != "" {
			meta = scrapeTMDB(qTitle, qYear, isTV, cfg.TMDBApiKey, cfg.TMDBMirror)
		}
		if meta == nil {
			if isTV {
				meta = scrapeTVMaze(qTitle)
			} else {
				meta = scrapeITunesMovie(qTitle)
			}
		}
		if meta == nil {
			continue
		}

		posterLocal := it.PosterPath
		if (force || posterLocal == "") && meta.PosterURL != "" {
			dest := filepath.Join(thumbDir, it.Guid+"_poster.jpg")
			if downloadPosterToCache(meta.PosterURL, dest) {
				posterLocal = dest
			}
		}

		s.mu.Lock()
		if cur, exists := s.data.Items[it.Guid]; exists {
			if meta.Title != "" {
				cur.Title = meta.Title
			}
			if meta.Overview != "" {
				cur.Overview = meta.Overview
			}
			if meta.Year > 0 {
				cur.Year = meta.Year
			}
			if meta.Score > 0 {
				cur.Score = meta.Score
			}
			if posterLocal != "" {
				cur.PosterPath = posterLocal
			}
			updated++
		}
		s.mu.Unlock()
		time.Sleep(120 * time.Millisecond) // polite rate limit
	}

	if updated > 0 {
		_ = s.Save()
		log.Printf("[Scraper] Library %s scraped %d items", lib.Name, updated)
	}
	return updated
}
