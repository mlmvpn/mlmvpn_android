package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/urnetwork/connect"
	"github.com/urnetwork/sdk"
)

// A SOCKS5 server (RFC 1928, no authentication) whose connections go through the device:
// CONNECT for TCP, UDP ASSOCIATE for UDP. MAE, MLM Scout and LAN sharing all use it; the tile's
// own TUN does not -- its packets take the SDK's packet path.
//
// URnetwork's blocker works on the packet path, where the SDK sees the names being resolved. Here
// names arrive before anything resolves them, so this server applies the same blocker data
// (connect.NewBlockerWithDefaults, the list URnetwork ships) to them itself.
type socksServer struct {
	device  *sdk.DeviceLocal
	blocker connect.Blocker
	rules   atomic.Pointer[ruleList]
	blocked atomic.Int64
	onBlock func()
}

func newSocksServer(device *sdk.DeviceLocal) *socksServer {
	return &socksServer{device: device, blocker: connect.NewBlockerWithDefaults()}
}

func (self *socksServer) setBlocker(on bool) { self.blocker.SetEnabled(on) }

func (self *socksServer) serve(ln net.Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			time.Sleep(50 * time.Millisecond)
			continue
		}
		go self.handle(c)
	}
}

const (
	replyOk           = 0
	replyFailure      = 1
	replyNotAllowed   = 2
	replyNetUnreach   = 3
	replyHostUnreach  = 4
	replyRefused      = 5
	replyTtlExpired   = 6
	replyCmdUnknown   = 7
	replyAtypUnknown  = 8
	socksHandshakeMax = 30 * time.Second
)

func (self *socksServer) handle(c net.Conn) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(socksHandshakeMax))
	r := bufio.NewReaderSize(c, 4096)
	head := make([]byte, 2)
	if _, err := io.ReadFull(r, head); err != nil || head[0] != 5 {
		return
	}
	if _, err := io.ReadFull(r, make([]byte, head[1])); err != nil {
		return
	}
	if _, err := c.Write([]byte{5, 0}); err != nil {
		return
	}
	req := make([]byte, 4)
	if _, err := io.ReadFull(r, req); err != nil || req[0] != 5 {
		return
	}
	host, err := readSocksAddr(r, req[3])
	if err != nil {
		writeReply(c, replyAtypUnknown, nil)
		return
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(r, pb); err != nil {
		return
	}
	port := int(binary.BigEndian.Uint16(pb))
	switch req[1] {
	case 1:
		self.connect(c, r, host, port)
	case 3:
		self.associate(c, r)
	default:
		writeReply(c, replyCmdUnknown, nil)
	}
}

// isBlocked: the destination is on URnetwork's ad and tracker list, while the blocker is on.
func (self *socksServer) isBlocked(host string) bool {
	if !self.blocker.Enabled() {
		return false
	}
	if addr, err := netip.ParseAddr(host); err == nil {
		return self.blocker.BlockIp(addr.Unmap())
	}
	return self.blocker.BlockHost(host)
}

func (self *socksServer) countBlocked() {
	self.blocked.Add(1)
	if self.onBlock != nil {
		self.onBlock()
	}
}

func (self *socksServer) dial(ctx context.Context, network string, host string, port int) (net.Conn, error) {
	target := net.JoinHostPort(host, strconv.Itoa(port))
	if self.rules.Load().direct(host) {
		// This process is outside the VPN: a plain dial leaves on the phone's own connection.
		return (&net.Dialer{}).DialContext(ctx, network, target)
	}
	return self.device.DialContext(ctx, network, target)
}

func (self *socksServer) connect(c net.Conn, r *bufio.Reader, host string, port int) {
	if self.rules.Load().blocked(host) {
		writeReply(c, replyNotAllowed, nil)
		return
	}
	if self.isBlocked(host) {
		self.countBlocked()
		writeReply(c, replyNotAllowed, nil)
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	up, err := self.dial(ctx, "tcp", host, port)
	cancel()
	if err != nil {
		writeReply(c, replyCodeOf(err), nil)
		return
	}
	defer up.Close()
	if err := writeReply(c, replyOk, nil); err != nil {
		return
	}
	c.SetDeadline(time.Time{})
	relay(c, r, up)
}

var relayBuffers = sync.Pool{New: func() any { return make([]byte, 32*1024) }}

// relay copies both ways. Each direction ends with a half-close of its destination, so a peer that
// finished sending still gets the rest of the answer; once one direction is done the other gets a
// minute, so a half-open pair cannot hold its goroutines forever.
func relay(client net.Conn, clientReader io.Reader, up net.Conn) {
	done := make(chan struct{}, 2)
	copyOne := func(dst net.Conn, src io.Reader) {
		buf := relayBuffers.Get().([]byte)
		io.CopyBuffer(dst, src, buf)
		relayBuffers.Put(buf)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		} else {
			dst.Close()
		}
		done <- struct{}{}
	}
	go copyOne(up, clientReader)
	go copyOne(client, up)
	<-done
	finished := 1
	select {
	case <-done:
		finished = 2
	case <-time.After(60 * time.Second):
	}
	client.Close()
	up.Close()
	if finished < 2 {
		<-done
	}
}

