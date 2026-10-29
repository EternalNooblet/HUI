//go:build darwin

package main

import (
	"os"
	"syscall"
	"unsafe"
)

const (
	tiocgets = 0x40487413 // TIOCGETA
	tiocsets = 0x80487414 // TIOCSETA
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
	return ioctlTermios(fd, tiocgets, &t) == nil
}

type termSaver struct {
	fd    uintptr
	saved syscall.Termios
}

func makeRaw(fd uintptr) (*termSaver, error) {
	var t syscall.Termios
	if err := ioctlTermios(fd, tiocgets, &t); err != nil {
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
	if err := ioctlTermios(fd, tiocsets, &t); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *termSaver) restore() {
	if s == nil {
		return
	}
	_ = ioctlTermios(s.fd, tiocsets, &s.saved)
}

func terminalSize(f *os.File) (rows, cols int) {
	var ws [4]uint16
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, f.Fd(),
		uintptr(0x40087468), // TIOCGWINSZ
		uintptr(unsafe.Pointer(&ws)))
	if errno != 0 {
		return 24, 80
	}
	return int(ws[0]), int(ws[1])
}
