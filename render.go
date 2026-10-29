package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"
	"unicode"
	"unicode/utf8"
)

const promptGlyph = "𖦏"

// renderer draws a double-buffered full-screen frame: the transcript tail in
// the top region and the live input line in the bottom region. The screen is
// repainted by diffing the previous frame, so cursor position is never
// guessed and line-wrapping drift (which broke the old renderer on Windows)
// cannot accumulate. It owns the terminal via the alternate screen buffer.
type renderer struct {
	out    *bufio.Writer
	tty    bool
	width  int
	height int

	draft       []rune
	cursor      int
	promptLabel string
	echo        bool

	lines     []string // transcript lines (may contain ANSI)
	rows      []string // wrapped transcript rows (per current width)
	rowStart  []int    // rows index where line i starts; len = len(lines)+1
	wrappedAt int      // width used to wrap rows
	screen    []string // last painted frame
}

func newRenderer() *renderer {
	r := &renderer{
		out:       bufio.NewWriter(os.Stdout),
		tty:       isTTY(os.Stdin.Fd()),
		width:     80,
		height:    24,
		echo:      true,
		rowStart:  []int{0},
		wrappedAt: -1,
	}
	if r.tty {
		r.out.WriteString("\x1b[?1049h") // enter alternate screen
		_ = r.out.Flush()
	}
	return r
}

// ─── transcript model ─────────────────────────────────────────────────────────

// addLine appends a transcript line and wraps it for the current width.
func (r *renderer) addLine(s string) {
	r.lines = append(r.lines, s)
	for len(r.rowStart) <= len(r.lines) {
		r.rowStart = append(r.rowStart, 0)
	}
	r.rewrapFrom(len(r.lines) - 1)
}

// rewrapFrom rewraps line i and everything after it into rows.
func (r *renderer) rewrapFrom(i int) {
	if i < 0 {
		i = 0
	}
	if i > len(r.lines) {
		i = len(r.lines)
	}
	start := r.rowStart[i]
	r.rows = r.rows[:start]
	for j := i; j < len(r.lines); j++ {
		wr := wrapLine(r.lines[j], r.width)
		r.rows = append(r.rows, wr...)
		r.rowStart[j+1] = len(r.rows)
	}
	r.wrappedAt = r.width
}

// writeChunk appends streamed output to the transcript (may contain \n).
func (r *renderer) writeChunk(s string) {
	if !r.tty {
		r.out.WriteString(s)
		return
	}
	parts := strings.Split(s, "\n")
	for i, p := range parts {
		if i > 0 {
			r.addLine("")
		}
		if p == "" {
			continue
		}
		if len(r.lines) == 0 {
			r.addLine("")
		}
		r.lines[len(r.lines)-1] += p
		r.rewrapFrom(len(r.lines) - 1)
	}
}

// printBlock writes one transcript block, starting on a fresh line.
func (r *renderer) printBlock(s string, trailingNewline bool) {
	if !r.tty {
		r.out.WriteString(s)
		if !strings.HasSuffix(s, "\n") {
			r.out.WriteByte('\n')
		}
		if trailingNewline {
			r.out.WriteByte('\n')
		}
		return
	}
	if len(r.lines) > 0 && r.lines[len(r.lines)-1] != "" {
		r.addLine("")
	}
	if !strings.HasSuffix(s, "\n") {
		s += "\n"
	}
	if trailingNewline {
		s += "\n"
	}
	r.writeChunk(s)
}

func (r *renderer) dimf(format string, args ...any) {
	r.printBlock(ansiDim+fmt.Sprintf(format, args...)+ansiReset, false)
}

// ─── input line ───────────────────────────────────────────────────────────────

// eraseDraft is a no-op: the input area is a fixed region of the frame, so
// there is never a stale draft to erase. Kept for API compatibility.
func (r *renderer) eraseDraft() {}

// drawPrompt / drawPromptIfDraft are no-ops: the prompt is drawn by flush()
// as part of every frame. Kept for API compatibility.
func (r *renderer) drawPrompt()        {}
func (r *renderer) drawPromptIfDraft() {}

func (r *renderer) setPromptLabel(label string) {
	r.promptLabel = label
}

func (r *renderer) clearScreen() {
	if !r.tty {
		return
	}
	r.lines = nil
	r.rowStart = []int{0}
	r.rows = nil
	r.screen = nil
}

// promptRows wraps the prompt label + draft into display rows and returns the
// edit cursor's (row, col) within those rows.
func (r *renderer) promptRows() (rows []string, cRow, cCol int) {
	draft := string(r.draft)
	if !r.echo {
		draft = strings.Repeat("*", utf8.RuneCountInString(draft))
	}
	label := []rune(r.promptLabel)
	body := []rune(draft)
	cur := len(label) + r.cursor
	if cur < 0 {
		cur = 0
	}
	if cur > len(label)+len(body) {
		cur = len(label) + len(body)
	}

	var sb strings.Builder
	col := 0
	cursorSet := false
	flushRow := func() {
		rows = append(rows, sb.String())
		sb.Reset()
		col = 0
	}
	for i, rn := range append(label, body...) {
		if i == cur && !cursorSet {
			cRow, cCol = len(rows), col
			cursorSet = true
		}
		w := runeWidth(rn)
		if col > 0 && col+w > r.width {
			flushRow()
		}
		if rn == '\n' {
			flushRow()
			continue
		}
		sb.WriteRune(rn)
		col += w
	}
	if !cursorSet {
		cRow, cCol = len(rows), col
	}
	flushRow()
	return rows, cRow, cCol
}

// ─── painting ─────────────────────────────────────────────────────────────────

