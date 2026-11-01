// mlmcore: URnetwork's SDK (github.com/urnetwork/sdk, MPL-2.0) as MLMVPN's own core,
// liburnetwork.so, run by the app as a child process.
//
// One binary, four jobs, picked by flags. Every job takes -state DIR, a folder of its own (the
// tile's, MAE's, Scout's), where the SDK keeps this device's sign-in and settings, and -account
// FILE, the account's network token, shared by every job and written only by -create and -restore.
//
//	-create             make an instant account (the user accepted URnetwork's terms in the app):
//	                    write -account, print "[ur] account <name>" and "[ur] seed <phrase>", exit
//	-restore            read a recovery phrase on stdin, sign in, write -account, exit
//	-api balance|locations -out FILE
//	                    one call with the account token; its answer as JSON in FILE, then exit
//	(none of those)     run a device: connect to URnetwork's providers and carry traffic --
//	  -socks ADDR       a local SOCKS5 server (CONNECT and UDP ASSOCIATE) through the providers
//	  -tun-sock PATH    a unix socket the VPN service hands its TUN descriptor to; the TUN's
//	                    packets then go to the providers on the SDK's own packet path
//	  -location JSON    an sdk.ConnectLocation; empty means the best available
//	  -blocker          URnetwork's own ad and tracker blocker on from the start
//	  -rules FILE       routing rules: hosts and subnets routed locally around the providers
//	                    (smart Iran, «outside the tunnel») or dropped («blocked»)
//	  -dns LIST         the phone's resolvers, for the few names this process resolves itself
//	  -control          commands on stdin (blocker, location); exit when stdin closes
//
// The app reads only the lines starting "[ur] ": "listening", "ev <json>", "error <text>".
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/urnetwork/sdk"
)

const appVersion = "mlmvpn-android"

var outLock sync.Mutex

// say writes one contract line. One write per line, so a line never interleaves with the
// SDK's own stderr output on the shared pipe.
func say(format string, a ...any) {
	line := "[ur] " + fmt.Sprintf(format, a...) + "\n"
	outLock.Lock()
	os.Stdout.WriteString(line)
	outLock.Unlock()
}

// emit writes one event: "[ur] ev {"t": kind, ...}".
func emit(kind string, fields map[string]any) {
	if fields == nil {
		fields = map[string]any{}
	}
	fields["t"] = kind
	b, err := json.Marshal(fields)
	if err != nil {
		return
	}
	say("ev %s", b)
}

func fail(code int, format string, a ...any) {
	say("error "+format, a...)
	os.Exit(code)
}

// Exit codes the app tells apart.
const (
	exitError     = 1 // anything else
	exitNoAccount = 2 // no account token
	exitRefused   = 3 // the platform refused this device (client limit, plan)
	exitLoggedOut = 4 // the account's sign-in is no longer valid
)

type spaceUpdate struct{}

// The space the official apps use: ur.network's names, served from bringyour.com, with the
// official apps' connectivity settings (urnetwork/android app/build.gradle, BRINGYOUR_BUNDLE_*).
//
// NetExposeServerIps and NetExposeServerHostNames are what let the client strategy dial the
// platform directly -- its "normal" and "fragment" dialers exist only with both on. Left at their
// zero value, only extenders, VLESS and proxies may carry the platform connection: the api still
// answered over the alt carriers, but the provider transport never connected and no provider ever
// joined a window (measured on the owner's Wi-Fi, 2026-10-19, while the official app on the same
// line had twelve).
func (spaceUpdate) Update(v *sdk.NetworkSpaceValues) {
	v.MigrationHostName = "bringyour.com"
	v.NetExposeServerIps = true
	v.NetExposeServerHostNames = true
	v.LinkHostName = "ur.io"
}

func openSpace(state string) *sdk.NetworkSpace {
	mgr := sdk.NewNetworkSpaceManager(filepath.Join(state, "spaces"))
	return mgr.UpdateNetworkSpace(sdk.NewNetworkSpaceKey("ur.network", "main"), spaceUpdate{})
}

func main() {
	state := flag.String("state", "", "this job's folder")
	account := flag.String("account", "", "the account's network token file")
	create := flag.Bool("create", false, "make an instant account")
	restore := flag.Bool("restore", false, "sign in with a recovery phrase read on stdin")
	apiCall := flag.String("api", "", "balance | locations")
	out := flag.String("out", "", "where -api writes its answer")
	socks := flag.String("socks", "", "SOCKS5 listen address")
	tunSock := flag.String("tun-sock", "", "unix socket for the TUN descriptor")
	location := flag.String("location", "", "connect location (JSON); empty = best available")
	blocker := flag.Bool("blocker", false, "the ad and tracker blocker on")
	rules := flag.String("rules", "", "routing rules file (local / block)")
	dns := flag.String("dns", "", "the phone's resolvers, comma separated")
	memory := flag.Int("memory", 64, "memory target, MiB")
	desc := flag.String("desc", "MLMVPN", "this device's name in the account")
	country := flag.String("country", "", "the mobile network's country code, when on mobile data")
	control := flag.Bool("control", false, "commands on stdin; exit when it closes")
	degraded := flag.Bool("degraded", false, "the phone is saving power or held back (battery saver, heat, Data Saver, a congested network)")
	flag.Parse()

	if *state == "" || *account == "" {
		fail(exitError, "-state and -account are required")
	}
	os.MkdirAll(*state, 0700)
	sdk.SetLogDir(filepath.Join(*state, "logs"))
	installResolver(*dns)
	// Before any space is built: on a mobile network the extender dials front with that country's
	// list while the extender hint cannot be fetched (the official apps report it the same way).
	sdk.SetNetworkCountryCode(strings.ToLower(strings.TrimSpace(*country)))

	switch {
	case *create:
		createAccount(*state, *account)
	case *restore:
		phrase, _ := bufio.NewReader(os.Stdin).ReadString('\n')
		restoreAccount(*state, *account, strings.TrimSpace(phrase))
	case *apiCall != "":
		if *out == "" {
			fail(exitError, "-api needs -out")
		}
		callApi(*state, *account, *apiCall, *out)
	default:
		run(runOptions{
			state:    *state,
			account:  *account,
			socks:    *socks,
			tunSock:  *tunSock,
			location: *location,
			blocker:  *blocker,
			rules:    *rules,
			memory:   *memory,
			desc:     *desc,
			control:  *control,
			degraded: *degraded,
		})
	}
}

// syncResult turns one of the SDK's asynchronous API calls into a blocking one.
type syncResult[T any] struct {
	done chan struct{}
	once sync.Once
	r    T
	err  error
}

func newSyncResult[T any]() *syncResult[T] { return &syncResult[T]{done: make(chan struct{})} }

func (self *syncResult[T]) Result(r T, err error) {
	self.once.Do(func() {
		self.r, self.err = r, err
		close(self.done)
	})
}

func (self *syncResult[T]) wait(timeout time.Duration) (T, error) {
	select {
	case <-self.done:
		return self.r, self.err
	case <-time.After(timeout):
		var zero T
		return zero, fmt.Errorf("no answer from URnetwork in %s", timeout)
	}
}