// ── UDP ASSOCIATE ──

const (
	udpFlowIdle    = 120 * time.Second
	udpDnsIdle     = 20 * time.Second
	udpMaxFlows    = 512
	udpMaxDatagram = 65535
)

func (self *socksServer) associate(c net.Conn, r *bufio.Reader) {
	local, ok := c.LocalAddr().(*net.TCPAddr)
	if !ok {
		writeReply(c, replyFailure, nil)
		return
	}
	pc, err := net.ListenUDP("udp", &net.UDPAddr{IP: local.IP})
	if err != nil {
		writeReply(c, replyFailure, nil)
		return
	}
	defer pc.Close()
	if err := writeReply(c, replyOk, pc.LocalAddr().(*net.UDPAddr)); err != nil {
		return
	}
	c.SetDeadline(time.Time{})
	var peerIp net.IP
	if a, ok := c.RemoteAddr().(*net.TCPAddr); ok {
		peerIp = a.IP
	}
	s := &udpSession{server: self, pc: pc, peerIp: peerIp, flows: map[string]*udpFlow{}}
	go s.run()
	// The association lives exactly as long as its TCP connection.
	io.Copy(io.Discard, r)
	s.close()
}

type udpSession struct {
	server *socksServer
	pc     *net.UDPConn
	peerIp net.IP

	lock   sync.Mutex
	client *net.UDPAddr
	flows  map[string]*udpFlow
	closed bool
}

type udpFlow struct {
	key     string
	host    string
	port    int
	lastUse atomic.Int64
	conn    net.Conn
	pending [][]byte
	dialed  bool
	failed  bool
	dnsLike bool
	lock    sync.Mutex
}

func (self *udpSession) close() {
	self.lock.Lock()
	self.closed = true
	flows := self.flows
	self.flows = map[string]*udpFlow{}
	self.lock.Unlock()
	self.pc.Close()
	for _, f := range flows {
		f.lock.Lock()
		if f.conn != nil {
			f.conn.Close()
		}
		f.lock.Unlock()
	}
}

func (self *udpSession) run() {
	go self.reap()
	buf := make([]byte, udpMaxDatagram)
	for {
		n, from, err := self.pc.ReadFromUDP(buf)
		if err != nil {
			return
		}
		// Only the host that opened the association may use it.
		if self.peerIp != nil && !from.IP.Equal(self.peerIp) {
			continue
		}
		host, port, payload, ok := parseUdpHeader(buf[:n])
		if !ok {
			continue
		}
		self.lock.Lock()
		self.client = from
		self.lock.Unlock()
		self.send(host, port, payload)
	}
}

func (self *udpSession) send(host string, port int, payload []byte) {
	if self.server.rules.Load().blocked(host) {
		return
	}
	if self.server.isBlocked(host) {
		self.server.countBlocked()
		return
	}
	key := net.JoinHostPort(host, strconv.Itoa(port))
	self.lock.Lock()
	if self.closed {
		self.lock.Unlock()
		return
	}
	f := self.flows[key]
	if f == nil {
		if len(self.flows) >= udpMaxFlows {
			self.lock.Unlock()
			return
		}
		f = &udpFlow{key: key, host: host, port: port, dnsLike: port == 53}
		self.flows[key] = f
		self.lock.Unlock()
		f.lastUse.Store(time.Now().UnixNano())
		f.pending = append(f.pending, append([]byte(nil), payload...))
		go self.open(f)
		return
	}
	self.lock.Unlock()
	f.lastUse.Store(time.Now().UnixNano())
	f.lock.Lock()
	defer f.lock.Unlock()
	switch {
	case f.failed:
	case !f.dialed:
		if len(f.pending) < 32 {
			f.pending = append(f.pending, append([]byte(nil), payload...))
		}
	default:
		f.conn.Write(payload)
	}
}

func (self *udpSession) open(f *udpFlow) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	conn, err := self.server.dial(ctx, "udp", f.host, f.port)
	cancel()
	f.lock.Lock()
	if err != nil {
		f.failed = true
		f.pending = nil
		f.lock.Unlock()
		self.drop(f)
		return
	}
	f.conn = conn
	f.dialed = true
	for _, p := range f.pending {
		conn.Write(p)
	}
	f.pending = nil
	f.lock.Unlock()

	self.lock.Lock()
	closed := self.closed
	self.lock.Unlock()
	if closed {
		conn.Close()
		return
	}

	// Answers go back with the address they came from, as SOCKS asks.
	var srcHost string
	srcPort := f.port
	if ra, ok := conn.RemoteAddr().(*net.UDPAddr); ok && ra.IP != nil {
		srcHost = ra.IP.String()
		srcPort = ra.Port
	} else if ap, err := netip.ParseAddrPort(conn.RemoteAddr().String()); err == nil {
		srcHost = ap.Addr().Unmap().String()
		srcPort = int(ap.Port())
	} else {
		srcHost = f.host
	}
	head := udpHeader(srcHost, srcPort)
	buf := make([]byte, udpMaxDatagram)
	for {
		n, err := conn.Read(buf)
		if err != nil {
			break
		}
		f.lastUse.Store(time.Now().UnixNano())
		self.lock.Lock()
		client := self.client
		self.lock.Unlock()
		if client == nil {
			continue
		}
		out := make([]byte, 0, len(head)+n)
		out = append(out, head...)
		out = append(out, buf[:n]...)
		self.pc.WriteToUDP(out, client)
	}
	self.drop(f)
}

