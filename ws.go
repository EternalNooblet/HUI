package main

// Minimal RFC 6455 WebSocket client (text frames). No external dependencies:
// ws:// dials plain TCP, wss:// wraps TLS. Client frames are always masked.

import (
	"bufio"
	"crypto/rand"
	"crypto/sha1" //nolint:gosec // RFC 6455 requires SHA-1 for the handshake accept key
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"strings"
	"sync"
	"time"
)

const wsGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

const wsMaxMessage = 16 << 20 // 16 MiB, matching the gateway's own receive cap

var errWSClosed = errors.New("websocket closed")

type WSConn struct {
	conn net.Conn
	br   *bufio.Reader
	wmu  sync.Mutex

	// reassembly of fragmented messages
	fragOpCode byte
	fragBuf    []byte
}

func wsDial(rawURL string, subprotocols []string, timeout time.Duration) (*WSConn, error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return nil, fmt.Errorf("invalid url: %w", err)
	}
	var secure bool
	switch u.Scheme {
	case "ws":
	case "wss":
		secure = true
	default:
		return nil, fmt.Errorf("unsupported scheme %q (want ws:// or wss://)", u.Scheme)
	}
	host := u.Host
	if _, _, err := net.SplitHostPort(host); err != nil {
		if secure {
			host = net.JoinHostPort(host, "443")
		} else {
			host = net.JoinHostPort(host, "80")
		}
	}
	dialer := &net.Dialer{Timeout: timeout}
	var conn net.Conn
	if secure {
		conn, err = tls.DialWithDialer(dialer, "tcp", host, &tls.Config{ServerName: u.Hostname()})
	} else {
		conn, err = dialer.Dial("tcp", host)
	}
	if err != nil {
		return nil, err
	}
	ws := &WSConn{conn: conn, br: bufio.NewReaderSize(conn, 1<<16)}

	keyBytes := make([]byte, 16)
	if _, err := rand.Read(keyBytes); err != nil {
		conn.Close()
		return nil, err
	}
	key := base64.StdEncoding.EncodeToString(keyBytes)

	path := u.Path
	if path == "" {
		path = "/"
	}
	if u.RawQuery != "" {
		path += "?" + u.RawQuery
	}
	req := "GET " + path + " HTTP/1.1\r\n" +
		"Host: " + u.Host + "\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Key: " + key + "\r\n" +
		"Sec-WebSocket-Version: 13\r\n"
	if len(subprotocols) > 0 {
		req += "Sec-WebSocket-Protocol: " + strings.Join(subprotocols, ", ") + "\r\n"
	}
	req += "\r\n"
	if _, err := conn.Write([]byte(req)); err != nil {
		conn.Close()
		return nil, fmt.Errorf("handshake write: %w", err)
	}

	statusCode, statusText, headers, err := readHandshakeResponse(ws.br)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("handshake read: %w", err)
	}
	if statusCode != 101 {
		conn.Close()
		return nil, fmt.Errorf("websocket upgrade refused: HTTP %d (%s)", statusCode, statusText)
	}
	accept := base64.StdEncoding.EncodeToString(hashSHA1([]byte(key + wsGUID)))
	if headers.Get("sec-websocket-accept") != accept {
		conn.Close()
		return nil, errors.New("websocket handshake: bad Sec-WebSocket-Accept")
	}
	return ws, nil
}

func hashSHA1(b []byte) []byte {
	h := sha1.New() //nolint:gosec
	h.Write(b)
	return h.Sum(nil)
}

// readHandshakeResponse reads one HTTP/1.x response head (status line + headers).
func readHandshakeResponse(br *bufio.Reader) (int, string, textHeaders, error) {
	statusLine, err := readHTTPLine(br)
	if err != nil {
		return 0, "", nil, err
	}
	parts := strings.SplitN(statusLine, " ", 3)
	code := 0
	if len(parts) >= 2 {
		code = atoiSafe(parts[1])
	}
	text := statusLine
	if len(parts) == 3 {
		text = parts[2]
	}
	h := textHeaders{}
	for {
		line, err := readHTTPLine(br)
		if err != nil {
			return 0, "", nil, err
		}
		if line == "" {
			break
		}
		if i := strings.IndexByte(line, ':'); i > 0 {
			h.add(strings.ToLower(strings.TrimSpace(line[:i])), strings.TrimSpace(line[i+1:]))
		}
	}
	return code, text, h, nil
}

func readHTTPLine(br *bufio.Reader) (string, error) {
	line, err := br.ReadString('\n')
	if err != nil {
		return "", err
	}
	return strings.TrimRight(line, "\r\n"), nil
}

func atoiSafe(s string) int {
	n := 0
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0
		}
		n = n*10 + int(c-'0')
	}
	return n
}

type textHeaders map[string][]string

