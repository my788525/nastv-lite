package main

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/xml"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var videoExts = map[string]bool{
	".mp4": true, ".mkv": true, ".avi": true, ".mov": true, ".wmv": true,
	".flv": true, ".webm": true, ".m4v": true, ".ts": true, ".m2ts": true,
	".mpg": true, ".mpeg": true, ".iso": true, ".rmvb": true, ".3gp": true,
}

var audioExts = map[string]bool{
	".mp3": true, ".flac": true, ".wav": true, ".aac": true, ".m4a": true,
	".ogg": true, ".opus": true, ".wma": true, ".ape": true, ".dsf": true,
}

var subExts = map[string]bool{
	".srt": true, ".ass": true, ".ssa": true, ".vtt": true,
}

var posterNames = []string{"poster.jpg", "poster.png", "folder.jpg", "folder.png", "cover.jpg", "cover.png"}

var (
	reSeasonEp = regexp.MustCompile(`(?i)[Ss](\d{1,2})[Ee](\d{1,4})`)
	reEpOnly   = regexp.MustCompile(`(?i)(?:^|[\s._\-\[])(?:EP?|第)\s*(\d{1,4})\s*(?:集|话|話)?(?:$|[\s._\-\]])`)
	rePureNum  = regexp.MustCompile(`^\s*(\d{1,3})\s*$`)
	reYear     = regexp.MustCompile(`\((\d{4})\)|\.(\d{4})\.`)
	reTechTags = regexp.MustCompile(`(?i)\b(2160p|1080p|1080i|720p|480p|4k|8k|hdr|hdr10|dv|dolby|vision|atmos|truehd|dts|dtshd|ac3|aac|flac|6ch|8ch|2ch|x264|x265|h264|h265|hevc|avc|10bit|8bit|bluray|blu-ray|bdrip|bd|remux|web-dl|webdl|webrip|web|amazon|amzn|netflix|nf|hdtv|hdrip|dvdrip|mp4|mkv|avi|ts|chs|cht|eng|sub|mp3)\b`)
	reCnTags   = regexp.MustCompile(`(国语|粤语|双语|三语|中字|中英|特效|简繁|简体|繁体|字幕|未删减|加长版|高码率|蓝光|原盘|1080|2160|720)`)
)

func isGenericMediaFilename(base string) bool {
	s := reYear.ReplaceAllString(base, " ")
	s = reTechTags.ReplaceAllString(s, " ")
	s = reCnTags.ReplaceAllString(s, " ")
	s = regexp.MustCompile(`[\s._\-\[\]()（）【】0-9]+`).ReplaceAllString(s, "")
	return len([]rune(s)) <= 1
}

func resolveMovieFolderTitle(dir, rootPath string) string {
	cur := dir
	for cur != rootPath && cur != "/" && cur != "." {
		b := filepath.Base(cur)
		up := strings.ToUpper(b)
		if up == "BDMV" || up == "STREAM" || up == "VIDEO_TS" || isGenericMediaFilename(b) {
			cur = filepath.Dir(cur)
			continue
		}
		return b
	}
	return filepath.Base(dir)
}

func deterministicGuid(prefix, key string) string {
	sum := sha1.Sum([]byte(key))
	return prefix + hex.EncodeToString(sum[:8])
}

type nfoMeta struct {
	Title    string  `xml:"title"`
	Plot     string  `xml:"plot"`
	Year     int     `xml:"year"`
	Rating   float64 `xml:"rating"`
	Overview string  `xml:"outline"`
}

func readNFO(nfoPath string) *nfoMeta {
	raw, err := os.ReadFile(nfoPath)
	if err != nil {
		return nil
	}
	var m nfoMeta
	if err := xml.Unmarshal(raw, &m); err != nil {
		return nil
	}
	if m.Plot == "" && m.Overview != "" {
		m.Plot = m.Overview
	}
	return &m
}

func findPosterForFile(dir, baseNoExt string) string {
	for _, ext := range []string{".jpg", ".jpeg", ".png", ".webp"} {
		p := filepath.Join(dir, baseNoExt+ext)
		if _, err := os.Stat(p); err == nil {
			return p
		}
		p2 := filepath.Join(dir, baseNoExt+"-poster"+ext)
		if _, err := os.Stat(p2); err == nil {
			return p2
		}
	}
	return ""
}

