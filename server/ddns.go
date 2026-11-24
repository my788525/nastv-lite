package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

func fetchEchoIP(urls []string, wantV6 bool) string {
	client := &http.Client{Timeout: 6 * time.Second}
	for _, u := range urls {
		resp, err := client.Get(u)
		if err != nil {
			continue
		}
		b, err := io.ReadAll(io.LimitReader(resp.Body, 128))
		resp.Body.Close()
		if err != nil || resp.StatusCode != 200 {
			continue
		}
		ipStr := strings.TrimSpace(string(b))
		ip := net.ParseIP(ipStr)
		if ip == nil {
			continue
		}
		if wantV6 && ip.To4() == nil {
			return ip.String()
		}
		if !wantV6 && ip.To4() != nil {
			return ip.String()
		}
	}
	return ""
}

func DetectPublicIPv4() string {
	return fetchEchoIP([]string{
		"https://4.ipw.cn",
		"https://api.ipify.org",
		"https://v4.ident.me",
	}, false)
}

func DetectPublicIPv6() string {
	// First check HTTP IPv6 echo so we get the actual egress public IPv6
	if ip := fetchEchoIP([]string{
		"https://6.ipw.cn",
		"https://api6.ipify.org",
		"https://v6.ident.me",
	}, true); ip != "" {
		return ip
	}
	// Fallback: inspect local global unicast IPv6 (excluding ULA fc00::/7)
	_, v6List := getLocalIPs()
	for _, s := range v6List {
		ip := net.ParseIP(s)
		if ip != nil && ip.To4() == nil && ip.IsGlobalUnicast() && (ip[0]&0xfe) != 0xfc {
			return ip.String()
		}
	}
	return ""
}

func splitDomain(fqdn string) (sub, root string) {
	fqdn = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(fqdn)), ".")
	parts := strings.Split(fqdn, ".")
	if len(parts) <= 2 {
		return "@", fqdn
	}
	root = strings.Join(parts[len(parts)-2:], ".")
	sub = strings.Join(parts[:len(parts)-2], ".")
	return sub, root
}

func RunDDNSConfig(s *Store, cfg *DDNSConfig) string {
	var ipv4, ipv6 string
	mode := cfg.IPMode
	if mode == "" {
		mode = "ipv6"
	}
	if mode == "ipv4" || mode == "dual" {
		ipv4 = DetectPublicIPv4()
	}
	if mode == "ipv6" || mode == "dual" {
		ipv6 = DetectPublicIPv6()
	}

	var msgs []string
	var err error
	if (mode == "ipv4" || mode == "dual") && ipv4 == "" {
		msgs = append(msgs, "未检测到公网 IPv4")
	}
	if (mode == "ipv6" || mode == "dual") && ipv6 == "" {
		msgs = append(msgs, "未检测到公网 IPv6")
	}

	switch cfg.Provider {
	case "cloudflare":
		err = updateCloudflare(cfg, ipv4, ipv6)
	case "alidns":
		err = updateAliDNS(cfg, ipv4, ipv6)
	case "dnspod":
		err = updateDNSPod(cfg, ipv4, ipv6)
	case "duckdns":
		err = updateDuckDNS(cfg, ipv4, ipv6)
	case "custom":
		err = updateCustomDDNS(cfg, ipv4, ipv6)
	default:
		err = fmt.Errorf("未知 DDNS 服务商: %s", cfg.Provider)
	}

	status := "同步成功"
	if err != nil {
		status = "失败: " + err.Error()
	} else if len(msgs) > 0 && ipv4 == "" && ipv6 == "" {
		status = strings.Join(msgs, "; ")
	} else {
		var parts []string
		if ipv4 != "" {
			parts = append(parts, "A="+ipv4)
		}
		if ipv6 != "" {
			parts = append(parts, "AAAA="+ipv6)
		}
		if len(parts) > 0 {
			status = "成功 (" + strings.Join(parts, ", ") + ")"
		}
	}

	s.mu.Lock()
	if cur, ok := s.data.DDNSConfigs[cfg.ID]; ok {
		if ipv4 != "" {
			cur.LastIPv4 = ipv4
		}
		if ipv6 != "" {
			cur.LastIPv6 = ipv6
		}
		cur.LastStatus = status
		cur.LastRun = time.Now().Unix()
		_ = s.saveLocked()
	}
	s.mu.Unlock()
	return status
}