func (h textHeaders) add(k, v string)    { h[k] = append(h[k], v) }
func (h textHeaders) Get(k string) string {
	if len(h[k]) == 0 {
		return ""
	}
	return h[k][0]
}

// ReadMessage reads one complete text (or binary) message, reassembling
// fragments; it answers pings itself and surfaces close frames as errors.
func (ws *WSConn) ReadMessage() ([]byte, error) {
	var payload []byte
	msgOp := byte(0)
	for {
		fin, op, data, err := ws.readFrame()
		if err != nil {
			return nil, err
		}
		switch op {
		case opPing:
			_ = ws.writeFrame(opPong, data)
			continue
		case opPong:
			continue
		case opClose:
			_ = ws.writeFrame(opClose, nil)
			return nil, errWSClosed
		case opText, opBinary:
			if !fin {
				ws.fragOpCode, ws.fragBuf = op, append([]byte(nil), data...)
				continue
			}
			payload, msgOp = data, op
		case opCont:
			if ws.fragBuf == nil {
				return nil, errors.New("websocket: continuation without start")
			}
			ws.fragBuf = append(ws.fragBuf, data...)
			if len(ws.fragBuf) > wsMaxMessage {
				return nil, errors.New("websocket: message too large")
			}
			if !fin {
				continue
			}
			payload, msgOp = ws.fragBuf, ws.fragOpCode
			ws.fragBuf, ws.fragOpCode = nil, 0
		default:
			return nil, fmt.Errorf("websocket: unexpected opcode %d", op)
		}
		if msgOp == opBinary {
			// The gateway speaks text-only JSON; keep binary frames out of the JSON path.
			return nil, errors.New("websocket: unexpected binary message")
		}
		if len(payload) > wsMaxMessage {
			return nil, errors.New("websocket: message too large")
		}
		return payload, nil
	}
}

func (ws *WSConn) readFrame() (fin bool, op byte, payload []byte, err error) {
	var head [2]byte
	if _, err = io.ReadFull(ws.br, head[:]); err != nil {
		return false, 0, nil, err
	}
	fin = head[0]&0x80 != 0
	op = head[0] & 0x0f
	masked := head[1]&0x80 != 0
	length := uint64(head[1] & 0x7f)
	switch length {
	case 126:
		var ext [2]byte
		if _, err = io.ReadFull(ws.br, ext[:]); err != nil {
			return false, 0, nil, err
		}
		length = uint64(binary.BigEndian.Uint16(ext[:]))
	case 127:
		var ext [8]byte
		if _, err = io.ReadFull(ws.br, ext[:]); err != nil {
			return false, 0, nil, err
		}
		length = binary.BigEndian.Uint64(ext[:])
	}
	if length > wsMaxMessage {
		return false, 0, nil, errors.New("websocket: frame too large")
	}
	var maskKey [4]byte
	if masked {
		if _, err = io.ReadFull(ws.br, maskKey[:]); err != nil {
			return false, 0, nil, err
		}
	}
	payload = make([]byte, length)
	if _, err = io.ReadFull(ws.br, payload); err != nil {
		return false, 0, nil, err
	}
	if masked {
		for i := range payload {
			payload[i] ^= maskKey[i%4]
		}
	}
	return fin, op, payload, nil
}

const (
	opCont  = 0x0
	opText  = 0x1
	opBinary = 0x2
	opClose = 0x8
	opPing  = 0x9
	opPong  = 0xA
)

func (ws *WSConn) writeFrame(op byte, payload []byte) error {
	ws.wmu.Lock()
	defer ws.wmu.Unlock()
	if ws.conn == nil {
		return errWSClosed
	}
	var mask [4]byte
	if _, err := rand.Read(mask[:]); err != nil {
		return err
	}
	header := make([]byte, 0, 10)
	header = append(header, 0x80|op)
	n := len(payload)
	switch {
	case n < 126:
		header = append(header, byte(n)|0x80)
	case n <= 0xFFFF:
		header = append(header, 126|0x80, byte(n>>8), byte(n))
	default:
		header = append(header, 127|0x80)
		var ext [8]byte
		binary.BigEndian.PutUint64(ext[:], uint64(n))
		header = append(header, ext[:]...)
	}
	header = append(header, mask[:]...)
	buf := make([]byte, 0, len(header)+n)
	buf = append(buf, header...)
	for i, b := range payload {
		buf = append(buf, b^mask[i%4])
	}
	_, err := ws.conn.Write(buf)
	return err
}

func (ws *WSConn) WriteText(payload []byte) error { return ws.writeFrame(opText, payload) }

func (ws *WSConn) Close() error {
	ws.wmu.Lock()
	conn := ws.conn
	ws.conn = nil
	ws.wmu.Unlock()
	if conn == nil {
		return nil
	}
	_ = conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
	_, _ = conn.Write([]byte{0x88, 0x80, 0, 0, 0, 0}) // masked empty close
	return conn.Close()
}
