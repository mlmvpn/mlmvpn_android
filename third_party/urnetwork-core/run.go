package main

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/urnetwork/sdk"
)

type runOptions struct {
	state    string
	account  string
	socks    string
	tunSock  string
	location string
	blocker  bool
	rules    string
	memory   int
	desc     string
	control  bool
	degraded bool
}

const deviceSpec = "android"

func run(o runOptions) {
	token := readAccount(o.account)
	if token == "" {
		fail(exitNoAccount, "no account")
	}
	emit("stage", map[string]any{"s": "start"})
	// The process-wide budget the SDK sizes its pools and Go's soft limit by, as the official apps
	// set it; the device's own target is within it.
	sdk.SetMemoryLimit(int64(o.memory) * 2 * 1024 * 1024)
	prepareFolder(o.state, token)
	space := openSpace(o.state)
	localState := space.GetAsyncLocalState().GetLocalState()

	clientJwt, instanceId := clientFor(space, localState, token, o.desc)

	settings := sdk.DefaultDeviceLocalSettings()
	settings.MemoryTargetByteCount = sdk.ByteCount(o.memory) * 1024 * 1024
	// This phone never relays for anyone else, and never routes past the providers on its own.
	settings.ProvideExtenderEnabled = false
	settings.DefaultProvideExtender = false
	settings.DefaultRouteLocal = false
	settings.DefaultBlockerEnabled = o.blocker
	device, err := sdk.NewDeviceLocal(space, clientJwt, o.desc, deviceSpec, appVersion, instanceId, settings)
	if err != nil {
		fail(exitError, "device: %v", err)
	}
	// No provider path while nothing is connected: traffic waits for the providers instead of
	// leaving on the phone's own connection (the SDK's "route local" is its fail-open).
	device.SetRouteLocal(false)
	device.SetProvideMode(sdk.ProvideModeNone)
	device.SetBlockerEnabled(o.blocker)
	rules := loadRules(o.rules)
	device.SetBlockActionOverrides(rules.overrides())
	// As the official app reports battery saver, heat, Data Saver and a congested network: the
	// SDK eases its liveness timings and rests 3x longer between idle pings, so an idle tunnel
	// wakes the radio less often. Kept by the device for every window it builds.
	device.SetPerformanceDegraded(o.degraded)

	w := newWatcher(device)
	w.attach()
	local, block := rules.has()
	emit("stage", map[string]any{"s": "device", "blocker": device.GetBlockerEnabled(), "local": local, "block": block, "degraded": o.degraded})

	var socks *socksServer
	if o.socks != "" {
		ln, err := net.Listen("tcp", o.socks)
		if err != nil {
			fail(exitError, "listen %s: %v", o.socks, err)
		}
		socks = newSocksServer(device)
		socks.setBlocker(o.blocker)
		socks.rules.Store(rules)
		socks.onBlock = w.blocksChanged
		w.socks = socks
		go socks.serve(ln)
		say("listening %s", ln.Addr().String())
	}
	if o.tunSock != "" {
		if err := serveTun(device, o.tunSock); err != nil {
			fail(exitError, "tun socket: %v", err)
		}
		emit("tunaddr", map[string]any{
			"v4":   device.TunnelLocalAddress(),
			"v6":   device.TunnelLocalAddressIpv6(),
			"p6":   sdk.GetTunnelLocalPrefixLengthIpv6(),
			"dns4": strings.Join(stringList(device.TunnelDnsAddressesIpv4()), ","),
			"dns6": strings.Join(stringList(device.TunnelDnsAddressesIpv6()), ","),
			"mtu":  sdk.GetDefaultTunnelMtu(),
			"sock": o.tunSock,
		})
	}

	device.SetConnectLocation(parseLocation(o.location))
	emit("stage", map[string]any{"s": "connect", "location": describeLocation(device.GetConnectLocation())})
	w.windowChanged(device.GetWindowStatus())

	if !o.control {
		select {}
	}
	r := bufio.NewReader(os.Stdin)
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			if err == io.EOF || line == "" {
				break
			}
		}
		command(device, socks, w, strings.TrimSpace(line))
	}
	device.Close()
	os.Exit(0)
}

