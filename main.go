package main

// HUI — Hermes Unofficial Interface: a standalone terminal client for a remote
// Hermes gateway over WebSocket JSON-RPC. Zero Hermes code on this side.
//
// Default gateway port for -host: the bound port of the owner's gateway.
// Override per invocation with -host name:port.

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
)

var buildStamp = "dev"

// ─── wire types ──────────────────────────────────────────────────────────────

type frame map[string]any

func (f frame) method() string { s, _ := f["method"].(string); return s }
func (f frame) id() any        { return f["id"] }
func (f frame) str(key string) string {
	v, _ := f[key].(string)
	return v
}

func paramsOf(f frame) map[string]any {
	p, _ := f["params"].(map[string]any)
	if p == nil {
		return map[string]any{}
	}
	return p
}

func payloadOf(f frame) map[string]any {
	p, _ := paramsOf(f)["payload"].(map[string]any)
	if p == nil {
		return map[string]any{}
	}
	return p
}

func pstr(m map[string]any, key string) string {
	v, _ := m[key].(string)
	return v
}

func pnum(m map[string]any, key string) (float64, bool) {
	v, ok := m[key].(float64)
	return v, ok
}

// ─── gateway connection ──────────────────────────────────────────────────────

const (
	protocolName      = "hermes-gateway-v1"
	heartbeatEvery    = 15 * time.Second
	heartbeatDeadline = 45 * time.Second
	defaultRPCTimeout = 30 * time.Second
	attachRPCTimeout  = 120 * time.Second
	readyTimeout      = 15 * time.Second
	reconnectAttempts = 5
	reconnectBackoff  = 2 * time.Second
)

type gateway struct {
	url string

	ws      *WSConn
	wmu     sync.Mutex
	seq     int
	pending map[int]chan frame
	pmu     sync.Mutex

	frames   chan frame
	dead     chan struct{} // closed when the socket reader exits
	deadOnce sync.Once

	lastPong time.Time
	hbStop   chan struct{}
}

func newGateway(url string) *gateway {
	return &gateway{
		url:     url,
		frames:  make(chan frame, 256),
		dead:    make(chan struct{}),
		pending: map[int]chan frame{},
		hbStop:  make(chan struct{}),
	}
}

func (g *gateway) subprotocols() []string {
	return []string{protocolName}
}

func (g *gateway) connect() error {
	ws, err := wsDial(g.url, g.subprotocols(), 10*time.Second)
	if err != nil {
		return err
	}
	g.ws = ws
	g.lastPong = time.Now()
	go g.readLoop()
	go g.heartbeat()
	return nil
}

func (g *gateway) readLoop() {
	defer g.deadOnce.Do(func() { close(g.dead) })
	var lineBuf []byte
	for {
		msg, err := g.ws.ReadMessage()
		if err != nil {
			return
		}
		// newline-delimited JSON-RPC; a message may carry several lines
		lineBuf = append(lineBuf[:0], msg...)
		for len(lineBuf) > 0 {
			var line []byte
			if i := indexByte(lineBuf, '\n'); i >= 0 {
				line, lineBuf = lineBuf[:i], lineBuf[i+1:]
			} else {
				line, lineBuf = lineBuf, nil
			}
			if len(strings.TrimSpace(string(line))) == 0 {
				continue
			}
			var f frame
			if err := json.Unmarshal(line, &f); err != nil {
				continue
			}
			g.dispatchFrame(f)
		}
	}
}

func indexByte(b []byte, c byte) int {
	for i, x := range b {
		if x == c {
			return i
		}
	}
	return -1
}

func (g *gateway) dispatchFrame(f frame) {
	debugLog("recv", f)
	if _, hasMethod := f["method"]; hasMethod {
		// server→client request (srq-*) or event
		select {
		case g.frames <- f:
		case <-g.dead:
		}
		return
	}
	if id, ok := asInt(f["id"]); ok {
		g.pmu.Lock()
		ch, live := g.pending[id]
		if live {
			delete(g.pending, id)
		}
		g.pmu.Unlock()
		if live {
			ch <- f
		}
	}
}

func asInt(v any) (int, bool) {
	switch n := v.(type) {
	case float64:
		return int(n), true
	case int:
		return n, true
	}
	return 0, false
}

func (g *gateway) send(f frame) error {
	g.wmu.Lock()
	ws := g.ws
	g.wmu.Unlock()
	if ws == nil {
		return errWSClosed
	}
	b, err := json.Marshal(f)
	if err != nil {
		return err
	}
	debugLog("send", f)
	return ws.WriteText(b)
}

// call sends one RPC and waits for its answer.
func (g *gateway) call(method string, params map[string]any, timeout time.Duration) (frame, error) {
	g.pmu.Lock()
	g.seq++
	id := g.seq
	ch := make(chan frame, 1)
	g.pending[id] = ch
	g.pmu.Unlock()

	req := frame{"jsonrpc": "2.0", "id": id, "method": method, "params": params}
	if err := g.send(req); err != nil {
		g.pmu.Lock()
		delete(g.pending, id)
		g.pmu.Unlock()
		return nil, err
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case resp := <-ch:
		if e, has := resp["error"]; has {
			em, _ := e.(map[string]any)
			return nil, rpcError{code: asFloat(em["code"]), reason: asString(em["message"]),
				data: asMap(em["data"])}
		}
		return resp, nil
	case <-timer.C:
		g.pmu.Lock()
		delete(g.pending, id)
		g.pmu.Unlock()
		return nil, fmt.Errorf("%s: timed out after %s", method, timeout)
	case <-g.dead:
		return nil, fmt.Errorf("%s: gateway disconnected", method)
	}
}