func (r *renderer) flush() {
	if !r.tty {
		_ = r.out.Flush()
		return
	}
	// resize check
	h, w := terminalSize(os.Stdin) // returns (rows, cols)
	if w <= 0 {
		w = 80
	}
	if h <= 0 {
		h = 24
	}
	if w != r.width || h != r.height {
		r.width, r.height = w, h
		r.rewrapFrom(0)
	} else if r.wrappedAt != r.width {
		r.rewrapFrom(0)
	}

	pRows, pCurRow, pCurCol := r.promptRows()
	avail := r.height - len(pRows)
	if avail < 0 {
		avail = 0
	}
	frame := make([]string, 0, r.height)
	if n := len(r.rows); n < avail {
		for i := 0; i < avail-n; i++ {
			frame = append(frame, "")
		}
		frame = append(frame, r.rows...)
	} else {
		frame = append(frame, r.rows[n-avail:]...)
	}
	frame = append(frame, pRows...)
	for len(frame) < r.height {
		frame = append(frame, "")
	}

	var sb strings.Builder
	for i := 0; i < len(frame) && i < r.height; i++ {
		if i < len(r.screen) && r.screen[i] == frame[i] {
			continue
		}
		fmt.Fprintf(&sb, "\x1b[%d;1H", i+1)
		sb.WriteString(frame[i])
		sb.WriteString("\x1b[K")
	}
	cr := avail + pCurRow
	if cr < 0 {
		cr = 0
	}
	if cr >= r.height {
		cr = r.height - 1
	}
	fmt.Fprintf(&sb, "\x1b[%d;%dH", cr+1, pCurCol+1)
	r.out.WriteString(sb.String())
	r.screen = frame
	_ = r.out.Flush()
}

// close restores the terminal: pops the kitty/win32 input modes, leaves the
// alternate screen, and dumps the tail of the conversation into the normal
// scrollback so the session stays visible after exit.
func (r *renderer) close() {
	if !r.tty {
		_ = r.out.Flush()
		return
	}
	r.flush() // paint the final frame (e.g. "bye")
	r.out.WriteString("\x1b[<u\x1b[?9001l") // kitty pop + win32 pop (harmless elsewhere)
	r.out.WriteString("\x1b[?1049l")         // leave alternate screen
	_ = r.out.Flush()

	start := len(r.rows) - r.height
	if start < 0 {
		start = 0
	}
	for _, row := range r.rows[start:] {
		fmt.Fprintln(os.Stdout, stripANSI(row))
	}
}

// ─── width helpers ────────────────────────────────────────────────────────────

func runeWidth(rn rune) int {
	switch {
	case rn == 0:
		return 0
	case unicode.Is(unicode.Mn, rn), unicode.Is(unicode.Me, rn), unicode.Is(unicode.Cf, rn):
		return 0
	case rn >= 0x1100 && rn <= 0x115F, // Hangul Jamo
		rn >= 0x2E80 && rn <= 0xA4CF, // CJK radicals, Kangxi, CJK, Yi
		rn >= 0xAC00 && rn <= 0xD7A3, // Hangul syllables
		rn >= 0xF900 && rn <= 0xFAFF, // CJK compat ideographs
		rn >= 0xFE30 && rn <= 0xFE6F, // CJK compat forms
		rn >= 0xFF00 && rn <= 0xFF60, // fullwidth forms
		rn >= 0xFFE0 && rn <= 0xFFE6:
		return 2
	}
	return 1
}

func textWidth(s string) int {
	w := 0
	for _, rn := range s {
		w += runeWidth(rn)
	}
	return w
}

// wrapLine wraps s to width. ANSI escape sequences are preserved (zero display
// width); when a row wraps mid-style, the active SGR is re-applied at the
// start of the next row.
func wrapLine(s string, width int) []string {
	if width <= 0 {
		width = 80
	}
	var rows []string
	var cur strings.Builder
	col := 0
	activeSGR := ""
	runes := []rune(s)
	for i := 0; i < len(runes); {
		r := runes[i]
		if r == 0x1b {
			j := i + 1
			if j < len(runes) && runes[j] == '[' {
				j++
				for j < len(runes) && !(runes[j] >= 0x40 && runes[j] <= 0x7e) {
					j++
				}
				if j < len(runes) {
					j++
				}
			}
			seq := string(runes[i:j])
			cur.WriteString(seq)
			if strings.HasSuffix(seq, "m") {
				activeSGR = seq
			}
			i = j
			continue
		}
		w := runeWidth(r)
		if col > 0 && col+w > width {
			rows = append(rows, cur.String())
			cur.Reset()
			col = 0
			if activeSGR != "" {
				cur.WriteString(activeSGR)
			}
		}
		cur.WriteRune(r)
		col += w
		i++
	}
	rows = append(rows, cur.String())
	return rows
}

func stripANSI(s string) string {
	var sb strings.Builder
	runes := []rune(s)
	for i := 0; i < len(runes); {
		if runes[i] == 0x1b {
			j := i + 1
			if j < len(runes) && runes[j] == '[' {
				j++
				for j < len(runes) && !(runes[j] >= 0x40 && runes[j] <= 0x7e) {
					j++
				}
				if j < len(runes) {
					j++
				}
			}
			i = j
			continue
		}
		sb.WriteRune(runes[i])
		i++
	}
	return sb.String()
}

// ANSI helpers
const (
	ansiReset  = "\x1b[0m"
	ansiBold   = "\x1b[1m"
	ansiDim    = "\x1b[2m"
	ansiItalic = "\x1b[3m"
	ansiRed    = "\x1b[31m"
	ansiGreen  = "\x1b[32m"
	ansiYellow = "\x1b[33m"
	ansiCyan   = "\x1b[36m"
)
