package main

// LibraryMode defines how a library presents its media
const (
	ModeMovieTV = "movie_tv"
	ModeFolder  = "folder"
	ModeMusic   = "music"
)

type User struct {
	ID          string   `json:"id"`
	Username    string   `json:"username"`
	Password    string   `json:"password_hash"` // sha256(salt + ":" + password)
	Salt        string   `json:"salt"`
	Role        string   `json:"role"`         // "admin" or "user"
	AllowedLibs []string `json:"allowed_libs"` // library IDs; ["*"] or admin sees all
	Disabled    bool     `json:"disabled"`
	CreatedAt   int64    `json:"created_at"`
}

type ServerSettings struct {
	ServerName       string `json:"server_name"`
	EnableVAAPI      bool   `json:"enable_vaapi"`
	DisableHWDecode  bool   `json:"disable_hwdecode"`   // false=enable full VAAPI hwaccel decode+encode
	TranscodePreset  string `json:"transcode_preset"`   // ultrafast, veryfast, fast, medium
	TranscodeCodec   string `json:"transcode_codec"`    // h264, hevc
	HLSContainer     string `json:"hls_container"`      // ts, fmp4
	HLSSegmentSec    int    `json:"hls_segment_sec"`    // 2, 4, 6
	DisableGzip      bool   `json:"disable_gzip"`       // false=enable HTTP gzip for JSON/M3U8/subtitles
	AutoScanHours    int    `json:"auto_scan_hours"`    // 0=off, 1, 6, 24
	EnableThumbnails bool   `json:"enable_thumbnails"`  // auto ffmpeg frame thumbs
	EnableScraper    bool   `json:"enable_scraper"`     // auto public metadata scraper for movie_tv libs
	TMDBApiKey       string `json:"tmdb_api_key"`       // optional TMDB v3 API key
	TMDBMirror       string `json:"tmdb_mirror"`        // optional TMDB API base URL mirror
	AllowGuestSearch bool   `json:"allow_guest_search"` // reserved
}

type Library struct {
	ID            string   `json:"id"`
	Name          string   `json:"name"`
	Mode          string   `json:"mode"` // "movie_tv" or "folder"
	Paths         []string `json:"paths"`
	Category      string   `json:"category"`       // e.g. "Movie", "TV", "Folder", "Music"
	AllowDownload bool     `json:"allow_download"` // 文件共享属性：允许客户端下载该库内文件
	CreatedAt     int64    `json:"created_at"`
	LastScan      int64    `json:"last_scan"`
	ItemCount     int      `json:"item_count"`
}

type SubtitleInfo struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Language string `json:"language"`
	Format   string `json:"format"`
	FilePath string `json:"file_path"`
}

type MediaItem struct {
	Guid          string         `json:"guid"`
	LibID         string         `json:"lib_id"`
	ViewMode      string         `json:"view_mode,omitempty"` // "folder" or "movie_tv"
	ParentGuid    string         `json:"parent_guid"`
	Title         string         `json:"title"`
	OriginalTitle string         `json:"original_title,omitempty"`
	TvTitle       string         `json:"tv_title,omitempty"`
	Artist        string         `json:"artist,omitempty"`
	Album         string         `json:"album,omitempty"`
	Lyrics        string         `json:"lyrics,omitempty"`
	Type          string         `json:"type"` // "Directory", "Video", "Audio", "Movie", "TV", "Episode"
	Category      string         `json:"category,omitempty"`
	FilePath      string         `json:"file_path"`
	RelPath       string         `json:"rel_path"`
	PosterPath    string         `json:"poster_path,omitempty"`
	Overview      string         `json:"overview,omitempty"`
	Year          int            `json:"year,omitempty"`
	Score         float64        `json:"score,omitempty"`
	SeasonNumber  int            `json:"season_number,omitempty"`
	EpisodeNumber int            `json:"episode_number,omitempty"`
	Duration      int64          `json:"duration"` // seconds
	FileSize      int64          `json:"file_size"`
	Width         int            `json:"width,omitempty"`
	Height        int            `json:"height,omitempty"`
	VCodec        string         `json:"v_codec,omitempty"`
	ACodec        string         `json:"a_codec,omitempty"`
	Bitrate       int64          `json:"bitrate,omitempty"`
	ModTime       int64          `json:"mod_time"`
	Subtitles     []SubtitleInfo `json:"subtitles,omitempty"`
}

