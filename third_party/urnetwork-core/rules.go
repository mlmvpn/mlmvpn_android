package main

import (
	"bufio"
	"net/netip"
	"os"
	"strings"

	"github.com/urnetwork/sdk"
)

// The routing rules the app writes, one a line: "local <entry>" leaves on the phone's own
// connection and never reaches a provider (smart Iran, the user's «outside the tunnel» list);
// "block <entry>" is dropped (the user's «blocked» list). An entry is an IPv4 or IPv6 subnet or
// address, or a host pattern: "**.x" is x and every name under it, "*.x" every name under it,
// anything else that exact name.
type ruleList struct {
	local *matcher
	block *matcher
}

type matcher struct {
	prefixes []netip.Prefix
	// "**.x": x itself (bases) and every name under it (suffixes); "*.x": only under it
	suffixes []string
	bases    map[string]bool
	exact    map[string]bool
	entries  []string
}

func newMatcher() *matcher {
	return &matcher{bases: map[string]bool{}, exact: map[string]bool{}}
}

func (self *matcher) add(entry string) {
	entry = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(entry)), ".")
	if entry == "" {
		return
	}
	if p, err := netip.ParsePrefix(entry); err == nil {
		self.prefixes = append(self.prefixes, p.Masked())
		self.entries = append(self.entries, p.Masked().String())
		return
	}
	if a, err := netip.ParseAddr(entry); err == nil {
		p := netip.PrefixFrom(a.Unmap(), a.Unmap().BitLen())
		self.prefixes = append(self.prefixes, p)
		self.entries = append(self.entries, p.String())
		return
	}
	if base, ok := strings.CutPrefix(entry, "**."); ok && base != "" {
		self.suffixes = append(self.suffixes, "."+base)
		self.bases[base] = true
		self.entries = append(self.entries, entry)
		return
	}
	if base, ok := strings.CutPrefix(entry, "*."); ok && base != "" {
		self.suffixes = append(self.suffixes, "."+base)
		self.entries = append(self.entries, entry)
		return
	}
	if !strings.ContainsAny(entry, " /*") {
		self.exact[entry] = true
		self.entries = append(self.entries, entry)
	}
}

func (self *matcher) empty() bool { return self == nil || len(self.entries) == 0 }

// matches answers for the local SOCKS server, which sees names before anything resolves them.
func (self *matcher) matches(host string) bool {
	if self.empty() {
		return false
	}
	h := strings.TrimSuffix(strings.ToLower(host), ".")
	if addr, err := netip.ParseAddr(h); err == nil {
		addr = addr.Unmap()
		for _, p := range self.prefixes {
			if p.Contains(addr) {
				return true
			}
		}
		return false
	}
	if self.exact[h] || self.bases[h] {
		return true
	}
	for _, suffix := range self.suffixes {
		if strings.HasSuffix(h, suffix) {
			return true
		}
	}
	return false
}

func loadRules(path string) *ruleList {
	if path == "" {
		return nil
	}
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	l := &ruleList{local: newMatcher(), block: newMatcher()}
	s := bufio.NewScanner(f)
	s.Buffer(make([]byte, 64*1024), 1024*1024)
	for s.Scan() {
		line := strings.TrimSpace(s.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		verb, entry, ok := strings.Cut(line, " ")
		if !ok {
			continue
		}
		switch verb {
		case "local":
			l.local.add(entry)
		case "block":
			l.block.add(entry)
		}
	}
	if l.local.empty() && l.block.empty() {
		return nil
	}
	return l
}

func (self *ruleList) has() (local bool, block bool) {
	if self == nil {
		return false, false
	}
	return !self.local.empty(), !self.block.empty()
}

func (self *ruleList) direct(host string) bool { return self != nil && self.local.matches(host) }

func (self *ruleList) blocked(host string) bool { return self != nil && self.block.matches(host) }

// overrides is the list as URnetwork's own routing rules, applied by the SDK on the packet path to
// addresses and to the names it saw resolved. Fixed ids: the same rules replace themselves on every
// start instead of piling up in the folder's saved settings.
func (self *ruleList) overrides() *sdk.BlockActionOverrideList {
	list := sdk.NewBlockActionOverrideList()
	if self == nil {
		return list
	}
	if !self.local.empty() {
		id, _ := sdk.ParseId("6d6c6d76-706e-4c6f-6361-6c2d726f7574")
		list.Add(&sdk.BlockActionOverride{
			OverrideId:    id,
			Hosts:         stringListOf(self.local.entries),
			RouteOverride: &sdk.RouteOverride{Local: true},
		})
	}
	if !self.block.empty() {
		id, _ := sdk.ParseId("6d6c6d76-706e-426c-6f63-6b2d726f7574")
		list.Add(&sdk.BlockActionOverride{
			OverrideId:    id,
			Hosts:         stringListOf(self.block.entries),
			BlockOverride: &sdk.BlockOverride{Block: true},
		})
	}
	return list
}

func stringListOf(values []string) *sdk.StringList {
	l := sdk.NewStringList()
	for _, v := range values {
		l.Add(v)
	}
	return l
}
