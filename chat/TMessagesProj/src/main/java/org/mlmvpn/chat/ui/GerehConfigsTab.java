package org.mlmvpn.chat.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.mlmvpn.chat.bridge.GerehConfigs;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MLMCHAT: the «کانفیگ‌ها» tab of a group's or channel's info page, beside «اعضا» and «رسانه» (q1;
 * Ehsan, 30 Oct 2026: the row above the tabs sat in a bad place). Every message of the chat that
 * may hold configs, under its kind (GerehConfigs.kindOf): the core's history page (the bridge
 * answers it, as for the media tabs) together with Telegram's own copy of the chat, which keeps
 * what the core let go. An official chat's core holds its 500 newest posts, so the row's page,
 * which read only the core, found one config in the official group that has many (Ehsan). A tap
 * opens the chat at that message, whose «سنجش» and «انتقال» buttons measure and move them. Nothing
 * is downloaded here.
 */
public final class GerehConfigsTab {
    private static final String TAG = "MLMCHAT";
    /** How far back the core is asked: the bridge's history page (GerehSearch.PAGE). */
    private static final int PAGE = 1000;
    /** Telegram's copy: at most this many messages that may hold configs, the newest first. */
    private static final int KEPT_MAX = 3000;
    /** Bytes one of them carries somewhere (a link, a profile, a file's name); kindOf decides. */
    private static final String[] MARKS = {
            "://", "[Interface]", "remote", ".npv", ".NPV", ".ovpn", ".OVPN", ".conf", ".CONF",
            ".txt", ".TXT", ".json", ".JSON",
    };
    private static final int[] KIND_NAMES = {
            R.string.GerehCfgKindLinks, R.string.GerehCfgKindNpv, R.string.GerehCfgKindOvpn,
            R.string.GerehCfgKindWg, R.string.GerehCfgKindLists,
    };
    /** Each kind's badge: one of the avatar colours (blue, violet, orange, red, green), which follow the theme. */
    private static final int[] KIND_COLORS = {5, 2, 1, 0, 3};
    /** Apart from the other tabs' kinds: a page's lists share one pool of views (SharedMediaLayout). */
    private static final int TYPE_HEADER = 71000, TYPE_ROW = 71001, TYPE_FOOTER = 71002;
    private static final Object FOOTER = new Object();

    private static final class Row {
        final int msgId;
        final int date;
        final int kind;
        final String title;
        final String sub;
        /** The badge's word: the file's extension, or the kind's for a text; null shows a link. */
        final String badge;

        Row(int msgId, int date, int kind, String title, String sub, String badge) {
            this.msgId = msgId;
            this.date = date;
            this.kind = kind;
            this.title = title;
            this.sub = sub;
            this.badge = badge;
        }
    }

    private final BaseFragment fragment;
    private final long dialogId;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Runnable onShownChanged;
    /** What the list shows: a kind's header (its Integer), its rows, then the hint (FOOTER). */
    private final List<Object> items = new ArrayList<>();
    private final int[] kindCounts = new int[KIND_NAMES.length];
    private int count;
    public final RecyclerListView.SelectionAdapter adapter;

    /** `onShownChanged` runs when the tab comes to have rows, or stops having any. */
    public GerehConfigsTab(BaseFragment fragment, long dialogId, Theme.ResourcesProvider resourcesProvider, Runnable onShownChanged) {
        this.fragment = fragment;
        this.dialogId = dialogId;
        this.resourcesProvider = resourcesProvider;
        this.onShownChanged = onShownChanged;
        adapter = new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return holder.getItemViewType() == TYPE_ROW;
            }

            @Override
            public int getItemCount() {
                return items.size();
            }