type WatchRecord struct {
	UserID    string `json:"user_id"`
	ItemGuid  string `json:"item_guid"`
	Ts        int64  `json:"ts"`
	Duration  int64  `json:"duration"`
	UpdatedAt int64  `json:"updated_at"`
}

type DDNSConfig struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Provider    string `json:"provider"` // "cloudflare", "alidns", "dnspod", "duckdns", "custom"
	Enabled     bool   `json:"enabled"`
	Domain      string `json:"domain"`
	IPMode      string `json:"ip_mode"` // "ipv6", "ipv4", "dual"
	IntervalMin int    `json:"interval_min"`
	APIKey      string `json:"api_key"`    // CF Token / Aliyun KeyID / DNSPod ID / Duck Token
	APISecret   string `json:"api_secret"` // CF Email (optional) / Aliyun Secret / DNSPod Token
	ZoneID      string `json:"zone_id"`
	Proxied     bool   `json:"proxied"`
	CustomURL   string `json:"custom_url"`
	LastIPv4    string `json:"last_ipv4"`
	LastIPv6    string `json:"last_ipv6"`
	LastStatus  string `json:"last_status"`
	LastRun     int64  `json:"last_run"`
}

type StoreData struct {
	JWTSecret   string                 `json:"jwt_secret"`
	Settings    ServerSettings         `json:"settings"`
	Users       map[string]*User       `json:"users"`
	Libraries   map[string]*Library    `json:"libraries"`
	Items       map[string]*MediaItem  `json:"items"`
	Records     map[string]WatchRecord `json:"records"` // key: userID + ":" + itemGuid
	DDNSConfigs map[string]*DDNSConfig `json:"ddns_configs"`
}

// ===== Client-compatible API DTOs =====

type ApiResponse struct {
	Code int         `json:"code"`
	Msg  string      `json:"msg"`
	Data interface{} `json:"data"`
}

type MediaDbDTO struct {
	Guid          string `json:"guid"`
	Title         string `json:"title"`
	Category      string `json:"category"`
	Mode          string `json:"mode"` // "movie_tv" or "folder"
	AllowDownload bool   `json:"allow_download"`
	Poster        string `json:"poster,omitempty"`
	Count         int    `json:"count"`
}

type PlayListItemDTO struct {
	Guid          string  `json:"guid"`
	ParentGuid    string  `json:"parent_guid,omitempty"`
	Title         string  `json:"title"`
	TvTitle       string  `json:"tv_title,omitempty"`
	Artist        string  `json:"artist,omitempty"`
	Album         string  `json:"album,omitempty"`
	Type          string  `json:"type"`
	Category      string  `json:"category,omitempty"`
	Poster        string  `json:"poster,omitempty"`
	Duration      int64   `json:"duration"`
	Ts            int64   `json:"ts"`
	EpisodeNumber int     `json:"episode_number,omitempty"`
	SeasonNumber  int     `json:"season_number,omitempty"`
	Overview      string  `json:"overview,omitempty"`
	Year          int     `json:"year,omitempty"`
	Score         float64 `json:"score,omitempty"`
	FileSize      int64   `json:"file_size,omitempty"`
	UpdateTime    int64   `json:"update_time,omitempty"`
	LibraryMode   string  `json:"library_mode,omitempty"`
	AllowDownload bool    `json:"allow_download,omitempty"`
}

type ItemListResponseDTO struct {
	List  []PlayListItemDTO `json:"list"`
	Total int               `json:"total"`
}