func (self *udpSession) drop(f *udpFlow) {
	self.lock.Lock()
	if self.flows[f.key] == f {
		delete(self.flows, f.key)
	}
	self.lock.Unlock()
	f.lock.Lock()
	if f.conn != nil {
		f.conn.Close()
	}
	f.lock.Unlock()
}

// reap closes flows nobody used for a while (sooner for DNS, which is one question and one answer).
func (self *udpSession) reap() {
	for {
		time.Sleep(10 * time.Second)
		self.lock.Lock()
		if self.closed {
			self.lock.Unlock()
			return
		}
		now := time.Now().UnixNano()
		idle := []*udpFlow{}
		for _, f := range self.flows {
			limit := udpFlowIdle
			if f.dnsLike {
				limit = udpDnsIdle
			}
			if time.Duration(now-f.lastUse.Load()) > limit {
				idle = append(idle, f)
			}
		}
		self.lock.Unlock()
		for _, f := range idle {
			self.drop(f)
		}
	}
}

// ── wire helpers ──

func readSocksAddr(r *bufio.Reader, kind byte) (string, error) {
	switch kind {
	case 1:
		b := make([]byte, 4)
		_, err := io.ReadFull(r, b)
		return netip.AddrFrom4([4]byte(b)).String(), err
	case 4:
		b := make([]byte, 16)
		_, err := io.ReadFull(r, b)
		return netip.AddrFrom16([16]byte(b)).Unmap().String(), err
	case 3:
		n, err := r.ReadByte()
		if err != nil {
			return "", err
		}
		b := make([]byte, n)
		_, err = io.ReadFull(r, b)
		return string(b), err
	}
	return "", errors.New("bad address type")
}

// writeReply answers a request; bound is the address to report (UDP ASSOCIATE), else 0.0.0.0:0.
func writeReply(c net.Conn, code byte, bound *net.UDPAddr) error {
	out := []byte{5, code, 0}
	if bound != nil {
		if ip4 := bound.IP.To4(); ip4 != nil {
			out = append(out, 1)
			out = append(out, ip4...)
		} else {
			out = append(out, 4)
			out = append(out, bound.IP.To16()...)
		}
		out = binary.BigEndian.AppendUint16(out, uint16(bound.Port))
	} else {
		out = append(out, 1, 0, 0, 0, 0, 0, 0)
	}
	_, err := c.Write(out)
	return err
}

func parseUdpHeader(b []byte) (host string, port int, payload []byte, ok bool) {
	// RSV(2) FRAG(1) ATYP(1) ADDR PORT(2) DATA; fragments are not supported (RFC 1928 allows dropping them)
	if len(b) < 4 || b[2] != 0 {
		return
	}
	at := 4
	switch b[3] {
	case 1:
		if len(b) < at+4+2 {
			return
		}
		host = netip.AddrFrom4([4]byte(b[at : at+4])).String()
		at += 4
	case 4:
		if len(b) < at+16+2 {
			return
		}
		host = netip.AddrFrom16([16]byte(b[at : at+16])).Unmap().String()
		at += 16
	case 3:
		if len(b) < at+1 {
			return
		}
		n := int(b[at])
		at++
		if n == 0 || len(b) < at+n+2 {
			return
		}
		host = string(b[at : at+n])
		at += n
	default:
		return
	}
	port = int(binary.BigEndian.Uint16(b[at : at+2]))
	at += 2
	return host, port, b[at:], true
}

func udpHeader(host string, port int) []byte {
	out := []byte{0, 0, 0}
	if addr, err := netip.ParseAddr(host); err == nil {
		addr = addr.Unmap()
		if addr.Is4() {
			a := addr.As4()
			out = append(out, 1)
			out = append(out, a[:]...)
		} else {
			a := addr.As16()
			out = append(out, 4)
			out = append(out, a[:]...)
		}
	} else {
		out = append(out, 3, byte(len(host)))
		out = append(out, host...)
	}
	return binary.BigEndian.AppendUint16(out, uint16(port))
}

func replyCodeOf(err error) byte {
	var ne net.Error
	switch {
	case errors.Is(err, context.DeadlineExceeded), errors.As(err, &ne) && ne.Timeout():
		return replyTtlExpired
	case errors.Is(err, syscall.ECONNREFUSED):
		return replyRefused
	case errors.Is(err, syscall.ENETUNREACH):
		return replyNetUnreach
	case errors.Is(err, syscall.EHOSTUNREACH):
		return replyHostUnreach
	case errors.Is(err, os.ErrDeadlineExceeded):
		return replyTtlExpired
	}
	return replyHostUnreach
}
