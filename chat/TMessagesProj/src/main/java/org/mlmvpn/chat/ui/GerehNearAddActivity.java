package org.mlmvpn.chat.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.PairPeer;
import org.mlmchat.core.PairState;
import org.mlmvpn.chat.bridge.GerehIdentity;
import org.mlmvpn.chat.bridge.GerehNet;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
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
 * MLMCHAT: «Add nearby» (SPEC 16.4, T36; Ehsan, 23 Oct 2026): two people side by side add each
 * other with no internet and no QR, over Bluetooth, the same Wi-Fi or one phone's hotspot.
 *
 * Only people who have this very screen open are listed, each under the name they typed here;
 * nobody else is ever seen. Asking someone shows the same four emoji on both phones; the cards are
 * exchanged only when both people say they match. The screen closes itself after two minutes.
 */
public final class GerehNearAddActivity extends BaseFragment {
    private static final int NAME = 1, REOPEN = 2, ALLOW_BT = 3, PEER = 1000;
    private static final int REQUEST_BT = 7702;
    private static final long REFRESH_MS = 700;

    private UniversalRecyclerView listView;
    private PairState state = new PairState(false, 0, Collections.emptyList());
    private final List<PairPeer> shown = new ArrayList<>();
    /** Code dialogs already shown, so a refresh does not open one twice. */
    private final Set<String> asked = new HashSet<>();
    private final Set<String> announced = new HashSet<>();
    private AlertDialog codeDialog;
    private String name;
    private final Runnable refresh = this::refresh;

