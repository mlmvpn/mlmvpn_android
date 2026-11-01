package org.mlmvpn.chat.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What the app that carries Gereh may set for itself. Its official channel and group (SPEC 18):
 * MLMVPN's are {@link org.mlmchat.core.Official#config()}; another app passes its own config of the
 * same shape, or none to share MLMVPN's (Dr.Net from 31 Oct 2026, ت۷۲: one channel and group for
 * both apps, each named after the app it is seen in, {@link #setOfficialTitle}). Set it in every
 * process, before Gereh starts: in the Application's onCreate, before anything of the chat.
 * The first feed with {@code broadcast} is the official channel, the first without it the group.
 * Their names come from an admin's signed title, else the app's strings ({@code GerehOfficialChannel}
 * and the others), which the app may override in its own resources.
 */
public final class GerehHost {
    private static volatile JSONObject feeds;
    private static volatile String widget;
    private static volatile OfficialTitle officialTitle;
    private static volatile Release release;
    private static volatile boolean linksInSheet;

    private GerehHost() {
    }

    /**
     * Where the attach menu's tiles and settings open (q11, q12), each app its own layout: false,
     * the default, a page of their own in two columns with each tile's picture (GerehLinkPickActivity,
     * MLMVPN's); true, inside the attach sheet as Files and Music are (ChatAttachAlertGerehLinksLayout,
     * Dr.Net's, 31 Oct 2026). Set it in the chat process, before a chat opens.
     */
    public static void setLinksInSheet(boolean inSheet) {
        linksInSheet = inSheet;
    }

    /** {@link #setLinksInSheet}. */
    public static boolean linksInSheet() {
        return linksInSheet;
    }

    /** The app's official feeds: {"feeds":[…], "seed":[hex], "mirror":"mlmchat://r/…"}. */
    public static void setFeeds(JSONObject config) {
        feeds = config;
    }

    /** The name an app gives the official chats it shares with another (ت۷۲). */
    public interface OfficialTitle {
        /** What this app shows for the official channel (or group) whose own name is `title`; null keeps it. */
        String title(boolean channel, String title);
    }

    /**
     * The app's own name for the official channel and group, over an admin's title and the strings:
     * an app that shares MLMVPN's (sets no {@link #setFeeds}) shows them under its own name (Dr.Net,
     * 31 Oct 2026: «کسی که دکتر نت رو نصب داره بزنه دکتر نت … ام‌ال‌ام بزنه ام‌ال‌ام»). Set it in the
     * chat process, before Gereh starts; with none, the chats keep their own names.
     */
    public static void setOfficialTitle(OfficialTitle t) {
        officialTitle = t;
    }

    /** `title` as this app shows it ({@link #setOfficialTitle}). */
    static String officialTitle(boolean channel, String title) {
        OfficialTitle t = officialTitle;
        if (t == null) {
            return title;
        }
        try {
            String own = t.title(channel, title);
            return own == null || own.isEmpty() ? title : own;
        } catch (Throwable e) {
            return title;
        }
    }

    /**
     * The app's own home-screen widget for Gereh, if it has one: an AppWidgetProvider of the app
     * that draws {@link GerehWidgetState#read} in its own look and draws again on
     * {@link GerehWidgetState#ACTION_CHANGED}. Set it in the chat process, before Gereh starts;
     * with none, no widget is told anything (the state is still written).
     */
    public static void setWidget(Class<?> provider) {
        widget = provider != null ? provider.getName() : null;
    }

    static String widget() {
        return widget;
    }

    /** The app's own releases, as its updates know them (T75). */
    public interface Release {
        /** The newest release's version name ("1.2.46"), or null when none is known. */
        String latest();

        /** Opens where this app updates itself; false leaves it to Gereh, which opens the app. */
        boolean openUpdate(android.content.Context context);
    }

    /**
     * The app's own releases (T75): Gereh puts «نسخهٔ قدیمی» beside whoever runs an older version of
     * this app than the newest release, and tells the user when their own is older. Set it in the
     * chat process, before Gereh starts; with none, only those older than this phone's own version,
     * and those whose app is from before the tag, get it.
     */
    public static void setRelease(Release r) {
        release = r;
    }

    private static volatile String support;

    /**
     * The app's support page (an https link): a beating heart beside the main screen's menu opens
     * a sheet that asks for support and leads there (Ehsan, 31 Oct 2026). Set it in the chat
     * process, before Gereh starts; with none, there is no heart.
     */
    public static void setSupport(String url) {
        support = url != null && url.startsWith("https://") ? url : null;
    }

    public static String support() {
        return support;
    }

    static Release release() {
        return release;
    }

    static JSONObject feeds() {
        JSONObject f = feeds;
        return f != null ? f : org.mlmchat.core.Official.config();
    }

    /** The id of the first feed that is (or is not) a channel; "" when there is none. */
    static String feedId(boolean channel) {
        JSONArray list = feeds().optJSONArray("feeds");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject f = list.optJSONObject(i);
            if (f != null && f.optBoolean("broadcast") == channel) {
                return f.optString("id");
            }
        }
        return "";
    }
}