func asFloat(v any) int {
	f, _ := v.(float64)
	return int(f)
}

func asString(v any) string {
	s, _ := v.(string)
	return s
}

func asMap(v any) map[string]any {
	m, _ := v.(map[string]any)
	return m
}

type rpcError struct {
	code   int
	reason string
	data   map[string]any
}

func (e rpcError) Error() string {
	if e.reason != "" && e.code != 0 {
		return fmt.Sprintf("%s (code %d)", e.reason, e.code)
	}
	if e.reason != "" {
		return e.reason
	}
	return fmt.Sprintf("rpc error (code %d)", e.code)
}

// heartbeat: gateway.ping every 15s; a 45s-silent socket is dead (the gateway
// answers within one send deadline).
func (g *gateway) heartbeat() {
	t := time.NewTicker(heartbeatEvery)
	defer t.Stop()
	for {
		select {
		case <-t.C:
			if time.Since(g.lastPong) > heartbeatDeadline {
				g.wmu.Lock()
				ws := g.ws
				g.wmu.Unlock()
				if ws != nil {
					_ = ws.Close()
				}
				return
			}
			if err := g.send(frame{"jsonrpc": "2.0", "id": -1, "method": "gateway.ping"}); err != nil {
				return
			}
		case <-g.hbStop:
			return
		}
	}
}

func (g *gateway) close() {
	close(g.hbStop)
	g.wmu.Lock()
	ws := g.ws
	g.wmu.Unlock()
	if ws != nil {
		_ = ws.Close()
	}
}

// ─── prompts (approvals, clarify, sudo, secret) ──────────────────────────────

type pendingPrompt struct {
	id          string // prompt_id (queue) or srq- id (server request)
	kind        string // "approval" | "clarify" | "value"
	viaQueue    bool   // answered with approval.respond / clarify.respond RPC
	sessionID   string
	generation  int
	choices     []string
	label       string   // what to show
	detail      []string // extra lines (description, …)
	secret      bool     // mask input
	srqMethod   string
	questions   []map[string]any // srq clarify batch
	qIndex      int              // which batch question is on screen
	batchANS    map[string]any   // answers locked so far
	multiSelect bool
}

// ─── app ─────────────────────────────────────────────────────────────────────

type app struct {
	gw      *gateway
	ren     *renderer
	raw     *termSaver
	isTTY   bool
	keys    chan keyEvent
	keyQuit chan struct{}

	sessionID      string
	subscriptionID string
	snapshotInfo   map[string]any

	prompts  []*pendingPrompt // FIFO of open prompts
	streamed strings.Builder  // assistant text streamed this turn
	pInTurn  bool             // an assistant reply is streaming
	usage    map[string]any

	history   []string // submitted lines, newest last
	histPos   int      // index into history while browsing (-1 = not browsing)
	histDraft []rune   // draft the user was typing before history navigation

	quit    bool
	cleaned bool
	quitMu  sync.Mutex
}