            @Override
            public int getItemViewType(int position) {
                Object o = items.get(position);
                return o instanceof Row ? TYPE_ROW : o instanceof Integer ? TYPE_HEADER : TYPE_FOOTER;
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                Context context = parent.getContext();
                View view;
                if (viewType == TYPE_HEADER) {
                    view = new GraySectionCell(context, GerehConfigsTab.this.resourcesProvider);
                } else if (viewType == TYPE_ROW) {
                    view = new ConfigCell(context, GerehConfigsTab.this.resourcesProvider);
                } else {
                    TextView hint = new TextView(context);
                    hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, GerehConfigsTab.this.resourcesProvider));
                    hint.setGravity(Gravity.CENTER);
                    hint.setPadding(dp(24), dp(14), dp(24), dp(20));
                    hint.setText(getString(R.string.GerehChatConfigsHint));
                    view = hint;
                }
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(view);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                Object o = items.get(position);
                if (o instanceof Integer) {
                    int kind = (Integer) o;
                    ((GraySectionCell) holder.itemView).setText(getString(KIND_NAMES[kind]) + " (" + LocaleController.formatNumber(kindCounts[kind], ',') + ")");
                } else if (o instanceof Row) {
                    Row r = (Row) o;
                    boolean divider = position + 1 < items.size() && items.get(position + 1) instanceof Row;
                    int color = Theme.getColor(Theme.keys_avatar_background[KIND_COLORS[r.kind]], GerehConfigsTab.this.resourcesProvider);
                    ((ConfigCell) holder.itemView).set(r, color, divider);
                }
            }
        };
    }

    /** Whether the info page shows the tab. */
    public boolean hasAny() {
        return count > 0;
    }

    /** How many messages it lists. */
    public int count() {
        return count;
    }

    /** Reads the chat (the core, then Telegram's copy); the list and the tab follow when done. */
    public void load() {
        final int account = fragment.getCurrentAccount();
        TLRPC.TL_messages_getHistory h = new TLRPC.TL_messages_getHistory();
        h.peer = fragment.getMessagesController().getInputPeer(dialogId);
        h.limit = PAGE;
        fragment.getConnectionsManager().sendRequest(h, (response, error) -> {
            final List<TLRPC.Message> fromCore = new ArrayList<>();
            if (response instanceof TLRPC.messages_Messages) {
                fromCore.addAll(((TLRPC.messages_Messages) response).messages);
            }
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                List<TLRPC.Message> fromCopy = kept(storage, dialogId);
                List<Row> found = rowsOf(fromCopy, fromCore);
                Log.i(TAG, "configs tab: " + fromCore.size() + " from the core, " + fromCopy.size() + " kept by Telegram, " + found.size() + " listed");
                AndroidUtilities.runOnUIThread(() -> show(found));
            });
        });
    }

    /** A tap on the list: a row opens the chat at its message. */
    public void onClick(int position) {
        if (position < 0 || position >= items.size() || !(items.get(position) instanceof Row)) {
            return;
        }
        Bundle args = new Bundle();
        if (dialogId < 0) {
            args.putLong("chat_id", -dialogId);
        } else {
            args.putLong("user_id", dialogId);
        }
        args.putInt("message_id", ((Row) items.get(position)).msgId);
        fragment.presentFragment(new ChatActivity(args));
    }

    private void show(List<Row> found) {
        boolean had = count > 0;
        items.clear();
        Arrays.fill(kindCounts, 0);
        int kind = -1;
        for (Row r : found) {
            if (r.kind != kind) {
                kind = r.kind;
                items.add(kind);
            }
            kindCounts[kind]++;
            items.add(r);
        }
        if (!found.isEmpty()) {
            items.add(FOOTER);
        }
        count = found.size();
        adapter.notifyDataSetChanged();
        if (had != (count > 0) && onShownChanged != null) {
            onShownChanged.run();
        }
    }

    /**
     * Telegram's copy of the chat: the messages that may hold configs, newest first. The bytes are
     * looked through by SQLite (the marks), so only those few are read back into messages.
     */
    private static List<TLRPC.Message> kept(MessagesStorage storage, long dialogId) {
        List<TLRPC.Message> out = new ArrayList<>();
        SQLiteDatabase db = storage.getDatabase();
        if (db == null) {
            return out;
        }
        StringBuilder any = new StringBuilder();
        for (String mark : MARKS) {
            if (any.length() > 0) {
                any.append(" OR ");
            }
            any.append("instr(data, CAST('").append(mark).append("' AS BLOB)) > 0");
        }
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized("SELECT data FROM messages_v2 WHERE uid = " + dialogId + " AND mid > 0 AND (" + any
                    + ") ORDER BY mid DESC LIMIT " + KEPT_MAX);
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                TLRPC.Message m = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                data.reuse();
                if (m != null) {
                    out.add(m);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "configs tab: Telegram's copy not read: " + t.getClass().getSimpleName());
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return out;
    }

    /** One row per message that holds configs, the core's copy first; by kind, then the newest first. */
    private static List<Row> rowsOf(List<TLRPC.Message> fromCopy, List<TLRPC.Message> fromCore) {
        Map<Integer, TLRPC.Message> byId = new LinkedHashMap<>();
        for (TLRPC.Message m : fromCopy) {
            byId.put(m.id, m);
        }
        for (TLRPC.Message m : fromCore) {
            byId.put(m.id, m);
        }
        List<Row> rows = new ArrayList<>();
        for (TLRPC.Message m : byId.values()) {
            int kind = m.id > 0 ? GerehConfigs.kindOf(m) : -1;
            if (kind >= 0) {
                rows.add(rowFor(m, kind));
            }
        }
        Collections.sort(rows, (a, b) -> a.kind != b.kind ? Integer.compare(a.kind, b.kind)
                : a.date != b.date ? Integer.compare(b.date, a.date) : Integer.compare(b.msgId, a.msgId));
        return rows;
    }

    /** A message's row: what it holds and when it came. */
    private static Row rowFor(TLRPC.Message msg, int kind) {
        String date = LocaleController.formatDateAudio(msg.date, true);
        String file = GerehConfigs.fileNameOf(msg);
        String text = msg.message != null ? msg.message.trim() : "";
        if (kind == GerehConfigs.KIND_LINKS) {
            List<String> links = GerehConfigs.links(text);
            String title = links.isEmpty() ? "" : GerehConfigs.linkName(links.get(0));
            String sub = links.size() > 1 ? formatString(R.string.GerehCfgLinksCount, links.size()) + " · " + date : date;
            return new Row(msg.id, msg.date, kind, title, sub, null);
        }
        if (file != null) {
            int dot = file.lastIndexOf('.');
            String ext = dot >= 0 && dot < file.length() - 1 ? file.substring(dot + 1).toUpperCase(Locale.ROOT) : "";
            return new Row(msg.id, msg.date, kind, file, date, ext.length() > 4 ? ext.substring(0, 4) : ext);
        }
        int nl = text.indexOf('\n');
        return new Row(msg.id, msg.date, kind, nl > 0 ? text.substring(0, nl) : text, date, kind == GerehConfigs.KIND_WG ? "WG" : "OVPN");
    }

    /** A row: a coloured badge (the file's extension, or a link), its name, and how many and when. */
    private static final class ConfigCell extends FrameLayout {
        private final FrameLayout badge;
        private final ImageView link;
        private final TextView word;
        private final TextView title;
        private final TextView sub;
        private boolean divider;

        ConfigCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            setWillNotDraw(false);
            boolean rtl = LocaleController.isRTL;
            int side = rtl ? Gravity.RIGHT : Gravity.LEFT;
            badge = new FrameLayout(context);
            addView(badge, LayoutHelper.createFrame(40, 40, side | Gravity.CENTER_VERTICAL, 16, 0, 16, 0));
            link = new ImageView(context);
            link.setScaleType(ImageView.ScaleType.CENTER);
            link.setImageResource(R.drawable.msg_link);
            link.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
            badge.addView(link, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            word = new TextView(context);
            word.setTextColor(0xffffffff);
            word.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            word.setTypeface(AndroidUtilities.bold());
            word.setGravity(Gravity.CENTER);
            word.setSingleLine(true);
            badge.addView(word, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            title = new TextView(context);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setGravity(side);
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, side | Gravity.TOP, rtl ? 16 : 72, 9, rtl ? 72 : 16, 0));
            sub = new TextView(context);
            sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setGravity(side);
            addView(sub, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, side | Gravity.TOP, rtl ? 16 : 72, 33, rtl ? 72 : 16, 0));
        }

        void set(Row r, int color, boolean divider) {
            this.divider = divider;
            badge.setBackground(Theme.createRoundRectDrawable(dp(10), color));
            link.setVisibility(r.badge == null ? VISIBLE : GONE);
            word.setVisibility(r.badge == null ? GONE : VISIBLE);
            word.setText(r.badge);
            title.setText(TextUtils.isEmpty(r.title) ? "…" : r.title);
            sub.setText(r.sub);
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(60) + (divider ? 1 : 0), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (divider) {
                boolean rtl = LocaleController.isRTL;
                canvas.drawLine(rtl ? 0 : dp(72), getMeasuredHeight() - 1, getMeasuredWidth() - (rtl ? dp(72) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }
}
