package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

const agentVersion = "0.6.0"

type config struct {
	Token string `json:"token"`
}

type execRequest struct {
	Command string `json:"command"`
	Timeout int    `json:"timeout"`
}

type openRequest struct {
	Target string `json:"target"`
}

type updateManifest struct {
	VersionCode int    `json:"versionCode"`
	VersionName string `json:"versionName"`
	APK         string `json:"apk"`
	SHA256      string `json:"sha256"`
	Notes       string `json:"notes"`
}

var (
	authToken string
	updateDir string
	allowExec bool
)

func main() {
	port := flag.Int("port", 8765, "listen port")
	dir := flag.String("dir", ".", "directory containing update.json and the APK")
	tokenFlag := flag.String("token", "", "auth token (optional)")
	allowExecFlag := flag.Bool("allow-exec", false, "enable arbitrary command execution")
	flag.Parse()

	updateDir = *dir
	allowExec = *allowExecFlag
	authToken = strings.TrimSpace(*tokenFlag)
	if authToken == "" {
		authToken = loadOrCreateToken()
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", handleHealth)
	mux.HandleFunc("/api/info", requireAuth(handleInfo))
	mux.HandleFunc("/api/exec", requireAuth(handleExec))
	mux.HandleFunc("/api/open", requireAuth(handleOpen))
	mux.HandleFunc("/api/update/manifest", requireAuth(handleManifest))
	mux.HandleFunc("/api/update/apk", requireAuth(handleAPK))
	mux.HandleFunc("/", handleIndex)

	addr := fmt.Sprintf("0.0.0.0:%d", *port)
	server := &http.Server{
		Addr:              addr,
		Handler:           cors(mux),
		ReadHeaderTimeout: 5 * time.Second,
	}

	printBanner(*port)
	log.Printf("listening on %s", addr)
	if err := server.ListenAndServe(); err != nil {
		log.Fatal(err)
	}
}

func loadOrCreateToken() string {
	path := filepath.Join(updateDir, "pc-agent-config.json")
	if data, err := os.ReadFile(path); err == nil {
		var cfg config
		if json.Unmarshal(data, &cfg) == nil && strings.TrimSpace(cfg.Token) != "" {
			return strings.TrimSpace(cfg.Token)
		}
	}

	buf := make([]byte, 24)
	if _, err := rand.Read(buf); err != nil {
		log.Fatal(err)
	}
	token := hex.EncodeToString(buf)
	data, _ := json.MarshalIndent(config{Token: token}, "", "  ")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		log.Printf("warning: cannot save config: %v", err)
	}
	return token
}

func printBanner(port int) {
	fmt.Println("")
	fmt.Println("DSAssist PC Agent", agentVersion)
	fmt.Println("Token:", authToken)
	fmt.Println("Update dir:", updateDir)
	for _, ip := range localIPv4() {
		fmt.Printf("地址: http://%s:%d\n", ip, port)
	}
	fmt.Println("")
	fmt.Println("手机端填写上面的地址和 Token。只允许在可信局域网使用。")
	if !allowExec {
		fmt.Println("命令执行默认关闭；需要时用 -allow-exec 启动。")
	}
	fmt.Println("")
}

func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-Auth-Token")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func requireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		provided := r.Header.Get("X-Auth-Token")
		if provided == "" {
			provided = r.URL.Query().Get("token")
		}
		if subtle.ConstantTimeCompare([]byte(provided), []byte(authToken)) != 1 {
			writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "Token 无效"})
			return
		}
		next(w, r)
	}
}

func handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"agent":   agentVersion,
		"os":      runtime.GOOS,
		"version": agentVersion,
	})
}

func handleInfo(w http.ResponseWriter, _ *http.Request) {
	hostname, _ := os.Hostname()
	user := os.Getenv("USERNAME")
	if user == "" {
		user = os.Getenv("USER")
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"hostname":    hostname,
		"os":          runtime.GOOS + "/" + runtime.GOARCH,
		"user":        user,
		"ips":         localIPv4(),
		"time":        time.Now().Format(time.RFC3339),
		"agent":       agentVersion,
		"execEnabled": allowExec,
	})
}