// ===== 1. Cloudflare DDNS (Supports API Token & Global Key, A + AAAA IPv6) =====

func cfRequest(cfg *DDNSConfig, method, endpoint string, bodyObj interface{}) (map[string]interface{}, error) {
	var bodyReader io.Reader
	if bodyObj != nil {
		b, _ := json.Marshal(bodyObj)
		bodyReader = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, "https://api.cloudflare.com/client/v4"+endpoint, bodyReader)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if strings.TrimSpace(cfg.APISecret) != "" {
		// Global API Key mode: api_secret = Email, api_key = Global Key
		req.Header.Set("X-Auth-Email", strings.TrimSpace(cfg.APISecret))
		req.Header.Set("X-Auth-Key", strings.TrimSpace(cfg.APIKey))
	} else {
		// Scoped API Token mode (Recommended)
		req.Header.Set("Authorization", "Bearer "+strings.TrimSpace(cfg.APIKey))
	}
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var res map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		return nil, err
	}
	if ok, _ := res["success"].(bool); !ok {
		return res, fmt.Errorf("Cloudflare API 错误: %v", res["errors"])
	}
	return res, nil
}

func updateCloudflare(cfg *DDNSConfig, ipv4, ipv6 string) error {
	zoneID := strings.TrimSpace(cfg.ZoneID)
	_, root := splitDomain(cfg.Domain)
	if zoneID == "" {
		res, err := cfRequest(cfg, http.MethodGet, "/zones?name="+url.QueryEscape(root), nil)
		if err != nil {
			return err
		}
		arr, _ := res["result"].([]interface{})
		if len(arr) == 0 {
			return fmt.Errorf("未找到域名 Zone: %s", root)
		}
		first, _ := arr[0].(map[string]interface{})
		zoneID, _ = first["id"].(string)
	}

	syncType := func(recType, ipVal string) error {
		if ipVal == "" {
			return nil
		}
		q := fmt.Sprintf("/zones/%s/dns_records?type=%s&name=%s", zoneID, recType, url.QueryEscape(cfg.Domain))
		res, err := cfRequest(cfg, http.MethodGet, q, nil)
		if err != nil {
			return err
		}
		arr, _ := res["result"].([]interface{})
		payload := map[string]interface{}{
			"type":    recType,
			"name":    cfg.Domain,
			"content": ipVal,
			"ttl":     120,
			"proxied": cfg.Proxied,
		}
		if len(arr) > 0 {
			rec, _ := arr[0].(map[string]interface{})
			recID, _ := rec["id"].(string)
			oldContent, _ := rec["content"].(string)
			if oldContent == ipVal {
				return nil
			}
			_, err = cfRequest(cfg, http.MethodPut, fmt.Sprintf("/zones/%s/dns_records/%s", zoneID, recID), payload)
			return err
		}
		_, err = cfRequest(cfg, http.MethodPost, fmt.Sprintf("/zones/%s/dns_records", zoneID), payload)
		return err
	}

	if err := syncType("A", ipv4); err != nil {
		return err
	}
	return syncType("AAAA", ipv6)
}

// ===== 2. Aliyun Alidns DDNS =====

func aliPercentEncode(s string) string {
	r := url.QueryEscape(s)
	r = strings.ReplaceAll(r, "+", "%20")
	r = strings.ReplaceAll(r, "*", "%2A")
	r = strings.ReplaceAll(r, "%7E", "~")
	return r
}