    @Override
    public boolean onFragmentCreate() {
        name = GerehNet.pairName();
        GerehNet.startPairing(name);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(refresh);
        GerehNet.stopPairing();
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.GerehNearAddTitle));
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
        // back from the permission dialog: the radio can start now
        GerehNet.pairingRadio();
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
        react();
        AndroidUtilities.runOnUIThread(refresh, REFRESH_MS);
    }

    private void load() {
        ChatNode node = GerehIdentity.node();
        try {
            if (node != null) {
                state = node.pairState();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Someone asked us: the code at once. Someone was added: say so, and refresh the contacts. */
    private void react() {
        for (PairPeer p : state.getPeers()) {
            if ("incoming".equals(p.getState()) && p.getCode() != null && asked.add(p.getId())) {
                showCode(p);
            }
            if ("done".equals(p.getState()) && announced.add(p.getId())) {
                if (codeDialog != null) {
                    codeDialog.dismiss();
                }
                ContactsController.getInstance(currentAccount).loadContacts(false, 0);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, formatString(R.string.GerehNearAddDone, p.getName())).show();
            }
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asShadow(getString(R.string.GerehNearAddInfo)));
        items.add(UItem.asButton(NAME, getString(R.string.GerehNearAddName), name));
        if (state.getOpen()) {
            items.add(UItem.asButton(0, formatString(R.string.GerehNearAddOpen, state.getLeftS())));
        } else {
            items.add(UItem.asButton(REOPEN, getString(R.string.GerehNearAddReopen), getString(R.string.GerehNearAddClosed)));
        }
        if (!GerehNet.pairingBluetooth()) {
            items.add(UItem.asButton(ALLOW_BT, getString(R.string.GerehNearAddAllowBt)));
            items.add(UItem.asShadow(getString(R.string.GerehNearAddNoBt)));
        }

        items.add(UItem.asHeader(getString(R.string.GerehNearAddPeople)));
        shown.clear();
        if (state.getPeers().isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.GerehNearAddNobody)));
            return;
        }
        for (PairPeer p : state.getPeers()) {
            String via = getString("ble".equals(p.getVia()) ? R.string.GerehNearAddViaBle : R.string.GerehNearAddViaWifi);
            items.add(UItem.asButton(PEER + shown.size(), p.getName().isEmpty() ? "?" : p.getName(), stateText(p.getState()) + " · " + via));
            shown.add(p);
        }
        items.add(UItem.asShadow(getString(R.string.GerehNearAddPeopleInfo)));
    }

    private static String stateText(String s) {
        switch (s) {
            case "outgoing": return getString(R.string.GerehNearAddStOutgoing);
            case "incoming": return getString(R.string.GerehNearAddStIncoming);
            case "waiting": return getString(R.string.GerehNearAddStWaiting);
            case "done": return getString(R.string.GerehNearAddStDone);
            case "declined": return getString(R.string.GerehNearAddStDeclined);
            case "failed": return getString(R.string.GerehNearAddStFailed);
            default: return getString(R.string.GerehNearAddStFound);
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == NAME) {
            editName();
        } else if (item.id == REOPEN) {
            asked.clear();
            GerehNet.startPairing(name);
            refresh();
        } else if (item.id == ALLOW_BT && getParentActivity() != null) {
            if (!org.mlmchat.core.ble.BleRadio.Companion.hasPermissions(getParentActivity())) {
                getParentActivity().requestPermissions(org.mlmchat.core.ble.BleRadio.Companion.permissions(), REQUEST_BT);
            } else {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehNearbyBtOff)).show();
            }
        } else if (item.id >= PEER && item.id - PEER < shown.size()) {
            PairPeer p = shown.get(item.id - PEER);
            switch (p.getState()) {
                case "found":
                    asked.add(p.getId());
                    Utilities.globalQueue.postRunnable(() -> {
                        ChatNode node = GerehIdentity.node();
                        boolean ok = false;
                        try {
                            ok = node != null && node.pairAsk(p.getId());
                        } catch (Throwable ignored) {
                        }
                        final boolean sent = ok;
                        AndroidUtilities.runOnUIThread(() -> {
                            if (!sent) {
                                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehNearAddStFailed)).show();
                                return;
                            }
                            waitForCode(p.getId(), 0);
                        });
                    });
                    break;
                case "outgoing":
                case "incoming":
                    if (p.getCode() != null) {
                        showCode(p);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /** The code appears in the list right after asking; show it as soon as it is there. */
    private void waitForCode(String id, int tries) {
        load();
        for (PairPeer p : state.getPeers()) {
            if (p.getId().equals(id) && p.getCode() != null) {
                showCode(p);
                return;
            }
        }
        if (tries < 10) {
            AndroidUtilities.runOnUIThread(() -> waitForCode(id, tries + 1), 200);
        }
    }

    private void showCode(PairPeer p) {
        if (getParentActivity() == null) {
            return;
        }
        if (codeDialog != null) {
            codeDialog.dismiss();
        }
        String title = "incoming".equals(p.getState()) ? formatString(R.string.GerehNearAddAsksYou, p.getName()) : getString(R.string.GerehNearAddCodeTitle);
        codeDialog = new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(title)
                .setMessage(formatString(R.string.GerehNearAddCodeText, p.getName(), p.getCode()))
                .setPositiveButton(getString(R.string.GerehNearAddYes), (d, w) -> act(p.getId(), true))
                .setNegativeButton(getString(R.string.GerehNearAddNo), (d, w) -> act(p.getId(), false))
                .create();
        codeDialog.setCanceledOnTouchOutside(false);
        showDialog(codeDialog);
    }

    private void act(String id, boolean yes) {
        Utilities.globalQueue.postRunnable(() -> {
            ChatNode node = GerehIdentity.node();
            try {
                if (node != null) {
                    if (yes) {
                        node.pairConfirm(id);
                    } else {
                        node.pairDecline(id);
                    }
                }
            } catch (Throwable ignored) {
            }
            AndroidUtilities.runOnUIThread(this::refresh);
        });
    }

    private void editName() {
        if (getParentActivity() == null) {
            return;
        }
        EditTextBoldCursor field = new EditTextBoldCursor(getParentActivity());
        field.setText(name);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setSingleLine(true);
        field.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(8), AndroidUtilities.dp(4), AndroidUtilities.dp(8));
        AlertDialog d = new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.GerehNearAddNameEdit))
                .setView(field)
                .setPositiveButton(getString(R.string.OK), (dlg, w) -> {
                    String n = field.getText().toString().trim();
                    if (!n.isEmpty() && !n.equals(name)) {
                        name = n;
                        GerehNet.setPairName(n);
                        asked.clear();
                        GerehNet.startPairing(n);
                        refresh();
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .create();
        showDialog(d);
    }
}
