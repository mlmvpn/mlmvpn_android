package org.mlmvpn.chat.bridge;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.GasRelayView;
import org.mlmchat.core.GasServer;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * MLMCHAT: the Google relays (SPEC 19), the app side. A relay is a small mailbox the user makes in
 * their own Google account (an Apps Script); two phones that cannot reach each other (two
 * operators) leave sealed frames there for each other. The core does everything (finds the way to
 * Google by itself, balances the load over the relays, counts); this class starts it with the node,
 * tells it when the app is in use, and words what the screens show.
 *
 * Screens: {@code GerehGasListActivity} (the list, in place of Telegram's proxy list),
 * {@code GerehGasDetailActivity} and {@code GerehGasSetupActivity} (the steps of making one).
 */
public final class GerehGas {
    private static final String TAG = "MLMCHAT";
    /** The scheme a relay link takes inside Telegram's screens, so a tap on it in a chat opens the add screen. */
    public static final String TG_PREFIX = "tg://gereh_relay?l=";
    public static final String CORE_PREFIX = "mlmchat://r/";

    private static final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "gereh-gas");
        t.setDaemon(true);
        return t;
    });

    private GerehGas() {
    }

    /** Starts the mailbox transport with the node (from the core's thread, once). A failure leaves the other routes working. */
    static void start(ChatNode node) {
        try {
            node.gasStart("{}");
            Log.i(TAG, "google relays up");
        } catch (Throwable e) {
            Log.e(TAG, "google relays failed: " + e.getMessage());
        }
    }

    /** The app is in use (takes wait at the mailbox, so messages arrive in about a second) or not. */
    public static void foreground(boolean on) {
        try {
            // opening the node can fail too («database is locked»), and this runs in onPause
            // (mlmvpn/crashes #3311)
            ChatNode node = GerehIdentity.node();
            if (node != null) {
                node.gasForeground(on);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Off the main thread: what calls Google or the database. */
    public static void async(Runnable r) {
        worker.execute(r);
    }

    // ---- words ------------------------------------------------------------------------------

    /** The state of a relay in a few words. */
    public static String stateText(String state) {
        switch (state == null ? "" : state) {
            case "ok": return getString(R.string.GerehGasStOk);
            case "new": return getString(R.string.GerehGasStNew);
            case "off": return getString(R.string.GerehGasStOff);
            case "draft": return getString(R.string.GerehGasStDraft);
            case "front": return getString(R.string.GerehGasStFront);
            case "network": return getString(R.string.GerehGasStNetwork);
            case "timeout": return getString(R.string.GerehGasStTimeout);
            case "not_found": return getString(R.string.GerehGasStNotFound);
            case "access": return getString(R.string.GerehGasStAccess);
            case "auth": return getString(R.string.GerehGasStAuth);
            case "script": return getString(R.string.GerehGasStScript);
            case "paused": return getString(R.string.GerehGasStPaused);
            case "limit": return getString(R.string.GerehGasStLimit);
            case "quota": return getString(R.string.GerehGasStQuota);
            case "busy": return getString(R.string.GerehGasStBusy);
            case "remote": return getString(R.string.GerehGasStRemote);
            default: return getString(R.string.GerehGasStBad);
        }
    }

    /** What the user can do about a failure, in plain words. */
    public static String hintText(String code) {
        switch (code == null ? "" : code) {
            case "front": return getString(R.string.GerehGasHintFront);
            case "not_found": return getString(R.string.GerehGasHintNotFound);
            case "access": return getString(R.string.GerehGasHintAccess);
            case "auth": return getString(R.string.GerehGasHintAuth);
            case "script": return getString(R.string.GerehGasHintScript);
            case "paused": return getString(R.string.GerehGasHintPaused);
            case "limit": return getString(R.string.GerehGasHintLimit);
            case "quota": return getString(R.string.GerehGasHintQuota);
            case "update": return getString(R.string.GerehGasHintUpdate);
            case "network": return getString(R.string.GerehGasHintNetwork);
            case "timeout": return getString(R.string.GerehGasHintTimeout);
            case "busy": return getString(R.string.GerehGasHintBusy);
            case "remote": return getString(R.string.GerehGasHintRemote);
            case "not_owner": return getString(R.string.GerehGasHintNotOwner);
            default: return getString(R.string.GerehGasHintBad);
        }
    }

    public static String stepText(String step) {
        switch (step == null ? "" : step) {
            case "worker": return getString(R.string.GerehGasStepWorker);
            case "front": return getString(R.string.GerehGasStepFront);
            case "script": return getString(R.string.GerehGasStepScript);
            default: return getString(R.string.GerehGasStepMailbox);
        }
    }

    /** One line for a list row: how it is doing and how much it did today. */
    public static String rowText(GasRelayView r) {
        if (r.getDraft()) {
            return getString(R.string.GerehGasDraftRow);
        }
        if ("ok".equals(r.getState())) {
            GasServer s = r.getServer();
            // a relay made here, read from its own script lately: everyone's requests and the devices it helped
            if (r.getMine() && s != null && System.currentTimeMillis() - s.getAtMs() < 30 * 60 * 1000L) {
                return formatString(R.string.GerehGasRowOkPeople, (int) s.getRequests(), (int) s.getDevices());
            }
            String ok = formatString(R.string.GerehGasRowOk, (int) r.getToday().getReq());
            // a combined relay says which door this network uses
            if (r.getCombined() && !r.getDoor().isEmpty()) {
                ok += " · " + getString("cloudflare".equals(r.getDoor()) ? R.string.GerehComboDoorCf : R.string.GerehComboDoorGoogle);
            }
            return ok;
        }
        return stateText(r.getState());
    }

    public static String ago(long ms) {
        if (ms <= 0) {
            return getString(R.string.GerehGasNever);
        }
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 90) {
            return formatString(R.string.GerehGasAgoSec, (int) s);
        }
        if (s < 5400) {
            return formatString(R.string.GerehGasAgoMin, (int) (s / 60));
        }
        return formatString(R.string.GerehGasAgoHour, (int) (s / 3600));
    }

    public static String size(long bytes) {
        return AndroidUtilities.formatFileSize(bytes);
    }

    /** The relay link as Telegram's screens show it, so a tap on it in a chat opens the add screen. */
    public static String shown(String text) {
        return text == null || !text.contains(CORE_PREFIX) ? text : text.replace(CORE_PREFIX, TG_PREFIX);
    }

    // ---- small helpers for the screens ------------------------------------------------------

    public static String clipboardText(Context context) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).coerceToText(context);
                return t == null ? "" : t.toString().trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** The link in a pasted text (a friend may send it with words around it), or null. */
    public static String linkIn(String text) {
        if (text == null) {
            return null;
        }
        for (String prefix : new String[]{CORE_PREFIX, TG_PREFIX}) {
            int i = text.indexOf(prefix);
            if (i >= 0) {
                int end = i + prefix.length();
                while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '-' || text.charAt(end) == '_')) {
                    end++;
                }
                return text.substring(i, end);
            }
        }
        return null;
    }

    /** A dialog with one text field. */
    public static void ask(BaseFragment fragment, String title, String message, String initial, String hint, String ok, Consumer<String> done) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Context context = fragment.getParentActivity();
        EditText field = new EditText(context);
        field.setText(initial == null ? "" : initial);
        field.setHint(hint);
        field.setSingleLine(false);
        field.setMaxLines(4);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        FrameLayout box = new FrameLayout(context);
        int pad = AndroidUtilities.dp(24);
        box.setPadding(pad, AndroidUtilities.dp(8), pad, 0);
        box.addView(field, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setTitle(title);
        if (message != null) {
            b.setMessage(message);
        }
        b.setView(box);
        b.setPositiveButton(ok, (d, w) -> done.accept(field.getText().toString().trim()));
        b.setNegativeButton(getString(R.string.Cancel), null);
        fragment.showDialog(b.create());
    }
}
