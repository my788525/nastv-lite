package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

type QualityProfile struct {
	Key       string `json:"key"`
	Label     string `json:"label"`
	Height    int    `json:"height"`
	Bitrate   int64  `json:"bitrate"` // bps
	AudioOnly bool   `json:"audio_only"`
}

var QualityProfiles = []QualityProfile{
	{Key: "original", Label: "原画 (Direct Play)", Height: 0, Bitrate: 0},
	{Key: "1080p", Label: "1080P 高清 (8M)", Height: 1080, Bitrate: 8000000},
	{Key: "720p", Label: "720P 标清 (4M)", Height: 720, Bitrate: 4000000},
	{Key: "480p", Label: "480P 流畅 (1.5M)", Height: 480, Bitrate: 1500000},
	{Key: "360p", Label: "360P 极速省流 (800K)", Height: 360, Bitrate: 800000},
	{Key: "audio", Label: "纯音频极速 (128K)", Height: 0, Bitrate: 128000, AudioOnly: true},
	{Key: "audio_96", Label: "音乐省流 (96K)", Height: 0, Bitrate: 96000, AudioOnly: true},
	{Key: "audio_128", Label: "音乐标准 (128K)", Height: 0, Bitrate: 128000, AudioOnly: true},
	{Key: "audio_192", Label: "音乐均衡 (192K)", Height: 0, Bitrate: 192000, AudioOnly: true},
	{Key: "audio_320", Label: "音乐高音质 (320K)", Height: 0, Bitrate: 320000, AudioOnly: true},
}

func FindQualityProfile(key string) *QualityProfile {
	for i := range QualityProfiles {
		if QualityProfiles[i].Key == key {
			return &QualityProfiles[i]
		}
	}
	return nil
}

type HLSSession struct {
	Key        string
	Dir        string
	Cmd        *exec.Cmd
	LastAccess time.Time
}

type Transcoder struct {
	ffmpegPath  string
	ffprobePath string
	hasVAAPI    bool
	thumbDir    string
	hlsRoot     string
	mu          sync.Mutex
	sessions    map[string]*HLSSession
}

func NewTranscoder(dataDir string) *Transcoder {
	t := &Transcoder{
		ffmpegPath:  findBinary("ffmpeg", []string{"/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/trim/bin/ffmpeg"}),
		ffprobePath: findBinary("ffprobe", []string{"/usr/bin/ffprobe", "/usr/local/bin/ffprobe", "/usr/trim/bin/ffprobe"}),
		thumbDir:    filepath.Join(dataDir, "thumbs"),
		hlsRoot:     filepath.Join(os.TempDir(), "nastv_hls"),
		sessions:    make(map[string]*HLSSession),
	}
	_ = os.MkdirAll(t.thumbDir, 0755)
	_ = os.MkdirAll(t.hlsRoot, 0755)
	if _, err := os.Stat("/dev/dri/renderD128"); err == nil {
		t.hasVAAPI = true
	}
	go t.cleanupLoop()
	return t
}

func findBinary(name string, candidates []string) string {
	if p, err := exec.LookPath(name); err == nil {
		return p
	}
	for _, c := range candidates {
		if _, err := os.Stat(c); err == nil {
			return c
		}
	}
	return ""
}

func (t *Transcoder) cleanupLoop() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		t.mu.Lock()
		now := time.Now()
		for k, sess := range t.sessions {
			if now.Sub(sess.LastAccess) > 3*time.Minute {
				if sess.Cmd != nil && sess.Cmd.Process != nil {
					_ = sess.Cmd.Process.Kill()
				}
				_ = os.RemoveAll(sess.Dir)
				delete(t.sessions, k)
			}
		}
		t.mu.Unlock()
	}
}

type ffprobeOut struct {
	Format struct {
		Duration string `json:"duration"`
		BitRate  string `json:"bit_rate"`
	} `json:"format"`
	Streams []struct {
		CodecType string `json:"codec_type"`
		CodecName string `json:"codec_name"`
		Width     int    `json:"width"`
		Height    int    `json:"height"`
	} `json:"streams"`
}