func handleExec(w http.ResponseWriter, r *http.Request) {
	if !allowExec {
		writeJSON(w, http.StatusForbidden, map[string]any{"error": "命令执行未启用，请使用 -allow-exec 启动"})
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "只支持 POST"})
		return
	}

	var req execRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "请求格式错误"})
		return
	}
	req.Command = strings.TrimSpace(req.Command)
	if req.Command == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "命令为空"})
		return
	}
	if len(req.Command) > 4000 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "命令过长"})
		return
	}

	timeout := req.Timeout
	if timeout <= 0 {
		timeout = 30
	}
	if timeout > 120 {
		timeout = 120
	}

	ctx, cancel := context.WithTimeout(r.Context(), time.Duration(timeout)*time.Second)
	defer cancel()

	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		cmd = exec.CommandContext(ctx, "cmd", "/C", req.Command)
	} else {
		cmd = exec.CommandContext(ctx, "sh", "-c", req.Command)
	}

	output, err := cmd.CombinedOutput()
	text := truncate(string(output), 64*1024)
	exitCode := 0
	errorText := ""
	if err != nil {
		errorText = err.Error()
		if exitErr, ok := err.(*exec.ExitError); ok {
			exitCode = exitErr.ExitCode()
		}
	}
	if ctx.Err() == context.DeadlineExceeded {
		errorText = "命令执行超时"
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"output":   text,
		"exitCode": exitCode,
		"error":    errorText,
	})
}

func handleOpen(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "只支持 POST"})
		return
	}

	var req openRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "请求格式错误"})
		return
	}
	req.Target = strings.TrimSpace(req.Target)
	if req.Target == "" || len(req.Target) > 2000 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "目标为空或过长"})
		return
	}

	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		cmd = exec.Command("cmd", "/C", "start", "", req.Target)
	} else {
		cmd = exec.Command("xdg-open", req.Target)
	}
	if err := cmd.Start(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"output": "opened " + req.Target})
}

func handleManifest(w http.ResponseWriter, _ *http.Request) {
	path := filepath.Join(updateDir, "update.json")
	data, err := os.ReadFile(path)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "还没有 update.json"})
		return
	}
	var manifest updateManifest
	if err := json.Unmarshal(data, &manifest); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "update.json 格式错误"})
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func handleAPK(w http.ResponseWriter, r *http.Request) {
	path := filepath.Join(updateDir, "update.json")
	data, err := os.ReadFile(path)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "还没有 update.json"})
		return
	}
	var manifest updateManifest
	if err := json.Unmarshal(data, &manifest); err != nil || manifest.APK == "" {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "update.json 缺少 apk"})
		return
	}
	name := filepath.Base(manifest.APK)
	apkPath := filepath.Join(updateDir, name)
	if _, err := os.Stat(apkPath); err != nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "APK 文件不存在"})
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", "attachment; filename="+name)
	http.ServeFile(w, r, apkPath)
}

func handleIndex(w http.ResponseWriter, _ *http.Request) {
	hostname, _ := os.Hostname()
	manifest := "未配置"
	if data, err := os.ReadFile(filepath.Join(updateDir, "update.json")); err == nil {
		var m updateManifest
		if json.Unmarshal(data, &m) == nil {
			manifest = fmt.Sprintf("%s (%d)", m.VersionName, m.VersionCode)
		}
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!doctype html><html><head><meta charset="utf-8"><title>DSAssist PC Agent</title>
<style>body{font-family:system-ui,sans-serif;background:#0B0F14;color:#E8EEF7;padding:40px;line-height:1.7}
code{background:#1A2230;padding:2px 6px;border-radius:6px}a{color:#4F8CFF}</style></head><body>
<h1>DSAssist PC Agent</h1>
<p>主机：%s</p><p>Agent 版本：%s</p><p>更新版本：%s</p>
<p>健康检查：<a href="/health">/health</a></p>
<p>手机端填写地址和 Token。Token 在启动窗口里。</p>
</body></html>`, hostname, agentVersion, manifest)
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func localIPv4() []string {
	var result []string
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return result
	}
	for _, addr := range addrs {
		ipNet, ok := addr.(*net.IPNet)
		if !ok || ipNet.IP.IsLoopback() {
			continue
		}
		ip := ipNet.IP.To4()
		if ip != nil {
			result = append(result, ip.String())
		}
	}
	if len(result) == 0 {
		result = append(result, "127.0.0.1")
	}
	return result
}

func truncate(s string, max int) string {
	if len(s) <= max {
		return s
	}
	half := max / 2
	return s[:half] + "\n... (输出已截断) ...\n" + s[len(s)-half:]
}
