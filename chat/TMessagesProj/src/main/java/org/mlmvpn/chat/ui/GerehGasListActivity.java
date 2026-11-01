package org.mlmvpn.chat.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.GasOverview;
import org.mlmchat.core.GasRelayView;
import org.mlmchat.core.GasShareInfo;
import org.mlmvpn.chat.bridge.GerehGas;
import org.mlmvpn.chat.bridge.GerehIdentity;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MLMCHAT: Google relays (SPEC 19), where Telegram's proxy list is in Telegram. The user's list of
 * mailboxes in their own Google accounts and their friends': how each is doing and how much it did
 * today, and the ways to make one or add a friend's. The way to Google (address and name) is found
 * by the phone itself and only shown here. Refreshed every few seconds while open.
 */
public final class GerehGasListActivity extends BaseFragment {
    private static final int NEW = 1, ADD = 2, PASTE = 3, BORROW = 4, RELAY = 1000;
    private static final long REFRESH_MS = 4000;

    private UniversalRecyclerView listView;
    private GasOverview overview;
    private final List<GasRelayView> shown = new ArrayList<>();
    private final Runnable refresh = this::refresh;
    /** Relays made here whose own count was asked for while this screen was open (once each, then every few minutes). */
    private final Set<String> asked = new HashSet<>();
    /** A relay link that brought us here (a tap on it in a chat): asked about once the screen is up. */
    private String pendingLink;

    public GerehGasListActivity() {
    }

