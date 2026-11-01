# URnetwork core (`liburnetwork.so`)

The «URnetwork» tile runs URnetwork's own SDK ([github.com/urnetwork/sdk](https://github.com/urnetwork/sdk),
MPL-2.0, © BringYour, Inc.) as a child process of the app, built from the Go files in this folder.
The SDK itself is used unmodified; these files are MLMVPN's own program around it.

It is not URnetwork's gomobile AAR: that would clash with `libv2ray.aar` (both are gomobile builds,
see `third_party/README.md`), and a Go crash in a child process ends the core, not the app.

## What the program does

One binary, picked by flags (see the comment at the top of `main.go`):

| job | flags | used by |
|---|---|---|
| make an instant account (terms accepted on the screen) | `-create` | the onboarding screen |
| sign in with a recovery phrase | `-restore` (phrase on stdin) | the account page |
| one API call (`/subscription/balance`, `/network/provider-locations`) | `-api balance\|locations -out FILE` | the usage card, the location picker |
| run a device | `-socks ADDR [-tun-sock PATH] [-location JSON] [-blocker] [-rules FILE] -control` | the tile, MAE, MLM Scout |

- **The tile** hands its VPN TUN to the core over a unix socket (`-tun-sock`, SCM_RIGHTS) and the
  core carries it on the SDK's own packet path (`sdk.NewIoLoop`), the path URnetwork's official
  apps use. The TUN takes the addresses and the resolver the SDK chose (`tunaddr` event); plain
  DNS to that resolver is upgraded to DoH inside the tunnel, where URnetwork's ad blocker works.
- **MAE and Scout** use the core's local SOCKS5 server (CONNECT and UDP ASSOCIATE through the
  providers). The same server serves LAN sharing for the tile.
- **Routing rules** (`-rules`): `local` entries are routed around the providers with the SDK's own
  per-host routing overrides (smart Iran: every Iranian range and every `.ir` name; the transport's
  «outside the tunnel» list), `block` entries are dropped (the «blocked» list).
- Every job runs in a folder of its own (`files/urnetwork/<job>`), as a device of its own in the
  account; the account's network token is one file all of them read (`files/urnetwork/account/jwt`).
- Events go to stdout as `[ur] ev {json}` lines: the provider window (`window`), the providers'
  countries (`providers`), the contract status (`contract`: balance used up), the client limit
  (`limit`), the blocker and its counts, the TUN's addresses, sign-in refusals. The app reads
  nothing else (`core/urnetwork/UrnetworkEngine.kt`).

## Sources (pinned)

| module | repository | commit |
|---|---|---|
| sdk | https://github.com/urnetwork/sdk | `a9197cdc58535131442ee683b1dc32096f0245aa` |
| connect | https://github.com/urnetwork/connect | `3514941067d8939b6d661ff9875aa773e97b259b` |
| glog | https://github.com/urnetwork/glog | `fe810311aa908259ff133ce502bbf0618dd46a0c` |
| goidenticons | https://github.com/urnetwork/goidenticons | `e94d63579c6b9031d8e110d75cb1f0a9d15ea30a` |
| gvisor | https://github.com/urnetwork/gvisor | `c0783dba2eeff7b79692a7f122ffee5f8bd837c1` |

`go.mod` replaces them with sibling folders: clone each at its commit next to a folder holding these
files (`../sdk`, `../connect`, …), the way `go.mod` names them. `connect`'s `sctp` replacement is
inside the connect checkout.

## Build

Go 1.26, no cgo, arm64 only (Go cannot build `android/arm` without cgo):

```bash
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -trimpath -ldflags "-s -w -checklinkname=0" -o liburnetwork.so .
```

`-checklinkname=0` is required: `wlynxg/anet` (Android interface listing) links to runtime
internals. `build.sh` is the same command with the module and build caches on the build machine.
Copy the result to `app/src/main/jniLibs/arm64-v8a/liburnetwork.so` (about 48 MB; it is
compressed in the APK).