func command(device *sdk.DeviceLocal, socks *socksServer, w *watcher, line string) {
	verb, arg, _ := strings.Cut(line, " ")
	arg = strings.TrimSpace(arg)
	switch verb {
	case "blocker":
		on := arg == "1" || arg == "on" || arg == "true"
		device.SetBlockerEnabled(on)
		if socks != nil {
			socks.setBlocker(on)
		}
	case "location":
		device.SetConnectLocation(parseLocation(arg))
		emit("stage", map[string]any{"s": "connect", "location": describeLocation(device.GetConnectLocation())})
	case "reconnect":
		// A rebuild from nothing: the user's own "connect again". Never for a network change.
		device.Reconnect(device.GetConnectLocation())
		emit("stage", map[string]any{"s": "connect", "location": describeLocation(device.GetConnectLocation())})
	case "network":
		// The phone's path changed (Wi-Fi <-> mobile, a lost network): the transports redial over
		// the new path at once and the providers stay, as the official app does.
		device.NetworkChanged()
		emit("network", map[string]any{"changed": true})
	case "quality":
		// Same network, another radio or link estimate: the SDK remeasures its pacing.
		device.NetworkQualityChanged()
	case "probe":
		// Qualify every exit in the window now (the official app's wake audit and recovery), not
		// at the background sweep's own pace.
		emit("probe", map[string]any{"n": device.ProbeAllExits()})
	case "dns":
		// The new network's resolvers, for the destinations this process dials itself.
		setResolvers(arg)
	case "country":
		// The mobile network's country while on mobile data, "" on anything else (see -country).
		sdk.SetNetworkCountryCode(strings.ToLower(arg))
	case "degraded":
		on := arg == "1" || arg == "on" || arg == "true"
		device.SetPerformanceDegraded(on)
		emit("degraded", map[string]any{"on": on})
	case "rules":
		var l *ruleList
		if arg != "" && arg != "off" {
			l = loadRules(arg)
		}
		device.SetBlockActionOverrides(l.overrides())
		if socks != nil {
			socks.rules.Store(l)
		}
		local, block := l.has()
		emit("rules", map[string]any{"local": local, "block": block})
	case "status":
		w.windowChanged(device.GetWindowStatus())
		w.providersChanged()
		w.ContractStatusChanged(device.GetContractStatus())
	case "quit":
		device.Close()
		os.Exit(0)
	}
}

// The layout of a job's folder this build writes; anything else is started over.
const folderLayout = "2"

// prepareFolder: a job's folder belongs to one account and holds that job's own client and
// nothing else. It is started over when the account changed (restored, signed in again) and when
// an earlier build left the account's network token inside: the SDK renews a network token it
// finds in a folder, and several jobs renewing one token -- or two processes refreshing one client
// -- is how a sign-in gets revoked (seen on the owner's phone, 2026-10-19). The network token stays
// in the account file, renewed by its one owner (callApi).
func prepareFolder(state string, token string) {
	marker := filepath.Join(state, "layout")
	want := folderLayout + " " + claimsOf(token).network
	if b, err := os.ReadFile(marker); err == nil && strings.TrimSpace(string(b)) == want {
		return
	}
	if _, err := os.Stat(filepath.Join(state, "spaces")); err == nil {
		emit("stage", map[string]any{"s": "account-switch"})
	}
	os.RemoveAll(filepath.Join(state, "spaces"))
	os.WriteFile(marker, []byte(want), 0600)
}