func (t *Transcoder) ProbeItem(s *Store, item *MediaItem) {
	if t.ffprobePath == "" || item.FilePath == "" || item.Type == "Directory" || item.Type == "TV" {
		return
	}
	if item.Duration > 0 && item.VCodec != "" {
		return
	}
	cmd := exec.Command(t.ffprobePath, "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", item.FilePath)
	out, err := cmd.Output()
	if err != nil {
		return
	}
	var parsed ffprobeOut
	if err := json.Unmarshal(out, &parsed); err != nil {
		return
	}
	if d, err := strconv.ParseFloat(parsed.Format.Duration, 64); err == nil {
		item.Duration = int64(d)
	}
	if b, err := strconv.ParseInt(parsed.Format.BitRate, 10, 64); err == nil {
		item.Bitrate = b
	}
	for _, st := range parsed.Streams {
		if st.CodecType == "video" && item.VCodec == "" {
			item.VCodec = st.CodecName
			item.Width = st.Width
			item.Height = st.Height
		} else if st.CodecType == "audio" && item.ACodec == "" {
			item.ACodec = st.CodecName
		}
	}
	s.mu.Lock()
	if cur, ok := s.data.Items[item.Guid]; ok {
		cur.Duration = item.Duration
		cur.Bitrate = item.Bitrate
		cur.VCodec = item.VCodec
		cur.ACodec = item.ACodec
		cur.Width = item.Width
		cur.Height = item.Height
		_ = s.saveLocked()
	}
	s.mu.Unlock()
}

func (t *Transcoder) EnsureThumbnail(item *MediaItem, enabled bool) string {
	if item.PosterPath != "" {
		if _, err := os.Stat(item.PosterPath); err == nil {
			return item.PosterPath
		}
	}
	if item.Type == "Audio" {
		audThumb := filepath.Join(t.thumbDir, "aud_"+item.Guid+".jpg")
		if _, err := os.Stat(audThumb); err == nil {
			return audThumb
		}
		noMarker := filepath.Join(t.thumbDir, "aud_"+item.Guid+".none")
		if _, err := os.Stat(noMarker); err == nil {
			return ""
		}
		if t.ffmpegPath != "" && item.FilePath != "" {
			cmd := exec.Command(t.ffmpegPath, "-y", "-v", "quiet", "-i", item.FilePath, "-an", "-vcodec", "mjpeg", "-frames:v", "1", audThumb)
			if err := cmd.Run(); err == nil {
				if st, err2 := os.Stat(audThumb); err2 == nil && st.Size() > 0 {
					return audThumb
				}
			}
		}
		// Fallback: public iTunes album cover art on NAS server
		qTitle, qArtist := parseTrackTitleArtist(item.Title, item.RelPath)
		if item.Artist != "" {
			qArtist = item.Artist
		}
		_, _, _, artURL := scrapeITunesMusic(qTitle, qArtist)
		if artURL != "" && downloadPosterToCache(artURL, audThumb) {
			return audThumb
		}
		_ = os.WriteFile(noMarker, []byte("0"), 0644)
		return ""
	}
	if !enabled || t.ffmpegPath == "" || item.FilePath == "" || item.Type == "Directory" || item.Type == "TV" {
		return ""
	}
	thumbPath := filepath.Join(t.thumbDir, item.Guid+".jpg")
	if _, err := os.Stat(thumbPath); err == nil {
		return thumbPath
	}
	// Seek to 10s (or 0s for short clips) and grab 1 frame scaled to 480px wide
	seek := "00:00:10"
	if item.Duration > 0 && item.Duration < 15 {
		seek = "00:00:01"
	}
	cmd := exec.Command(t.ffmpegPath, "-y", "-ss", seek, "-i", item.FilePath, "-vframes", "1", "-vf", "scale=480:-1", "-q:v", "5", thumbPath)
	if err := cmd.Run(); err == nil {
		if _, err2 := os.Stat(thumbPath); err2 == nil {
			return thumbPath
		}
	}
	return ""
}

