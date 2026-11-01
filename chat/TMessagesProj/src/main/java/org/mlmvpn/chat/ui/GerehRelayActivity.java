package org.mlmvpn.chat.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.NetStatus;
import org.mlmchat.core.RelayPeer;
import org.mlmchat.core.RelayStatus;
import org.mlmvpn.chat.bridge.GerehIdentity;
import org.mlmvpn.chat.bridge.GerehNet;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
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
import java.util.List;

/**
 * MLMCHAT: Profile › Volunteer (relay), under the Gereh name (Ehsan, 23 Oct 2026): the volunteer
 * role on or off, «is this network suitable?», what the volunteer does now (who is connected and
 * their traffic, how many it helped, all its traffic) with a way to disconnect someone, and the
 * network: how the phone reaches volunteers, and «inside Iran only» (SPEC 15.10 to 15.12).
 *
 * People are shown only by the core's short anonymous names: a volunteer never learns who anyone
 * is. Refreshed every few seconds while open.
 */
public final class GerehRelayActivity extends BaseFragment {
    private static final int SWITCH = 1, CHECK = 2, DOMESTIC = 3, COPY = 4, CELL = 5, COPY_MINE = 6, NEARBY = 7, PEER = 1000;
    private static final int REQUEST_NEARBY = 7701;
    private static final long REFRESH_MS = 3000;

