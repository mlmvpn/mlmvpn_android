package org.mlmvpn.chat.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.mlmvpn.chat.bridge.GerehConfigs;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MLMCHAT: the configs of one message or file, each measured for real (GerehConfigs, n5): opened by
 * the user's «سنجش», so every one is tried at once, a few at a time; what connects can then go to
 * the app's section for it in one tap («انتقال سالم‌ها»), or all of them.
 */
public final class GerehConfigSheet extends BottomSheet {
    /** At once: each one is a core in the app's test process, so a list of hundreds goes a few at a time. */
    private static final int AT_ONCE = 6;

    private final BaseFragment fragment;
    private final List<GerehConfigs.Config> configs;
    /** Per config: null while measured, -1 when it is measured in its section, 0 when nothing came back, else ms. */
    private final Long[] delays;
    private final ExecutorService pool = Executors.newFixedThreadPool(AT_ONCE);
    private final TextView summary;
    private final TextView moveHealthy;
    private final RecyclerListView.SelectionAdapter adapter;
    private volatile boolean closed;

    public static void show(BaseFragment fragment, List<GerehConfigs.Config> configs) {
        if (fragment == null || fragment.getParentActivity() == null || configs.isEmpty()) {
            return;
        }
        new GerehConfigSheet(fragment, configs).show();
    }

    private GerehConfigSheet(BaseFragment fragment, List<GerehConfigs.Config> configs) {
        super(fragment.getParentActivity(), true);
        this.fragment = fragment;
        this.configs = configs;
        this.delays = new Long[configs.size()];
        Context context = fragment.getParentActivity();
        setApplyBottomPadding(true);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(8), 0, dp(8));

        TextView title = new TextView(context);
        title.setText(LocaleController.formatString(R.string.GerehCfgTitle, configs.size()));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 10, 22, 0));

        summary = new TextView(context);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        summary.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        summary.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        column.addView(summary, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 6, 22, 0));

        RecyclerListView list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        adapter = new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return false;
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                return new RecyclerListView.Holder(new Row(parent.getContext()));
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                ((Row) holder.itemView).set(configs.get(position), delays[position]);
            }

            @Override
            public int getItemCount() {
                return configs.size();
            }
        };
        list.setAdapter(adapter);
        // up to about half the screen: a list of hundreds scrolls inside the sheet
        int rowsShown = Math.min(configs.size(), Math.max(3, (int) (AndroidUtilities.displaySize.y * 0.5f / dp(58))));
        column.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, rowsShown * 58, 0, 10, 0, 0));

        moveHealthy = button(context, true, () -> move(true));
        column.addView(moveHealthy, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 12, 16, 0));
        TextView moveAll = button(context, false, () -> move(false));
        moveAll.setText(LocaleController.formatString(R.string.GerehCfgMoveAll, configs.size()));
        column.addView(moveAll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 10, 16, 8));

        setCustomView(column);
        refresh();
        measure();
    }

    /** Every config, a few at a time: the user asked for it with «سنجش». */
    private void measure() {
        for (int i = 0; i < configs.size(); i++) {
            final int at = i;
            pool.execute(() -> {
                if (closed) {
                    return;
                }
                long ms = GerehConfigs.delay(configs.get(at));
                AndroidUtilities.runOnUIThread(() -> {
                    delays[at] = ms;
                    adapter.notifyItemChanged(at);
                    refresh();
                });
            });
        }
        pool.shutdown();
    }

    private void refresh() {
        // counted over the ones measured here: an OpenVPN or WireGuard file is measured in its section
        int testable = 0;
        int done = 0;
        int healthy = 0;
        for (int i = 0; i < delays.length; i++) {
            if (!configs.get(i).testable) {
                continue;
            }
            testable++;
            Long d = delays[i];
            if (d != null) {
                done++;
                if (d > 0) {
                    healthy++;
                }
            }
        }
        summary.setText(testable == 0
                ? getString(R.string.GerehCfgNoneTestable)
                : done < testable
                ? LocaleController.formatString(R.string.GerehCfgMeasuring, done, testable)
                : LocaleController.formatString(R.string.GerehCfgMeasured, healthy, testable));
        moveHealthy.setText(LocaleController.formatString(R.string.GerehCfgMoveHealthy, healthy));
        moveHealthy.setEnabled(healthy > 0);
        moveHealthy.setAlpha(healthy > 0 ? 1f : 0.5f);
    }

    private void move(boolean healthyOnly) {
        List<GerehConfigs.Config> out = new ArrayList<>();
        for (int i = 0; i < configs.size(); i++) {
            if (!healthyOnly || delays[i] != null && delays[i] > 0) {
                out.add(configs.get(i));
            }
        }
        if (out.isEmpty()) {
            return;
        }
        Activity activity = fragment.getParentActivity();
        dismiss();
        GerehConfigs.transfer(activity, out);
    }

    @Override
    public void dismissInternal() {
        closed = true;
        pool.shutdownNow();
        super.dismissInternal();
    }

    private TextView button(Context context, boolean primary, Runnable click) {
        TextView b = new TextView(context);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setTypeface(AndroidUtilities.bold());
        if (primary) {
            b.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(10), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        } else {
            b.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(10), Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.12f), Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.24f)));
        }
        b.setOnClickListener(v -> click.run());
        return b;
    }

    /** One config: its name (as written, left to right), its section, and its delay. */
    private static final class Row extends LinearLayout {
        private final TextView name;
        private final TextView line;

        Row(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(22), dp(6), dp(22), dp(6));
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            line = new TextView(context);
            line.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            line.setSingleLine(true);
            line.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        }

        void set(GerehConfigs.Config c, Long delay) {
            name.setText(c.name);
            String state;
            int color;
            if (!c.testable || delay != null && delay < 0) {
                state = getString(R.string.GerehCfgInSection);
                color = Theme.key_dialogTextGray2;
            } else if (delay == null) {
                state = getString(R.string.GerehCfgTesting);
                color = Theme.key_dialogTextGray2;
            } else if (delay == 0) {
                state = getString(R.string.GerehCfgFailed);
                color = Theme.key_text_RedRegular;
            } else {
                state = LocaleController.formatString(R.string.GerehCfgDelay, delay);
                color = Theme.key_windowBackgroundWhiteGreenText;
            }
            line.setText(LocaleController.formatString(R.string.GerehCfgRowLine, c.section, state));
            line.setTextColor(Theme.getColor(color));
        }
    }
}
