package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"time"
)

const (
	ServerVersionCode = 14
	ServerVersionName = "1.4.0"
	UpdateManifestURL = "https://raw.githubusercontent.com/my788525/nastv-lite/main/update.json"
)

type ServerUpdateInfo struct {
	VersionCode  int    `json:"versionCode"`
	VersionName  string `json:"versionName"`
	BinaryURL    string `json:"binaryUrl"`
	WinBinaryURL string `json:"winBinaryUrl"`
	FpkURL       string `json:"fpkUrl"`
	Changelog    string `json:"changelog"`
}

type UnifiedUpdateManifest struct {
	Server  ServerUpdateInfo `json:"server"`
	Android json.RawMessage  `json:"android"`
}

type UpdateCheckResult struct {
	CurrentCode int              `json:"current_code"`
	CurrentName string           `json:"current_name"`
	HasUpdate   bool             `json:"has_update"`
	Latest      ServerUpdateInfo `json:"latest"`
}

func CheckServerUpdate() (*UpdateCheckResult, error) {
	client := &http.Client{Timeout: 12 * time.Second}
	resp, err := client.Get(UpdateManifestURL + "?t=" + fmt.Sprintf("%d", time.Now().Unix()))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var m UnifiedUpdateManifest
	if err := json.NewDecoder(resp.Body).Decode(&m); err != nil {
		return nil, err
	}
	return &UpdateCheckResult{
		CurrentCode: ServerVersionCode,
		CurrentName: ServerVersionName,
		HasUpdate:   m.Server.VersionCode > ServerVersionCode,
		Latest:      func() ServerUpdateInfo {
			if runtime.GOOS == "windows" && m.Server.WinBinaryURL != "" {
				m.Server.BinaryURL = m.Server.WinBinaryURL
			}
			return m.Server
		}(),
	}, nil
}

func PerformServerSelfUpdate(binaryURL string) error {
	if binaryURL == "" {
		return fmt.Errorf("empty binaryUrl")
	}
	exePath, err := os.Executable()
	if err != nil {
		return err
	}
	exePath, _ = filepath.EvalSymlinks(exePath)

	client := &http.Client{Timeout: 120 * time.Second}
	resp, err := client.Get(binaryURL)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("download HTTP %d", resp.StatusCode)
	}

	tmpPath := exePath + ".new"
	f, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, resp.Body); err != nil {
		f.Close()
		_ = os.Remove(tmpPath)
		return err
	}
	f.Close()

	if runtime.GOOS == "windows" {
		oldPath := exePath + ".old"
		_ = os.Remove(oldPath)
		if err := os.Rename(exePath, oldPath); err != nil {
			_ = os.Remove(tmpPath)
			return err
		}
	}
	if err := os.Rename(tmpPath, exePath); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}

	// Schedule restart in background
	go func() {
		time.Sleep(800 * time.Millisecond)
		args := os.Args[1:]
		cmd := exec.Command(exePath, args...)
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Start(); err == nil {
			os.Exit(0)
		}
	}()
	return nil
}
