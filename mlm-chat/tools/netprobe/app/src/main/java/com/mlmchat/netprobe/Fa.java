package com.mlmchat.netprobe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Persian summaries of the native results, for the screen. The JSON report keeps the details. */
final class Fa {
    private Fa() {}

    private static final Locale FA = new Locale("fa");

    static String num(double v) {
        if (v == Math.rint(v)) return String.format(FA, "%d", (long) v);
        return String.format(FA, "%.1f", v);
    }

    static String yes(boolean b) {
        return b ? "بله" : "نه";
    }

    static String mapping(String v) {
        switch (v) {
            case "no-nat": return "بدون NAT (نشانی عمومی روی خود گوشی)";
            case "endpoint-independent": return "مستقل از مقصد (بهترین حالت برای سوراخ‌زنی)";
            case "endpoint-independent-same-ip-only": return "مستقل از درگاه مقصد (با یک ناظر؛ نشانی دوم سنجیده نشد)";
            case "endpoint-independent-other-ip-only": return "مستقل از مقصد (فقط با ناظر دوم سنجیده شد)";
            case "address-dependent": return "وابسته به نشانی مقصد";
            case "address-and-port-dependent": return "وابسته به نشانی و درگاه مقصد (متقارن؛ سوراخ‌زنی سخت)";
            case "dependent": return "وابسته به مقصد";
            default: return "نامعلوم";
        }
    }

    static String filtering(String v) {
        switch (v) {
            case "endpoint-independent": return "باز برای هر فرستنده";
            case "address-dependent": return "فقط از نشانی‌ای که به آن فرستاده‌ایم";
            case "address-dependent-or-independent": return "از درگاه دیگرِ همان نشانی می‌رسد (ناظر دوم لازم است تا دقیق شود)";
            case "address-and-port-dependent": return "فقط از همان نشانی و درگاه (سخت‌گیر)";
            default: return "نامعلوم (حتی پاسخ از همان نشانی هم نرسید)";
        }
    }

    private static String speed(JSONObject m) {
        if (m == null) return "";
        StringBuilder b = new StringBuilder();
        JSONObject rtt = m.optJSONObject("rtt_ms");
        if (rtt != null) b.append("تأخیر رفت‌وبرگشت میانه ").append(num(rtt.optDouble("med"))).append(" میلی‌ثانیه");
        JSONObject up = m.optJSONObject("up");
        if (up != null) b.append(" · بارگذاری ").append(num(up.optDouble("mbps"))).append(" مگابیت");
        JSONObject down = m.optJSONObject("down");
        if (down != null) b.append(" · بارگیری ").append(num(down.optDouble("mbps"))).append(" مگابیت");
        return b.toString();
    }

