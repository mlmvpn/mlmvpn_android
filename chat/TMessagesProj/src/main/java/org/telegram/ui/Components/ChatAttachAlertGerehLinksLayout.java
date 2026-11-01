package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import org.mlmvpn.chat.bridge.GerehLinks;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.SharedDocumentCell;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MLMCHAT: the attach menu's host-app tiles or settings (q11, q12), laid out as Files lays out its
 * places (Dr.Net, 31 Oct 2026: «اصلاً شبیه بقیه نیست», «مینی‌مال به سبک فایل‌ها», «انتخاب اخیر داره،
 * رنگش فرق داره، کارت‌بندی داره»): on the sheet's grey, «اخیر» and a card of the last three sent from
 * here on top (Dr.Net: «کار اخیر میخوام بیاد بالا و فقط تا سه گزینه»), then a card of Files' own rows
 * (a 42dp mark, the name, a line under it). A tile wears its home-screen face, a setting its glyph
 * in Files' folder circle. A tap sends that one to the chat (GerehLinks.send) and closes the sheet.
 * Only in an app that asks for it (GerehHost.setLinksInSheet); MLMVPN keeps its own page
 * (GerehLinkPickActivity).
 */
public class ChatAttachAlertGerehLinksLayout extends ChatAttachAlert.AttachAlertLayout {
    private static final int FACE_DP = 42;
    /** How many of the last sent ones «اخیر» keeps. */
    private static final int RECENT_MAX = 3;
    private static final String PREFS = "gereh_links_recent";

    private static final int TYPE_HEADER = 0, TYPE_ROW = 1, TYPE_GAP = 2, TYPE_END = 3;

    /** One line of the list: a header, a row (with its card's last flag), a gap between cards, or the end. */
    private static final class Line {
        final int type;
        final GerehLinks.Item item;
        final boolean lastInCard;

        Line(int type, GerehLinks.Item item, boolean lastInCard) {
            this.type = type;
            this.item = item;
            this.lastInCard = lastInCard;
        }
    }

    private final boolean tiles;
    private final List<GerehLinks.Item> items = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final Map<String, Bitmap> faces = new HashMap<>();
    private final RecyclerListView listView;
    private final LinearLayoutManager layoutManager;
    private final Adapter adapter;

