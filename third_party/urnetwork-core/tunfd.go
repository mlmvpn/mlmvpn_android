package main

import (
	"errors"
	"net"
	"os"
	"sync"
	"syscall"
	"time"

	"github.com/urnetwork/sdk"
)

// The VPN's TUN, carried on the SDK's own packet path (sdk.NewIoLoop) -- the path URnetwork's
// official apps use, with no proxy hop in between.
//
// The descriptor belongs to the VPN service, in the app's process, and this core is a child of
// that process. Java's ProcessBuilder closes every descriptor above 2 in a child, so it comes over
// a unix socket instead, as an SCM_RIGHTS message: the service connects to this socket, sends the
// TUN with one byte, and waits for one byte back, which says the packet loop is running. The
// socket lives in this job's private folder, where no other app can reach it.
//
// A second descriptor replaces the first (the service rebuilt its TUN); the loop of the old one is
// closed, which closes this process's copy of it.
type tunServer struct {
	device *sdk.DeviceLocal
	lock   sync.Mutex
	loop   *sdk.IoLoop
	gen    int
}

type loopDone struct {
	server *tunServer
	gen    int
}

func (self loopDone) IoLoopDone() {
	self.server.lock.Lock()
	current := self.server.gen == self.gen
	if current {
		self.server.loop = nil
	}
	self.server.lock.Unlock()
	if current {
		emit("tun", map[string]any{"up": false})
	}
}

func serveTun(device *sdk.DeviceLocal, path string) error {
	os.Remove(path)
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return err
	}
	os.Chmod(path, 0600)
	server := &tunServer{device: device}
	go func() {
		for {
			c, err := ln.AcceptUnix()
			if err != nil {
				if errors.Is(err, net.ErrClosed) {
					return
				}
				time.Sleep(100 * time.Millisecond)
				continue
			}
			server.take(c)
		}
	}()
	return nil
}

func (self *tunServer) take(c *net.UnixConn) {
	defer c.Close()
	fd, err := receiveFd(c)
	if err != nil {
		emit("tun", map[string]any{"up": false, "error": err.Error()})
		return
	}
	self.lock.Lock()
	old := self.loop
	self.gen++
	gen := self.gen
	self.loop = sdk.NewIoLoop(self.device, int32(fd), loopDone{server: self, gen: gen})
	self.lock.Unlock()
	if old != nil {
		old.Close()
	}
	self.device.SetTunnelStarted(true)
	c.Write([]byte{1})
	emit("tun", map[string]any{"up": true})
}

func receiveFd(c *net.UnixConn) (int, error) {
	c.SetReadDeadline(time.Now().Add(15 * time.Second))
	buf := make([]byte, 16)
	oob := make([]byte, syscall.CmsgSpace(4*8))
	_, oobn, _, _, err := c.ReadMsgUnix(buf, oob)
	if err != nil {
		return -1, err
	}
	msgs, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return -1, err
	}
	fd := -1
	for i := range msgs {
		fds, err := syscall.ParseUnixRights(&msgs[i])
		if err != nil {
			continue
		}
		for _, f := range fds {
			if fd < 0 {
				fd = f
			} else {
				syscall.Close(f)
			}
		}
	}
	if fd < 0 {
		return -1, errors.New("no descriptor in the message")
	}
	return fd, nil
}