    static String summary(String cmd, JSONObject r) {
        if (r == null) return "پاسخی نیامد.";
        if ("p2p_chat".equals(cmd) || "iroh_chat".equals(cmd)) return chat(cmd, r);
        if (!r.optBoolean("ok")) return "خطا: " + r.optString("error");
        if ("pqtls".equals(cmd)) return pqtls(r);
        StringBuilder b = new StringBuilder();
        switch (cmd) {
            case "nat": {
                JSONArray reach = r.optJSONArray("reach");
                if (reach != null) {
                    b.append("دسترسی به ناظر:\n");
                    for (int i = 0; i < reach.length(); i++) {
                        JSONObject x = reach.optJSONObject(i);
                        b.append("  ").append(x.optString("observer")).append(" ").append(x.optString("proto").toUpperCase(Locale.US))
                                .append(" ").append(num(x.optInt("port"))).append(": ").append(x.optBoolean("ok") ? "می‌رسد" : "نمی‌رسد (" + x.optString("error") + ")").append('\n');
                    }
                }
                JSONObject um = r.optJSONObject("udp_mapping");
                if (um != null) b.append("نگاشت UDP: ").append(um.has("error") ? "نشد (" + um.optString("error") + ")" : mapping(um.optString("verdict"))
                        + " · درگاه حفظ شد: " + yes(um.optBoolean("port_preserved"))).append('\n');
                JSONObject uf = r.optJSONObject("udp_filtering");
                if (uf != null) b.append("پالایش UDP: ").append(uf.has("error") ? "نشد (" + uf.optString("error") + ")" : filtering(uf.optString("verdict"))).append('\n');
                JSONObject tm = r.optJSONObject("tcp_mapping");
                if (tm != null) {
                    b.append("نگاشت TCP: ").append(tm.has("error") ? "نشد (" + tm.optString("error") + ")" : mapping(tm.optString("verdict"))).append('\n');
                    JSONObject in = tm.optJSONObject("inbound");
                    if (in != null && in.has("accepted")) b.append("اتصال TCP ورودی از ناظر: ").append(in.optBoolean("accepted") ? "رسید" : "نرسید").append('\n');
                }
                JSONObject v6 = r.optJSONObject("ipv6");
                if (v6 != null) {
                    b.append("IPv6 سراسری روی گوشی: ").append(v6.optInt("global_addresses") > 0 ? "دارد" : "ندارد");
                    if (v6.has("outbound_tcp")) {
                        b.append(" · خروجی TCP: ").append(yes(v6.optBoolean("outbound_tcp")))
                                .append(" · خروجی UDP: ").append(yes(v6.optBoolean("outbound_udp")))
                                .append(" · ورودی TCP: ").append(yes(v6.optBoolean("inbound_tcp")))
                                .append(" · ورودی UDP: ").append(yes(v6.optBoolean("inbound_udp")));
                    } else {
                        b.append(" (هیچ ناظری IPv6 نداشت)");
                    }
                    b.append('\n');
                }
                break;
            }
            case "obs_speed":
                b.append("تا ناظر ").append(r.optString("observer")).append(": ").append(speed(r));
                break;
            case "peer": {
                JSONArray o = r.optJSONArray("outbound");
                b.append("خروجی از این گوشی:\n");
                if (o != null) for (int i = 0; i < o.length(); i++) {
                    JSONObject x = o.optJSONObject(i);
                    b.append("  ").append(x.optString("path")).append(": ")
                            .append(x.optBoolean("ok") ? "وصل شد · " + speed(x.optJSONObject("measure")) : "نشد (" + x.optString("error") + ")").append('\n');
                }
                JSONArray in = r.optJSONArray("inbound");
                int n = in == null ? 0 : in.length();
                b.append("ورودی به این گوشی: ").append(num(n)).append(" اتصال");
                if (in != null) for (int i = 0; i < in.length(); i++) {
                    JSONObject x = in.optJSONObject(i);
                    b.append("\n  ").append(x.optString("transport")).append(" از ").append(x.optString("from")).append(": ").append(x.optBoolean("ok") ? "موفق" : "ناقص");
                }
                break;
            }
            case "punch": {
                JSONObject u = r.optJSONObject("udp"), t = r.optJSONObject("tcp");
                b.append("سوراخ‌زنی UDP (QUIC): ").append(u != null && u.optBoolean("ok") ? "موفق · " + speed(u.optJSONObject("measure")) : "نشد" + (u == null ? "" : " (" + u.optString("error") + ")")).append('\n');
                b.append("سوراخ‌زنی TCP: ").append(t != null && t.optBoolean("ok") ? "موفق · " + speed(t.optJSONObject("measure")) : "نشد" + (t == null ? "" : " (" + t.optString("error") + ")"));
                if ("b".equals(r.optString("role"))) b.append("\n(سرعت را گوشی دیگر اندازه می‌گیرد)");
                break;
            }
            case "relay":
                b.append("از راه ناظر: ").append(r.optBoolean("ok") ? "موفق · " + speed(r.optJSONObject("measure")) : "نشد (" + r.optString("error") + ")");
                if ("b".equals(r.optString("role"))) b.append("\n(سرعت را گوشی دیگر اندازه می‌گیرد)");
                break;
            case "nat_lifetime":
                b.append("نگاشت UDP بی‌کار زنده ماند تا: ").append(r.isNull("udp_alive_up_to_s") ? "هیچ مرحله" : num(r.optInt("udp_alive_up_to_s")) + " ثانیه");
                if (!r.isNull("udp_dead_from_s")) b.append(" · از ").append(num(r.optInt("udp_dead_from_s"))).append(" ثانیه بسته شد");
                if (r.optJSONArray("tcp") != null && r.optJSONArray("tcp").length() > 0) {
                    b.append("\nاتصال TCP بی‌کار زنده ماند تا: ").append(r.isNull("tcp_alive_up_to_s") ? "هیچ مرحله" : num(r.optInt("tcp_alive_up_to_s")) + " ثانیه");
                }
                break;
            case "upnp":
                if (!r.optBoolean("gateway")) {
                    b.append("مودم به UPnP پاسخ نداد.");
                    break;
                }
                b.append("مودم پیدا شد · نشانی بیرونی: ").append(r.optString("external_kind"));
                if (r.optBoolean("home_line_behind_isp_nat")) b.append(" (خط خانه پشت NAT سرویس‌دهنده است)");
                b.append("\nباز کردن درگاه TCP: ").append(yes(r.optBoolean("add_tcp"))).append(" · UDP: ").append(yes(r.optBoolean("add_udp")));
                JSONObject v = r.optJSONObject("verify");
                if (v != null && v.has("tcp_from_outside")) b.append("\nاتصال از بیرون به درگاه باز: TCP ").append(yes(v.optBoolean("tcp_from_outside")))
                        .append(" · UDP ").append(yes(v.optBoolean("udp_from_outside")));
                break;
            case "make_card":
                b.append("کارت آماده است. آن را با گوشی دیگر بخوانید.\n");
                JSONArray c = r.optJSONArray("candidates");
                b.append("نشانی‌های این گوشی در کارت: ").append(num(c == null ? 0 : c.length()));
                if (c != null) for (int i = 0; i < c.length(); i++) b.append("\n  ").append(c.optJSONObject(i).optString("kind")).append(" روی ").append(c.optJSONObject(i).optString("if"));
                JSONArray n = r.optJSONArray("notes");
                if (n != null && n.length() > 0) b.append("\nیادداشت: ").append(n.toString());
                break;
            default:
                b.append(r.toString());
        }
        return b.toString().trim();
    }

