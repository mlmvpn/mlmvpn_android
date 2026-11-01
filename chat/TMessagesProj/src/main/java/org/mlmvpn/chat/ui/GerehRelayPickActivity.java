package org.mlmvpn.chat.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.NostrChoice;
import org.mlmvpn.chat.bridge.GerehGas;
import org.mlmvpn.chat.bridge.GerehIdentity;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MLMCHAT: the relays (SPEC 20.6; Ehsan, 31 Oct 2026: «بالای برنامه … یک آیکون تعویض رله»), from
 * the arrows on the main screen: «خودکار», or one relay the user prefers. The one chosen is always
 * read and comes first; the others stay as its backup, so a chat never stops on it (Ehsan's
 * choice). «سنجش» tries every relay once, for real, only on the user's tap; automatic then reads
 * from the fastest. The Google relays have their own list, one tap away.
 */
public final class GerehRelayPickActivity extends BaseFragment {
    private static final int AUTO = 1, MEASURE = 2, GOOGLE = 3, RELAY = 1000;
    private static final long REFRESH_MS = 3000;

    private UniversalRecyclerView listView;
    private List<NostrChoice> choices = Collections.emptyList();
    private boolean measuring;
    private final Runnable refresh = this::refresh;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.GerehRelayPickTitle));
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
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        load();
        return fragmentView = contentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.runOnUIThread(refresh, REFRESH_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(refresh);
    }

    private void refresh() {
        load();
        update();
        AndroidUtilities.runOnUIThread(refresh, REFRESH_MS);
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void load() {
        ChatNode node = GerehIdentity.node();
        try {
            choices = node == null ? Collections.emptyList() : node.nostrChoices();
        } catch (Throwable e) {
            choices = Collections.emptyList();
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean auto = true;
        for (NostrChoice c : choices) {
            auto &= !c.getPreferred();
        }
        items.add(UItem.asRadio2(AUTO, getString(R.string.GerehRelayPickAuto), getString(R.string.GerehRelayPickAutoInfo)).setChecked(auto));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(getString(R.string.GerehRelayPickHeader)));
        for (int i = 0; i < choices.size(); i++) {
            NostrChoice c = choices.get(i);
            String name = c.getOwn() ? getString(R.string.GerehRelayPickIran) : c.getName();
            items.add(UItem.asRadio2(RELAY + i, name, line(c)).setChecked(c.getPreferred()));
        }
        items.add(UItem.asButton(MEASURE, getString(measuring ? R.string.GerehRelayPickMeasuring : R.string.GerehRelayPickMeasure)));
        items.add(UItem.asShadow(getString(R.string.GerehRelayPickInfo)));
        items.add(UItem.asButton(GOOGLE, getString(R.string.GerehRelayPickGoogle)));
        items.add(UItem.asShadow(getString(R.string.GerehRelayPickGoogleInfo)));
    }

    /** How a relay stands now, and what the last measuring found. */
    private static String line(NostrChoice c) {
        String s;
        switch (c.getState()) {
            case "open":
                s = getString(c.getReads() ? R.string.GerehRelayPickInUse : R.string.GerehRelayPickOpen);
                break;
            case "connecting":
                s = getString(R.string.GerehRelayPickConnecting);
                break;
            case "resting":
                s = getString(R.string.GerehRelayPickResting);
                break;
            case "idle":
                s = getString(R.string.GerehRelayPickIdle);
                break;
            default:
                s = getString(R.string.GerehRelayPickFailed);
        }
        if (!c.getWrites()) {
            s += " · " + getString(R.string.GerehRelayPickNoWrites);
        }
        if (c.getMeasuredMs() > 0) {
            Long ms = c.getMs();
            s += " · " + (ms != null ? formatString(R.string.GerehRelayPickMs, LocaleController.formatNumber(ms, ',')) : getString(R.string.GerehRelayPickMeasureFail));
        }
        return s;
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            return;
        }
        if (item.id == AUTO || item.id >= RELAY && item.id - RELAY < choices.size()) {
            String relay = item.id == AUTO ? null : choices.get(item.id - RELAY).getRelay();
            GerehGas.async(() -> {
                try {
                    node.nostrPrefer(relay);
                } catch (Throwable ignored) {
                }
                AndroidUtilities.runOnUIThread(() -> {
                    load();
                    update();
                });
            });
        } else if (item.id == MEASURE && !measuring) {
            measuring = true;
            update();
            GerehGas.async(() -> {
                List<NostrChoice> found;
                try {
                    found = node.nostrMeasure();
                } catch (Throwable e) {
                    found = null;
                }
                List<NostrChoice> got = found;
                AndroidUtilities.runOnUIThread(() -> {
                    measuring = false;
                    if (got != null && !got.isEmpty()) {
                        choices = got;
                    }
                    update();
                });
            });
        } else if (item.id == GOOGLE) {
            presentFragment(new GerehGasListActivity());
        }
    }
}