    private UniversalRecyclerView listView;
    private String checkText;
    /** What the user can do by hand, for the last check (null before one). */
    private String stepsText;
    private boolean checking;
    private RelayStatus relay;
    private NetStatus net;
    private final List<RelayPeer> shownPeers = new ArrayList<>();
    private final Runnable refresh = this::refresh;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.GerehRelayTitle));
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
        // back from the permission dialog or the Bluetooth settings: start the radio if it can now
        ChatNode node = GerehIdentity.node();
        if (node != null && GerehNet.nearbyChosen()) {
            GerehNet.setNearby(true);
        }
        AndroidUtilities.runOnUIThread(refresh, REFRESH_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(refresh);
    }

    private void refresh() {
        load();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        AndroidUtilities.runOnUIThread(refresh, REFRESH_MS);
    }

    private void load() {
        ChatNode node = GerehIdentity.node();
        try {
            relay = node == null ? null : node.relayStatus();
            net = node == null ? null : node.netStatus();
        } catch (Throwable e) {
            relay = null;
            net = null;
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean chosen = GerehNet.volunteerChosen();
        items.add(UItem.asHeader(getString(R.string.GerehRelayRow)));
        items.add(UItem.asCheck(SWITCH, getString(R.string.GerehRelaySwitch)).setChecked(chosen));
        if (chosen) {
            items.add(UItem.asCheck(CELL, getString(R.string.GerehRelayOnCell)).setChecked(GerehNet.volunteerOnCell()));
        }
        items.add(UItem.asButton(CHECK, getString(R.string.GerehRelayCheck)));
        items.add(UItem.asShadow(checking ? getString(R.string.GerehRelayChecking) : checkText != null ? checkText : getString(R.string.GerehRelayInfo)));
        if (!checking && stepsText != null) {
            items.add(UItem.asHeader(getString(R.string.GerehStepsHeader)));
            items.add(UItem.asShadow(stepsText));
            items.add(UItem.asButton(COPY_MINE, getString(R.string.GerehStepsCopy)));
        }

        shownPeers.clear();
        if (chosen) {
            items.add(UItem.asHeader(getString(R.string.GerehRelayState)));
            String state;
            if (!GerehNet.volunteerRunning() && !GerehNet.paused().isEmpty()) {
                state = getString("cell".equals(GerehNet.paused()) ? R.string.GerehRelayPausedCell
                        : "cap".equals(GerehNet.paused()) ? R.string.GerehRelayPausedCap : R.string.GerehRelayPausedBattery);
            } else {
                state = getString(net != null && !net.getVerified().isEmpty() ? R.string.GerehRelayVerified : R.string.GerehRelayNotVerified);
            }
            items.add(UItem.asButton(0, state));
            RelayStatus r = relay;
            int now = r == null ? 0 : r.getNow();
            long served = r == null ? 0 : r.getServed();
            items.add(UItem.asButton(0, getString(R.string.GerehRelayNow), formatString(R.string.GerehRelayPeople, now)));
            items.add(UItem.asButton(0, getString(R.string.GerehRelayServed), formatString(R.string.GerehRelayPeople, (int) served)));
            items.add(UItem.asButton(0, getString(R.string.GerehRelayTraffic), size(r == null ? 0 : r.getBytesTotal())));
            items.add(UItem.asButton(0, getString(R.string.GerehRelayTrafficRun),
                    size(r == null ? 0 : r.getRxRun()) + " / " + size(r == null ? 0 : r.getTxRun())));
            List<String> addrs = net == null ? Collections.emptyList() : net.getVolunteerAddrs();
            if (GerehNet.volunteerRunning() && !addrs.isEmpty()) {
                items.add(UItem.asButton(COPY, getString(R.string.GerehRelayCopyAddrs)));
                items.add(UItem.asShadow(android.text.TextUtils.join("\n", addrs)));
            }

            items.add(UItem.asHeader(getString(R.string.GerehRelayPeersHeader)));
            List<RelayPeer> peers = r == null ? Collections.emptyList() : r.getPeers();
            if (peers.isEmpty()) {
                items.add(UItem.asShadow(getString(R.string.GerehRelayNobody)));
            } else {
                for (RelayPeer p : peers) {
                    String title = formatString(R.string.GerehRelayPeer, p.getName());
                    if (p.getReserved()) {
                        title += " · " + getString(R.string.GerehRelayPeerReserved);
                    }
                    items.add(UItem.asButton(PEER + shownPeers.size(), title, size(p.getRx() + p.getTx())));
                    shownPeers.add(p);
                }
                items.add(UItem.asShadow(getString(R.string.GerehRelayPeersInfo)));
            }
        }

        items.add(UItem.asHeader(getString(R.string.GerehNetHeader)));
        items.add(UItem.asButton(0, getString(R.string.GerehNetVolunteers), ladderText()));
        if (net != null && net.getOutOfScope() > 0) {
            items.add(UItem.asButton(0, formatString(R.string.GerehNetOutOfScope, net.getOutOfScope())));
        }
        if (net != null && "unreachable".equals(net.getLadder())) {
            items.add(UItem.asShadow(formatString(R.string.GerehNetUnreachableInfo, (int) net.getNextTryS())));
        } else if (net != null && "idle".equals(net.getLadder())) {
            items.add(UItem.asShadow(getString(R.string.GerehNetIdleInfo)));
        }
        items.add(UItem.asCheck(NEARBY, getString(R.string.GerehNearby)).setChecked(GerehNet.nearbyChosen()));
        if (GerehNet.nearbyChosen()) {
            String st = GerehNet.nearbyState();
            if ("location_off".equals(st)) {
                items.add(UItem.asShadow(getString(R.string.GerehNearbyLocationOff)));
            } else {
                items.add(UItem.asButton(0, getString(R.string.GerehRelayState), "on".equals(st) ? formatString(R.string.GerehNearbyOn, GerehNet.nearbyCount())
                        : getString("no_permission".equals(st) ? R.string.GerehNearbyNoPermission : R.string.GerehNearbyBtOff)));
            }
        }
        items.add(UItem.asShadow(getString(R.string.GerehNearbyInfo)));
        items.add(UItem.asCheck(DOMESTIC, getString(R.string.GerehNetDomestic)).setChecked(GerehNet.domestic()));
        items.add(UItem.asShadow(getString(R.string.GerehNetDomesticInfo)));
    }

    private String ladderText() {
        NetStatus n = net;
        if (n == null) {
            return "";
        }
        switch (n.getLadder()) {
            case "reachable": return formatString(R.string.GerehNetLadderReachable, n.getRung());
            case "probing": return formatString(R.string.GerehNetLadderProbing, n.getTried(), n.getTotal());
            case "unreachable": return getString(R.string.GerehNetLadderUnreachable);
            default: return getString(R.string.GerehNetLadderIdle);
        }
    }

    private static String size(long bytes) {
        return AndroidUtilities.formatFileSize(bytes);
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == SWITCH) {
            GerehNet.setVolunteer(!GerehNet.volunteerChosen());
            listView.adapter.update(true);
        } else if (item.id == CELL) {
            GerehNet.setVolunteerOnCell(!GerehNet.volunteerOnCell());
            listView.adapter.update(true);
        } else if (item.id == NEARBY) {
            boolean on = !GerehNet.nearbyChosen();
            GerehNet.setNearby(on);
            // the permissions are asked only now, when the user turns «nearby» on (PLAN 15)
            if (on && getParentActivity() != null && !org.mlmchat.core.ble.BleRadio.Companion.hasPermissions(getParentActivity())) {
                getParentActivity().requestPermissions(org.mlmchat.core.ble.BleRadio.Companion.permissions(), REQUEST_NEARBY);
            }
            listView.adapter.update(true);
        } else if (item.id == DOMESTIC) {
            GerehNet.setDomestic(!GerehNet.domestic());
            listView.adapter.update(true);
        } else if (item.id == CHECK && !checking) {
            checking = true;
            listView.adapter.update(true);
            GerehNet.check(c -> {
                checking = false;
                checkText = GerehNet.verdictText(c);
                stepsText = c == null ? null : GerehNet.manualSteps(c);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        } else if (item.id == COPY_MINE) {
            AndroidUtilities.addToClipboard(GerehNet.myAddressesText());
            BulletinFactory.of(this).createCopyBulletin(getString(R.string.GerehRelayAddrsCopied)).show();
        } else if (item.id == COPY && net != null) {
            AndroidUtilities.addToClipboard(android.text.TextUtils.join("\n", net.getVolunteerAddrs()));
            BulletinFactory.of(this).createCopyBulletin(getString(R.string.GerehRelayAddrsCopied)).show();
        } else if (item.id >= PEER && item.id - PEER < shownPeers.size()) {
            kick(shownPeers.get(item.id - PEER));
        }
    }

    private void kick(RelayPeer p) {
        String since = LocaleController.getInstance().getFormatterDay().format(p.getSinceMs());
        String detail = formatString(R.string.GerehRelayPeerLine, since, size(p.getRx()), size(p.getTx()));
        if (p.getCircuits() > 0) {
            detail += "\n" + formatString(R.string.GerehRelayPeerCircuits, p.getCircuits());
        }
        new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.GerehRelayKickTitle))
                .setMessage(formatString(R.string.GerehRelayKickText, p.getName()) + "\n\n" + detail)
                .setPositiveButton(getString(R.string.GerehRelayKickTitle), (d, w) -> {
                    ChatNode node = GerehIdentity.node();
                    boolean done = false;
                    try {
                        done = node != null && node.relayKick(p.getName());
                    } catch (Throwable ignored) {
                    }
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(done ? R.string.GerehRelayKickDone : R.string.GerehRelayKickGone)).show();
                    load();
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }
}
