package org.mlmvpn.chat.bridge;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * MLMCHAT: a combined relay's Worker (SPEC 19.13) on one of the host app's Cloudflare accounts
 * (MLMVPN's cloud section, Dr. Net's accounts). The chat cannot see the app's code (the app depends
 * on it, not the other way round), so the app sets {@link #set} when it starts, in every process;
 * with no app behind it the combined relay is simply not offered.
 *
 * The Worker is the relay's mailbox. Its script comes from the chat core with the relay's keys in
 * it; its state lives in one Durable Object (SQLite storage, free plan), whose class is made by a
 * tagged migration exactly once -- the create form first for a new Worker, the keep form for one
 * already there, and the other if that is refused. These steps are the same for every app and live
 * here (moved from MLMVPN's GerehCloudLink, both teams, issue 7, 28 Oct 2026); the app gives only
 * its accounts and the way it reaches Cloudflare's API on this network.
 */
public final class GerehCloud {
    private static final String TAG = "GerehCloud";
    private static final String CLASS = "Mailbox";
    private static final String DO_TAG = "v1";
    /** A fixed past date: a date "in the future" by the phone's clock is refused. */
    private static final String COMPATIBILITY_DATE = "2024-09-23";

    /** What the app gives. Every call blocks: call them off the main thread. */
    public interface Hook {
        /** The Cloudflare accounts the user added, as names to show, in the app's order. */
        List<String> accounts();

        /**
         * One call to Cloudflare's API as account [index]: [path] is under
         * {@code https://api.cloudflare.com/client/v4/accounts/<that account's id>} (as
         * {@code /workers/subdomain}), sent with the account's own auth by whatever road reaches
         * Cloudflare on this network. [body] null: none. Answers what came back, whatever its
         * status; throws only when nothing answered (with a message for the user).
         */
        Reply call(int index, String method, String path, String contentType, byte[] body) throws Exception;

        /** A name for a new Worker, one that says nothing about what it is (the app's own rule). */
        String newWorkerName();
    }

    /** An HTTP answer as it came. */
    public static final class Reply {
        public final int status;
        public final String body;

        public Reply(int status, String body) {
            this.status = status;
            this.body = body != null ? body : "";
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private static volatile Hook hook;

    private GerehCloud() {
    }

    public static void set(Hook h) {
        hook = h;
    }

    public static boolean available() {
        return hook != null;
    }

    public static List<String> accounts() {
        Hook h = hook;
        if (h == null) {
            return Collections.emptyList();
        }
        try {
            return h.accounts();
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    /**
     * Puts [script] (an ES module with a Durable Object class {@code Mailbox}, bound as
     * {@code MBOX}) on account [index] as a Worker on its workers.dev address. [previousHost]: the
     * Worker this relay had, to replace in place when it is on the same account (null for a new
     * one). Returns the Worker's host ({@code name.subdomain.workers.dev}); throws with a sentence
     * for the user.
     */
    public static String deployWorker(int index, String script, String previousHost) throws Exception {
        Hook h = hook;
        if (h == null) {
            throw new IllegalStateException("no cloud");
        }
        if (index < 0 || index >= accounts().size()) {
            throw new IllegalStateException(LocaleController.getString(R.string.GerehCloudNoAccount));
        }
        Reply sub = h.call(index, "GET", "/workers/subdomain", null, null);
        String subdomain = "";
        try {
            JSONObject result = new JSONObject(sub.body).optJSONObject("result");
            subdomain = result != null ? result.optString("subdomain") : "";
        } catch (Throwable ignored) {
        }
        if (subdomain.trim().isEmpty()) {
            throw new IllegalStateException(LocaleController.getString(R.string.GerehCloudNoSubdomain));
        }
        // the same Worker again on the same account (an update); else a new name that says nothing
        String previousName = previousHost != null && previousHost.indexOf('.') > 0 ? previousHost.substring(0, previousHost.indexOf('.')) : null;
        boolean sameWorker = previousName != null && previousHost.endsWith("." + subdomain + ".workers.dev");
        String name = sameWorker ? previousName : h.newWorkerName();

        JSONObject create = new JSONObject().put("new_tag", DO_TAG).put("new_sqlite_classes", new JSONArray().put(CLASS));
        JSONObject keep = new JSONObject().put("old_tag", DO_TAG).put("new_tag", DO_TAG);
        String last = "";
        boolean done = false;
        for (JSONObject migration : sameWorker ? new JSONObject[]{keep, create} : new JSONObject[]{create, keep}) {
            Reply r = upload(h, index, name, script, migration);
            if (r.ok()) {
                done = true;
                break;
            }
            last = r.body.length() > 400 ? r.body.substring(0, 400) : r.body;
        }
        if (!done) {
            Log.w(TAG, "upload refused");
            String why = "";
            try {
                JSONArray errors = new JSONObject(last).optJSONArray("errors");
                JSONObject first = errors != null ? errors.optJSONObject(0) : null;
                why = first != null ? first.optString("message") : "";
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException(LocaleController.formatString(R.string.GerehCloudRefused, why).trim());
        }
        Reply on = h.call(index, "POST", "/workers/scripts/" + name + "/subdomain", "application/json",
                "{\"enabled\":true}".getBytes(StandardCharsets.UTF_8));
        if (!on.ok()) {
            throw new IllegalStateException(LocaleController.formatString(R.string.GerehCloudNoAddress, on.status));
        }
        return name + "." + subdomain + ".workers.dev";
    }

    /** The Worker module upload: a metadata part (the module, the date, the MBOX binding, the migration) and the script. */
    private static Reply upload(Hook h, int index, String name, String script, JSONObject migration) throws Exception {
        JSONObject meta = new JSONObject()
                .put("main_module", "worker.js")
                .put("compatibility_date", COMPATIBILITY_DATE)
                .put("bindings", new JSONArray().put(new JSONObject().put("type", "durable_object_namespace").put("name", "MBOX").put("class_name", CLASS)))
                .put("migrations", migration);
        String boundary = "gereh" + Long.toHexString(System.nanoTime()) + Long.toHexString(Double.doubleToLongBits(Math.random()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        part(out, boundary, "metadata", "metadata.json", "application/json", meta.toString());
        part(out, boundary, "worker.js", "worker.js", "application/javascript+module", script);
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return h.call(index, "PUT", "/workers/scripts/" + name, "multipart/form-data; boundary=" + boundary, out.toByteArray());
    }

    private static void part(ByteArrayOutputStream out, String boundary, String name, String file, String type, String content) throws Exception {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file + "\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
