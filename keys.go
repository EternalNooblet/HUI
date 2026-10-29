package main

import (
	"io"
	"unicode/utf8"
)

type keyKind int

const (
	keyRune keyKind = iota
	keyEnter
	keyShiftEnter // newline into the draft
	keyAltEnter   // newline fallback for terminals without the kitty protocol
	keyBackspace
	keyDelete
	keyLeft
	keyRight
	keyUp
	keyDown
	keyHome
	keyEnd
	keyCtrlC
	keyCtrlD
	keyCtrlU
	keyCtrlW
	keyCtrlA
	keyCtrlE
	keyOther
)

type keyEvent struct {
	kind keyKind
	r    rune
}

// keyReader decodes raw stdin bytes into key events. Escape sequences are
// parsed chunk-wise: terminals deliver a complete sequence in one read, and a
// partial tail is carried over to the next read.
type keyReader struct {
	buf []byte
	eof bool
}

func (k *keyReader) feed(data []byte, atEOF bool) {
	k.buf = append(k.buf, data...)
	k.eof = k.eof || atEOF
}

// next returns the next decoded event, or ok=false when it needs more bytes
// (or input ended).
func (k *keyReader) next() (keyEvent, bool) {
	for len(k.buf) > 0 {
		b := k.buf[0]
		switch {
		case b == 0x03:
			k.buf = k.buf[1:]
			return keyEvent{kind: keyCtrlC}, true
		case b == 0x04:
			k.buf = k.buf[1:]
			return keyEvent{kind: keyCtrlD}, true
		case b == 0x15:
			k.buf = k.buf[1:]
			return keyEvent{kind: keyCtrlU}, true
		case b == 0x17:
			k.buf = k.buf[1:]
			return keyEvent{kind: keyCtrlW}, true
		case b == 0x01:
			k.buf = k.buf[1:]
			return keyEvent{kind: keyHome}, true
		case b == 0x05:
			k.buf = k.buf[1:]
			return keyEvent{kind: keyEnd}, true
		case b == 0x08, b == 0x7f:
			k.buf = k.buf[1:]
			return keyEvent{kind: keyBackspace}, true
		case b == '\r' || b == '\n':
			k.buf = k.buf[1:]
			return keyEvent{kind: keyEnter}, true
		case b == 0x1b:
			return k.nextEscape()
		case b < 0x20:
			k.buf = k.buf[1:]
			return keyEvent{kind: keyOther}, true
		default:
			r, size := utf8.DecodeRune(k.buf)
			if r == utf8.RuneError && size <= 1 && len(k.buf) < 4 && !k.eof {
				return keyEvent{}, false // wait for the rest of the rune
			}
			k.buf = k.buf[size:]
			return keyEvent{kind: keyRune, r: r}, true
		}
	}
	return keyEvent{}, false
}

// nextEscape decodes one escape sequence; buf starts with 0x1b.
func (k *keyReader) nextEscape() (keyEvent, bool) {
	need := func(n int) bool { return len(k.buf) < n && !k.eof }
	if need(2) {
		return keyEvent{}, false
	}
	switch k.buf[1] {
	case '[': // CSI
		return k.nextCSI()
	case 'O': // SS3 arrows
		if need(3) {
			return keyEvent{}, false
		}
		ev, ok := arrowEvent(k.buf[2])
		if ok {
			k.buf = k.buf[3:]
			return ev, true
		}
		k.buf = k.buf[3:]
		return keyEvent{kind: keyOther}, true
	case '\r', '\n': // Alt+Enter (ESC CR)
		k.buf = k.buf[2:]
		return keyEvent{kind: keyAltEnter}, true
	case 0x1b: // lone double-ESC: swallow one
		k.buf = k.buf[1:]
		return keyEvent{kind: keyOther}, true
	default:
		// ESC + rune = alt-rune: treat the rune as input.
		if k.buf[1] < utf8.RuneSelf {
			r, size := utf8.DecodeRune(k.buf[1:])
			k.buf = k.buf[1+size:]
			return keyEvent{kind: keyRune, r: r}, true
		}
		r, size := utf8.DecodeRune(k.buf[1:])
		if r == utf8.RuneError && size <= 1 && len(k.buf) < 5 && !k.eof {
			return keyEvent{}, false
		}
		k.buf = k.buf[1+size:]
		return keyEvent{kind: keyRune, r: r}, true
	}
}

// nextCSI decodes a CSI sequence; buf starts with ESC [.
func (k *keyReader) nextCSI() (keyEvent, bool) {
	// find the final byte
	i := 2
	for i < len(k.buf) && (k.buf[i] >= 0x30 && k.buf[i] <= 0x3f) {
		i++
	}
	if i >= len(k.buf) {
		if k.eof {
			k.buf = k.buf[1:]
			return keyEvent{kind: keyOther}, true
		}
		return keyEvent{}, false // sequence not complete yet
	}
	final := k.buf[i]
	params := string(k.buf[2:i])
	k.buf = k.buf[i+1:]

	switch final {
	case 'A':
		return keyEvent{kind: keyUp}, true
	case 'B':
		return keyEvent{kind: keyDown}, true
	case 'C':
		return keyEvent{kind: keyRight}, true
	case 'D':
		return keyEvent{kind: keyLeft}, true
	case 'H':
		return keyEvent{kind: keyHome}, true
	case 'F':
		return keyEvent{kind: keyEnd}, true
	case '~':
		switch params {
		case "1", "7":
			return keyEvent{kind: keyHome}, true
		case "3":
			return keyEvent{kind: keyDelete}, true
		case "4", "8":
			return keyEvent{kind: keyEnd}, true
		}
	case 'u': // kitty keyboard protocol: CSI <code>;<mods>u
		code, mods := parseCSIu(params)
		mbits := 0
		if mods >= 1 {
			mbits = mods - 1 // transmitted value is the bitfield + 1
		}
		if code == 13 && mbits&1 != 0 && mbits&4 == 0 { // shift (no ctrl) → newline
			return keyEvent{kind: keyShiftEnter}, true
		}
		if code == 13 && mbits&2 != 0 { // alt → newline fallback
			return keyEvent{kind: keyAltEnter}, true
		}
		if code == 13 {
			return keyEvent{kind: keyEnter}, true
		}
		if mbits == 0 {
			switch code {
			case 57414: // keypad enter
				return keyEvent{kind: keyEnter}, true
			case 27:
				return keyEvent{kind: keyOther}, true
			}
		}
	case '_': // win32-input-mode: CSI vk;sc;uch;kd;ctrls;rev;count_
		vk, uch, kd, ctrl := parseWin32(params)
		if kd != 0 {
			return keyEvent{kind: keyOther}, true // key release: ignore
		}
		return win32Event(vk, uch, ctrl), true
	}
	return keyEvent{kind: keyOther}, true
}