func findPosterInDir(dir string) string {
	for _, name := range posterNames {
		p := filepath.Join(dir, name)
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

func findSubtitles(dir, baseNoExt string) []SubtitleInfo {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var subs []SubtitleInfo
	lowerBase := strings.ToLower(baseNoExt)
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		ext := strings.ToLower(filepath.Ext(name))
		if !subExts[ext] {
			continue
		}
		nameNoExt := strings.TrimSuffix(name, filepath.Ext(name))
		if strings.HasPrefix(strings.ToLower(nameNoExt), lowerBase) {
			fullPath := filepath.Join(dir, name)
			lang := "und"
			suffix := strings.TrimPrefix(strings.ToLower(nameNoExt), lowerBase)
			if strings.Contains(suffix, "zh") || strings.Contains(suffix, "chs") || strings.Contains(suffix, "sc") || strings.Contains(suffix, "简") {
				lang = "chi"
			} else if strings.Contains(suffix, "cht") || strings.Contains(suffix, "tc") || strings.Contains(suffix, "繁") {
				lang = "cht"
			} else if strings.Contains(suffix, "en") {
				lang = "eng"
			} else if strings.Contains(suffix, "jp") || strings.Contains(suffix, "ja") {
				lang = "jpn"
			}
			subs = append(subs, SubtitleInfo{
				ID:       deterministicGuid("sub_", fullPath),
				Title:    name,
				Language: lang,
				Format:   strings.TrimPrefix(ext, "."),
				FilePath: fullPath,
			})
		}
	}
	return subs
}

func ScanLibrary(s *Store, libID string) (int, error) {
	lib, ok := s.GetLibrary(libID)
	if !ok {
		return 0, os.ErrNotExist
	}

	folderItems := make(map[string]*MediaItem)
	movieItems := make(map[string]*MediaItem)
	for _, rootPath := range lib.Paths {
		scanFolderMode(lib, rootPath, rootPath, lib.ID, folderItems)
		scanMovieTVMode(lib, rootPath, movieItems)
	}
	newItems := make(map[string]*MediaItem, len(folderItems)+len(movieItems))
	defaultCount := 0
	for k, v := range folderItems {
		v.ViewMode = ModeFolder
		newItems[k] = v
		if lib.Mode == ModeFolder {
			defaultCount++
		}
	}
	for k, v := range movieItems {
		v.ViewMode = ModeMovieTV
		newItems[k] = v
		if lib.Mode != ModeFolder {
			defaultCount++
		}
	}

	// Preserve cached probe metadata (duration/codec) for unchanged files
	s.mu.Lock()
	for guid, item := range newItems {
		if old, exists := s.data.Items[guid]; exists && old.FileSize == item.FileSize && old.ModTime == item.ModTime {
			if item.Duration == 0 && old.Duration > 0 {
				item.Duration = old.Duration
				item.Width = old.Width
				item.Height = old.Height
				item.VCodec = old.VCodec
				item.ACodec = old.ACodec
				item.Bitrate = old.Bitrate
			}
		}
	}
	// Remove old items belonging to this library
	for guid, item := range s.data.Items {
		if item.LibID == libID {
			delete(s.data.Items, guid)
		}
	}
	for guid, item := range newItems {
		s.data.Items[guid] = item
	}
	if l, exists := s.data.Libraries[libID]; exists {
		l.LastScan = time.Now().Unix()
		l.ItemCount = defaultCount
	}
	_ = s.saveLocked()
	s.mu.Unlock()

	return len(newItems), nil
}

func scanFolderMode(lib *Library, rootPath, currentDir, parentGuid string, out map[string]*MediaItem) {
	entries, err := os.ReadDir(currentDir)
	if err != nil {
		return
	}
	for _, entry := range entries {
		name := entry.Name()
		if strings.HasPrefix(name, ".") || name == "@eaDir" || name == "#recycle" {
			continue
		}
		fullPath := filepath.Join(currentDir, name)
		info, err := entry.Info()
		if err != nil {
			continue
		}
		rel, _ := filepath.Rel(rootPath, fullPath)
		if entry.IsDir() {
			dirGuid := deterministicGuid("fv_", lib.ID+":"+fullPath)
			poster := findPosterInDir(fullPath)
			out[dirGuid] = &MediaItem{
				Guid:       dirGuid,
				LibID:      lib.ID,
				ParentGuid: parentGuid,
				Title:      name,
				Type:       "Directory",
				Category:   "Folder",
				FilePath:   fullPath,
				RelPath:    rel,
				PosterPath: poster,
				ModTime:    info.ModTime().Unix(),
			}
			scanFolderMode(lib, rootPath, fullPath, dirGuid, out)
		} else {
			ext := strings.ToLower(filepath.Ext(name))
			isVid := videoExts[ext]
			isAud := audioExts[ext]
			if !isVid && !isAud {
				continue
			}
			baseNoExt := strings.TrimSuffix(name, filepath.Ext(name))
			itemGuid := deterministicGuid("it_", lib.ID+":"+fullPath)
			typ := "Video"
			cat := "Video"
			if isAud {
				typ = "Audio"
				cat = "Music"
			}
			poster := findPosterForFile(currentDir, baseNoExt)
			if poster == "" {
				poster = findPosterInDir(currentDir)
			}
			var subs []SubtitleInfo
			if isVid {
				subs = findSubtitles(currentDir, baseNoExt)
			}
			out[itemGuid] = &MediaItem{
				Guid:       itemGuid,
				LibID:      lib.ID,
				ParentGuid: parentGuid,
				Title:      baseNoExt,
				Type:       typ,
				Category:   cat,
				FilePath:   fullPath,
				RelPath:    rel,
				PosterPath: poster,
				FileSize:   info.Size(),
				ModTime:    info.ModTime().Unix(),
				Subtitles:  subs,
			}
		}
	}
}

func parseSeasonEpisode(filename string, inSubDir bool) (int, int, bool) {
	if m := reSeasonEp.FindStringSubmatch(filename); len(m) == 3 {
		s, _ := strconv.Atoi(m[1])
		e, _ := strconv.Atoi(m[2])
		return s, e, true
	}
	if m := reEpOnly.FindStringSubmatch(filename); len(m) == 2 {
		e, _ := strconv.Atoi(m[1])
		return 1, e, true
	}
	if inSubDir {
		if m := rePureNum.FindStringSubmatch(filename); len(m) == 2 {
			e, _ := strconv.Atoi(m[1])
			if e > 0 && e < 1000 {
				return 1, e, true
			}
		}
	}
	return 0, 0, false
}

func scanMovieTVMode(lib *Library, rootPath string, out map[string]*MediaItem) {
	// Walk directories; if a directory contains episode-named files or multiple videos and tvshow.nfo -> TV series
	// Otherwise treat video files as Movies
	tvShows := make(map[string]*MediaItem) // dirPath -> TV item

	_ = filepath.WalkDir(rootPath, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		name := d.Name()
		if strings.HasPrefix(name, ".") || name == "@eaDir" || name == "#recycle" {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if d.IsDir() {
			return nil
		}
		ext := strings.ToLower(filepath.Ext(name))
		if !videoExts[ext] {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		dir := filepath.Dir(path)
		baseNoExt := strings.TrimSuffix(name, filepath.Ext(name))
		rel, _ := filepath.Rel(rootPath, path)

		season, ep, isEp := parseSeasonEpisode(baseNoExt, dir != rootPath)
		// Also check if parent folder is named "Season X" or "S01"
		parentDirName := filepath.Base(dir)
		showDir := dir
		if strings.HasPrefix(strings.ToLower(parentDirName), "season") || regexp.MustCompile(`(?i)^s\d{1,2}$`).MatchString(parentDirName) {
			showDir = filepath.Dir(dir)
			isEp = true
			if season == 0 {
				digits := regexp.MustCompile(`\d+`).FindString(parentDirName)
				if n, err := strconv.Atoi(digits); err == nil {
					season = n
				} else {
					season = 1
				}
			}
		}

		if isEp && showDir != rootPath {
			tvItem, exists := tvShows[showDir]
			if !exists {
				tvGuid := deterministicGuid("tv_", lib.ID+":"+showDir)
				tvTitle := filepath.Base(showDir)
				poster := findPosterInDir(showDir)
				nfo := readNFO(filepath.Join(showDir, "tvshow.nfo"))
				overview := ""
				year := 0
				var score float64
				if nfo != nil {
					if nfo.Title != "" {
						tvTitle = nfo.Title
					}
					overview = nfo.Plot
					year = nfo.Year
					score = nfo.Rating
				}
				tvItem = &MediaItem{
					Guid:       tvGuid,
					LibID:      lib.ID,
					ParentGuid: lib.ID,
					Title:      tvTitle,
					Type:       "TV",
					Category:   "TV",
					FilePath:   showDir,
					PosterPath: poster,
					Overview:   overview,
					Year:       year,
					Score:      score,
					ModTime:    info.ModTime().Unix(),
				}
				tvShows[showDir] = tvItem
				out[tvGuid] = tvItem
			}
			epGuid := deterministicGuid("ep_", lib.ID+":"+path)
			nfo := readNFO(filepath.Join(dir, baseNoExt+".nfo"))
			epTitle := baseNoExt
			overview := ""
			if nfo != nil && nfo.Title != "" {
				epTitle = nfo.Title
				overview = nfo.Plot
			}
			poster := findPosterForFile(dir, baseNoExt)
			if poster == "" {
				poster = tvItem.PosterPath
			}
			out[epGuid] = &MediaItem{
				Guid:          epGuid,
				LibID:         lib.ID,
				ParentGuid:    tvItem.Guid,
				Title:         epTitle,
				TvTitle:       tvItem.Title,
				Type:          "Episode",
				Category:      "TV",
				FilePath:      path,
				RelPath:       rel,
				PosterPath:    poster,
				Overview:      overview,
				SeasonNumber:  season,
				EpisodeNumber: ep,
				FileSize:      info.Size(),
				ModTime:       info.ModTime().Unix(),
				Subtitles:     findSubtitles(dir, baseNoExt),
			}
		} else {
			// Standalone Movie
			movGuid := deterministicGuid("mv_", lib.ID+":"+path)
			title := baseNoExt
			if dir != rootPath && isGenericMediaFilename(baseNoExt) {
				folderTitle := resolveMovieFolderTitle(dir, rootPath)
				if folderTitle != "" && folderTitle != filepath.Base(rootPath) {
					title = folderTitle
				}
			}
			overview := ""
			year := 0
			var score float64
			if m := reYear.FindStringSubmatch(baseNoExt); len(m) >= 3 {
				ys := m[1]
				if ys == "" {
					ys = m[2]
				}
				year, _ = strconv.Atoi(ys)
			} else if m := reYear.FindStringSubmatch(title); len(m) >= 3 {
				ys := m[1]
				if ys == "" {
					ys = m[2]
				}
				year, _ = strconv.Atoi(ys)
			}
			nfo := readNFO(filepath.Join(dir, baseNoExt+".nfo"))
			if nfo == nil {
				nfo = readNFO(filepath.Join(dir, "movie.nfo"))
			}
			if nfo != nil {
				if nfo.Title != "" {
					title = nfo.Title
				}
				overview = nfo.Plot
				if nfo.Year > 0 {
					year = nfo.Year
				}
				score = nfo.Rating
			}
			poster := findPosterForFile(dir, baseNoExt)
			if poster == "" && dir != rootPath {
				poster = findPosterInDir(dir)
			}
			out[movGuid] = &MediaItem{
				Guid:       movGuid,
				LibID:      lib.ID,
				ParentGuid: lib.ID,
				Title:      title,
				Type:       "Movie",
				Category:   "Movie",
				FilePath:   path,
				RelPath:    rel,
				PosterPath: poster,
				Overview:   overview,
				Year:       year,
				Score:      score,
				FileSize:   info.Size(),
				ModTime:    info.ModTime().Unix(),
				Subtitles:  findSubtitles(dir, baseNoExt),
			}
		}
		return nil
	})
}
