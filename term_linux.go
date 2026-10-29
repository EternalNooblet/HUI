//go:build linux

package main

import (
	"os"
	"syscall"
	"unsafe"
)

func ioctlTermios(fd uintptr, req uintptr, t *syscall.Termios) error {
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, req, uintptr(unsafe.Pointer(t)))
	if errno != 0 {
		return errno
	}
	return nil
}

func isTTY(fd uintptr) bool {
	var t syscall.Termios
	return ioctlTermios(fd, syscall.TCGETS, &t) == nil
}

type termSaver struct {
	fd    uintptr
	saved syscall.Termios
}

// makeRaw disables canonical mode, echo and signals: keystrokes arrive as raw
// bytes (Ctrl+C is byte 0x03, handled by the app so it can detach cleanly).
func makeRaw(fd uintptr) (*termSaver, error) {
	var t syscall.Termios
	if err := ioctlTermios(fd, syscall.TCGETS, &t); err != nil {
		return nil, err
	}
	s := &termSaver{fd: fd, saved: t}
	t.Iflag &^= syscall.IGNBRK | syscall.BRKINT | syscall.PARMRK | syscall.ISTRIP |
		syscall.INLCR | syscall.IGNCR | syscall.ICRNL | syscall.IXON
	t.Oflag &^= syscall.OPOST
	t.Lflag &^= syscall.ECHO | syscall.ECHONL | syscall.ICANON | syscall.ISIG | syscall.IEXTEN
	t.Cflag &^= syscall.CSIZE | syscall.PARENB
	t.Cflag |= syscall.CS8
	t.Cc[syscall.VMIN] = 1
	t.Cc[syscall.VTIME] = 0
	if err := ioctlTermios(fd, syscall.TCSETS, &t); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *termSaver) restore() {
	if s == nil {
		return
	}
	_ = ioctlTermios(s.fd, syscall.TCSETS, &s.saved)
}

func terminalSize(f *os.File) (rows, cols int) {
	var ws [4]uint16
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, f.Fd(),
		syscall.TIOCGWINSZ, uintptr(unsafe.Pointer(&ws)))
	if errno != 0 {
		return 24, 80
	}
	return int(ws[0]), int(ws[1])
}