func aliyunRequest(cfg *DDNSConfig, params map[string]string) (map[string]interface{}, error) {
	params["Format"] = "JSON"
	params["Version"] = "2015-01-09"
	params["AccessKeyId"] = strings.TrimSpace(cfg.APIKey)
	params["SignatureMethod"] = "HMAC-SHA1"
	params["Timestamp"] = time.Now().UTC().Format("2006-01-02T15:04:05Z")
	params["SignatureVersion"] = "1.0"
	params["SignatureNonce"] = randomHex(8)

	keys := make([]string, 0, len(params))
	for k := range params {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var parts []string
	for _, k := range keys {
		parts = append(parts, aliPercentEncode(k)+"="+aliPercentEncode(params[k]))
	}
	canonical := strings.Join(parts, "&")
	strToSign := "GET&%2F&" + aliPercentEncode(canonical)

	mac := hmac.New(sha1.New, []byte(strings.TrimSpace(cfg.APISecret)+"&"))
	mac.Write([]byte(strToSign))
	sig := base64.StdEncoding.EncodeToString(mac.Sum(nil))

	fullURL := "https://alidns.aliyuncs.com/?" + canonical + "&Signature=" + aliPercentEncode(sig)
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Get(fullURL)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var res map[string]interface{}
	_ = json.NewDecoder(resp.Body).Decode(&res)
	if msg, ok := res["Message"].(string); ok && msg != "" {
		return res, fmt.Errorf("阿里云DNS: %s", msg)
	}
	return res, nil
}

func updateAliDNS(cfg *DDNSConfig, ipv4, ipv6 string) error {
	sub, root := splitDomain(cfg.Domain)
	syncType := func(recType, ipVal string) error {
		if ipVal == "" {
			return nil
		}
		res, err := aliyunRequest(cfg, map[string]string{
			"Action":    "DescribeSubDomainRecords",
			"SubDomain": cfg.Domain,
			"Type":      recType,
		})
		if err != nil {
			return err
		}
		dr, _ := res["DomainRecords"].(map[string]interface{})
		recs, _ := dr["Record"].([]interface{})
		if len(recs) > 0 {
			rec, _ := recs[0].(map[string]interface{})
			recID, _ := rec["RecordId"].(string)
			oldVal, _ := rec["Value"].(string)
			if oldVal == ipVal {
				return nil
			}
			_, err = aliyunRequest(cfg, map[string]string{
				"Action":   "UpdateDomainRecord",
				"RecordId": recID,
				"RR":       sub,
				"Type":     recType,
				"Value":    ipVal,
			})
			return err
		}
		_, err = aliyunRequest(cfg, map[string]string{
			"Action":     "AddDomainRecord",
			"DomainName": root,
			"RR":         sub,
			"Type":       recType,
			"Value":      ipVal,
		})
		return err
	}
	if err := syncType("A", ipv4); err != nil {
		return err
	}
	return syncType("AAAA", ipv6)
}

// ===== 3. DNSPod (Tencent Cloud) DDNS =====

func dnspodRequest(cfg *DDNSConfig, endpoint string, form url.Values) (map[string]interface{}, error) {
	token := strings.TrimSpace(cfg.APIKey)
	if strings.TrimSpace(cfg.APISecret) != "" {
		token = token + "," + strings.TrimSpace(cfg.APISecret)
	}
	form.Set("login_token", token)
	form.Set("format", "json")
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.PostForm("https://dnsapi.cn/"+endpoint, form)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var res map[string]interface{}
	_ = json.NewDecoder(resp.Body).Decode(&res)
	if st, ok := res["status"].(map[string]interface{}); ok {
		if code, _ := st["code"].(string); code != "1" && code != "" {
			return res, fmt.Errorf("DNSPod: %v", st["message"])
		}
	}
	return res, nil
}

func updateDNSPod(cfg *DDNSConfig, ipv4, ipv6 string) error {
	sub, root := splitDomain(cfg.Domain)
	syncType := func(recType, ipVal string) error {
		if ipVal == "" {
			return nil
		}
		f := url.Values{}
		f.Set("domain", root)
		f.Set("sub_domain", sub)
		f.Set("record_type", recType)
		res, err := dnspodRequest(cfg, "Record.List", f)
		if err != nil {
			// Record.List returns code 10 if no records found; handle create
			if !strings.Contains(err.Error(), "10") && !strings.Contains(err.Error(), "No records") {
				return err
			}
		}
		recs, _ := res["records"].([]interface{})
		if len(recs) > 0 {
			rec, _ := recs[0].(map[string]interface{})
			recID, _ := rec["id"].(string)
			oldVal, _ := rec["value"].(string)
			if oldVal == ipVal {
				return nil
			}
			mf := url.Values{}
			mf.Set("domain", root)
			mf.Set("record_id", recID)
			mf.Set("sub_domain", sub)
			mf.Set("record_type", recType)
			mf.Set("record_line", "默认")
			mf.Set("value", ipVal)
			_, err = dnspodRequest(cfg, "Record.Modify", mf)
			return err
		}
		cf := url.Values{}
		cf.Set("domain", root)
		cf.Set("sub_domain", sub)
		cf.Set("record_type", recType)
		cf.Set("record_line", "默认")
		cf.Set("value", ipVal)
		_, err = dnspodRequest(cfg, "Record.Create", cf)
		return err
	}
	if err := syncType("A", ipv4); err != nil {
		return err
	}
	return syncType("AAAA", ipv6)
}

// ===== 4. DuckDNS & 5. Custom Webhook =====

func updateDuckDNS(cfg *DDNSConfig, ipv4, ipv6 string) error {
	domain := strings.TrimSuffix(strings.TrimSpace(cfg.Domain), ".duckdns.org")
	u := fmt.Sprintf("https://www.duckdns.org/update?domains=%s&token=%s",
		url.QueryEscape(domain), url.QueryEscape(strings.TrimSpace(cfg.APIKey)))
	if ipv4 != "" {
		u += "&ip=" + url.QueryEscape(ipv4)
	}
	if ipv6 != "" {
		u += "&ipv6=" + url.QueryEscape(ipv6)
	}
	client := &http.Client{Timeout: 12 * time.Second}
	resp, err := client.Get(u)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if strings.TrimSpace(string(b)) != "OK" {
		return fmt.Errorf("DuckDNS 响应: %s", string(b))
	}
	return nil
}

func updateCustomDDNS(cfg *DDNSConfig, ipv4, ipv6 string) error {
	u := strings.TrimSpace(cfg.CustomURL)
	if u == "" {
		return fmt.Errorf("自定义 URL 不能为空")
	}
	ip := ipv6
	if ip == "" {
		ip = ipv4
	}
	u = strings.ReplaceAll(u, "{ip}", url.QueryEscape(ip))
	u = strings.ReplaceAll(u, "{ipv4}", url.QueryEscape(ipv4))
	u = strings.ReplaceAll(u, "{ipv6}", url.QueryEscape(ipv6))
	u = strings.ReplaceAll(u, "{domain}", url.QueryEscape(cfg.Domain))
	client := &http.Client{Timeout: 12 * time.Second}
	resp, err := client.Get(u)
	if err != nil {
		return err
	}
	resp.Body.Close()
	if resp.StatusCode >= 400 {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return nil
}

func StartDDNSScheduler(s *Store) {
	go func() {
		for {
			time.Sleep(1 * time.Minute)
			s.mu.RLock()
			var todo []*DDNSConfig
			now := time.Now().Unix()
			for _, cfg := range s.data.DDNSConfigs {
				if !cfg.Enabled {
					continue
				}
				iv := cfg.IntervalMin
				if iv <= 0 {
					iv = 5
				}
				if now-cfg.LastRun >= int64(iv*60) {
					cp := *cfg
					todo = append(todo, &cp)
				}
			}
			s.mu.RUnlock()
			for _, cfg := range todo {
				st := RunDDNSConfig(s, cfg)
				log.Printf("[DDNS] %s (%s): %s", cfg.Name, cfg.Domain, st)
			}
		}
	}()
}
