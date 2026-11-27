package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
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
	{Key: "auto", Label: "智能自适应 (ABR 720P~360P)", Height: 720, Bitrate: 1600000},
	{Key: "1080p", Label: "1080P 高清 (4M)", Height: 1080, Bitrate: 4000000},
	{Key: "720p", Label: "720P 省流 (1.6M)", Height: 720, Bitrate: 1600000},
	{Key: "480p", Label: "480P 流畅 (900K)", Height: 480, Bitrate: 900000},
	{Key: "360p", Label: "360P 极速 (500K)", Height: 360, Bitrate: 500000},
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
	Key          string
	Dir          string
	Cmd          *exec.Cmd
	LastAccess   time.Time
	AudioPersist bool
}

type Transcoder struct {
	ffmpegPath     string
	ffprobePath    string
	hasVAAPI       bool
	hasVAAPIHEVC   bool
	thumbDir       string
	audioCacheDir  string
	hlsRoot        string
	mu             sync.Mutex
	sessions       map[string]*HLSSession
	lastVideoAt    time.Time
	prewarmMu      sync.Mutex
	prewarmRunning bool
}

func probeVAAPIEncoder(ffmpegPath, encoder string) bool {
	if ffmpegPath == "" {
		return false
	}
	cmd := exec.Command(ffmpegPath,
		"-y", "-v", "quiet",
		"-vaapi_device", "/dev/dri/renderD128",
		"-f", "lavfi", "-i", "color=c=black:s=640x360:d=0.1",
		"-vf", "format=nv12,hwupload",
		"-c:v", encoder, "-f", "null", "-",
	)
	return cmd.Run() == nil
}

func chooseHLSRoot() string {
	if runtime.GOOS == "linux" {
		shm := "/dev/shm"
		if st, err := os.Stat(shm); err == nil && st.IsDir() {
			p := filepath.Join(shm, "nastv_hls")
			if err := os.MkdirAll(p, 0755); err == nil {
				// 清理上次遗留的临时转码目录，释放内存盘空间
				_ = os.RemoveAll(p)
				_ = os.MkdirAll(p, 0755)
				log.Printf("[Transcoder] 使用内存盘 (tmpfs) 存放实时视频 HLS 切片: %s", p)
				return p
			}
		}
	}
	return filepath.Join(os.TempDir(), "nastv_hls")
}

