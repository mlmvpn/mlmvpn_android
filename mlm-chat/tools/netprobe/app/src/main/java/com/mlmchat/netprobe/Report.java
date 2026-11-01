package com.mlmchat.netprobe;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Every test result of this phone, in one JSON file (schema netprobe-report/1). It holds no IP
 * address, key or card: the native side replaces addresses with labels such as "v4-public#2".
 */
final class Report {
    private final File file;
    private JSONObject doc;
    private static Report instance;

    /** One instance per process: the screen and the battery service add to the same file. */
    static synchronized Report get(Context c) {
        if (instance == null) instance = new Report(c.getApplicationContext());
        return instance;
    }

    private Report(Context c) {
        file = new File(c.getFilesDir(), "report.json");
        load();
    }

    private void load() {
        try {
            if (file.exists()) {
                doc = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                return;
            }
        } catch (Exception ignored) { }
        reset();
    }

    void reset() {
        doc = new JSONObject();
        try {
            doc.put("schema", "netprobe-report/1");
            doc.put("app", BuildConfigShim.VERSION);
            doc.put("device", NetInfo.device());
            doc.put("created", now());
            doc.put("runs", new JSONArray());
        } catch (Exception ignored) { }
        save();
    }

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date());
    }

    synchronized void add(String test, JSONObject network, JSONObject result) {
        try {
            JSONObject r = new JSONObject();
            r.put("test", test);
            r.put("at", now());
            r.put("network", network);
            r.put("result", result);
            doc.getJSONArray("runs").put(r);
        } catch (Exception ignored) { }
        save();
    }

    synchronized int count() {
        JSONArray a = doc.optJSONArray("runs");
        return a == null ? 0 : a.length();
    }

    synchronized String text() {
        try {
            return doc.toString(2);
        } catch (Exception e) {
            return doc.toString();
        }
    }

    private synchronized void save() {
        try (FileOutputStream f = new FileOutputStream(file)) {
            f.write(doc.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** A copy under Android/data/com.mlmchat.netprobe/files/reports/, reachable with adb pull. */
    synchronized File export(Context c, String label) throws Exception {
        File dir = new File(c.getExternalFilesDir(null), "reports");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("cannot create " + dir);
        StringBuilder sb = new StringBuilder();
        if (label != null) {
            for (char ch : label.toCharArray()) sb.append(Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' ? ch : '-');
        }
        String safe = sb.toString();
        String name = "netprobe-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date())
                + (safe.isEmpty() ? "" : "-" + safe) + ".json";
        File out = new File(dir, name);
        try (FileOutputStream f = new FileOutputStream(out)) {
            f.write(text().getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }
}