    public GerehGasListActivity(String link) {
        pendingLink = link;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.GerehGasTitle));
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
        if (pendingLink != null) {
            String link = pendingLink;
            pendingLink = null;
            AndroidUtilities.runOnUIThread(() -> confirmAdd(link), 300);
        }
        return fragmentView = contentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
        AndroidUtilities.runOnUIThread(refresh, REFRESH_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(refresh);
    }

    private void refresh() {
        load();
        AndroidUtilities.runOnUIThread(refresh, REFRESH_MS);
    }

    private void load() {
        GerehGas.async(() -> {
            GasOverview o = null;
            try {
                ChatNode node = GerehIdentity.node();
                o = node == null ? null : node.gasOverview();
            } catch (Throwable ignored) {
            }
            GasOverview result = o;
            AndroidUtilities.runOnUIThread(() -> {
                overview = result;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                readOwnCounts(result);
            });
        });
    }

    /** The relays made here: their script's own count (requests, devices helped) is read when it is stale. */
    private void readOwnCounts(GasOverview o) {
        ChatNode node = GerehIdentity.node();
        if (o == null || node == null) {
            return;
        }
        for (GasRelayView r : o.getRelays()) {
            boolean stale = r.getServer() == null || System.currentTimeMillis() - r.getServer().getAtMs() > 3 * 60 * 1000L;
            if (r.getMine() && !r.getDraft() && r.getEnabled() && stale && asked.add(r.getRid())) {
                String rid = r.getRid();
                GerehGas.async(() -> {
                    try {
                        node.gasRelayServerStats(rid);
                    } catch (Throwable ignored) {
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        // asked again after a while, if the screen is still open
                        AndroidUtilities.runOnUIThread(() -> asked.remove(rid), 3 * 60 * 1000L);
                        load();
                    });
                });
            }
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        shown.clear();
        List<GasRelayView> relays = overview == null ? Collections.emptyList() : overview.getRelays();
        items.add(UItem.asHeader(getString(R.string.GerehGasHeader)));
        if (relays.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.GerehGasNone)));
        }
        for (GasRelayView r : relays) {
            String mark = r.isBorrowed() ? "  · " + getString(R.string.GerehGasBorrowedMark) : r.isPublic() ? "  · " + getString(R.string.GerehGasPublicMark) : "";
            items.add(UItem.asButton(RELAY + shown.size(), r.getName() + "  ···" + r.getTail() + mark, GerehGas.rowText(r)));
            shown.add(r);
        }
        items.add(UItem.asButton(NEW, getString(R.string.GerehGasNew)));
        items.add(UItem.asButton(ADD, getString(R.string.GerehGasAdd)));
        items.add(UItem.asButton(PASTE, getString(R.string.GerehGasPasteLink)));
        items.add(UItem.asShadow(getString(R.string.GerehGasInfo)));

        // public relays (SPEC 19.12): borrowed while we have none of our own
        items.add(UItem.asHeader(getString(R.string.GerehGasPublicHeader)));
        items.add(UItem.asCheck(BORROW, getString(R.string.GerehGasBorrowAuto)).setChecked(overview == null || overview.getBorrowAuto()));
        int known = overview == null ? 0 : overview.getPublicKnown();
        items.add(UItem.asButton(0, getString(R.string.GerehGasPublicKnown), String.valueOf(known)));
        items.add(UItem.asShadow(getString(R.string.GerehGasBorrowInfo)));

        items.add(UItem.asHeader(getString(R.string.GerehGasAutoHeader)));
        String front = overview == null || overview.getFront().isEmpty() ? getString(R.string.GerehGasFrontNone)
                : formatString(R.string.GerehGasFrontValue, overview.getFront(), (int) overview.getFrontMs());
        items.add(UItem.asButton(0, getString(R.string.GerehGasFront), front));
        int announced = overview == null ? 0 : overview.getAnnounced().size();
        items.add(UItem.asButton(0, getString(R.string.GerehGasAnnounced), formatString(R.string.GerehGasAnnouncedValue, announced)));
        int waiting = overview == null ? 0 : overview.getWaiting();
        items.add(UItem.asButton(0, getString(R.string.GerehGasWaiting), String.valueOf(waiting)));
        items.add(UItem.asShadow(getString(R.string.GerehGasAutoInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == NEW) {
            presentFragment(new GerehGasSetupActivity(null));
        } else if (item.id == ADD) {
            GerehGas.ask(this, getString(R.string.GerehGasAdd), getString(R.string.GerehGasAddAsk), "", getString(R.string.GerehGasAddHint),
                    getString(R.string.GerehGasNext), text -> {
                        String link = GerehGas.linkIn(text);
                        if (link == null) {
                            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasAddBad)).show();
                        } else {
                            confirmAdd(link);
                        }
                    });
        } else if (item.id == PASTE) {
            String link = GerehGas.linkIn(GerehGas.clipboardText(getParentActivity()));
            if (link == null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasPasteNone)).show();
            } else {
                confirmAdd(link);
            }
        } else if (item.id == BORROW) {
            ChatNode node = GerehIdentity.node();
            boolean on = overview == null || !overview.getBorrowAuto();
            if (node != null) {
                GerehGas.async(() -> {
                    try {
                        node.gasSetBorrowAuto(on);
                    } catch (Throwable ignored) {
                    }
                    AndroidUtilities.runOnUIThread(this::load);
                });
            }
        } else if (item.id >= RELAY && item.id - RELAY < shown.size()) {
            GasRelayView r = shown.get(item.id - RELAY);
            presentFragment(r.getDraft() ? new GerehGasSetupActivity(r.getRid()) : new GerehGasDetailActivity(r.getRid()));
        }
    }

    /** What a friend's relay is, and what adding it means, before it is added. */
    private void confirmAdd(String link) {
        ChatNode node = GerehIdentity.node();
        if (node == null || getParentActivity() == null) {
            return;
        }
        GasShareInfo info;
        try {
            info = node.gasShareInfo(link);
        } catch (Throwable e) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasAddBad)).show();
            return;
        }
        String name = info.getName().isEmpty() ? getString(R.string.GerehGasFriendName) : info.getName();
        new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(formatString(R.string.GerehGasAddTitle, name, info.getTail()))
                .setMessage(getString(R.string.GerehGasAddText))
                .setPositiveButton(getString(R.string.GerehGasAddDo), (d, w) -> GerehGas.async(() -> {
                    String rid = null;
                    try {
                        rid = node.gasRelayAddShare(link);
                    } catch (Throwable ignored) {
                    }
                    boolean ok = rid != null;
                    AndroidUtilities.runOnUIThread(() -> {
                        BulletinFactory.of(this).createSimpleBulletin(ok ? R.raw.contact_check : R.raw.error,
                                getString(ok ? R.string.GerehGasAddDone : R.string.GerehGasAddBad)).show();
                        load();
                    });
                }))
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }
}
