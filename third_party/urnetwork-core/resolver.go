package main

import (
	"context"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

// installResolver points Go's resolver at the phone's own resolvers.
//
// This core is built without cgo, and Go's own resolver reads /etc/resolv.conf, which Android does
// not have -- its DNS lives in system properties -- so left alone every name this process looks up
// itself would go to 127.0.0.1:53 and fail. The SDK resolves its own servers over DoH and does not
// care; what needs this is the little this process dials directly: the smart-Iran destinations it
// keeps off the providers. The app passes the active network's resolvers; without any, two public
// ones.
func installResolver(list string) {
	setResolvers(list)
	var next atomic.Uint32
	dialer := &net.Dialer{Timeout: 5 * time.Second}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network string, address string) (net.Conn, error) {
			servers := *resolvers.Load()
			server := servers[int(next.Add(1))%len(servers)]
			return dialer.DialContext(ctx, network, server)
		},
	}
}

// The resolvers the process dials, replaced whole when the phone moves to another network (the
// router's resolver of the Wi-Fi left behind answers nothing on mobile data).
var resolvers atomic.Pointer[[]string]

func setResolvers(list string) {
	servers := []string{}
	for _, s := range strings.Split(list, ",") {
		s = strings.TrimSpace(s)
		if ip, err := netip.ParseAddr(s); err == nil {
			servers = append(servers, net.JoinHostPort(ip.Unmap().String(), "53"))
		} else if ap, err := netip.ParseAddrPort(s); err == nil {
			// "[2001:db8::1]:53", the form the app gives IPv6 resolvers in
			servers = append(servers, net.JoinHostPort(ap.Addr().Unmap().String(), strconv.Itoa(int(ap.Port()))))
		}
	}
	if len(servers) == 0 {
		servers = []string{"1.1.1.1:53", "8.8.8.8:53"}
	}
	resolvers.Store(&servers)
}
