//go:build windows

package main

// Windows 11 terminal support, stdlib only: the raw-mode switches go through
// kernel32.dll via syscall.NewLazyDLL (no cgo, no external modules).
//
// Input:  ENABLE_VIRTUAL_TERMINAL_INPUT makes the console deliver the same
//         VT/CSI byte sequences a Unix terminal sends, so keys.go's decoder
//         works unchanged (arrows, Ctrl+C as 0x03, kitty CSI u where the
//         terminal supports it — Windows Terminal 1.22+).
// Output: ENABLE_VIRTUAL_TERMINAL_PROCESSING renders the ANSI escapes the
//         renderer emits; the console codepage is switched to UTF-8 (65001)
//         so multi-byte runes (𖦏, CJK) display correctly.

import (
	"errors"
	"os"
	"syscall"
	"unsafe"
)

var (
	kernel32                       = syscall.NewLazyDLL("kernel32.dll")
	procGetConsoleMode             = kernel32.NewProc("GetConsoleMode")
	procSetConsoleMode             = kernel32.NewProc("SetConsoleMode")
	procGetConsoleScreenBufferInfo = kernel32.NewProc("GetConsoleScreenBufferInfo")
	procGetConsoleCP               = kernel32.NewProc("GetConsoleCP")
	procSetConsoleCP               = kernel32.NewProc("SetConsoleCP")
	procGetConsoleOutputCP         = kernel32.NewProc("GetConsoleOutputCP")
	procSetConsoleOutputCP         = kernel32.NewProc("SetConsoleOutputCP")
)

const (
	enableProcessedInput            = 0x0001
	enableLineInput                 = 0x0002
	enableEchoInput                 = 0x0004
	enableQuickEditMode             = 0x0040
	enableExtendedFlags             = 0x0080
	enableVirtualTerminalProcessing = 0x0004
	enableVirtualTerminalInput      = 0x0200

	utf8CodePage = 65001
)

func callBool(proc *syscall.LazyProc, args ...uintptr) bool {
	r, _, _ := proc.Call(args...)
	return r != 0
}

func getMode(fd uintptr) (uint32, bool) {
	var mode uint32
	if !callBool(procGetConsoleMode, fd, uintptr(unsafe.Pointer(&mode))) {
		return 0, false
	}
	return mode, true
}

func setMode(fd uintptr, mode uint32) bool {
	return callBool(procSetConsoleMode, fd, uintptr(mode))
}

func isTTY(fd uintptr) bool {
	_, ok := getMode(fd)
	return ok
}

// termSaver stores the console modes and codepages to restore on exit.
type termSaver struct {
	inFD    uintptr
	inMode  uint32
	outFD   uintptr
	outMode uint32
	inCP    uint32
	outCP   uint32
}

// makeRaw switches the console into raw mode: no line input, no echo, no
// processed input (Ctrl+C arrives as byte 0x03 exactly like on Unix, so the
// app can detach cleanly), VT input enabled so escape sequences reach keys.go,
// VT output enabled on stdout, and both codepages switched to UTF-8.
// Everything is saved and restored by termSaver.restore().
func makeRaw(fd uintptr) (*termSaver, error) {
	inMode, ok := getMode(fd)
	if !ok {
		return nil, errors.New("not a console")
	}
	s := &termSaver{inFD: fd, inMode: inMode}

	raw := inMode &^ (enableLineInput | enableEchoInput | enableProcessedInput)
	raw |= enableVirtualTerminalInput | enableExtendedFlags
	raw &^= enableQuickEditMode // quick-edit would block reads on a click
	if !setMode(fd, raw) {
		return nil, errors.New("SetConsoleMode(raw) failed")
	}

	// stdout: VT processing so the renderer's ANSI escapes work
	if outMode, ok := getMode(uintptr(os.Stdout.Fd())); ok {
		s.outFD = uintptr(os.Stdout.Fd())
		s.outMode = outMode
		setMode(s.outFD, outMode|enableVirtualTerminalProcessing)
	}

	// codepage: UTF-8 in and out, so multi-byte runes render and round-trip
	if r, _ := getCP(); r != 0 {
		s.inCP = r
	}
	if r, _ := getOutCP(); r != 0 {
		s.outCP = r
	}
	if s.inCP != 0 && s.inCP != utf8CodePage {
		callBool(procSetConsoleCP, utf8CodePage)
	}
	if s.outCP != 0 && s.outCP != utf8CodePage {
		callBool(procSetConsoleOutputCP, utf8CodePage)
	}
	return s, nil
}

func getCP() (uint32, bool) {
	r, _, _ := procGetConsoleCP.Call()
	return uint32(r), r != 0
}

func getOutCP() (uint32, bool) {
	r, _, _ := procGetConsoleOutputCP.Call()
	return uint32(r), r != 0
}

func (s *termSaver) restore() {
	if s == nil {
		return
	}
	if s.inFD != 0 {
		setMode(s.inFD, s.inMode)
	}
	if s.outFD != 0 {
		setMode(s.outFD, s.outMode)
	}
	if s.inCP != 0 && s.inCP != utf8CodePage {
		callBool(procSetConsoleCP, uintptr(s.inCP))
	}
	if s.outCP != 0 && s.outCP != utf8CodePage {
		callBool(procSetConsoleOutputCP, uintptr(s.outCP))
	}
}

// consoleScreenBufferInfo mirrors CONSOLE_SCREEN_BUFFER_INFO (kernel32).
type coord16 struct{ x, y int16 }
type smallRect struct{ left, top, right, bottom int16 }
type consoleScreenBufferInfo struct {
	size              coord16
	cursorPosition    coord16
	attributes        uint16
	window            smallRect
	maximumWindowSize coord16
}

// terminalSize reads the visible window size. On Windows the input handle is
// not a screen buffer, so fall back to the stdout handle.
func terminalSize(f *os.File) (rows, cols int) {
	for _, h := range []uintptr{f.Fd(), os.Stdout.Fd()} {
		var info consoleScreenBufferInfo
		if callBool(procGetConsoleScreenBufferInfo, h, uintptr(unsafe.Pointer(&info))) {
			return int(info.window.bottom-info.window.top) + 1,
				int(info.window.right-info.window.left) + 1
		}
	}
	return 24, 80
}