    private static String ms(JSONObject r, String k) {
        return r.isNull(k) ? "نشد" : num(r.optLong(k) / 1000.0) + " ثانیه";
    }

    private static String path(JSONObject p) {
        if (p == null) return "سنجیده نشد";
        StringBuilder b = new StringBuilder();
        if (p.has("message_rtt_ms")) b.append("پیام ").append(num(p.optLong("message_rtt_ms"))).append(" میلی‌ثانیه");
        if (p.has("upload_1mib_ms")) b.append(" · ۱ مگابایت در ").append(num(p.optLong("upload_1mib_ms") / 1000.0)).append(" ثانیه (")
                .append(num(p.optDouble("upload_mbps"))).append(" مگابیت)");
        return b.toString();
    }

    /** libp2p / iroh two-phone run through the observer's relay (PLAN 17.2). */
    static String chat(String cmd, JSONObject r) {
        StringBuilder b = new StringBuilder("p2p_chat".equals(cmd) ? "libp2p" : "iroh").append(r.optBoolean("ok") ? " · موفق\n" : " · ناموفق\n");
        if (r.has("error")) b.append("خطا: ").append(r.optString("error")).append('\n');
        if ("p2p_chat".equals(cmd)) {
            JSONObject t = r.optJSONObject("to_relay");
            if (t != null) {
                JSONObject tcp = t.optJSONObject("tcp"), quic = t.optJSONObject("quic");
                b.append("رسیدن به رلهٔ ناظر: TCP ").append(tcp != null && tcp.optBoolean("ok") ? "بله" : "نه")
                        .append(" · QUIC ").append(quic != null && quic.optBoolean("ok") ? "بله" : "نه").append('\n');
            }
            b.append("پیدا شدن گوشی دیگر: ").append(ms(r, "other_phone_found_ms")).append('\n');
            b.append("اتصال از راه رله: ").append(ms(r, "relayed_connection_ms")).append('\n');
            JSONObject d = r.optJSONObject("dcutr");
            b.append("اتصال مستقیم (DCUtR): ").append(d == null ? "گوشی دیگر امتحان کرد" : d.optBoolean("ok") ? "موفق" : "نشد").append('\n');
        } else {
            b.append("رسیدن به رلهٔ ناظر: ").append(ms(r, "relay_ms")).append('\n');
            b.append("پیدا شدن گوشی دیگر: ").append(ms(r, "other_phone_found_ms")).append('\n');
            b.append("اتصال: ").append(ms(r, "connected_ms")).append('\n');
            b.append("مسیر مستقیم بعد از سوراخ‌زنی: ").append(r.isNull("direct") || !r.has("direct") ? "نشد" : "شد").append('\n');
        }
        JSONObject direct = r.optJSONObject("direct");
        if (direct != null) b.append("مسیر مستقیم: ").append(direct.optString("family")).append(' ').append(direct.optString("kind")).append(' ').append(direct.optString("transport")).append('\n');
        JSONObject paths = r.optJSONObject("paths");
        if (paths != null) {
            b.append("از راه رله: ").append(path(paths.optJSONObject("relay"))).append('\n');
            b.append("مستقیم: ").append(path(paths.optJSONObject("direct")));
        }
        return b.toString().trim();
    }

