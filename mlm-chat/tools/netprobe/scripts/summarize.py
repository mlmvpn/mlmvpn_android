"""Summarise netprobe reports (netprobe-report/1) as Markdown rows for docs/PHASE0-RESULTS.fa.md.

    python -I scripts/summarize.py report-a.json report-b.json ...

One-phone tests give one row per run. Two-phone tests (peer, punch, relay) are paired across
reports by session id, so both directions of a pair end up on one row. libp2p / iroh chats,
Bluetooth, battery and TLS runs (session 2 of phase 0) get one row each.
"""
import json
import sys

ONE = ("nat", "obs_speed", "nat_lifetime", "upnp", "wifi_aware")
SPIKES = ("p2p_chat", "iroh_chat", "ble", "battery_hold", "pqtls")


def path_cell(p):
    if not p:
        return "-"
    return f"msg {p.get('message_rtt_ms')} ms, 1 MiB {p.get('upload_1mib_ms')} ms ({p.get('upload_mbps')} Mbit/s)"


def spike_row(run):
    r, t = run.get("result", {}), run.get("test")
    paths = r.get("paths") or {}
    if t == "p2p_chat":
        tr = r.get("to_relay") or {}
        d = r.get("dcutr")
        detail = (f"relay tcp {(tr.get('tcp') or {}).get('ok')} quic {(tr.get('quic') or {}).get('ok')}; found {r.get('other_phone_found_ms')} ms; "
                  f"relayed {r.get('relayed_connection_ms')} ms; DCUtR {'-' if d is None else d.get('ok')}; direct {r.get('direct')}; "
                  f"relay path: {path_cell(paths.get('relay'))}; direct path: {path_cell(paths.get('direct'))}; errors {r.get('errors')}")
    elif t == "iroh_chat":
        detail = (f"relay {r.get('relay_ms')} ms; found {r.get('other_phone_found_ms')} ms; connected {r.get('connected_ms')} ms; "
                  f"direct {r.get('direct')}; relay path: {path_cell(paths.get('relay'))}; direct path: {path_cell(paths.get('direct'))}; error {r.get('error')}")
    elif t == "ble":
        rng = r.get("range") or []
        lost = sum(1 for x in rng if x.get("lost"))
        weakest = min((x.get("rssi", 0) for x in rng), default=None)
        detail = (f"role {r.get('role')}, mtu {r.get('mtu')}, secure {r.get('secure_ms')} ms, rtt {r.get('rtt_median_ms')} ms, "
                  f"up {r.get('upload_kbps')} kbit/s, down {r.get('download_kbps')} kbit/s, range {len(rng)} samples / {lost} lost / weakest {weakest} dBm, error {r.get('error')}")
    elif t == "battery_hold":
        b0, b1 = r.get("battery_before") or {}, r.get("battery_after") or {}
        detail = (f"{r.get('hours')} h, {r.get('drain_mah_per_hour')} mAh/h, {b0.get('percent')}% -> {b1.get('percent')}%, "
                  f"connected {r.get('connected_s')} s, drops {r.get('drops')}, pings {r.get('pings_ok')} ok / {r.get('pings_failed')} failed, {r.get('warning', '')}")
    else:
        c, h = r.get("classic") or {}, r.get("hybrid") or {}
        detail = (f"X25519 {c.get('ms_median')} ms, CH {c.get('client_hello_bytes')} B, server {c.get('server_to_client_bytes')} B; "
                  f"X25519MLKEM768 {h.get('ms_median')} ms, CH {h.get('client_hello_bytes')} B, server {h.get('server_to_client_bytes')} B")
    return f"| {run.get('at','')[:16]} | {net_label(run.get('network'))} | {t} | {'ok' if r.get('ok') else 'FAILED'} | {detail} |"


def net_label(n):
    a = (n or {}).get("active") or {}
    kind = {"wifi": "Wi-Fi", "cellular": "mobile", "vpn": "VPN"}.get(a.get("transport"), a.get("transport", "?"))
    label = (n or {}).get("label") or ""
    vpn = " +VPN" if (n or {}).get("vpn_on") else ""
    return f"{label} / {(n or {}).get('carrier', '?')} / {kind}{vpn}".strip(" /")


def speed(m):
    if not m:
        return "-"
    rtt = (m.get("rtt_ms") or {}).get("med")
    up = (m.get("up") or {}).get("mbps")
    down = (m.get("down") or {}).get("mbps")
    return f"{rtt} ms, up {up} / down {down} Mbit/s"


