package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type Store struct {
	mu       sync.RWMutex
	dataPath string
	data     StoreData
}

func NewStore(dataDir string) (*Store, error) {
	if err := os.MkdirAll(dataDir, 0755); err != nil {
		return nil, err
	}
	s := &Store{
		dataPath: filepath.Join(dataDir, "data.json"),
		data: StoreData{
			Users:       make(map[string]*User),
			Libraries:   make(map[string]*Library),
			Items:       make(map[string]*MediaItem),
			Records:     make(map[string]WatchRecord),
			DDNSConfigs: make(map[string]*DDNSConfig),
		},
	}
	if err := s.load(); err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	changed := false
	if s.data.JWTSecret == "" {
		s.data.JWTSecret = randomHex(32)
		changed = true
	}
	if s.data.Settings.ServerName == "" {
		s.data.Settings = ServerSettings{
			ServerName:       "NASTV Lite",
			EnableVAAPI:      true,
			TranscodePreset:  "veryfast",
			HLSSegmentSec:    4,
			AutoScanHours:    6,
			EnableThumbnails: true,
		}
		changed = true
	}
	if len(s.data.Users) == 0 {
		admin := s.newUserObj("admin", "admin123", "admin", []string{"*"})
		s.data.Users[admin.ID] = admin
		changed = true
	}
	if changed {
		_ = s.saveLocked()
	}
	return s, nil
}

func (s *Store) load() error {
	raw, err := os.ReadFile(s.dataPath)
	if err != nil {
		return err
	}
	var d StoreData
	if err := json.Unmarshal(raw, &d); err != nil {
		return err
	}
	if d.Users == nil {
		d.Users = make(map[string]*User)
	}
	if d.Libraries == nil {
		d.Libraries = make(map[string]*Library)
	}
	if d.Items == nil {
		d.Items = make(map[string]*MediaItem)
	}
	if d.Records == nil {
		d.Records = make(map[string]WatchRecord)
	}
	if d.DDNSConfigs == nil {
		d.DDNSConfigs = make(map[string]*DDNSConfig)
	}
	s.data = d
	return nil
}

func (s *Store) saveLocked() error {
	raw, err := json.MarshalIndent(s.data, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.dataPath + ".tmp"
	if err := os.WriteFile(tmp, raw, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, s.dataPath)
}

func (s *Store) Save() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.saveLocked()
}

func randomHex(nBytes int) string {
	b := make([]byte, nBytes)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func hashPassword(salt, password string) string {
	sum := sha256.Sum256([]byte(salt + ":" + password))
	return hex.EncodeToString(sum[:])
}

func (s *Store) newUserObj(username, password, role string, allowedLibs []string) *User {
	salt := randomHex(8)
	return &User{
		ID:          "u_" + randomHex(6),
		Username:    username,
		Salt:        salt,
		Password:    hashPassword(salt, password),
		Role:        role,
		AllowedLibs: allowedLibs,
		CreatedAt:   time.Now().Unix(),
	}
}

func (s *Store) Authenticate(username, password string) (*User, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, u := range s.data.Users {
		if strings.EqualFold(u.Username, username) {
			if u.Disabled {
				return nil, errors.New("该账号已被管理员禁用")
			}
			if u.Password == hashPassword(u.Salt, password) {
				cp := *u
				return &cp, nil
			}
			return nil, errors.New("密码错误")
		}
	}
	return nil, errors.New("用户不存在")
}

// ===== Minimal HMAC-SHA256 JWT =====

type TokenClaims struct {
	UserID   string `json:"uid"`
	Username string `json:"uname"`
	Role     string `json:"role"`
	Exp      int64  `json:"exp"`
}

func (s *Store) IssueToken(u *User) (string, error) {
	s.mu.RLock()
	secret := s.data.JWTSecret
	s.mu.RUnlock()

	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	claims := TokenClaims{
		UserID:   u.ID,
		Username: u.Username,
		Role:     u.Role,
		Exp:      time.Now().Add(30 * 24 * time.Hour).Unix(),
	}
	cb, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	payload := base64.RawURLEncoding.EncodeToString(cb)
	signingInput := header + "." + payload
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(signingInput))
	sig := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	return signingInput + "." + sig, nil
}

func (s *Store) VerifyToken(tokenStr string) (*User, error) {
	parts := strings.Split(tokenStr, ".")
	if len(parts) != 3 {
		return nil, errors.New("invalid token format")
	}
	s.mu.RLock()
	secret := s.data.JWTSecret
	s.mu.RUnlock()

	signingInput := parts[0] + "." + parts[1]
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(signingInput))
	expectedSig := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(parts[2]), []byte(expectedSig)) {
		return nil, errors.New("invalid signature")
	}
	cb, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, err
	}
	var claims TokenClaims
	if err := json.Unmarshal(cb, &claims); err != nil {
		return nil, err
	}
	if time.Now().Unix() > claims.Exp {
		return nil, errors.New("token expired")
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	u, ok := s.data.Users[claims.UserID]
	if !ok || u.Disabled {
		return nil, errors.New("user not found or disabled")
	}
	cp := *u
	return &cp, nil
}

func (u *User) CanAccessLibrary(libID string) bool {
	if u == nil {
		return false
	}
	if u.Role == "admin" {
		return true
	}
	for _, id := range u.AllowedLibs {
		if id == "*" || id == libID {
			return true
		}
	}
	return false
}

func (s *Store) GetUserLibraries(u *User) []*Library {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var res []*Library
	for _, lib := range s.data.Libraries {
		if u.CanAccessLibrary(lib.ID) {
			cp := *lib
			res = append(res, &cp)
		}
	}
	return res
}

func (s *Store) GetItem(guid string) (*MediaItem, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	it, ok := s.data.Items[guid]
	if !ok {
		return nil, false
	}
	cp := *it
	return &cp, true
}

func (s *Store) GetLibrary(libID string) (*Library, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	lib, ok := s.data.Libraries[libID]
	if !ok {
		return nil, false
	}
	cp := *lib
	return &cp, true
}

func (s *Store) RecordWatch(userID, itemGuid string, ts, duration int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := fmt.Sprintf("%s:%s", userID, itemGuid)
	s.data.Records[key] = WatchRecord{
		UserID:    userID,
		ItemGuid:  itemGuid,
		Ts:        ts,
		Duration:  duration,
		UpdatedAt: time.Now().Unix(),
	}
	_ = s.saveLocked()
}

func (s *Store) GetWatchTs(userID, itemGuid string) int64 {
	s.mu.RLock()
	defer s.mu.RUnlock()
	key := fmt.Sprintf("%s:%s", userID, itemGuid)
	if rec, ok := s.data.Records[key]; ok {
		return rec.Ts
	}
	return 0
}

func (s *Store) GetSettings() ServerSettings {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.data.Settings
}

func (s *Store) UpdateSettings(cfg ServerSettings) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if cfg.ServerName == "" {
		cfg.ServerName = "NASTV Lite"
	}
	if cfg.TranscodePreset == "" {
		cfg.TranscodePreset = "veryfast"
	}
	if cfg.HLSSegmentSec <= 0 {
		cfg.HLSSegmentSec = 4
	}
	s.data.Settings = cfg
	return s.saveLocked()
}