// clientFor returns this job's own client in the account -- made once, kept in the folder --
// so the tile, MAE and Scout are three devices of one account and never take each other's place.
// The network token only signs the registration: the folder keeps the client alone.
func clientFor(space *sdk.NetworkSpace, localState *sdk.LocalState, token string, desc string) (string, *sdk.Id) {
	clientJwt := localState.GetByClientJwt()
	instanceId := localState.GetInstanceId()
	if clientJwt != "" && instanceId != nil {
		return clientJwt, instanceId
	}
	emit("stage", map[string]any{"s": "register"})
	api := space.GetApi()
	api.SetByJwt(token)
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	res, err := api.AuthNetworkClientSyncWithContext(ctx, &sdk.AuthNetworkClientArgs{
		DeviceDescription: desc,
		DeviceSpec:        deviceSpec,
	})
	cancel()
	if err != nil {
		if isAuthError(err) {
			emit("logout", map[string]any{"cause": "register: " + apiError(err)})
			fail(exitLoggedOut, "auth %s", apiError(err))
		}
		fail(exitError, "register: %s", apiError(err))
	}
	if res == nil {
		fail(exitError, "register: empty answer")
	}
	if res.Error != nil {
		why := "refused"
		if res.Error.ClientLimitExceeded {
			why = "client_limit"
		} else if res.Error.UpgradeRequired {
			why = "upgrade_required"
		}
		emit("refused", map[string]any{"why": why, "message": res.Error.Message})
		fail(exitRefused, "%s: %s", why, res.Error.Message)
	}
	if res.ByClientJwt == "" {
		fail(exitError, "register: no client in the answer")
	}
	// The SDK pairs a new client with a new instance of its own making.
	if err := localState.SetByClientJwt(res.ByClientJwt); err != nil {
		fail(exitError, "register: %v", err)
	}
	instanceId = localState.GetInstanceId()
	if instanceId == nil {
		fail(exitError, "register: no instance for the new client")
	}
	emit("stage", map[string]any{"s": "registered"})
	return res.ByClientJwt, instanceId
}

func parseLocation(arg string) *sdk.ConnectLocation {
	arg = strings.TrimSpace(arg)
	if arg != "" && arg != "best" {
		loc := &sdk.ConnectLocation{}
		if err := json.Unmarshal([]byte(arg), loc); err == nil && loc.ConnectLocationId != nil {
			return loc
		}
	}
	return &sdk.ConnectLocation{ConnectLocationId: &sdk.ConnectLocationId{BestAvailable: true}}
}

func describeLocation(loc *sdk.ConnectLocation) map[string]any {
	if loc == nil || loc.ConnectLocationId == nil || loc.ConnectLocationId.BestAvailable {
		return map[string]any{"best": true}
	}
	return map[string]any{"name": loc.Name, "cc": strings.ToLower(loc.CountryCode), "type": loc.LocationType}
}

func stringList(l *sdk.StringList) []string {
	out := []string{}
	if l == nil {
		return out
	}
	for i := 0; i < l.Len(); i++ {
		out = append(out, l.Get(i))
	}
	return out
}

// ── what the device tells the app ──

type watcher struct {
	device *sdk.DeviceLocal
	socks  *socksServer

	lock        sync.Mutex
	window      *sdk.WindowStatus
	windowDirty bool
	blocks      *sdk.BlockStats
	blocksDirty bool
}

func newWatcher(device *sdk.DeviceLocal) *watcher {
	return &watcher{device: device}
}

func (self *watcher) attach() {
	self.device.AddWindowStatusChangeListener(self)
	self.device.AddConnectedProviderLocationChangeListener(self)
	self.device.AddContractStatusChangeListener(self)
	self.device.AddClientLimitStatusChangeListener(self)
	self.device.AddBlockerEnabledChangeListener(self)
	self.device.AddBlockStatsChangeListener(self)
	self.device.AddAuthLogoutListener(self)
	go self.flush()
}

func (self *watcher) WindowStatusChanged(s *sdk.WindowStatus) { self.windowChanged(s) }

func (self *watcher) windowChanged(s *sdk.WindowStatus) {
	if s == nil {
		return
	}
	self.lock.Lock()
	self.window = s
	self.windowDirty = true
	self.lock.Unlock()
}

func (self *watcher) BlockStatsChanged(s *sdk.BlockStats) {
	self.lock.Lock()
	self.blocks = s
	self.blocksDirty = true
	self.lock.Unlock()
}