func parseCSIu(params string) (code, mods int) {
	code, mods = 0, 0
	for i, part := range splitParams(params) {
		n := atoiSafe(part)
		if i == 0 {
			code = n
		} else if i == 1 {
			mods = n
		}
	}
	return code, mods
}

// win32-input-mode: CSI <vk>;<sc>;<uch>;<kd>;<ctrls>;[rev;count]_
// vk = virtual key code, sc = scan code, uch = unicode char (0 = none),
// kd = 1 on key release, ctrls = control key state bitfield.
func parseWin32(params string) (vk, uch, kd, ctrl int) {
	for i, part := range splitParams(params) {
		n := atoiSafe(part)
		switch i {
		case 0:
			vk = n
		case 2:
			uch = n
		case 3:
			kd = n
		case 4:
			ctrl = n
		}
	}
	return vk, uch, kd, ctrl
}

// win32-input-mode control key state bits (from the console input records).
const (
	win32Shift = 0x0010
	win32Ctrl  = 0x0003 // left|right ctrl
	win32Alt   = 0x000C // left|right alt
)

// win32Event maps a win32-input-mode key-down record to a key event.
func win32Event(vk, uch, ctrl int) keyEvent {
	hasCtrl := ctrl&win32Ctrl != 0
	hasAlt := ctrl&win32Alt != 0
	hasShift := ctrl&win32Shift != 0

	switch vk {
	case 13: // enter
		switch {
		case hasShift && !hasCtrl && !hasAlt:
			return keyEvent{kind: keyShiftEnter}
		case hasAlt:
			return keyEvent{kind: keyAltEnter}
		case hasCtrl:
			return keyEvent{kind: keyShiftEnter} // Ctrl+Enter: newline fallback
		}
		return keyEvent{kind: keyEnter}
	case 8: // backspace
		return keyEvent{kind: keyBackspace}
	case 46: // delete
		return keyEvent{kind: keyDelete}
	case 37: // left
		return keyEvent{kind: keyLeft}
	case 39: // right
		return keyEvent{kind: keyRight}
	case 38: // up
		return keyEvent{kind: keyUp}
	case 40: // down
		return keyEvent{kind: keyDown}
	case 35: // end
		return keyEvent{kind: keyEnd}
	case 36: // home
		return keyEvent{kind: keyHome}
	}

	// ctrl+letter shortcuts
	if hasCtrl && !hasAlt {
		switch vk {
		case 'C':
			return keyEvent{kind: keyCtrlC}
		case 'D':
			return keyEvent{kind: keyCtrlD}
		case 'U':
			return keyEvent{kind: keyCtrlU}
		case 'W':
			return keyEvent{kind: keyCtrlW}
		case 'A':
			return keyEvent{kind: keyCtrlA}
		case 'E':
			return keyEvent{kind: keyCtrlE}
		}
	}

	// plain text: uch carries the (possibly shifted) character
	if uch > 0 && !hasCtrl && !hasAlt {
		return keyEvent{kind: keyRune, r: rune(uch)}
	}
	return keyEvent{kind: keyOther}
}

func splitParams(s string) []string {
	var out []string
	start := 0
	for i := 0; i <= len(s); i++ {
		if i == len(s) || s[i] == ';' || s[i] == ':' {
			if i > start || len(out) > 0 {
				out = append(out, s[start:i])
			}
			start = i + 1
		}
	}
	return out
}

func arrowEvent(b byte) (keyEvent, bool) {
	switch b {
	case 'A':
		return keyEvent{kind: keyUp}, true
	case 'B':
		return keyEvent{kind: keyDown}, true
	case 'C':
		return keyEvent{kind: keyRight}, true
	case 'D':
		return keyEvent{kind: keyLeft}, true
	}
	return keyEvent{kind: keyOther}, false
}

// readKeys pumps stdin through a keyReader into ch; returns when stdin ends.
func readKeys(in io.Reader, ch chan<- keyEvent) {
	k := &keyReader{}
	buf := make([]byte, 4096)
	for {
		n, err := in.Read(buf)
		if n > 0 {
			k.feed(buf[:n], false)
			for {
				ev, ok := k.next()
				if !ok {
					break
				}
				ch <- ev
			}
		}
		if err != nil {
			k.feed(nil, true)
			for {
				ev, ok := k.next()
				if !ok {
					break
				}
				ch <- ev
			}
			close(ch)
			return
		}
	}
}