def one_row(run):
    r, t = run.get("result", {}), run.get("test")
    if not r.get("ok", True) and "error" in r:
        return f"| {run.get('at','')[:16]} | {net_label(run.get('network'))} | {t} | error: {r['error']} |"
    if t == "nat":
        reach = ", ".join(f"{x.get('proto')}/{x.get('port')}={'ok' if x.get('ok') else 'no'}" for x in r.get("reach", []))
        um, uf, tm, v6 = r.get("udp_mapping", {}), r.get("udp_filtering", {}), r.get("tcp_mapping", {}), r.get("ipv6", {})
        inbound = (tm.get("inbound") or {}).get("accepted")
        cells = [
            f"reach {reach}",
            f"UDP map {um.get('verdict', um.get('error'))} (port kept {um.get('port_preserved')}, pooling {um.get('ip_pooling')})",
            f"UDP filter {uf.get('verdict', uf.get('error'))}",
            f"TCP map {tm.get('verdict', tm.get('error'))}, TCP inbound {inbound}",
            f"IPv6 global {v6.get('global_addresses')}, out tcp {v6.get('outbound_tcp')} udp {v6.get('outbound_udp')}, in tcp {v6.get('inbound_tcp')} udp {v6.get('inbound_udp')}",
        ]
        detail = "; ".join(cells)
    elif t == "obs_speed":
        detail = speed(r)
    elif t == "nat_lifetime":
        detail = f"UDP alive up to {r.get('udp_alive_up_to_s')} s, dead from {r.get('udp_dead_from_s')} s; TCP alive up to {r.get('tcp_alive_up_to_s')} s"
    elif t == "upnp":
        v = r.get("verify") or {}
        detail = (f"gateway {r.get('gateway')}, external {r.get('external_kind')}, behind ISP NAT {r.get('home_line_behind_isp_nat')}, "
                  f"add tcp {r.get('add_tcp')} udp {r.get('add_udp')}, from outside tcp {v.get('tcp_from_outside')} udp {v.get('udp_from_outside')}")
    elif t == "wifi_aware":
        detail = f"feature {r.get('feature')}, available {r.get('available')}, data paths {r.get('data_paths')}"
    else:
        detail = json.dumps(r, ensure_ascii=False)[:200]
    return f"| {run.get('at','')[:16]} | {net_label(run.get('network'))} | {t} | {detail} |"


def main(paths):
    # Carrier names are Persian; a Windows console defaults to a code page that cannot print them.
    sys.stdout.reconfigure(encoding="utf-8")
    reports = []
    for p in paths:
        with open(p, encoding="utf-8") as f:
            reports.append(json.load(f))
    print("## One-phone runs\n")
    print("| time | network | test | result |")
    print("|---|---|---|---|")
    for rep in reports:
        for run in rep.get("runs", []):
            if run.get("test") in ONE:
                print(one_row(run))

    # session id -> network label, from make_card runs
    sid_net = {}
    for rep in reports:
        for run in rep.get("runs", []):
            if run.get("test") == "make_card":
                sid_net[run.get("result", {}).get("sid")] = net_label(run.get("network"))

    print("\n## Two-phone runs\n")
    print("| time | this phone | other phone | test | result |")
    print("|---|---|---|---|---|")
    for rep in reports:
        for run in rep.get("runs", []):
            t, r = run.get("test"), run.get("result", {})
            if t not in ("peer", "punch", "relay"):
                continue
            other = sid_net.get(r.get("peer_sid"), "?")
            if t == "peer":
                outs = [f"{o.get('path')}={'ok ' + speed(o.get('measure')) if o.get('ok') else 'no'}" for o in r.get("outbound", [])]
                ins = [f"{i.get('transport')} from {i.get('from')}" for i in r.get("inbound", [])]
                detail = "out: " + "; ".join(outs) + " | in: " + (", ".join(ins) or "none")
            elif t == "punch":
                u, c = r.get("udp", {}), r.get("tcp", {})
                detail = (f"role {r.get('role')}; UDP/QUIC {'ok ' + speed(u.get('measure')) if u.get('ok') else 'no (' + str(u.get('error')) + ')'}; "
                          f"TCP {'ok ' + speed(c.get('measure')) if c.get('ok') else 'no (' + str(c.get('error') or c.get('errors')) + ')'}")
            else:
                detail = f"role {r.get('role')}; {'ok ' + speed(r.get('measure')) if r.get('ok') else 'no (' + str(r.get('error')) + ')'}"
            print(f"| {run.get('at','')[:16]} | {net_label(run.get('network'))} | {other} | {t} | {detail} |")

    print("\n## libp2p, iroh, Bluetooth, battery, TLS\n")
    print("| time | network | test | ok | result |")
    print("|---|---|---|---|---|")
    for rep in reports:
        for run in rep.get("runs", []):
            if run.get("test") in SPIKES:
                print(spike_row(run))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1:])