func NewTranscoder(dataDir string) *Transcoder {
	t := &Transcoder{
		ffmpegPath:    findBinary("ffmpeg", []string{"/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/trim/bin/ffmpeg"}),
		ffprobePath:   findBinary("ffprobe", []string{"/usr/bin/ffprobe", "/usr/local/bin/ffprobe", "/usr/trim/bin/ffprobe"}),
		thumbDir:      filepath.Join(dataDir, "thumbs"),
		audioCacheDir: filepath.Join(dataDir, "audio_hls_cache"),
		hlsRoot:       chooseHLSRoot(),
		sessions:      make(map[string]*HLSSession),
	}
	_ = os.MkdirAll(t.thumbDir, 0755)
	_ = os.MkdirAll(t.audioCacheDir, 0755)
	_ = os.MkdirAll(t.hlsRoot, 0755)
	if _, err := os.Stat("/dev/dri/renderD128"); err == nil {
		t.hasVAAPI = probeVAAPIEncoder(t.ffmpegPath, "h264_vaapi")
		if t.hasVAAPI {
			t.hasVAAPIHEVC = probeVAAPIEncoder(t.ffmpegPath, "hevc_vaapi")
		}
		log.Printf("[Transcoder] VAAPI probe: h264_vaapi=%v, hevc_vaapi=%v", t.hasVAAPI, t.hasVAAPIHEVC)
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
			ttl := 3 * time.Minute
			if sess.AudioPersist {
				ttl = 10 * time.Minute
			}
			if now.Sub(sess.LastAccess) > ttl {
				if sess.Cmd != nil && sess.Cmd.Process != nil {
					_ = sess.Cmd.Process.Kill()
				}
				if !sess.AudioPersist {
					_ = os.RemoveAll(sess.Dir)
				}
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

type HLSOptions struct {
	Codec      string // "", "h264", "hevc"
	Container  string // "", "ts", "fmp4"
	SegmentSec int    // 0 (use cfg), 2, 4, 6
	HWDecode   string // "", "1", "0"
}

func (t *Transcoder) EnsureHLS(item *MediaItem, qualityKey string, cfg ServerSettings, opts HLSOptions) (string, error) {
	if t.ffmpegPath == "" {
		return "", fmt.Errorf("ffmpeg 未安装")
	}
	prof := FindQualityProfile(qualityKey)
	if prof == nil || prof.Key == "original" {
		return "", fmt.Errorf("invalid transcode quality")
	}
	segSec := opts.SegmentSec
	if segSec <= 0 {
		segSec = cfg.HLSSegmentSec
	}
	if segSec <= 0 {
		segSec = 2
	}
	codec := opts.Codec
	if codec == "" {
		codec = cfg.TranscodeCodec
	}
	if codec != "hevc" {
		codec = "h264"
	}
	container := opts.Container
	if container == "" {
		container = cfg.HLSContainer
	}
	if codec == "hevc" && container == "" {
		container = "fmp4"
	}
	if container != "fmp4" {
		container = "ts"
	}

	gop := segSec * 24
	preset := cfg.TranscodePreset
	if preset == "" {
		preset = "veryfast"
	}
	useHWDecode := !cfg.DisableHWDecode
	if opts.HWDecode == "0" {
		useHWDecode = false
	} else if opts.HWDecode == "1" {
		useHWDecode = true
	}
	audioPersist := prof.AudioOnly && !cfg.DisableAudioCache
	sessKey := fmt.Sprintf("%s_%s_%s_%s_%ds_hw%v", item.Guid, qualityKey, codec, container, segSec, useHWDecode && t.hasVAAPI && cfg.EnableVAAPI)
	if prof.AudioOnly {
		sessKey = item.Guid + "_" + qualityKey
	}

	if audioPersist {
		persistDir := filepath.Join(t.audioCacheDir, sessKey)
		if isCompletedHLS(filepath.Join(persistDir, "index.m3u8")) {
			return persistDir, nil
		}
	}

	t.mu.Lock()
	if !prof.AudioOnly {
		t.lastVideoAt = time.Now()
	}
	if sess, ok := t.sessions[sessKey]; ok {
		sess.LastAccess = time.Now()
		dir := sess.Dir
		t.mu.Unlock()
		return dir, nil
	}
	sessDir := filepath.Join(t.hlsRoot, sessKey)
	if audioPersist {
		sessDir = filepath.Join(t.audioCacheDir, sessKey)
	}
	_ = os.RemoveAll(sessDir)
	if err := os.MkdirAll(sessDir, 0755); err != nil {
		t.mu.Unlock()
		return "", err
	}

	m3u8Path := filepath.Join(sessDir, "index.m3u8")
	segExt := "ts"
	if container == "fmp4" {
		segExt = "m4s"
	}
	segPattern := filepath.Join(sessDir, "seg_%05d."+segExt)

	effectiveCodec := codec
	if t.hasVAAPI && cfg.EnableVAAPI && effectiveCodec == "hevc" && !t.hasVAAPIHEVC {
		effectiveCodec = "h264"
	}
	targetBitrate := prof.Bitrate
	if !prof.AudioOnly && effectiveCodec == "hevc" && targetBitrate > 0 {
		// HEVC 同画质下码率可降至 H.264 的 65%
		targetBitrate = targetBitrate * 65 / 100
	}
	targetH := prof.Height
	if item.Height > 0 && item.Height < targetH {
		targetH = item.Height - (item.Height % 2)
		if targetH < 240 {
			targetH = 240
		}
	}

	// 零转码直封（Remux）：源视频本身已是 H.264 且分辨率和码率均不超过目标档位时，直接 -c:v copy 切片
	canCopyVideo := !prof.AudioOnly && effectiveCodec == "h264" &&
		strings.EqualFold(item.VCodec, "h264") &&
		item.Height > 0 && item.Height <= prof.Height &&
		item.Bitrate > 0 && item.Bitrate <= prof.Bitrate*125/100

	// 仅对 VAAPI 硬件支持解码的编码器开启全链路硬解（如 J1900 不支持 HEVC/VP9 硬解，自动回退 CPU 解码 + VAAPI 硬编）
	vcLower := strings.ToLower(strings.TrimSpace(item.VCodec))
	hwDecodable := vcLower == "" || vcLower == "h264" || vcLower == "mpeg2video" || vcLower == "vc1" || vcLower == "wmv3" || vcLower == "mjpeg" || (vcLower == "hevc" && t.hasVAAPIHEVC)
	effectiveHWDecode := useHWDecode && hwDecodable

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
			"-hls_segment_filename", filepath.Join(sessDir, "seg_%05d.ts"), m3u8Path,
		}
	} else if canCopyVideo {
		audKbps := "96k"
		if prof.Height >= 1080 {
			audKbps = "128k"
		}
		args = []string{
			"-y", "-i", item.FilePath,
			"-c:v", "copy",
		}
		if strings.EqualFold(item.ACodec, "aac") {
			args = append(args, "-c:a", "copy")
		} else {
			args = append(args, "-c:a", "aac", "-b:a", audKbps, "-ac", "2")
		}
		args = append(args,
			"-f", "hls", "-hls_time", strconv.Itoa(segSec), "-hls_list_size", "0",
			"-hls_playlist_type", "event", "-hls_flags", "independent_segments",
		)
		if container == "fmp4" {
			args = append(args, "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4")
		}
		args = append(args, "-hls_segment_filename", segPattern, m3u8Path)
	} else if t.hasVAAPI && cfg.EnableVAAPI {
		audKbps := "96k"
		if prof.Height >= 1080 {
			audKbps = "128k"
		}
		vEncoder := "h264_vaapi"
		if effectiveCodec == "hevc" {
			vEncoder = "hevc_vaapi"
		}
		if effectiveHWDecode {
			// 全链路 VAAPI 硬件解码 + 硬件缩放 + 硬件编码（零拷贝，速度 5x-15x）
			scaleFilter := fmt.Sprintf("scale_vaapi=w=-2:h=%d:format=nv12", targetH)
			args = []string{
				"-y", "-hwaccel", "vaapi",
				"-hwaccel_device", "/dev/dri/renderD128",
				"-hwaccel_output_format", "vaapi",
				"-i", item.FilePath,
				"-vf", scaleFilter,
				"-c:v", vEncoder,
			}
		} else {
			scaleFilter := fmt.Sprintf("format=nv12,hwupload,scale_vaapi=w=-2:h=%d", targetH)
			args = []string{
				"-y", "-vaapi_device", "/dev/dri/renderD128",
				"-i", item.FilePath,
				"-vf", scaleFilter,
				"-c:v", vEncoder,
			}
		}
		if effectiveCodec == "hevc" {
			args = append(args, "-tag:v", "hvc1")
		}
		args = append(args,
			"-b:v", fmt.Sprintf("%d", targetBitrate),
			"-maxrate", fmt.Sprintf("%d", targetBitrate*115/100),
			"-bufsize", fmt.Sprintf("%d", targetBitrate*2),
			"-g", strconv.Itoa(gop), "-keyint_min", strconv.Itoa(gop), "-sc_threshold", "0",
			"-c:a", "aac", "-b:a", audKbps, "-ac", "2",
			"-f", "hls", "-hls_time", strconv.Itoa(segSec), "-hls_list_size", "0",
			"-hls_playlist_type", "event", "-hls_flags", "independent_segments",
		)
		if container == "fmp4" {
			args = append(args, "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4")
		}
		args = append(args, "-hls_segment_filename", segPattern, m3u8Path)
	} else {
		audKbps := "96k"
		if prof.Height >= 1080 {
			audKbps = "128k"
		}
		scaleFilter := fmt.Sprintf("scale=-2:%d", targetH)
		args = []string{
			"-y", "-threads", "0", "-i", item.FilePath,
			"-vf", scaleFilter,
		}
		if codec == "hevc" {
			args = append(args, "-c:v", "libx265", "-preset", preset, "-tag:v", "hvc1")
		} else {
			args = append(args, "-c:v", "libx264", "-preset", preset, "-tune", "zerolatency")
		}
		args = append(args,
			"-b:v", fmt.Sprintf("%d", targetBitrate),
			"-maxrate", fmt.Sprintf("%d", targetBitrate*115/100),
			"-bufsize", fmt.Sprintf("%d", targetBitrate*2),
			"-g", strconv.Itoa(gop), "-keyint_min", strconv.Itoa(gop), "-sc_threshold", "0",
			"-c:a", "aac", "-b:a", audKbps, "-ac", "2",
			"-f", "hls", "-hls_time", strconv.Itoa(segSec), "-hls_list_size", "0",
			"-hls_playlist_type", "event", "-hls_flags", "independent_segments",
		)
		if container == "fmp4" {
			args = append(args, "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4")
		}
		args = append(args, "-hls_segment_filename", segPattern, m3u8Path)
	}

	cmd := exec.Command(t.ffmpegPath, args...)
	if err := cmd.Start(); err != nil {
		t.mu.Unlock()
		return "", err
	}
	t.sessions[sessKey] = &HLSSession{
		Key:          sessKey,
		Dir:          sessDir,
		Cmd:          cmd,
		LastAccess:   time.Now(),
		AudioPersist: audioPersist,
	}
	doneCh := make(chan error, 1)
	go func() {
		err := cmd.Wait()
		if err != nil {
			log.Printf("[HLS] %s ended: %v", sessKey, err)
		}
		doneCh <- err
	}()
	t.mu.Unlock()

	// Wait up to 8 seconds for index.m3u8 and first segment seg_00000.ts to be written (or break early on exit)
	firstSeg := filepath.Join(sessDir, "seg_00000."+segExt)
	for i := 0; i < 40; i++ {
		stM, errM := os.Stat(m3u8Path)
		stS, errS := os.Stat(firstSeg)
		if errM == nil && stM.Size() > 20 && errS == nil && stS.Size() > 0 {
			break
		}
		select {
		case err := <-doneCh:
			if err != nil {
				return "", fmt.Errorf("ffmpeg 退出: %v", err)
			}
			return sessDir, nil
		default:
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

func isCompletedHLS(m3u8Path string) bool {
	b, err := os.ReadFile(m3u8Path)
	if err != nil {
		return false
	}
	return strings.Contains(string(b), "#EXT-X-ENDLIST")
}

// PrewarmAllAudioCache 后台低优先级单线程将所有音乐曲目预转码为 320K HLS 永久缓存
func (t *Transcoder) PrewarmAllAudioCache(s *Store, qualityKey string) {
	if t.ffmpegPath == "" || s == nil {
		return
	}
	cfg := s.GetSettings()
	if cfg.DisableAudioCache || cfg.DisableAudioPrewarm {
		return
	}
	if qualityKey == "" {
		qualityKey = cfg.PrewarmAudioQuality
	}
	if qualityKey == "" {
		qualityKey = "audio_320"
	}
	prof := FindQualityProfile(qualityKey)
	if prof == nil || !prof.AudioOnly {
		return
	}

	t.prewarmMu.Lock()
	if t.prewarmRunning {
		t.prewarmMu.Unlock()
		return
	}
	t.prewarmRunning = true
	t.prewarmMu.Unlock()
	defer func() {
		t.prewarmMu.Lock()
		t.prewarmRunning = false
		t.prewarmMu.Unlock()
	}()

	var audioItems []*MediaItem
	s.mu.RLock()
	for _, it := range s.data.Items {
		if it != nil && it.Type == "Audio" && it.FilePath != "" {
			audioItems = append(audioItems, it)
		}
	}
	s.mu.RUnlock()

	if len(audioItems) == 0 {
		return
	}
	kbps := prof.Bitrate / 1000
	if kbps <= 0 {
		kbps = 320
	}
	var cachedCount int64
	var pending []*MediaItem
	var pendingTranscode int
	for _, it := range audioItems {
		sessDir := filepath.Join(t.audioCacheDir, it.Guid+"_"+qualityKey)
		hlsOK := isCompletedHLS(filepath.Join(sessDir, "index.m3u8"))
		if hlsOK {
			cachedCount++
		} else {
			pendingTranscode++
		}
		needsEnrich := it.Lyrics == "" || it.PosterPath == "" || it.Artist == ""
		if !hlsOK || needsEnrich {
			pending = append(pending, it)
		}
	}
	if len(pending) == 0 {
		return
	}
	log.Printf("[AudioPrewarm] 已有缓存 %d/%d 首，待预缓存 %d 首 (%s)", cachedCount, len(audioItems), pendingTranscode, qualityKey)

	newCount := int64(0)
	enrichedCount := int64(0)
	var cntMu sync.Mutex

	workers := 2
	jobs := make(chan *MediaItem, len(pending))
	for _, it := range pending {
		jobs <- it
	}
	close(jobs)

	var wg sync.WaitGroup
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for it := range jobs {
				if s.GetSettings().DisableAudioPrewarm {
					return
				}
				// 视频正在转码/播放时主动让出 J1900 CPU 与磁盘 I/O
				for {
					t.mu.Lock()
					busyVideo := !t.lastVideoAt.IsZero() && time.Since(t.lastVideoAt) < 45*time.Second
					t.mu.Unlock()
					if !busyVideo || s.GetSettings().DisableAudioPrewarm {
						break
					}
					time.Sleep(3 * time.Second)
				}
				// 顺带补齐歌词、专辑封面、歌手/专辑/时长元数据
				if it.Lyrics == "" || it.PosterPath == "" || it.Artist == "" {
					if EnrichSingleAudioTrack(s, t.thumbDir, t.ffprobePath, t.ffmpegPath, it, false) {
						cntMu.Lock()
						enrichedCount++
						if enrichedCount%10 == 0 {
							_ = s.Save()
						}
						cntMu.Unlock()
					}
				}
				sessDir := filepath.Join(t.audioCacheDir, it.Guid+"_"+qualityKey)
				m3u8Path := filepath.Join(sessDir, "index.m3u8")
				if isCompletedHLS(m3u8Path) {
					continue
				}
				tmpDir := sessDir + ".pre.tmp"
				_ = os.RemoveAll(tmpDir)
				if err := os.MkdirAll(tmpDir, 0755); err != nil {
					continue
				}
				tmpM3u8 := filepath.Join(tmpDir, "index.m3u8")
				segPattern := filepath.Join(tmpDir, "seg_%05d.ts")
				cmd := exec.Command(t.ffmpegPath,
					"-y", "-v", "quiet", "-i", it.FilePath,
					"-vn", "-c:a", "aac", "-b:a", fmt.Sprintf("%dk", kbps), "-ac", "2",
					"-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
					"-hls_playlist_type", "vod", "-hls_flags", "independent_segments",
					"-hls_segment_filename", segPattern, tmpM3u8,
				)
				if err := cmd.Run(); err == nil && isCompletedHLS(tmpM3u8) {
					_ = os.RemoveAll(sessDir)
					if os.Rename(tmpDir, sessDir) == nil {
						cntMu.Lock()
						newCount++
						cachedCount++
						curNew := newCount
						curCached := cachedCount
						cntMu.Unlock()
						if curNew <= 5 || curNew%5 == 0 {
							log.Printf("[AudioPrewarm] 已预缓存 %d/%d 首 (%s): %s", curCached, len(audioItems), qualityKey, it.Title)
						}
					} else {
						_ = os.RemoveAll(tmpDir)
					}
				} else {
					_ = os.RemoveAll(tmpDir)
				}
				time.Sleep(100 * time.Millisecond)
			}
		}()
	}
	wg.Wait()
	if enrichedCount > 0 {
		_ = s.Save()
	}
	if newCount > 0 || enrichedCount > 0 {
		log.Printf("[AudioPrewarm] 本轮预缓存完成：新增转码 %d 首，补齐元数据 %d 首，总计已缓存 %d/%d 首 (%s)", newCount, enrichedCount, cachedCount, len(audioItems), qualityKey)
	}
}

func (t *Transcoder) GetAudioCacheStats(s *Store, qualityKey string) (totalAudio int, cachedAudio int, bytes int64, running bool) {
	if qualityKey == "" {
		qualityKey = "audio_320"
	}
	t.prewarmMu.Lock()
	running = t.prewarmRunning
	t.prewarmMu.Unlock()
	s.mu.RLock()
	for _, it := range s.data.Items {
		if it != nil && it.Type == "Audio" {
			totalAudio++
			if isCompletedHLS(filepath.Join(t.audioCacheDir, it.Guid+"_"+qualityKey, "index.m3u8")) {
				cachedAudio++
			}
		}
	}
	s.mu.RUnlock()
	_ = filepath.WalkDir(t.audioCacheDir, func(path string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if info, e := d.Info(); e == nil {
				bytes += info.Size()
			}
		}
		return nil
	})
	return
}
