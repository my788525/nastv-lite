package main

// LibraryMode defines how a library presents its media
const (
	ModeMovieTV = "movie_tv"
	ModeFolder  = "folder"
)

type User struct {
	ID          string   `json:"id"`
	Username    string   `json:"username"`
	Password    string   `json:"password_hash"` // sha256(salt + ":" + password)
	Salt        string   `json:"salt"`
	Role        string   `json:"role"`         // "admin" or "user"
	AllowedLibs []string `json:"allowed_libs"` // library IDs; ["*"] or admin sees all
	CreatedAt   int64    `json:"created_at"`
}

type Library struct {
	ID        string   `json:"id"`
	Name      string   `json:"name"`
	Mode      string   `json:"mode"` // "movie_tv" or "folder"
	Paths     []string `json:"paths"`
	Category  string   `json:"category"` // e.g. "Movie", "TV", "Folder", "Music"
	CreatedAt int64    `json:"created_at"`
	LastScan  int64    `json:"last_scan"`
	ItemCount int      `json:"item_count"`
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
	ParentGuid    string         `json:"parent_guid"`
	Title         string         `json:"title"`
	OriginalTitle string         `json:"original_title,omitempty"`
	TvTitle       string         `json:"tv_title,omitempty"`
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

type StoreData struct {
	JWTSecret string                 `json:"jwt_secret"`
	Users     map[string]*User       `json:"users"`
	Libraries map[string]*Library    `json:"libraries"`
	Items     map[string]*MediaItem  `json:"items"`
	Records   map[string]WatchRecord `json:"records"` // key: userID + ":" + itemGuid
}

// ===== Client-compatible API DTOs =====

type ApiResponse struct {
	Code int         `json:"code"`
	Msg  string      `json:"msg"`
	Data interface{} `json:"data"`
}

type MediaDbDTO struct {
	Guid     string `json:"guid"`
	Title    string `json:"title"`
	Category string `json:"category"`
	Mode     string `json:"mode"` // "movie_tv" or "folder"
	Poster   string `json:"poster,omitempty"`
	Count    int    `json:"count"`
}

type PlayListItemDTO struct {
	Guid          string  `json:"guid"`
	ParentGuid    string  `json:"parent_guid,omitempty"`
	Title         string  `json:"title"`
	TvTitle       string  `json:"tv_title,omitempty"`
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
}

type ItemListResponseDTO struct {
	List  []PlayListItemDTO `json:"list"`
	Total int               `json:"total"`
}