func newRandomID() string {
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

var debugFile *os.File

const defaultGatewayPort = "41841"

// gatewayURL builds the full ws URL from a bare host (optionally host:port).
func gatewayURL(hostSpec string) (string, error) {
	host, port := hostSpec, defaultGatewayPort
	if i := strings.IndexByte(hostSpec, ':'); i >= 0 {
		host = hostSpec[:i]
		port = hostSpec[i+1:]
	}
	if host == "" || port == "" {
		return "", fmt.Errorf("invalid -host %q (want name or name:port)", hostSpec)
	}
	return "ws://" + host + ":" + port + "/api/ws", nil
}

// percentEncodeToken escapes everything outside the URI query-safe set.
func percentEncodeToken(tok string) string {
	var b []byte
	for _, c := range []byte(tok) {
		keep := c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' ||
			c == '-' || c == '.' || c == '_' || c == ':'
		if keep {
			b = append(b, c)
		} else {
			b = append(b, []byte(fmt.Sprintf("%%%02X", c))...)
		}
	}
	return string(b)
}

// applyToken appends token=<tok> unless the URL already carries one (explicit
// URL token wins; a mismatch is reported on stderr, not fatal).
func applyToken(urlStr, tok string) string {
	if tok == "" {
		return urlStr
	}
	if i := strings.Index(urlStr, "token="); i >= 0 {
		end := len(urlStr)
		if j := strings.IndexByte(urlStr[i:], '&'); j >= 0 {
			end = i + j
		}
		if existing := urlStr[i+len("token=") : end]; existing != tok {
			fmt.Fprintf(os.Stderr, "hui: URL token=%s wins over -token %s\n", existing, tok)
		}
		return urlStr
	}
	sep := "?"
	if strings.Contains(urlStr, "?") {
		sep = "&"
	}
	return urlStr + sep + "token=" + percentEncodeToken(tok)
}

func debugLog(dir string, v any) {
	if debugFile == nil {
		return
	}
	b, _ := json.Marshal(v)
	fmt.Fprintf(debugFile, "%s %s %s\n", time.Now().Format("15:04:05.000"), dir, b)
}

func main() {
	url := flag.String("url", "", "WebSocket URL of the Hermes gateway (either -url or -host is required)")
	hostF := flag.String("host", "", "gateway host name, optionally host:port; the app fills in ws://<host>:<port>/api/ws with the default port (41841)")
	tokenF := flag.String("token", "", "gateway auth token for remote (non-loopback) connections; appended to the URL as ?token=")
	sessionID := flag.String("session", "", "resume this session id instead of creating a new one")
	debug := flag.String("debug", "", "log every wire frame to this file")
	flag.Parse()

	if *url == "" && *hostF == "" {
		fmt.Fprintln(os.Stderr, "usage: hui (-url ws://<host>:<port>/api/ws | -host <name>[:<port>]) [-token <t>] [-session <id>]")
		flag.PrintDefaults()
		os.Exit(2)
	}
	if *url != "" && *hostF != "" {
		fmt.Fprintln(os.Stderr, "hui: -url and -host are mutually exclusive")
		os.Exit(2)
	}
	target := *url
	if *hostF != "" {
		built, err := gatewayURL(*hostF)
		if err != nil {
			fmt.Fprintln(os.Stderr, "hui: "+err.Error())
			os.Exit(2)
		}
		target = built
	}
	target = applyToken(target, *tokenF)
	if *debug != "" {
		f, err := os.OpenFile(*debug, os.O_CREATE|os.O_WRONLY|os.O_APPEND|os.O_TRUNC, 0o600)
		if err == nil {
			debugFile = f
		}
	}

	a := &app{isTTY: isTTY(os.Stdin.Fd())}
	if a.isTTY {
		saver, err := makeRaw(os.Stdin.Fd())
		if err == nil {
			a.raw = saver
			defer saver.restore()
		} else {
			a.isTTY = false
		}
	}
	if a.isTTY {
		// kitty keyboard protocol: Shift+Enter becomes distinguishable from
		// Enter. On Windows also ask for win32-input-mode, which encodes
		// modifiers the same way for terminals without kitty support.
		// (Both are popped in renderer.close, which runs on every exit path.)
		fmt.Print("\x1b[>1u")
		if runtime.GOOS == "windows" {
			fmt.Print("\x1b[?9001h")
		}
	}

	a.ren = newRenderer()
	a.keys = make(chan keyEvent, 128)
	if a.isTTY {
		go readKeys(os.Stdin, a.keys)
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sig
		a.shutdown(false)
	}()

	if err := a.run(target, *sessionID); err != nil {
		a.ren.eraseDraft()
		a.ren.printBlock(ansiRed+"hui: "+err.Error()+ansiReset, false)
		a.ren.flush()
		if a.gw != nil {
			a.gw.close()
		}
		a.restoreTerm()
		os.Exit(1)
	}
}

func (a *app) restoreTerm() {
	if a.raw != nil {
		a.raw.restore()
		a.raw = nil
	}
	if a.ren != nil {
		a.ren.close()
	}
}

func (a *app) run(url, resumeID string) error {
	if err := a.attach(url, resumeID, true); err != nil {
		return err
	}
	a.loop()
	if a.gw != nil {
		a.gw.close()
	}
	return nil
}

// attach connects, waits for gateway.ready and opens (or resumes) a session.
func (a *app) attach(url, resumeID string, renderHistory bool) error {
	g := newGateway(url)
	a.gw = g
	if err := g.connect(); err != nil {
		return fmt.Errorf("connect: %w", err)
	}

	// gateway.ready arrives right after accept
	ready := false
	deadline := time.After(readyTimeout)
	for !ready {
		select {
		case f := <-g.frames:
			if f.method() == "event" && paramsOf(f)["type"] == "gateway.ready" {
				ready = true
			}
		case <-deadline:
			return fmt.Errorf("no gateway.ready from %s", url)
		case <-g.dead:
			return fmt.Errorf("connection closed while waiting for gateway.ready")
		}
	}
	if renderHistory {
		a.ren.printBlock(ansiDim+"connected "+url+ansiReset, false)
	}

	var snap map[string]any
	var err error
	if resumeID != "" || a.sessionID != "" {
		id := resumeID
		if id == "" {
			id = a.sessionID
		}
		snap, err = a.rpcSession("session.resume", map[string]any{
			"session_id": id, "display_projection": renderHistory})
		if err != nil {
			if a.sessionID == "" {
				return fmt.Errorf("resume %s: %w", id, err)
			}
			// reconnect path: the session may be gone; do not silently create a new one
			return fmt.Errorf("reattach to session %s failed: %v — session id kept for manual resume", id, err)
		}
	} else {
		snap, err = a.rpcSession("session.create", map[string]any{
			"request_id": newRandomID(), "source": "cli"})
		if err != nil {
			return fmt.Errorf("session.create: %w", err)
		}
	}
	a.sessionID, _ = snap["session_id"].(string)
	a.subscriptionID, _ = snap["subscription_id"].(string)
	if info, ok := snap["info"].(map[string]any); ok {
		a.snapshotInfo = info
	}

	if renderHistory {
		a.renderHistory(snap)
		label := "new session"
		if resumeID != "" {
			label = "resumed " + a.sessionID
		}
		a.ren.dimf("%s · %s · type /help for commands", label, a.sessionID)
		a.ren.setPromptLabel(promptGlyph + " ")
	}

	// restore any prompts that were pending before we attached
	for _, p := range asSlice(snap["prompts"]) {
		a.addQueuePrompt(asMap(p))
	}
	return nil
}

func asSlice(v any) []map[string]any {
	list, _ := v.([]any)
	out := make([]map[string]any, 0, len(list))
	for _, item := range list {
		if m, ok := item.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

func (a *app) rpcSession(method string, params map[string]any) (map[string]any, error) {
	resp, err := a.gw.call(method, params, attachRPCTimeout)
	if err != nil {
		return nil, err
	}
	res, _ := resp["result"].(map[string]any)
	if res == nil {
		res = map[string]any{}
	}
	return res, nil
}

// ─── transcript rendering ────────────────────────────────────────────────────

func (a *app) renderHistory(snap map[string]any) {
	rows := asSlice(snap["display_messages"])
	if len(rows) == 0 {
		rows = asSlice(snap["messages"])
	}
	for _, row := range rows {
		role := pstr(row, "role")
		text := pstr(row, "text")
		name := pstr(row, "name")
		switch {
		case role == "user":
			if text != "" {
				a.ren.printBlock(ansiBold+"› "+text+ansiReset, false)
			}
		case role == "assistant":
			if text != "" {
				a.ren.printBlock(text, false)
			}
		case name != "":
			summary := pstr(row, "context")
			a.ren.dimf("  · %s %s", name, summary)
		}
	}
}

// ─── event handling ──────────────────────────────────────────────────────────

func (a *app) handleEvent(f frame) {
	params := paramsOf(f)
	evType, _ := params["type"].(string)
	payload := payloadOf(f)
	switch evType {
	case "gateway.ready", "skin.changed":
		// handled/ignored
	case "message.delta":
		text, has := payload["text"]
		s, isStr := text.(string)
		if has && (!isStr || s == "") {
			// segment boundary: the reply box closes
			if a.pInTurn {
				a.ren.printBlock("", false)
				a.pInTurn = false
			}
			return
		}
		if s == "" {
			return
		}
		if !a.pInTurn {
			a.pInTurn = true
			a.streamed.Reset()
		}
		a.streamed.WriteString(s)
		a.ren.eraseDraft()
		a.ren.writeChunk(s)
		a.ren.drawPromptIfDraft()
	case "message.complete":
		a.finishTurn(payload)
	case "reasoning.delta", "thinking.delta", "reasoning.available",
		"tool.start", "tool.generating", "tool.progress", "tool.output_risk",
		"message.start", "message.interim", "session.usage":
		// silent by design
	case "tool.complete":
		name := pstr(payload, "name")
		summary := pstr(payload, "summary")
		dur := ""
		if d, ok := pnum(payload, "duration_s"); ok && d > 0 {
			dur = fmt.Sprintf(" (%.1fs)", d)
		}
		if summary == "" {
			if rt := pstr(payload, "result_text"); rt != "" {
				summary = firstLine(rt)
			}
		}
		a.ren.dimf("  · %s %s%s", name, oneSpace(summary), dur)
	case "status.update":
		a.ren.dimf("· %s", pstr(payload, "text"))
	case "error":
		msg := pstr(payload, "text")
		if msg == "" {
			msg = pstr(payload, "message")
		}
		if msg == "" {
			msg = pstr(payload, "error")
		}
		a.ren.printBlock(ansiRed+"! "+msg+ansiReset, false)
	case "notice":
		a.ren.dimf("· %s", pstr(payload, "text"))
	case "session.title":
		a.ren.dimf("· session titled: %s", pstr(payload, "title"))
	case "approval.request":
		a.addQueuePrompt(payload)
	case "clarify.request":
		a.addQueuePrompt(payload)
	case "approval.settled", "clarify.settled":
		id := pstr(payload, "prompt_id")
		a.removePrompt(id)
	case "request.cancel":
		a.removePrompt(pstr(payload, "id"))
	case "session.replay_gap":
		a.ren.dimf("! event gap detected; some output may be missing (reconnect to resync)")
	case "session.reclaimed":
		a.ren.printBlock(ansiYellow+"! the gateway reclaimed this session"+ansiReset, false)
	default:
		debugLog("event-ignored", f)
	}
}

func oneSpace(s string) string {
	if s == "" {
		return ""
	}
	return "— " + s
}

func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return s[:i]
	}
	return s
}

func (a *app) finishTurn(payload map[string]any) {
	a.pInTurn = false
	final := pstr(payload, "text")
	reused, _ := payload["response_reused"].(bool)
	if final != "" && !reused && final != a.streamed.String() {
		if a.streamed.Len() > 0 {
			a.ren.printBlock("", false)
		}
		a.ren.printBlock(final, false)
	}
	if warn := pstr(payload, "warning"); warn != "" {
		a.ren.printBlock(ansiYellow+"! "+warn+ansiReset, false)
	}
	switch status := pstr(payload, "status"); status {
	case "", "ok", "complete", "completed", "done":
	default:
		a.ren.printBlock(ansiYellow+"· turn ended: "+status+ansiReset, false)
	}
	if usage, ok := payload["usage"].(map[string]any); ok && len(usage) > 0 {
		a.usage = usage
		in, out := asFloat(usage["input"]), asFloat(usage["output"])
		if in+out > 0 {
			a.ren.dimf("  · %d in / %d out", in, out)
		}
	}
	a.streamed.Reset()
	// the 𖦏 prompt redraws as part of the next drawPrompt
}

// ─── prompts ─────────────────────────────────────────────────────────────────

func (a *app) addQueuePrompt(p map[string]any) {
	kind := pstr(p, "kind")
	if kind == "" {
		return
	}
	gen := 0
	if g, ok := pnum(p, "execution_generation"); ok {
		gen = int(g)
	}
	pp := &pendingPrompt{
		id: pstr(p, "prompt_id"), kind: kind, viaQueue: true,
		sessionID: a.sessionID, generation: gen,
		choices:     asStrings(p["choices"]),
		multiSelect: false,
	}
	switch kind {
	case "approval":
		pp.label = "approval requested"
		pp.detail = append(pp.detail, pstr(p, "command"), pstr(p, "description"))
	case "clarify":
		pp.label = pstr(p, "question")
	}
	a.showPrompt(pp)
}

func asStrings(v any) []string {
	list, _ := v.([]any)
	out := make([]string, 0, len(list))
	for _, item := range list {
		if s, ok := item.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

func (a *app) addSRQPrompt(f frame) {
	method := f.method()
	params := paramsOf(f)
	id := f.str("id")
	pp := &pendingPrompt{id: id, kind: "value", srqMethod: method}
	switch method {
	case "approval":
		pp.kind = "approval"
		pp.label = "approval requested"
		pp.detail = append(pp.detail, pstr(params, "command"), pstr(params, "description"))
		pp.choices = asStrings(params["choices"])
		if len(pp.choices) == 0 {
			pp.choices = []string{"once", "deny"}
		}
	case "clarify":
		pp.kind = "clarify"
		pp.questions = asSlice(params["questions"])
		pp.batchANS = map[string]any{}
		if len(pp.questions) > 0 {
			q := pp.questions[0]
			pp.label = pstr(q, "question")
			pp.choices = asStrings(q["choices"])
			pp.multiSelect, _ = q["multi_select"].(bool)
		}
	case "sudo":
		pp.label = "sudo password"
		pp.secret = true
		pp.detail = append(pp.detail, pstr(params, "command"))
	case "secret":
		pp.label = pstr(params, "prompt")
		if pp.label == "" {
			pp.label = "value for " + pstr(params, "env_var")
		}
		pp.secret = true
	case "vault.unlock_prompt", "vault.code":
		pp.label = pstr(params, "display_name")
		if pp.label == "" {
			pp.label = method
		}
		pp.secret = method == "vault.unlock_prompt"
	default:
		// unsupported server request: answer with a JSON-RPC error so the
		// asker's timeout path resolves instead of blocking a turn
		a.answerSRQ(id, frame{"jsonrpc": "2.0", "id": id,
			"error": map[string]any{"code": -32601, "message": "not supported by this client"}})
		return
	}
	a.showPrompt(pp)
}

func (a *app) showPrompt(pp *pendingPrompt) {
	a.prompts = append(a.prompts, pp)
	if len(a.prompts) > 1 {
		return // it will be shown when its turn comes
	}
	a.renderPrompt(pp)
}

func (a *app) renderPrompt(pp *pendingPrompt) {
	a.ren.eraseDraft()
	var b strings.Builder
	header := pp.kind
	if header == "value" && pp.srqMethod != "" {
		header = pp.srqMethod
	}
	fmt.Fprintf(&b, "%s── %s %s", ansiCyan, header, strings.Repeat("─", 8))
	detail := ""
	for _, d := range pp.detail {
		if strings.TrimSpace(d) != "" {
			detail = d
			break
		}
	}
	if detail != "" {
		fmt.Fprintf(&b, "\n%s", detail)
	}
	if pp.label != "" && pp.label != detail {
		fmt.Fprintf(&b, "\n%s", pp.label)
	}
	if pp.kind == "approval" {
		if len(pp.choices) == 0 {
			pp.choices = []string{"once", "deny"}
		}
		fmt.Fprintf(&b, "\n")
		for i, c := range pp.choices {
			fmt.Fprintf(&b, "[%d] %s  ", i+1, c)
		}
		fmt.Fprintf(&b, "\n(1-%d or the choice name, Enter to skip)", len(pp.choices))
	} else if pp.kind == "clarify" {
		if len(pp.choices) > 0 {
			fmt.Fprintf(&b, "\n")
			for i, c := range pp.choices {
				fmt.Fprintf(&b, "[%d] %s  ", i+1, c)
			}
		}
		fmt.Fprintf(&b, "\n(type an answer%s; Enter to skip)", choiceHint(pp))
	} else {
		fmt.Fprintf(&b, "\n(type it here; Enter to skip)")
	}
	a.ren.printBlock(b.String(), false)
	a.ren.echo = !pp.secret
	a.ren.setPromptLabel(promptGlyph + " ")
}

func choiceHint(pp *pendingPrompt) string {
	if len(pp.choices) > 0 {
		return ", a choice number, or a choice name"
	}
	return ""
}

func (a *app) removePrompt(id string) {
	if id == "" {
		return
	}
	for i, pp := range a.prompts {
		if pp.id == id {
			a.prompts = append(a.prompts[:i], a.prompts[i+1:]...)
			break
		}
	}
	if len(a.prompts) > 0 {
		a.renderPrompt(a.prompts[0])
	} else {
		a.ren.echo = true
		a.ren.setPromptLabel(promptGlyph + " ")
	}
}

// answerCurrent submits the user's draft as the answer to the first open prompt.
func (a *app) answerCurrent(text string) {
	if len(a.prompts) == 0 {
		return
	}
	pp := a.prompts[0]
	a.ren.echo = true
	switch pp.kind {
	case "approval":
		choice := mapApprovalInput(text, pp.choices)
		if choice == "" {
			a.ren.dimf("(skipped)")
			a.removePrompt(pp.id)
			return
		}
		if pp.viaQueue {
			_, err := a.rpcSession("approval.respond", map[string]any{
				"session_id": pp.sessionID, "execution_generation": pp.generation,
				"prompt_id": pp.id, "choice": choice})
			if err != nil {
				a.ren.printBlock(ansiRed+"! approval failed: "+err.Error()+ansiReset, false)
			} else {
				a.ren.dimf("✓ %s", choice)
			}
		} else {
			a.answerSRQ(pp.id, frame{"jsonrpc": "2.0", "id": pp.id,
				"result": map[string]any{"choice": choice}})
			a.ren.dimf("✓ %s", choice)
		}
		a.removePrompt(pp.id)
	case "clarify":
		ans := mapClarifyInput(text, pp.choices)
		if pp.viaQueue {
			_, err := a.rpcSession("clarify.respond", map[string]any{
				"session_id": pp.sessionID, "execution_generation": pp.generation,
				"prompt_id": pp.id, "answer": ans})
			if err != nil {
				a.ren.printBlock(ansiRed+"! answer failed: "+err.Error()+ansiReset, false)
			} else {
				a.ren.dimf("✓ answered")
			}
			a.removePrompt(pp.id)
			return
		}
		// server request: lock one answer per question of the batch
		if q := questionAt(pp, pp.qIndex); q != nil {
			if ans == "" {
				pp.batchANS[pstr(q, "qid")] = nil
			} else {
				pp.batchANS[pstr(q, "qid")] = ans
			}
			a.ren.dimf("✓ %s", firstLine(ans))
		}
		pp.qIndex++
		if q := questionAt(pp, pp.qIndex); q != nil {
			pp.label = pstr(q, "question")
			pp.choices = asStrings(q["choices"])
			pp.multiSelect, _ = q["multi_select"].(bool)
			a.renderPrompt(pp)
			return
		}
		a.answerSRQ(pp.id, frame{"jsonrpc": "2.0", "id": pp.id,
			"result": map[string]any{"answers": pp.batchANS}})
		a.removePrompt(pp.id)
	default: // value (sudo / secret / vault)
		if pp.viaQueue {
			a.ren.printBlock(ansiRed+"! unsupported queue prompt"+ansiReset, false)
		} else {
			a.answerSRQ(pp.id, frame{"jsonrpc": "2.0", "id": pp.id,
				"result": map[string]any{"value": text}})
			a.ren.dimf("✓ sent")
		}
		a.removePrompt(pp.id)
	}
}

func questionAt(pp *pendingPrompt, i int) map[string]any {
	if i < 0 || i >= len(pp.questions) {
		return nil
	}
	return pp.questions[i]
}

func mapApprovalInput(text string, choices []string) string {
	t := strings.ToLower(strings.TrimSpace(text))
	if t == "" {
		return ""
	}
	if n := atoiSafe(t); n >= 1 && n <= len(choices) {
		return choices[n-1]
	}
	switch t {
	case "y", "yes":
		return "once"
	case "n", "no":
		return "deny"
	}
	for _, c := range choices {
		if t == strings.ToLower(c) {
			return c
		}
	}
	return t // let the server refuse an unknown choice explicitly
}

func mapClarifyInput(text string, choices []string) string {
	t := strings.TrimSpace(text)
	if t == "" {
		return ""
	}
	if n := atoiSafe(t); n >= 1 && n <= len(choices) {
		return choices[n-1]
	}
	for _, c := range choices {
		if strings.EqualFold(t, c) {
			return c
		}
	}
	return t
}

func (a *app) answerSRQ(id string, resp frame) {
	if err := a.gw.send(resp); err != nil {
		debugLog("srq-send-failed", resp)
	}
}

// ─── main loop ───────────────────────────────────────────────────────────────

func (a *app) loop() {
	draft := &a.ren.draft
	cursor := &a.ren.cursor
	pipeLines := make(chan string)
	if !a.isTTY {
		go func() {
			sc := bufio.NewScanner(os.Stdin)
			sc.Buffer(make([]byte, 1<<20), 1<<20)
			for sc.Scan() {
				pipeLines <- sc.Text()
			}
			close(pipeLines)
		}()
	}

	for {
		select {
		case f := <-a.gw.frames:
			if _, isResp := f["result"]; isResp {
				continue // heartbeat acks
			}
			if _, isErr := f["error"]; isErr && f.method() == "" {
				continue
			}
			if f.method() == "event" {
				a.handleEvent(f)
			} else if f.id() != nil && strings.HasPrefix(f.str("id"), "srq-") {
				a.addSRQPrompt(f)
			}
			a.ren.flush()

		case ev, ok := <-a.keys:
			if !ok {
				a.shutdown(false)
				return
			}
			if a.handleKey(ev, draft, cursor) {
				a.ren.flush()
			}

		case line, ok := <-pipeLines:
			if !ok {
				a.shutdown(false)
				return
			}
			a.submitLine(line)
			a.ren.flush()

		case <-a.gw.dead:
			a.reconnect()
			if a.quit {
				return
			}
		}
		if a.quit {
			a.shutdown(true)
			return
		}
	}
}

// handleKey edits the draft; returns true when the screen changed.
func (a *app) handleKey(ev keyEvent, draft *[]rune, cursor *int) bool {
	r := a.ren
	erase := func() { r.eraseDraft() }
	switch ev.kind {
	case keyCtrlC, keyCtrlD:
		a.quit = true
		return false
	case keyCtrlU:
		*draft = (*draft)[:0]
		*cursor = 0
		erase()
		r.drawPrompt()
		return true
	case keyCtrlW:
		i := *cursor
		for i > 0 && isSpace((*draft)[i-1]) {
			i--
		}
		for i > 0 && !isSpace((*draft)[i-1]) {
			i--
		}
		*draft = append((*draft)[:i], (*draft)[*cursor:]...)
		*cursor = i
		erase()
		r.drawPrompt()
		return true
	case keyCtrlA:
		*cursor = 0
		erase()
		r.drawPrompt()
		return true
	case keyCtrlE:
		*cursor = len(*draft)
		erase()
		r.drawPrompt()
		return true
	case keyBackspace:
		if *cursor > 0 {
			*draft = append((*draft)[:*cursor-1], (*draft)[*cursor:]...)
			*cursor--
			erase()
			r.drawPrompt()
			return true
		}
		return false
	case keyDelete:
		if *cursor < len(*draft) {
			*draft = append((*draft)[:*cursor], (*draft)[*cursor+1:]...)
			erase()
			r.drawPrompt()
			return true
		}
		return false
	case keyLeft:
		if *cursor > 0 {
			*cursor--
			erase()
			r.drawPrompt()
			return true
		}
		return false
	case keyRight:
		if *cursor < len(*draft) {
			*cursor++
			erase()
			r.drawPrompt()
			return true
		}
		return false
	case keyHome:
		*cursor = 0
		erase()
		r.drawPrompt()
		return true
	case keyEnd:
		*cursor = len(*draft)
		erase()
		r.drawPrompt()
		return true
	case keyEnter:
		text := string(*draft)
		*draft = (*draft)[:0]
		*cursor = 0
		erase()
		if len(a.prompts) > 0 {
			a.answerCurrent(text)
			r.drawPrompt()
			return true
		}
		if strings.TrimSpace(text) != "" {
			a.history = append(a.history, text)
			if len(a.history) > 100 { // cap stored history
				a.history = a.history[len(a.history)-100:]
			}
		}
		a.histPos = -1
		a.submitLine(text)
		r.drawPrompt()
		return true
	case keyShiftEnter, keyAltEnter:
		*draft = append((*draft)[:*cursor], append([]rune{'\n'}, (*draft)[*cursor:]...)...)
		*cursor++
		erase()
		r.drawPrompt()
		return true
	case keyUp:
		if len(a.history) == 0 {
			return false
		}
		if a.histPos == -1 {
			a.histDraft = append([]rune{}, (*draft)...) // save current draft
			a.histPos = len(a.history) - 1
		} else if a.histPos > 0 {
			a.histPos--
		} else {
			return false // already at the oldest entry
		}
		text := []rune(a.history[a.histPos])
		*draft = text
		*cursor = len(text)
		erase()
		r.drawPrompt()
		return true
	case keyDown:
		if a.histPos == -1 {
			return false
		}
		if a.histPos < len(a.history)-1 {
			a.histPos++
			text := []rune(a.history[a.histPos])
			*draft = text
			*cursor = len(text)
		} else {
			// leave history mode and restore the draft being typed
			a.histPos = -1
			*draft = append([]rune{}, a.histDraft...)
			*cursor = len(*draft)
		}
		erase()
		r.drawPrompt()
		return true
	case keyRune:
		*draft = append((*draft)[:*cursor], append([]rune{ev.r}, (*draft)[*cursor:]...)...)
		*cursor++
		erase()
		r.drawPrompt()
		return true
	}
	return false
}

func isSpace(r rune) bool { return r == ' ' || r == '\t' }

// submitLine handles one submitted line (draft or pipe mode).
func (a *app) submitLine(line string) {
	if len(a.prompts) > 0 {
		a.answerCurrent(line)
		return
	}
	text := strings.TrimRight(line, " \t")
	if text == "" {
		return
	}
	switch text {
	case "/new", "/reset":
		a.newSession()
		return
	case "/status":
		a.printStatus()
		return
	case "/help":
		a.printHelp()
		return
	case "/exit", "/quit":
		a.quit = true
		return
	}
	if strings.HasPrefix(text, "/") {
		// unknown slash command: still shown to the agent verbatim
		a.ren.dimf("(unknown client command — sending to the agent)")
	}
	a.sendPrompt(text)
}

func (a *app) sendPrompt(text string) {
	a.ren.printBlock(ansiBold+"› "+strings.ReplaceAll(text, "\n", " ⏎ ")+ansiReset, false)
	a.streamed.Reset()
	// fire-and-forget: prompt.submit's receipt carries nothing the UI uses
	// (the reply streams in as events), and waiting for it would block the
	// render/key loop on a slow gateway — that freeze made every keypress
	// appear delayed and Ctrl+C dead.
	f := frame{
		"jsonrpc": "2.0",
		"id":      newRandomID(),
		"method":  "prompt.submit",
		"params": map[string]any{
			"session_id":    a.sessionID,
			"text":          text,
			"submission_id": newRandomID(),
			"queued":        true,
		},
	}
	if err := a.gw.send(f); err != nil {
		a.ren.printBlock(ansiRed+"! submit failed: "+err.Error()+ansiReset, false)
	}
}

func (a *app) newSession() {
	a.detach("new_session")
	a.prompts = nil
	a.sessionID = ""
	a.ren.dimf("── new session ──")
	if err := a.attach(a.gw.url, "", true); err != nil {
		a.ren.printBlock(ansiRed+"! "+err.Error()+ansiReset, false)
		a.quit = true
	}
}

func (a *app) detach(endReason string) {
	if a.sessionID == "" || a.subscriptionID == "" || a.gw == nil {
		return
	}
	params := map[string]any{
		"session_id":      a.sessionID,
		"subscription_id": a.subscriptionID,
		"end_reason":      endReason,
	}
	_, _ = a.gw.call("session.detach", params, 5*time.Second)
}

func (a *app) printStatus() {
	desc, err := a.rpcSession("runtime.describe", map[string]any{})
	a.ren.printBlock(ansiCyan+"── hui "+buildStamp+" ──"+ansiReset, false)
	a.ren.dimf("gateway  %s", a.gw.url)
	a.ren.dimf("session  %s", a.sessionID)
	if info := a.snapshotInfo; info != nil {
		if m := pstr(info, "model"); m != "" {
			a.ren.dimf("model    %s", m)
		}
		if pn := pstr(info, "profile_name"); pn != "" {
			a.ren.dimf("profile  %s", pn)
		}
	}
	if err != nil {
		a.ren.dimf("runtime  describe failed: %v", err)
		return
	}
	if caps := asStrings(desc["capabilities"]); len(caps) > 0 {
		a.ren.dimf("caps     %s", strings.Join(caps, ", "))
	}
	if inst := pstr(desc, "instance_id"); inst != "" {
		a.ren.dimf("instance %s", inst)
	}
}

func (a *app) printHelp() {
	a.ren.printBlock(ansiCyan+"── hui commands ──"+ansiReset, false)
	a.ren.dimf("Enter        send the message")
	a.ren.dimf("Shift+Enter  newline in the draft")
	a.ren.dimf("/new /reset  close this session and start a fresh one")
	a.ren.dimf("/status      gateway + session info")
	a.ren.dimf("/help        this list")
	a.ren.dimf("/exit        quit")
	a.ren.dimf("Ctrl+C       quit")
	a.ren.dimf("Approvals: when the agent asks, type the choice number + Enter.")
}

// ─── reconnect & shutdown ────────────────────────────────────────────────────

func (a *app) reconnect() {
	a.ren.printBlock(ansiYellow+"! gateway connection lost; reconnecting…"+ansiReset, false)
	url := a.gw.url
	a.gw.close()
	for attempt := 1; attempt <= reconnectAttempts; attempt++ {
		time.Sleep(reconnectBackoff)
		if a.quit {
			return
		}
		err := a.attach(url, "", false)
		if err == nil {
			a.ren.dimf("· reconnected to %s", url)
			return
		}
		if strings.Contains(err.Error(), "reattach to session") {
			a.ren.printBlock(ansiRed+"! "+err.Error()+ansiReset, false)
			a.quit = true
			return
		}
		a.ren.dimf("! reconnect %d/%d failed: %v", attempt, reconnectAttempts, err)
	}
	a.ren.printBlock(ansiRed+"! gave up reconnecting"+ansiReset, false)
	a.quit = true
}

// shutdown detaches the session, restores the terminal and exits. force=false
// means we are on the signal goroutine: clean up and exit the process here.
// force=true means the main loop asked: clean up once and return to it.
func (a *app) shutdown(force bool) {
	a.quitMu.Lock()
	a.quit = true
	if a.cleaned {
		a.quitMu.Unlock()
		if force {
			return
		}
		// a second signal while the first cleanup is running: force exit
		a.restoreTerm()
		os.Exit(130)
	}
	a.cleaned = true
	a.quitMu.Unlock()

	if a.gw != nil {
		a.detach("cli_close")
	}
	a.ren.eraseDraft()
	a.ren.printBlock(ansiDim+"bye"+ansiReset, false)
	a.restoreTerm()
	if !force {
		os.Exit(0)
	}
}