    public ChatAttachAlertGerehLinksLayout(ChatAttachAlert alert, Context context, boolean tiles, Theme.ResourcesProvider resourcesProvider) {
        super(alert, context, resourcesProvider);
        this.tiles = tiles;
        items.addAll(GerehLinks.items(tiles));

        listView = new RecyclerListView(context, resourcesProvider);
        // Files' rounded cards on the sheet's grey (ChatAttachAlertDocumentLayout)
        listView.setSections();
        iBlur3Capture = listView;
        iBlur3CaptureView = listView;
        occupyStatusBar = true;
        occupyNavigationBar = true;
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        listView.setGlowColor(getThemedColor(Theme.key_dialogScrollGlow));
        listView.setLayoutManager(layoutManager = new FillLastLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false, dp(56) + AndroidUtilities.statusBarHeight, listView) {
            @Override
            public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
                LinearSmoothScroller scroller = new LinearSmoothScroller(recyclerView.getContext()) {
                    @Override
                    public int calculateDyToMakeVisible(View view, int snapPreference) {
                        return super.calculateDyToMakeVisible(view, snapPreference) - (listView.getPaddingTop() - AndroidUtilities.statusBarHeight - dp(56));
                    }

                    @Override
                    protected int calculateTimeForDeceleration(int dx) {
                        return super.calculateTimeForDeceleration(dx) * 2;
                    }
                };
                scroller.setTargetPosition(position);
                startSmoothScroll(scroller);
            }
        });
        listView.setAdapter(adapter = new Adapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < lines.size() && lines.get(position).type == TYPE_ROW) {
                send(lines.get(position).item);
            }
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                parentAlert.updateLayout(ChatAttachAlertGerehLinksLayout.this, true, dy);
            }
        });
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fill();
    }

    /** «اخیر» and its card on top when something was sent from here before, then the places card. */
    private void fill() {
        lines.clear();
        List<GerehLinks.Item> recent = recent();
        if (!recent.isEmpty()) {
            lines.add(new Line(TYPE_HEADER, null, false));
            for (int i = 0; i < recent.size(); i++) {
                lines.add(new Line(TYPE_ROW, recent.get(i), i == recent.size() - 1));
            }
            lines.add(new Line(TYPE_GAP, null, false));
        }
        for (int i = 0; i < items.size(); i++) {
            lines.add(new Line(TYPE_ROW, items.get(i), i == items.size() - 1));
        }
        lines.add(new Line(TYPE_END, null, false));
        adapter.notifyDataSetChanged();
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The last ones sent from this sheet, newest first, that this version still offers. */
    private List<GerehLinks.Item> recent() {
        List<GerehLinks.Item> out = new ArrayList<>();
        String saved = prefs().getString(tiles ? "tiles" : "settings", "");
        for (String key : TextUtils.split(saved, "\n")) {
            for (GerehLinks.Item it : items) {
                if (it.key.equals(key) && out.size() < RECENT_MAX) {
                    out.add(it);
                }
            }
        }
        return out;
    }

    private void remember(GerehLinks.Item item) {
        List<String> keys = new ArrayList<>();
        keys.add(item.key);
        for (GerehLinks.Item it : recent()) {
            if (!it.key.equals(item.key) && keys.size() < RECENT_MAX) {
                keys.add(it.key);
            }
        }
        prefs().edit().putString(tiles ? "tiles" : "settings", TextUtils.join("\n", keys)).apply();
    }

    private void send(GerehLinks.Item item) {
        if (!(parentAlert.baseFragment instanceof ChatActivity)) {
            return;
        }
        long dialogId = ((ChatActivity) parentAlert.baseFragment).getDialogId();
        GerehLinks.send(parentAlert.currentAccount, dialogId, tiles, item);
        remember(item);
        parentAlert.dismiss(true);
    }

    private Bitmap face(String key) {
        if (!faces.containsKey(key)) {
            faces.put(key, GerehLinks.tileFace(key, dp(FACE_DP)));
        }
        return faces.get(key);
    }

    @Override
    public int needsActionBar() {
        return 1;
    }

    @Override
    public void onShow(ChatAttachAlert.AttachAlertLayout previousLayout) {
        parentAlert.actionBar.setTitle(getString(tiles ? R.string.GerehLinkPickTiles : R.string.GerehLinkPickSettings));
        fill();
        layoutManager.scrollToPositionWithOffset(0, 0);
    }

    @Override
    public void scrollToTop() {
        listView.smoothScrollToPosition(0);
    }

    @Override
    public int getListTopPadding() {
        return listView.getPaddingTop();
    }

    @Override
    public int getFirstOffset() {
        return getListTopPadding() + dp(5);
    }

    @Override
    public int getCurrentItemTop() {
        if (listView.getChildCount() <= 0) {
            return Integer.MAX_VALUE;
        }
        View child = listView.getChildAt(0);
        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findContainingViewHolder(child);
        int top = (int) child.getY() - AndroidUtilities.statusBarHeight - dp(4) - dp(8);
        int newOffset = top > 0 && holder != null && holder.getAdapterPosition() == 0 ? top : 0;
        return newOffset + dp(13);
    }

    @Override
    public void onPreMeasure(int availableWidth, int availableHeight) {
        // the sheet opens to the same height as Files and Music (ChatAttachAlertDocumentLayout)
        int padding;
        if (!AndroidUtilities.isTablet() && AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
            padding = (int) (availableHeight / 3.5f);
        } else {
            padding = availableHeight / 5 * 2;
        }
        padding = Math.max(0, padding - dp(1)) + AndroidUtilities.statusBarHeight;
        parentAlert.setAllowNestedScroll(true);
        listView.setPaddingWithoutRequestLayout(0, padding, 0, listPaddingBottom);
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY);
        parentAlert.getSheetContainer().invalidate();
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_ROW;
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }

        @Override
        public int getItemViewType(int position) {
            return lines.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(context, resourcesProvider);
                    break;
                case TYPE_ROW:
                    view = new SharedDocumentCell(context, SharedDocumentCell.VIEW_TYPE_PICKER, resourcesProvider);
                    break;
                case TYPE_GAP:
                    view = new ShadowSectionCell(context);
                    break;
                default:
                    // Files' last line too: nothing, and no part of a card
                    view = new View(context);
                    view.setTag(RecyclerListView.TAG_NOT_SECTION);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Line line = lines.get(position);
            if (line.type == TYPE_HEADER) {
                ((HeaderCell) holder.itemView).setText(getString(R.string.Recent));
            } else if (line.type == TYPE_ROW) {
                GerehLinks.Item it = line.item;
                SharedDocumentCell cell = (SharedDocumentCell) holder.itemView;
                // a line under every name, as Files has: the setting's place, or the app it opens in
                String under = TextUtils.isEmpty(it.subtitle) ? GerehLinks.appName() : it.subtitle;
                Bitmap face = tiles ? face(it.key) : null;
                int glyph = it.icon != 0 ? it.icon : tiles ? R.drawable.files_folder : R.drawable.msg_settings;
                cell.setTextAndValueAndTypeAndThumb(it.title, under, null, null, glyph, !line.lastInCard);
                if (face != null) {
                    cell.getImageView().setImageBitmap(face);
                }
            }
        }
    }
}