func (t *Transcoder) EnsureHLS(item *MediaItem, qualityKey string, cfg ServerSettings) (string, error) {
	if t.ffmpegPath == "" {
		return "", fmt.Errorf("ffmpeg 未安装")
	}
	prof := FindQualityProfile(qualityKey)
	if prof == nil || prof.Key == "original" {
		return "", fmt.Errorf("invalid transcode quality")
	}
	segSec := cfg.HLSSegmentSec
	if segSec <= 0 {
		segSec = 2
	}
	gop := segSec * 24
	preset := cfg.TranscodePreset
	if preset == "" {
		preset = "veryfast"
	}
	sessKey := item.Guid + "_" + qualityKey

	t.mu.Lock()
	if sess, ok := t.sessions[sessKey]; ok {
		sess.LastAccess = time.Now()
		dir := sess.Dir
		t.mu.Unlock()
		return dir, nil
	}
	sessDir := filepath.Join(t.hlsRoot, sessKey)
	_ = os.RemoveAll(sessDir)
	if err := os.MkdirAll(sessDir, 0755); err != nil {
		t.mu.Unlock()
		return "", err
	}

	m3u8Path := filepath.Join(sessDir, "index.m3u8")
	segPattern := filepath.Join(sessDir, "seg_%05d.ts")

	var args []string
	if prof.AudioOnly {
		kbps := prof.Bitrate / 1000
		if kbps <= 0 {
			kbps = 128
		}
		args = []string{
			"-y", "-i", item.FilePath,
			"-vn", "-c:a", "aac", "-b:a", fmt.Sprintf("%dk", kbps), "-ac", "2",
			"-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
			"-hls_playlist_type", "event", "-hls_flags", "independent_segments",
			"-hls_segment_filename", segPattern, m3u8Path,
		}
	} else if t.hasVAAPI && cfg.EnableVAAPI {
		// Intel/AMD VAAPI hardware transcode with strict GOP & independent HLS segments
		scaleFilter := fmt.Sprintf("format=nv12,hwupload,scale_vaapi=w=-2:h=%d", prof.Height)
		args = []string{
			"-y", "-vaapi_device", "/dev/dri/renderD128",
			"-i", item.FilePath,
			"-vf", scaleFilter,
			"-c:v", "h264_vaapi", "-b:v", fmt.Sprintf("%d", prof.Bitrate),
			"-g", strconv.Itoa(gop), "-keyint_min", strconv.Itoa(gop), "-sc_threshold", "0",
			"-c:a", "aac", "-b:a", "192k", "-ac", "2",
			"-f", "hls", "-hls_time", strconv.Itoa(segSec), "-hls_list_size", "0",
			"-hls_playlist_type", "event", "-hls_flags", "independent_segments",
			"-hls_segment_filename", segPattern, m3u8Path,
		}
	} else {
		// Software fallback libx264 with strict GOP & independent HLS segments
		scaleFilter := fmt.Sprintf("scale=-2:%d", prof.Height)
		args = []string{
			"-y", "-i", item.FilePath,
			"-vf", scaleFilter,
			"-c:v", "libx264", "-preset", preset, "-b:v", fmt.Sprintf("%d", prof.Bitrate),
			"-maxrate", fmt.Sprintf("%d", prof.Bitrate*12/10),
			"-bufsize", fmt.Sprintf("%d", prof.Bitrate*2),
			"-g", strconv.Itoa(gop), "-keyint_min", strconv.Itoa(gop), "-sc_threshold", "0",
			"-c:a", "aac", "-b:a", "192k", "-ac", "2",
			"-f", "hls", "-hls_time", strconv.Itoa(segSec), "-hls_list_size", "0",
			"-hls_playlist_type", "event", "-hls_flags", "independent_segments",
			"-hls_segment_filename", segPattern, m3u8Path,
		}
	}

	cmd := exec.Command(t.ffmpegPath, args...)
	if err := cmd.Start(); err != nil {
		t.mu.Unlock()
		return "", err
	}
	t.sessions[sessKey] = &HLSSession{
		Key:        sessKey,
		Dir:        sessDir,
		Cmd:        cmd,
		LastAccess: time.Now(),
	}
	go func() {
		if err := cmd.Wait(); err != nil {
			log.Printf("[HLS] %s ended: %v", sessKey, err)
		}
	}()
	t.mu.Unlock()

	// Wait up to 8 seconds for index.m3u8 and first segment seg_00000.ts to be written
	firstSeg := filepath.Join(sessDir, "seg_00000.ts")
	for i := 0; i < 40; i++ {
		stM, errM := os.Stat(m3u8Path)
		stS, errS := os.Stat(firstSeg)
		if errM == nil && stM.Size() > 20 && errS == nil && stS.Size() > 0 {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	return sessDir, nil
}

func (t *Transcoder) GetCacheStats() (activeSessions int, thumbCount int, cacheBytes int64) {
	t.mu.Lock()
	activeSessions = len(t.sessions)
	t.mu.Unlock()
	_ = filepath.WalkDir(t.thumbDir, func(path string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			thumbCount++
			if info, e := d.Info(); e == nil {
				cacheBytes += info.Size()
			}
		}
		return nil
	})
	_ = filepath.WalkDir(t.hlsRoot, func(path string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if info, e := d.Info(); e == nil {
				cacheBytes += info.Size()
			}
		}
		return nil
	})
	return
}

func (t *Transcoder) ClearCaches() {
	t.mu.Lock()
	for k, sess := range t.sessions {
		if sess.Cmd != nil && sess.Cmd.Process != nil {
			_ = sess.Cmd.Process.Kill()
		}
		_ = os.RemoveAll(sess.Dir)
		delete(t.sessions, k)
	}
	t.mu.Unlock()
	_ = os.RemoveAll(t.thumbDir)
	_ = os.MkdirAll(t.thumbDir, 0755)
}