func (self *watcher) blocksChanged() {
	self.lock.Lock()
	self.blocksDirty = true
	self.lock.Unlock()
}

// flush sends the window and the block counts at most four times a second: they change on every
// provider event, and the screen needs the latest, not each one.
func (self *watcher) flush() {
	for {
		time.Sleep(250 * time.Millisecond)
		self.lock.Lock()
		w := self.window
		wd := self.windowDirty
		self.windowDirty = false
		b := self.blocks
		bd := self.blocksDirty
		self.blocksDirty = false
		self.lock.Unlock()
		if wd && w != nil {
			emit("window", map[string]any{
				"gen":        w.ConnectionGeneration,
				"target":     w.TargetSize,
				"min":        w.MinSatisfied,
				"eval":       w.ProviderStateInEvaluation,
				"evalFailed": w.ProviderStateEvaluationFailed,
				"notAdded":   w.ProviderStateNotAdded,
				"added":      w.ProviderStateAdded,
				"removed":    w.ProviderStateRemoved,
				"dual":       w.ProviderDualstackCount,
				"v4only":     w.ProviderV4OnlyCount,
				"v6only":     w.ProviderV6OnlyCount,
				"v6":         w.Ipv6Available,
				"stall":      w.StallReason,
				"failed":     w.Failed,
			})
		}
		if bd {
			blocked, allowed := int64(0), int64(0)
			if b != nil {
				blocked, allowed = int64(b.BlockedCount), int64(b.AllowedCount)
			}
			if self.socks != nil {
				blocked += self.socks.blocked.Load()
			}
			emit("blocks", map[string]any{"blocked": blocked, "allowed": allowed})
		}
	}
}

func (self *watcher) ConnectedProviderLocationsChanged() { self.providersChanged() }

// providersChanged: where the connected providers are, as countries with counts.
func (self *watcher) providersChanged() {
	list := self.device.GetConnectedProviderLocations()
	type country struct {
		Cc   string `json:"cc"`
		Name string `json:"name"`
		N    int    `json:"n"`
	}
	byCc := map[string]*country{}
	total := 0
	if list != nil {
		for i := 0; i < list.Len(); i++ {
			p := list.Get(i)
			if p == nil {
				continue
			}
			total++
			cc := strings.ToLower(p.CountryCode)
			if !p.HasLocation || cc == "" {
				cc = "?"
			}
			c := byCc[cc]
			if c == nil {
				c = &country{Cc: cc, Name: p.Country}
				byCc[cc] = c
			}
			c.N++
		}
	}
	countries := make([]*country, 0, len(byCc))
	for _, c := range byCc {
		countries = append(countries, c)
	}
	sort.Slice(countries, func(i, j int) bool {
		if countries[i].N != countries[j].N {
			return countries[i].N > countries[j].N
		}
		return countries[i].Cc < countries[j].Cc
	})
	emit("providers", map[string]any{"n": total, "countries": countries})
}

func (self *watcher) ContractStatusChanged(s *sdk.ContractStatus) {
	if s == nil {
		return
	}
	emit("contract", map[string]any{"insufficient": s.InsufficientBalance, "noPermission": s.NoPermission, "premium": s.Premium})
}

func (self *watcher) ClientLimitStatusChanged(s *sdk.ClientLimitStatus) {
	if s == nil {
		return
	}
	emit("limit", map[string]any{"exceeded": s.Status == sdk.ClientLimitStatusExceeded, "retry": s.RetryTime})
}

func (self *watcher) BlockerEnabledChanged(on bool) {
	emit("blocker", map[string]any{"on": on})
}

func (self *watcher) AuthLogout() {
	emit("logout", map[string]any{"cause": "client: " + self.device.GetAuthLogoutCause()})
	// The SDK cleared this folder's client; the next start registers a new one from the account.
	go func() {
		time.Sleep(200 * time.Millisecond)
		os.Exit(exitLoggedOut)
	}()
}
