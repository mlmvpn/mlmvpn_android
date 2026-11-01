package org.mlmvpn.chat.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.mlmvpn.chat.bridge.GerehLinks;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MLMCHAT: the attach menu's «MLMVPN» (the app's tiles) or «تنظیمات» (its settings), q11 and q12.
 * A tap sends that one to the chat (GerehLinks.send) and comes back to it. The tiles are a grid of
 * two columns, each with its face as the home screen draws it (Ehsan, 30 Oct 2026: a one-column
 * list where most rows had no icon); the settings, whose rows carry their place, stay a list.
 */
public final class GerehLinkPickActivity extends BaseFragment {
    private static final int ROW = 1000;
    private static final int FACE_DP = 56;

    private final long dialogId;
    private final boolean tiles;
    private final List<GerehLinks.Item> items = new ArrayList<>();
    private final Map<String, Bitmap> faces = new HashMap<>();

    public GerehLinkPickActivity(long dialogId, boolean tiles) {
        this.dialogId = dialogId;
        this.tiles = tiles;
    }

    @Override
    public View createView(Context context) {
        items.clear();
        items.addAll(GerehLinks.items(tiles));
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(tiles ? R.string.GerehLinkPickTiles : R.string.GerehLinkPickSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        if (tiles) {
            contentView.addView(grid(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        } else {
            UniversalRecyclerView listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
            contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        }
        fragmentView = contentView;
        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> list, UniversalAdapter adapter) {
        list.add(UItem.asShadow(getString(R.string.GerehLinkPickSettingsInfo)));
        for (int i = 0; i < items.size(); i++) {
            GerehLinks.Item it = items.get(i);
            if (it.icon != 0) {
                list.add(UItem.asButton(ROW + i, it.icon, it.title, it.subtitle.isEmpty() ? null : it.subtitle));
            } else {
                list.add(UItem.asButton(ROW + i, it.title, it.subtitle.isEmpty() ? null : it.subtitle));
            }
        }
        list.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        send(item.id - ROW);
    }

    private void send(int i) {
        if (i < 0 || i >= items.size()) {
            return;
        }
        GerehLinks.send(currentAccount, dialogId, tiles, items.get(i));
        finishFragment();
    }

    /** The tiles: a line saying what a tap does, then two columns of faces and names. */
    private RecyclerListView grid(Context context) {
        RecyclerListView grid = new RecyclerListView(context, resourceProvider);
        GridLayoutManager layout = new GridLayoutManager(context, 2);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position == 0 ? 2 : 1;
            }
        });
        grid.setLayoutManager(layout);
        grid.setPadding(dp(8), 0, dp(8), dp(12));
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setDrawSelection(false); // each card shows its own press, rounded (Cell)
        grid.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return holder.getItemViewType() == 1;
            }

            @Override
            public int getItemCount() {
                return items.size() + 1;
            }

            @Override
            public int getItemViewType(int position) {
                return position == 0 ? 0 : 1;
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                View v = viewType == 0 ? info(context) : new Cell(context);
                v.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(v);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                if (holder.itemView instanceof Cell) {
                    GerehLinks.Item it = items.get(position - 1);
                    ((Cell) holder.itemView).set(it.title, face(it.key));
                }
            }
        });
        grid.setOnItemClickListener((view, position) -> send(position - 1));
        return grid;
    }

    private TextView info(Context context) {
        TextView t = new TextView(context);
        t.setText(getString(R.string.GerehLinkPickTilesInfo));
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, resourceProvider));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(14), dp(12), dp(10));
        return t;
    }

    private Bitmap face(String key) {
        if (!faces.containsKey(key)) {
            faces.put(key, GerehLinks.tileFace(key, dp(FACE_DP)));
        }
        return faces.get(key);
    }

    /** One tile: its face above its name, on a card of its own. */
    private final class Cell extends FrameLayout {
        private final ImageView image;
        private final TextView name;

        Cell(Context context) {
            super(context);
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(dp(8), dp(14), dp(8), dp(12));
            box.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16),
                    Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider),
                    Theme.getColor(Theme.key_listSelector, resourceProvider),
                    0xff000000));
            box.setDuplicateParentStateEnabled(true);
            image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(image, LayoutHelper.createLinear(FACE_DP, FACE_DP, Gravity.CENTER_HORIZONTAL));
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));
            addView(box, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL, 4, 4, 4, 4));
        }

        void set(String title, Bitmap face) {
            name.setText(title);
            if (face != null) {
                image.setImageBitmap(face);
                image.setVisibility(VISIBLE);
            } else {
                image.setImageDrawable(null);
                image.setVisibility(INVISIBLE);
            }
        }
    }
}