    static String pqtls(JSONObject r) {
        StringBuilder b = new StringBuilder("دست‌دادن TLS 1.3 روی این گوشی (میانه، هر دو طرف):\n");
        for (String k : new String[]{"classic", "hybrid"}) {
            JSONObject g = r.optJSONObject(k);
            if (g == null) continue;
            b.append(g.optString("group")).append(": ").append(num(g.optDouble("ms_median"))).append(" میلی‌ثانیه · ClientHello ")
                    .append(num(g.optInt("client_hello_bytes"))).append(" بایت · پاسخ سرور ").append(num(g.optInt("server_to_client_bytes"))).append(" بایت\n");
        }
        if (r.has("hybrid_extra_ms_median")) b.append("هزینهٔ اضافهٔ پساکوانتومی: ").append(num(r.optDouble("hybrid_extra_ms_median"))).append(" میلی‌ثانیه و ")
                .append(num(r.optLong("hybrid_extra_client_hello_bytes") + r.optLong("hybrid_extra_server_bytes"))).append(" بایت در هر اتصال");
        return b.toString().trim();
    }

    static String ble(JSONObject r) {
        StringBuilder b = new StringBuilder("بلوتوث · این گوشی ").append("server".equals(r.optString("role")) ? "سرور" : "جست‌وجوگر").append(r.optBoolean("ok") ? " · موفق\n" : " · ناموفق\n");
        if (r.has("error")) b.append("خطا: ").append(r.optString("error")).append('\n');
        if (r.has("secure_ms")) b.append("اتصال امن (Noise) بعد از ").append(num(r.optLong("secure_ms") / 1000.0)).append(" ثانیه · MTU ").append(num(r.optInt("mtu"))).append('\n');
        if (r.has("rtt_median_ms")) b.append("رفت‌وبرگشت پیام: ").append(num(r.optLong("rtt_median_ms"))).append(" میلی‌ثانیه\n");
        if (r.has("upload_kbps")) b.append("ارسال: ").append(num(r.optLong("upload_kbps"))).append(" کیلوبیت بر ثانیه\n");
        if (r.has("download_kbps")) b.append("دریافت: ").append(num(r.optLong("download_kbps"))).append(" کیلوبیت بر ثانیه\n");
        JSONArray range = r.optJSONArray("range");
        if (range != null && range.length() > 0) {
            int lost = 0, weakest = 0;
            for (int i = 0; i < range.length(); i++) {
                JSONObject s = range.optJSONObject(i);
                if (s.optBoolean("lost")) lost++;
                weakest = Math.min(weakest, s.optInt("rssi"));
            }
            b.append("حالت برد: ").append(num(range.length())).append(" نمونه · ").append(num(lost)).append(" پیام نرسید · ضعیف‌ترین سیگنال ").append(weakest).append(" dBm");
            if (r.has("disconnected_ms")) b.append(" · قطع شد");
        }
        if ("server".equals(r.optString("role"))) b.append("\n(سرعت و برد را گوشی دیگر اندازه می‌گیرد)");
        return b.toString().trim();
    }

    static String aware(JSONObject o) {
        if (!o.optBoolean("feature")) return "وای‌فای آگاه: این گوشی ندارد.";
        StringBuilder b = new StringBuilder("وای‌فای آگاه: دارد · الان در دسترس: ").append(yes(o.optBoolean("available")));
        if (!o.optBoolean("wifi_on")) b.append(" (وای‌فای خاموش است)");
        if (o.has("data_paths")) b.append(" · مسیر داده هم‌زمان: ").append(num(o.optInt("data_paths")));
        return b.toString();
    }
}
